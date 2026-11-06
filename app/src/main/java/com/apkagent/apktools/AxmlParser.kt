package com.apkagent.apktools

import com.apkagent.i18n.L
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android 二进制 XML (AXML) 解析器与二进制 Patch 引擎：
 * 将 APK 内的 AndroidManifest.xml 或 res/xml/ 下的 XML 还原为可读 XML 文本，
 * 并支持在保持合法二进制 AXML 结构的前提下直接修改 <application> 安全属性。
 *
 * 修复说明：
 * 1. 标准 AOSP ResStringPool chunkType 为 0x0001（兼容旧硬编码 0x001C）。
 * 2. 支持 0x0180 (RES_XML_RESOURCE_MAP_TYPE)，当混淆/资源压缩剥离属性名字符串时，通过 0x0101xxxx 资源 ID 还原属性名。
 * 3. 支持 0x0100 (RES_XML_START_NAMESPACE_TYPE)，将 http://schemas.android.com/apk/res/android 映射为 android: 前缀。
 */
class AxmlParser(private val data: ByteArray) {

    private val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    private var strings: List<String> = emptyList()
    private var resourceIds: IntArray = IntArray(0)
    private val uriToPrefix = mutableMapOf(
        ANDROID_NS_URI to "android"
    )
    private val sb = StringBuilder()
    private var indent = 0

    companion object {
        const val ANDROID_NS_URI = "http://schemas.android.com/apk/res/android"
        private const val CHUNK_AXML_FILE = 0x0003
        private const val CHUNK_STRING_POOL = 0x0001
        private const val CHUNK_STRING_POOL_LEGACY = 0x001C
        private const val CHUNK_RESOURCE_MAP = 0x0180
        private const val CHUNK_START_NAMESPACE = 0x0100
        private const val CHUNK_END_NAMESPACE = 0x0101
        private const val CHUNK_START_TAG = 0x0102
        private const val CHUNK_END_TAG = 0x0103
        private const val CHUNK_TEXT = 0x0104

        const val ATTR_NAME = 0x01010003
        const val ATTR_DEBUGGABLE = 0x0101000f
        const val ATTR_EXPORTED = 0x01010010
        const val ATTR_ALLOW_BACKUP = 0x01010280
        const val ATTR_CLEARTEXT_TRAFFIC = 0x010104ec
        const val ATTR_NETWORK_SECURITY_CONFIG = 0x01010527

        private val SYSTEM_ATTR_NAMES = mapOf(
            0x01010000 to "theme",
            0x01010001 to "label",
            0x01010002 to "icon",
            0x01010003 to "name",
            0x01010006 to "permission",
            0x01010008 to "readPermission",
            0x01010009 to "writePermission",
            0x0101000e to "enabled",
            0x0101000f to "debuggable",
            0x01010010 to "exported",
            0x01010011 to "process",
            0x01010012 to "taskAffinity",
            0x01010013 to "multiprocess",
            0x0101001b to "sharedUserId",
            0x0101001d to " authorities",
            0x0101001e to "authorities",
            0x0101002b to " launchMode",
            0x0101020c to "minSdkVersion",
            0x0101021b to "versionCode",
            0x0101021c to "versionName",
            0x01010270 to "targetSdkVersion",
            0x01010280 to "allowBackup",
            0x010103f2 to "extractNativeLibs",
            0x010104ec to "usesCleartextTraffic",
            0x01010527 to "networkSecurityConfig"
        )

        /** 判断字节数组是否为二进制 AXML 格式 */
        fun isBinaryAxml(bytes: ByteArray): Boolean {
            if (bytes.size < 8) return false
            val type = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
            val headerSize = (bytes[2].toInt() and 0xFF) or ((bytes[3].toInt() and 0xFF) shl 8)
            return type == CHUNK_AXML_FILE && headerSize == 0x0008
        }

        /**
         * 带缓存的解析。相同内容的 AXML 文件只解析一次。
         */
        fun parseCached(file: java.io.File): String {
            return ParseCache.getOrParse(file, "AxmlParser") {
                val bytes = file.readBytes()
                if (isBinaryAxml(bytes)) AxmlParser(bytes).parse() else String(bytes, Charsets.UTF_8)
            }
        }

        fun parseCached(data: ByteArray): String {
            if (!isBinaryAxml(data)) return String(data, Charsets.UTF_8)
            // 直接按字节内容做缓存 key，避免虚拟文件不存在导致所有 ByteArray 共用 "missing"
            return ParseCache.getOrParse(data, "AxmlParser") {
                // 非法/畸形 AXML 解析异常时返回明确注释，避免工具崩溃
                try {
                    AxmlParser(data).parse()
                } catch (e: Throwable) {
                    "<!-- AXML 解析失败: ${e.message} -->"
                }
            }
        }

        /**
         * 对二进制 AXML 的 <application> 节点执行原地布尔属性修改（如 debuggable / allowBackup / usesCleartextTraffic）。
         * 若属性已存在则原地覆写值，完全保持二进制 AXML 合法性。
         */
        fun patchBinaryManifest(
            raw: ByteArray,
            debuggable: Boolean? = null,
            allowBackup: Boolean? = null,
            cleartextTraffic: Boolean? = null
        ): Pair<ByteArray, List<String>> {
            val out = raw.copyOf()
            val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            if (!isBinaryAxml(out)) return out to emptyList()

            val fileSize = buf.getInt(4)
            var strings: List<String> = emptyList()
            var resIds = IntArray(0)
            val changes = mutableListOf<String>()

            val desiredUpdates = mutableMapOf<String, Pair<Int, Boolean>>()
            if (debuggable != null) desiredUpdates["debuggable"] = ATTR_DEBUGGABLE to debuggable
            if (allowBackup != null) desiredUpdates["allowBackup"] = ATTR_ALLOW_BACKUP to allowBackup
            if (cleartextTraffic != null) desiredUpdates["usesCleartextTraffic"] = ATTR_CLEARTEXT_TRAFFIC to cleartextTraffic
            if (desiredUpdates.isEmpty()) return out to emptyList()

            var pos = 8
            while (pos + 8 <= out.size && pos < fileSize) {
                val chunkType = buf.getShort(pos).toInt() and 0xFFFF
                val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
                val chunkSize = buf.getInt(pos + 4)
                if (chunkSize <= 8 || pos + chunkSize > out.size) break

                when (chunkType) {
                    CHUNK_STRING_POOL, CHUNK_STRING_POOL_LEGACY -> {
                        val parser = AxmlParser(out)
                        parser.buf.position(pos + 8)
                        parser.parseStringPool(headerSize, chunkSize)
                        strings = parser.strings
                    }
                    CHUNK_RESOURCE_MAP -> {
                        val count = (chunkSize - headerSize) / 4
                        if (count > 0) {
                            resIds = IntArray(count) { i -> buf.getInt(pos + headerSize + i * 4) }
                        }
                    }
                    CHUNK_START_TAG -> {
                        if (pos + 36 <= out.size) {
                            val nameIdx = buf.getInt(pos + 20)
                            val attrStart = buf.getShort(pos + 24).toInt() and 0xFFFF
                            val attrSize = (buf.getShort(pos + 26).toInt() and 0xFFFF).let { if (it == 0) 20 else it }
                            val attrCount = buf.getShort(pos + 28).toInt() and 0xFFFF
                            val tagName = strings.getOrNull(nameIdx) ?: ""

                            if (tagName == "application") {
                                val patchedAttrs = mutableSetOf<String>()
                                val attrBase = pos + 16 + attrStart
                                for (i in 0 until attrCount) {
                                    val aPos = attrBase + i * attrSize
                                    if (aPos + 20 > out.size) break
                                    val aNameIdx = buf.getInt(aPos + 4)
                                    val aName = strings.getOrNull(aNameIdx)?.takeIf { it.isNotEmpty() }
                                        ?: resIds.getOrNull(aNameIdx)?.let { SYSTEM_ATTR_NAMES[it]?.trim() }
                                        ?: ""
                                    val resId = resIds.getOrNull(aNameIdx) ?: 0

                                    for ((targetName, pair) in desiredUpdates) {
                                        val (targetResId, targetVal) = pair
                                        if (aName == targetName || resId == targetResId) {
                                            buf.putInt(aPos + 8, -1) // rawValueIdx = -1
                                            buf.putShort(aPos + 12, 8.toShort())
                                            out[aPos + 14] = 0
                                            out[aPos + 15] = 0x12.toByte() // TYPE_INT_BOOLEAN
                                            buf.putInt(aPos + 16, if (targetVal) -1 else 0)
                                            patchedAttrs.add(targetName)
                                            changes.add("android:$targetName=$targetVal (AXML 原地修改)")
                                        }
                                    }
                                }

                                // 对于 <application> 中尚未存在的属性，如果字符串池/资源表中已有对应项则直接插入 20 字节属性块
                                val missing = desiredUpdates.filterKeys { it !in patchedAttrs }
                                if (missing.isNotEmpty()) {
                                    val nsIdx = strings.indexOf(ANDROID_NS_URI)
                                    val insertBytes = ByteArrayOutputStream()
                                    var insertedCount = 0
                                    for ((targetName, pair) in missing) {
                                        val (targetResId, targetVal) = pair
                                        var attrIdx = resIds.indexOfFirst { it == targetResId }
                                        if (attrIdx < 0) attrIdx = strings.indexOf(targetName)
                                        if (attrIdx >= 0) {
                                            val ab = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                                            ab.putInt(nsIdx)
                                            ab.putInt(attrIdx)
                                            ab.putInt(-1)
                                            ab.putShort(8.toShort())
                                            ab.put(0.toByte())
                                            ab.put(0x12.toByte())
                                            ab.putInt(if (targetVal) -1 else 0)
                                            insertBytes.write(ab.array())
                                            insertedCount++
                                            changes.add("android:$targetName=$targetVal (AXML 节点注入)")
                                        }
                                    }
                                    if (insertedCount > 0) {
                                        val extra = insertBytes.toByteArray()
                                        val insertOffset = attrBase + attrCount * attrSize
                                        val newOut = ByteArray(out.size + extra.size)
                                        System.arraycopy(out, 0, newOut, 0, insertOffset)
                                        System.arraycopy(extra, 0, newOut, insertOffset, extra.size)
                                        System.arraycopy(out, insertOffset, newOut, insertOffset + extra.size, out.size - insertOffset)
                                        val newBuf = ByteBuffer.wrap(newOut).order(ByteOrder.LITTLE_ENDIAN)
                                        newBuf.putInt(4, fileSize + extra.size) // fileSize
                                        newBuf.putInt(pos + 4, chunkSize + extra.size) // chunkSize
                                        newBuf.putShort(pos + 28, (attrCount + insertedCount).toShort()) // attrCount
                                        return newOut to changes
                                    }
                                }
                            }
                        }
                    }
                }
                pos += chunkSize
            }
            return out to changes
        }
    }

    fun parse(): String {
        if (data.size < 8) return L("<!-- 文件过小，非合法 AXML -->", "<!-- File too small, invalid AXML -->")
        if (!isBinaryAxml(data)) {
            return String(data, Charsets.UTF_8)
        }
        val fileType = buf.short.toInt() and 0xFFFF
        val headerSize = buf.short.toInt() and 0xFFFF
        val fileSize = buf.int
        if (fileType != CHUNK_AXML_FILE) return "<!-- 非 AXML 文件: magic=0x${fileType.toString(16)} -->"

        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")

        var pos = headerSize
        while (pos + 8 <= data.size && pos < fileSize) {
            buf.position(pos)
            val chunkType = buf.short.toInt() and 0xFFFF
            val chunkHeaderSize = buf.short.toInt() and 0xFFFF
            val chunkSize = buf.int
            if (chunkSize <= 8 || pos + chunkSize > data.size) break

            when (chunkType) {
                CHUNK_STRING_POOL, CHUNK_STRING_POOL_LEGACY -> parseStringPool(chunkHeaderSize, chunkSize)
                CHUNK_RESOURCE_MAP -> parseResourceMap(pos, chunkHeaderSize, chunkSize)
                CHUNK_START_NAMESPACE -> parseStartNamespace(pos, chunkHeaderSize)
                CHUNK_END_NAMESPACE -> { /* end namespace */ }
                CHUNK_START_TAG -> parseStartTag(pos, chunkHeaderSize)
                CHUNK_END_TAG -> parseEndTag(pos, chunkHeaderSize)
                CHUNK_TEXT -> { /* text node */ }
            }
            pos += chunkSize
        }
        return sb.toString()
    }

    private fun parseStringPool(headerSize: Int, chunkSize: Int) {
        val chunkStart = buf.position() - 8
        if (chunkStart < 0 || chunkStart + 8 > data.size) {
            strings = emptyList()
            return
        }
        val stringCount = buf.int
        val styleCount = buf.int
        val flags = buf.int
        val stringsStart = buf.int
        buf.int // stylesStart

        if (stringCount <= 0 || stringCount > 500_000) {
            strings = emptyList()
            return
        }

        val isUtf8 = (flags and (1 shl 8)) != 0
        // 边界检查：offsets 表起点与长度必须落在缓冲区内，防止非法 headerSize/stringCount 让 position 越界
        val offsetsStart = chunkStart.toLong() + headerSize.toLong()
        val offsetsBytes = stringCount.toLong() * 4
        if (offsetsStart < 0 || offsetsStart + offsetsBytes > buf.limit().toLong()) {
            strings = emptyList()
            return
        }
        buf.position(offsetsStart.toInt())
        val offsets = IntArray(stringCount) {
            if (buf.remaining() >= 4) buf.int else 0
        }

        // 全程用 Long 计算，避免 chunkStart + stringsStart / count*4 溢出后 position 越界
        val poolStart = if (stringsStart > 0) {
            chunkStart.toLong() + stringsStart.toLong()
        } else {
            chunkStart.toLong() + headerSize.toLong() +
                stringCount.toLong() * 4 + styleCount.toLong() * 4
        }

        val list = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val strOffset = poolStart + offsets[i].toLong()
            if (strOffset < 0 || strOffset >= data.size.toLong()) {
                list.add("")
                continue
            }
            val at = strOffset.toInt()
            val s = try {
                if (isUtf8) readUtf8(at) else readUtf16(at)
            } catch (_: Throwable) {
                ""
            }
            list.add(s)
        }
        strings = list
    }

    private fun parseResourceMap(chunkStart: Int, headerSize: Int, chunkSize: Int) {
        val count = (chunkSize - headerSize) / 4
        if (count <= 0 || count > 100_000) return
        buf.position(chunkStart + headerSize)
        resourceIds = IntArray(count) {
            if (buf.remaining() >= 4) buf.int else 0
        }
    }

    private fun parseStartNamespace(chunkStart: Int, headerSize: Int) {
        if (chunkStart + headerSize + 8 > data.size) return
        buf.position(chunkStart + headerSize)
        val prefixIdx = buf.int
        val uriIdx = buf.int
        val prefix = str(prefixIdx)
        val uri = str(uriIdx)
        if (uri.isNotEmpty() && prefix.isNotEmpty()) {
            uriToPrefix[uri] = prefix
        }
    }

    private fun readUtf16(offset: Int): String {
        if (offset < 0 || offset + 2 > data.size) return ""
        buf.position(offset)
        var len = buf.short.toInt() and 0xFFFF
        if (len and 0x8000 != 0) {
            if (offset + 4 > data.size) return ""
            len = ((len and 0x7FFF) shl 16) or (buf.short.toInt() and 0xFFFF)
        }
        val byteLen = len * 2
        if (buf.position() + byteLen > data.size || byteLen < 0) return ""
        val bytes = ByteArray(byteLen)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_16LE)
    }

    private fun readUtf8(offset: Int): String {
        if (offset < 0 || offset >= data.size) return ""
        var pos = offset
        var charLen = data[pos].toInt() and 0xFF
        pos++
        if (charLen and 0x80 != 0) {
            if (pos >= data.size) return ""
            charLen = ((charLen and 0x7F) shl 8) or (data[pos].toInt() and 0xFF)
            pos++
        }
        if (pos >= data.size) return ""
        var byteLen = data[pos].toInt() and 0xFF
        pos++
        if (byteLen and 0x80 != 0) {
            if (pos >= data.size) return ""
            byteLen = ((byteLen and 0x7F) shl 8) or (data[pos].toInt() and 0xFF)
            pos++
        }
        if (byteLen < 0 || pos + byteLen > data.size) return ""
        return String(data, pos, byteLen, Charsets.UTF_8)
    }

    private fun parseStartTag(chunkStart: Int, headerSize: Int) {
        if (chunkStart + headerSize + 20 > data.size) return
        buf.position(chunkStart + headerSize)
        val nsIdx = buf.int
        val nameIdx = buf.int
        val attrStart = buf.short.toInt() and 0xFFFF
        val attrSize = (buf.short.toInt() and 0xFFFF).let { if (it == 0) 20 else it }
        val attrCount = buf.short.toInt() and 0xFFFF
        buf.short // idIndex
        buf.short // classIndex
        buf.short // styleIndex

        val name = strOrAttrName(nameIdx)
        sb.append("  ".repeat(indent)).append("<").append(name)

        if (name == "manifest" && attrCount > 0) {
            val androidPrefix = uriToPrefix[ANDROID_NS_URI] ?: "android"
            sb.append(" xmlns:").append(androidPrefix).append("=\"").append(ANDROID_NS_URI).append("\"")
        }

        val attrBase = chunkStart + headerSize + attrStart
        for (i in 0 until attrCount) {
            val aPos = attrBase + i * attrSize
            if (aPos + 20 > data.size) break
            buf.position(aPos)
            val aNsIdx = buf.int
            val aNameIdx = buf.int
            val aRawVal = buf.int
            buf.short // size
            buf.get() // res0
            val aType = buf.get().toInt() and 0xFF
            val aData = buf.int

            val aNsUri = str(aNsIdx)
            val aName = strOrAttrName(aNameIdx)
            val aVal = formatAttrValue(aRawVal, aType, aData)

            val prefix = when {
                aNsUri.isNotEmpty() -> uriToPrefix[aNsUri] ?: aNsUri.substringAfterLast('/')
                aNameIdx in resourceIds.indices && (resourceIds[aNameIdx] ushr 24) == 0x01 -> "android"
                else -> ""
            }
            val fullName = if (prefix.isNotEmpty() && !aName.contains(':')) "$prefix:$aName" else aName
            sb.append(" ").append(fullName).append("=\"").append(escapeXml(aVal)).append("\"")
        }
        sb.append(">\n")
        indent++
    }

    private fun parseEndTag(chunkStart: Int, headerSize: Int) {
        if (chunkStart + headerSize + 8 > data.size) return
        buf.position(chunkStart + headerSize)
        buf.int // ns
        val nameIdx = buf.int
        indent = (indent - 1).coerceAtLeast(0)
        sb.append("  ".repeat(indent)).append("</").append(strOrAttrName(nameIdx)).append(">\n")
    }

    private fun str(idx: Int): String = if (idx in strings.indices) strings[idx] else ""

    private fun strOrAttrName(idx: Int): String {
        val s = str(idx)
        if (s.isNotEmpty()) return s
        if (idx in resourceIds.indices) {
            val resId = resourceIds[idx]
            SYSTEM_ATTR_NAMES[resId]?.let { return it.trim() }
            if (resId != 0) return "attr_0x${resId.toUInt().toString(16)}"
        }
        return "unknown_$idx"
    }

    private fun formatAttrValue(rawIdx: Int, type: Int, data: Int): String {
        if (rawIdx >= 0 && rawIdx < strings.size) return strings[rawIdx]
        return when (type) {
            0x00 -> ""
            0x01 -> "@0x${data.toUInt().toString(16)}"
            0x02 -> "?0x${data.toUInt().toString(16)}"
            0x03 -> str(data)
            0x04 -> java.lang.Float.intBitsToFloat(data).toString()
            0x10 -> data.toString()
            0x11 -> "0x${data.toUInt().toString(16)}"
            0x12 -> if (data != 0) "true" else "false"
            0x1c, 0x1d, 0x1e, 0x1f -> "#${data.toUInt().toString(16).padStart(8, '0')}"
            else -> "0x${data.toUInt().toString(16)}"
        }
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
