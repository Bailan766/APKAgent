package com.apkagent.apktools

import com.apkagent.i18n.L
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DEX 文件解析器：解析头部统计信息、完整字符串池与所有类定义。
 * 纯 Java/Kotlin 实现，可在 Android 直接运行。
 */
class DexParser(private val data: ByteArray) {

    private val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    companion object {
        /**
         * 带缓存的解析。相同内容的 DEX 文件只解析一次。
         */
        fun parseCached(file: java.io.File): DexInfo {
            return ParseCache.getOrParse(file, "DexParser") {
                DexParser(file.readBytes()).parse()
            }
        }

        fun parseCached(data: ByteArray): DexInfo {
            val key = ParseCache.contentHash(data)
            return ParseCache.getOrParse(java.io.File("/virtual/$key"), "DexParser") {
                DexParser(data).parse()
            }
        }
    }

    data class DexInfo(
        val version: String,
        val fileSize: Long,
        val stringIdsSize: Int,
        val typeIdsSize: Int,
        val protoIdsSize: Int,
        val fieldIdsSize: Int,
        val methodIdsSize: Int,
        val classDefsSize: Int,
        val classes: List<String>,
        val strings: List<String> = emptyList()
    )

    fun parse(): DexInfo {
        if (data.size < 0x70) throw IllegalStateException(L("文件太小，不是合法 DEX", "File too small, not a valid DEX"))
        val magic = String(data, 0, 8, Charsets.US_ASCII)
        if (!magic.startsWith("dex\n")) throw IllegalStateException("非 DEX 文件: magic=${magic.take(8)}")
        val version = magic.substring(4, 7)

        buf.position(0x20)
        val fileSize = buf.int.toLong() and 0xFFFFFFFFL
        buf.int // header_size
        buf.int // endian_tag
        buf.int // link_size
        buf.int // link_off
        buf.int // map_off
        val stringIdsSize = buf.int.coerceAtLeast(0)
        val stringIdsOff = buf.int
        val typeIdsSize = buf.int.coerceAtLeast(0)
        val typeIdsOff = buf.int
        val protoIdsSize = buf.int.coerceAtLeast(0)
        buf.int // proto_off
        val fieldIdsSize = buf.int.coerceAtLeast(0)
        buf.int // field_off
        val methodIdsSize = buf.int.coerceAtLeast(0)
        buf.int // method_off
        val classDefsSize = buf.int.coerceAtLeast(0)
        val classDefsOff = buf.int

        val strings = readStringPool(stringIdsSize, stringIdsOff)
        val types = readTypeIds(typeIdsSize, typeIdsOff, strings)

        val classes = mutableListOf<String>()
        if (classDefsOff in 1 until data.size && classDefsSize > 0) {
            buf.position(classDefsOff)
            for (i in 0 until classDefsSize) {
                if (buf.remaining() < 32) break
                val classIdx = buf.int           // -> type_ids
                buf.int                          // access_flags
                buf.int                          // superclass_idx
                buf.int                          // interfaces_off
                buf.int                          // source_file_idx
                buf.int                          // annotations_off
                buf.int                          // class_data_off
                buf.int                          // static_values_off

                val descriptor = types.getOrElse(classIdx) { "?" }
                classes.add(descriptorToName(descriptor))
            }
        }

        return DexInfo(
            version = version,
            fileSize = fileSize,
            stringIdsSize = stringIdsSize,
            typeIdsSize = typeIdsSize,
            protoIdsSize = protoIdsSize,
            fieldIdsSize = fieldIdsSize,
            methodIdsSize = methodIdsSize,
            classDefsSize = classDefsSize,
            classes = classes,
            strings = strings.toList()
        )
    }

    private fun readStringPool(count: Int, offset: Int): Array<String> {
        val safeCount = count.coerceIn(0, 2_000_000)
        val arr = Array(safeCount) { "" }
        if (offset <= 0 || offset >= data.size || safeCount == 0) return arr
        val offsets = IntArray(safeCount)
        buf.position(offset)
        for (i in 0 until safeCount) {
            if (buf.remaining() < 4) break
            offsets[i] = buf.int
        }
        for (i in 0 until safeCount) {
            val strOff = offsets[i]
            if (strOff <= 0 || strOff >= data.size) continue
            buf.position(strOff)
            arr[i] = try {
                readMutf8String()
            } catch (_: Throwable) {
                ""
            }
        }
        return arr
    }

    private fun readMutf8String(): String {
        readUleb128()
        val ba = ByteArrayOutputStream()
        while (buf.hasRemaining()) {
            val b = buf.get().toInt() and 0xFF
            if (b == 0) break
            ba.write(b)
        }
        return ba.toString(Charsets.UTF_8.name())
    }

    private fun readUleb128(): Int {
        var result = 0
        var shift = 0
        while (buf.hasRemaining() && shift < 35) {
            val b = buf.get().toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    private fun readTypeIds(count: Int, offset: Int, strings: Array<String>): Array<String> {
        val safeCount = count.coerceIn(0, 500_000)
        val arr = Array(safeCount) { "" }
        if (offset <= 0 || offset >= data.size || safeCount == 0) return arr
        buf.position(offset)
        for (i in 0 until safeCount) {
            if (buf.remaining() < 4) break
            val descIdx = buf.int
            arr[i] = strings.getOrElse(descIdx) { "?" }
        }
        return arr
    }

    /** Lcom/foo/Bar; -> com.foo.Bar ; [I -> int[] ; [[Ljava/lang/String; -> java.lang.String[][] */
    fun descriptorToName(d: String): String {
        var arrayDim = 0
        var s = d
        while (s.startsWith("[")) { arrayDim++; s = s.substring(1) }
        val base = when {
            s.startsWith("L") && s.endsWith(";") -> s.substring(1, s.length - 1).replace('/', '.')
            s == "I" -> "int"; s == "Z" -> "boolean"; s == "B" -> "byte"
            s == "S" -> "short"; s == "C" -> "char"; s == "J" -> "long"
            s == "F" -> "float"; s == "D" -> "double"; s == "V" -> "void"
            else -> s
        }
        return base + "[]".repeat(arrayDim)
    }
}
