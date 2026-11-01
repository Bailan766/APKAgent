package com.apkagent.apktools.advanced

import com.apkagent.i18n.L
import com.apkagent.agent.Tool
import com.apkagent.agent.ToolContext
import com.apkagent.agent.ToolResult
import com.apkagent.agent.intProp
import com.apkagent.agent.strProp
import com.apkagent.agent.schemaObject
import com.apkagent.apktools.Sandbox
import com.apkagent.apktools.str
import com.apkagent.apktools.int
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * 资源表完整解码器（resources.arsc）。
 * 解析二进制资源表，提取字符串、颜色、尺寸、整数、布尔等资源值，以及资源 ID 映射。
 *
 * 修复说明：
 * 1. 修正 AOSP 标准 chunkType 常量：
 *    - RES_STRING_POOL_TYPE = 0x0001
 *    - RES_TABLE_PACKAGE_TYPE = 0x0200
 *    - RES_TABLE_TYPE_TYPE = 0x0201
 *    - RES_TABLE_TYPE_SPEC_TYPE = 0x0202
 * 2. 修正 ResStringPool 与 ResTable_type 的 headerSize 动态偏移与 entriesBase 基准位置。
 * 3. 补齐 parseTypeChunk 中读取 entry 前缺失的 buf.position(entryStart)。
 * 4. 支持缺省 apk_path 自动回退至当前打开的 APK 并基于 workspace 解析相对路径。
 */
object ResourceTableDecoder : Tool {
    override val name = "apk_decode_resources"
    override val description get() = L(
        "完整解码 APK 的 resources.arsc 资源表，提取所有字符串、颜色、尺寸、样式等资源值。",
        "Fully decode the APK's resources.arsc resource table and extract strings, colors, dimensions, and resource values."
    )
    override val parameters get() = schemaObject(
        mapOf(
            "apk_path" to strProp(L("APK 文件路径（留空默认当前打开的 APK）", "APK file path (defaults to currently open APK)")),
            "resource_type" to strProp(L("资源类型过滤：all（全部）/ string（字符串）/ color（颜色）/ dimen（尺寸）/ id（ID）", "Resource type filter: all/string/color/dimen/id")),
            "search_query" to strProp(L("搜索关键字（可选）", "Search keyword (optional)")),
            "max_results" to intProp(L("最大返回数量（默认 200）", "Maximum number of results (default 200)"))
        )
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawPath = args.str("apk_path")?.takeIf { it.isNotBlank() }
        val apkFile = when {
            rawPath != null -> Sandbox.resolve(File(rawPath), ctx.workspace)
            ctx.openApk != null -> ctx.openApk!!
            else -> File(ctx.workspace, "base.apk")
        }
        val resourceType = args.str("resource_type")?.takeIf { it.isNotBlank() } ?: "all"
        val searchQuery = args.str("search_query")?.takeIf { it.isNotBlank() }
        val maxResults = args.int("max_results") ?: 200

        if (!apkFile.exists() || !apkFile.isFile) {
            return ToolResult(false, "文件不存在: ${apkFile.absolutePath}")
        }

        return try {
            val arscBytes = extractArsc(apkFile)
                ?: return ToolResult(false, L("APK 内未找到 resources.arsc", "resources.arsc not found in APK"))

            val resources = decodeArsc(arscBytes, resourceType, searchQuery, maxResults)

            val sb = StringBuilder()
            sb.appendLine("📚 资源表解码：${apkFile.name}")
            sb.appendLine(L("resources.arsc 大小: ${formatSize(arscBytes.size.toLong())}", "resources.arsc size: ${formatSize(arscBytes.size.toLong())}"))
            sb.appendLine(L("解码资源数: ${resources.size}", "Decoded resources: ${resources.size}"))
            sb.appendLine()

            if (resources.isEmpty()) {
                sb.appendLine(L("未找到匹配的资源", "No matching resources found"))
            } else {
                val grouped = resources.groupBy { it.type }
                grouped.forEach { (type, items) ->
                    sb.appendLine("━━ ${type.uppercase()} (${items.size}) ━━")
                    items.take(100).forEach { res ->
                        sb.appendLine("  [0x${res.id.toUInt().toString(16).padStart(8, '0')}] ${res.name} = ${res.value}")
                    }
                    if (items.size > 100) {
                        sb.appendLine("  ... 省略 ${items.size - 100} 项")
                    }
                    sb.appendLine()
                }
            }

            ToolResult(true, sb.toString())
        } catch (e: Exception) {
            ToolResult(false, "解码失败: ${e.message}")
        }
    }

    private fun extractArsc(apkFile: File): ByteArray? {
        ZipFile(apkFile).use { zip ->
            val entry = zip.getEntry("resources.arsc") ?: return null
            return zip.getInputStream(entry).readBytes()
        }
    }

    internal fun decodeArsc(
        arscBytes: ByteArray,
        resourceType: String,
        searchQuery: String?,
        maxResults: Int
    ): List<ResourceEntry> {
        val resources = mutableListOf<ResourceEntry>()
        val buf = ByteBuffer.wrap(arscBytes).order(ByteOrder.LITTLE_ENDIAN)

        try {
            if (buf.remaining() < 12) return emptyList()
            val type = buf.short.toInt() and 0xFFFF
            val headerSize = buf.short.toInt() and 0xFFFF
            val chunkSize = buf.int
            val packageCount = buf.int

            if (type != 0x0002 || packageCount <= 0) return emptyList()

            val globalStrings = mutableListOf<String>()
            var pos = headerSize.coerceAtLeast(12)

            while (pos + 8 <= arscBytes.size && resources.size < maxResults) {
                buf.position(pos)
                val chunkType = buf.short.toInt() and 0xFFFF
                val subHeaderSize = buf.short.toInt() and 0xFFFF
                val subChunkSize = buf.int

                if (subChunkSize <= 8 || pos + subChunkSize > arscBytes.size) break

                when (chunkType) {
                    0x0001 -> { // RES_STRING_POOL_TYPE
                        globalStrings.addAll(parseStringPool(buf, pos, subHeaderSize, subChunkSize))
                    }
                    0x0200 -> { // RES_TABLE_PACKAGE_TYPE
                        val pkgResources = parsePackage(
                            buf,
                            pos,
                            subHeaderSize,
                            subChunkSize,
                            globalStrings,
                            resourceType,
                            searchQuery,
                            maxResults - resources.size
                        )
                        resources.addAll(pkgResources)
                    }
                }

                pos += subChunkSize
            }
        } catch (_: Exception) {
            // 解析部分完成即可返回已解码内容
        }

        // 如果未解析出结构化条目但存在全局字符串池（如混淆/非标 ARSC），回退提供字符串池检索结果
        if (resources.isEmpty() && (resourceType == "all" || resourceType == "string")) {
            val fallbackBuf = ByteBuffer.wrap(arscBytes).order(ByteOrder.LITTLE_ENDIAN)
            if (arscBytes.size >= 20) {
                val rootHeaderSize = (fallbackBuf.getShort(2).toInt() and 0xFFFF).coerceAtLeast(12)
                if (rootHeaderSize + 8 <= arscBytes.size) {
                    val cType = fallbackBuf.getShort(rootHeaderSize).toInt() and 0xFFFF
                    val cHeader = fallbackBuf.getShort(rootHeaderSize + 2).toInt() and 0xFFFF
                    val cSize = fallbackBuf.getInt(rootHeaderSize + 4)
                    if (cType == 0x0001 && cSize > 8 && rootHeaderSize + cSize <= arscBytes.size) {
                        val pool = parseStringPool(fallbackBuf, rootHeaderSize, cHeader, cSize)
                        pool.forEachIndexed { idx, str ->
                            if (str.isNotBlank() && (searchQuery == null || str.contains(searchQuery, ignoreCase = true))) {
                                if (resources.size < maxResults) {
                                    resources.add(ResourceEntry(0x7f000000 or idx, "string", "str_$idx", str))
                                }
                            }
                        }
                    }
                }
            }
        }

        return resources.take(maxResults)
    }

    private fun parseStringPool(
        buf: ByteBuffer,
        chunkStart: Int,
        headerSize: Int,
        chunkSize: Int
    ): List<String> {
        val strings = mutableListOf<String>()
        try {
            if (chunkStart + 28 > buf.limit()) return emptyList()
            buf.position(chunkStart + 8)
            val stringCount = buf.int
            val styleCount = buf.int
            val flags = buf.int
            val stringsStart = buf.int
            buf.int // stylesStart

            if (stringCount <= 0 || stringCount > 500_000) return emptyList()
            val isUtf8 = (flags and (1 shl 8)) != 0

            val offsetsStart = chunkStart + headerSize.coerceAtLeast(28)
            buf.position(offsetsStart)
            val offsets = IntArray(stringCount) {
                if (buf.remaining() >= 4) buf.int else 0
            }

            val stringsBase = if (stringsStart > 0) {
                chunkStart + stringsStart
            } else {
                offsetsStart + (stringCount + styleCount) * 4
            }

            for (offset in offsets) {
                val strPos = stringsBase + offset
                if (strPos < 0 || strPos >= chunkStart + chunkSize || strPos >= buf.limit()) {
                    strings.add("")
                    continue
                }
                buf.position(strPos)
                val str = if (isUtf8) readUtf8String(buf) else readUtf16String(buf)
                strings.add(str)
            }
        } catch (_: Exception) {}
        return strings
    }

    private fun readUtf8String(buf: ByteBuffer): String {
        return try {
            if (!buf.hasRemaining()) return ""
            var charLen = buf.get().toInt() and 0xFF
            if ((charLen and 0x80) != 0) {
                if (!buf.hasRemaining()) return ""
                charLen = ((charLen and 0x7F) shl 8) or (buf.get().toInt() and 0xFF)
            }
            if (!buf.hasRemaining()) return ""
            var byteLen = buf.get().toInt() and 0xFF
            if ((byteLen and 0x80) != 0) {
                if (!buf.hasRemaining()) return ""
                byteLen = ((byteLen and 0x7F) shl 8) or (buf.get().toInt() and 0xFF)
            }
            if (byteLen <= 0 || byteLen > 32768 || buf.remaining() < byteLen) return ""
            val bytes = ByteArray(byteLen)
            buf.get(bytes)
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun readUtf16String(buf: ByteBuffer): String {
        return try {
            if (buf.remaining() < 2) return ""
            var charLen = buf.short.toInt() and 0xFFFF
            if ((charLen and 0x8000) != 0) {
                if (buf.remaining() < 2) return ""
                charLen = ((charLen and 0x7FFF) shl 16) or (buf.short.toInt() and 0xFFFF)
            }
            val byteLen = charLen * 2
            if (byteLen <= 0 || byteLen > 65536 || buf.remaining() < byteLen) return ""
            val bytes = ByteArray(byteLen)
            buf.get(bytes)
            String(bytes, Charsets.UTF_16LE)
        } catch (_: Exception) {
            ""
        }
    }

    private fun parsePackage(
        buf: ByteBuffer,
        chunkStart: Int,
        headerSize: Int,
        chunkSize: Int,
        globalStrings: List<String>,
        resourceType: String,
        searchQuery: String?,
        maxResults: Int
    ): List<ResourceEntry> {
        val resources = mutableListOf<ResourceEntry>()
        try {
            if (chunkStart + 288 > buf.limit()) return emptyList()
            buf.position(chunkStart + 8)
            val packageId = buf.int

            // 跳过包名（128 个 char16 = 256 字节）
            buf.position(chunkStart + 8 + 4 + 256)
            val typeStringsOffset = buf.int
            buf.int // lastPublicType
            val keyStringsOffset = buf.int
            buf.int // lastPublicKey

            val typeStrings = if (typeStringsOffset > 0 && chunkStart + typeStringsOffset + 8 <= buf.limit()) {
                val tsPos = chunkStart + typeStringsOffset
                val tsHeader = buf.getShort(tsPos + 2).toInt() and 0xFFFF
                val tsSize = buf.getInt(tsPos + 4)
                parseStringPool(buf, tsPos, tsHeader, tsSize)
            } else emptyList()

            val keyStrings = if (keyStringsOffset > 0 && chunkStart + keyStringsOffset + 8 <= buf.limit()) {
                val ksPos = chunkStart + keyStringsOffset
                val ksHeader = buf.getShort(ksPos + 2).toInt() and 0xFFFF
                val ksSize = buf.getInt(ksPos + 4)
                parseStringPool(buf, ksPos, ksHeader, ksSize)
            } else emptyList()

            var pos = chunkStart + headerSize.coerceAtLeast(288)
            val endPos = (chunkStart + chunkSize).coerceAtMost(buf.limit())

            while (pos + 8 <= endPos && resources.size < maxResults) {
                buf.position(pos)
                val subType = buf.short.toInt() and 0xFFFF
                val subHeaderSize = buf.short.toInt() and 0xFFFF
                val subSize = buf.int

                if (subSize <= 8 || pos + subSize > endPos) break

                if (subType == 0x0201) { // RES_TABLE_TYPE_TYPE
                    val typeResources = parseTypeChunk(
                        buf,
                        pos,
                        subHeaderSize,
                        subSize,
                        packageId,
                        typeStrings,
                        keyStrings,
                        globalStrings,
                        resourceType,
                        searchQuery,
                        maxResults - resources.size
                    )
                    resources.addAll(typeResources)
                }

                pos += subSize
            }
        } catch (_: Exception) {}
        return resources
    }

    private fun parseTypeChunk(
        buf: ByteBuffer,
        chunkStart: Int,
        headerSize: Int,
        chunkSize: Int,
        packageId: Int,
        typeStrings: List<String>,
        keyStrings: List<String>,
        globalStrings: List<String>,
        resourceType: String,
        searchQuery: String?,
        maxResults: Int
    ): List<ResourceEntry> {
        val resources = mutableListOf<ResourceEntry>()
        try {
            if (chunkStart + 20 > buf.limit()) return emptyList()
            buf.position(chunkStart + 8)
            val typeId = buf.get().toInt() and 0xFF
            val flags = buf.get().toInt() and 0xFF
            buf.short // reserved
            val entryCount = buf.int
            val entriesStart = buf.int

            if (entryCount <= 0 || entryCount > 200_000) return emptyList()
            val typeName = typeStrings.getOrNull(typeId - 1) ?: "type_$typeId"
            if (resourceType != "all" && !typeName.equals(resourceType, ignoreCase = true)) {
                return emptyList()
            }

            val isSparse = (flags and 0x01) != 0
            if (isSparse) return emptyList()

            val offsetsPos = chunkStart + headerSize
            if (offsetsPos + entryCount * 4 > chunkStart + chunkSize) return emptyList()
            buf.position(offsetsPos)
            val entryOffsets = IntArray(entryCount) { buf.int }

            val entriesBase = chunkStart + entriesStart
            val chunkEnd = (chunkStart + chunkSize).coerceAtMost(buf.limit())

            for (i in 0 until entryCount) {
                if (resources.size >= maxResults) break
                val entryOffset = entryOffsets[i]
                if (entryOffset == -1) continue // NO_ENTRY

                val entryStart = entriesBase + entryOffset
                if (entryStart < 0 || entryStart + 16 > chunkEnd) continue

                buf.position(entryStart)
                val entrySize = buf.short.toInt() and 0xFFFF
                val entryFlags = buf.short.toInt() and 0xFFFF
                val keyIndex = buf.int

                val keyName = keyStrings.getOrNull(keyIndex) ?: "res_$i"
                val resId = (packageId shl 24) or (typeId shl 16) or i

                // 非 complex (bag) 资源紧跟 8 字节 Res_value
                if ((entryFlags and 0x0001) == 0) {
                    val valuePos = entryStart + entrySize.coerceAtLeast(8)
                    if (valuePos + 8 > chunkEnd) continue
                    buf.position(valuePos)
                    buf.short // valueSize
                    buf.get() // res0
                    val dataType = buf.get().toInt() and 0xFF
                    val data = buf.int

                    val value = formatResourceValue(dataType, data, globalStrings)
                    if (searchQuery == null ||
                        keyName.contains(searchQuery, ignoreCase = true) ||
                        value.contains(searchQuery, ignoreCase = true)
                    ) {
                        resources.add(ResourceEntry(resId, typeName, keyName, value))
                    }
                }
            }
        } catch (_: Exception) {}
        return resources
    }

    private fun formatResourceValue(dataType: Int, data: Int, globalStrings: List<String>): String {
        return when (dataType) {
            0x00 -> "null"
            0x01 -> "@0x${data.toUInt().toString(16)}"
            0x02 -> "?0x${data.toUInt().toString(16)}"
            0x03 -> globalStrings.getOrNull(data) ?: "string#$data"
            0x04 -> java.lang.Float.intBitsToFloat(data).toString()
            0x05 -> "${data and 0xFFFFFF}dp"
            0x10 -> data.toString()
            0x11 -> "0x${data.toUInt().toString(16)}"
            0x12 -> if (data != 0) "true" else "false"
            0x1c, 0x1d, 0x1e, 0x1f -> "#${data.toUInt().toString(16).padStart(8, '0')}"
            else -> "0x${data.toUInt().toString(16)} (type=0x${dataType.toString(16)})"
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        }
    }

    internal data class ResourceEntry(
        val id: Int,
        val type: String,
        val name: String,
        val value: String
    )
}
