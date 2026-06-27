package com.apkagent.apktools

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DEX 文件解析器：解析头部统计信息 + 列出所有类定义。
 * 纯 Java 实现，可在 Android 直接运行。
 */
class DexParser(private val data: ByteArray) {

    private val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    data class DexInfo(
        val version: String,
        val fileSize: Long,
        val stringIdsSize: Int,
        val typeIdsSize: Int,
        val protoIdsSize: Int,
        val fieldIdsSize: Int,
        val methodIdsSize: Int,
        val classDefsSize: Int,
        val classes: List<String>
    )

    fun parse(): DexInfo {
        if (data.size < 0x70) throw IllegalStateException("文件太小，不是合法 DEX")
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
        val stringIdsSize = buf.int
        val stringIdsOff = buf.int
        val typeIdsSize = buf.int
        val typeIdsOff = buf.int
        val protoIdsSize = buf.int
        buf.int // proto_off
        val fieldIdsSize = buf.int
        buf.int // field_off
        val methodIdsSize = buf.int
        buf.int // method_off
        val classDefsSize = buf.int
        val classDefsOff = buf.int

        // 读取字符串池（懒加载需要的）
        val strings = readStringPool(stringIdsSize, stringIdsOff)
        val types = readTypeIds(typeIdsSize, typeIdsOff, strings)

        val classes = mutableListOf<String>()
        buf.position(classDefsOff)
        for (i in 0 until classDefsSize) {
            val classIdx = buf.int           // -> type_ids
            val accessFlags = buf.int        // access flags
            val superclassIdx = buf.int
            buf.int // interfaces_off
            buf.int // source_file_idx
            buf.int // annotations_off
            buf.int // class_data_off
            buf.int // static_values_off

            val descriptor = types.getOrElse(classIdx) { "?" }
            classes.add(descriptorToName(descriptor))
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
            classes = classes
        )
    }

    private fun readStringPool(count: Int, offset: Int): Array<String> {
        val arr = Array(count) { "" }
        if (offset == 0 || count == 0) return arr
        val offsets = IntArray(count)
        buf.position(offset)
        for (i in 0 until count) offsets[i] = buf.int
        for (i in 0 until count) {
            buf.position(offsets[i])
            arr[i] = readMutf8String()
        }
        return arr
    }

    private fun readMutf8String(): String {
        // uleb128 utf-16 length（忽略）
        readUleb128()
        val ba = ByteArrayOutputStream()
        while (true) {
            val b = buf.get().toInt() and 0xFF
            if (b == 0) break
            ba.write(b)
        }
        return ba.toString(Charsets.UTF_8.name())
    }

    private fun readUleb128(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = buf.get().toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    private fun readTypeIds(count: Int, offset: Int, strings: Array<String>): Array<String> {
        val arr = Array(count) { "" }
        if (offset == 0 || count == 0) return arr
        buf.position(offset)
        for (i in 0 until count) {
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
