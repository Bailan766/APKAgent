package com.apkagent.apktools

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android 二进制 XML（AXML）解析器。
 * 将 APK 内的 AndroidManifest.xml / 资源 XML 还原为可读文本。
 * 纯 Java 实现，可在 Android 直接运行。
 *
 * 格式参考：AOSP frameworks/base/include/androidfw/ResourceTypes.h
 */
class AxmlParser(private val data: ByteArray) {

    private val buf: ByteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    private val strings = mutableListOf<String>()

    fun parse(): String {
        if (data.size < 8) return "<empty>"
        val magic = buf.short.toInt() and 0xFFFF
        if (magic != 0x0003) return "<not axml: magic=0x${magic.toString(16)}>"
        buf.short // headerSize
        buf.int   // file size

        val out = StringBuilder()
        var depth = 0

        while (buf.remaining() >= 8) {
            val mark = buf.position()
            val chunkType = buf.short.toInt() and 0xFFFF
            val headerSize = (buf.short.toInt() and 0xFFFF)
            val chunkSize = buf.int
            if (chunkSize <= 0 || mark + chunkSize > data.size) break

            when (chunkType) {
                0x001C -> parseStringPool(headerSize, chunkSize) // STRING_POOL
                0x0102 -> { // XML_START_TAG
                    parseStartTag(out, depth)
                    depth++
                }
                0x0103 -> { // XML_END_TAG
                    depth--
                    if (depth < 0) depth = 0
                    parseEndTag(out, depth)
                }
                0x0100, 0x0101, 0x0104, 0x0180 -> { /* namespace / resource map, skip */ }
            }
            buf.position(mark + chunkSize)
        }
        return out.toString().trim()
    }

    private fun parseStringPool(headerSize: Int, chunkSize: Int) {
        val stringCount = buf.int
        buf.int // styleCount
        val flags = buf.int
        val stringsStart = buf.int
        buf.int // stylesStart
        val isUtf8 = (flags and (1 shl 8)) != 0

        val offsets = IntArray(stringCount)
        for (i in 0 until stringCount) offsets[i] = buf.int

        // stringsStart 相对 chunk 起点的偏移；字符串数据区起点 = chunkStart + stringsStart
        // chunkStart = 当前 buf 位置 - headerSize
        val stringsBase = (buf.position() - headerSize) + stringsStart

        for (i in 0 until stringCount) {
            val pos = stringsBase + offsets[i]
            if (pos >= data.size) { strings.add(""); continue }
            buf.position(pos)
            strings.add(readString(isUtf8))
        }
        // position 由外层 (mark + chunkSize) 重置
    }

    private fun readString(isUtf8: Boolean): String {
        return if (isUtf8) {
            var len = buf.get().toInt() and 0xFF
            if (len and 0x80 != 0) {
                len = ((len and 0x7F) shl 8) or (buf.get().toInt() and 0xFF)
            }
            // utf-8 byte length
            var byteLen = buf.get().toInt() and 0xFF
            if (byteLen and 0x80 != 0) {
                byteLen = ((byteLen and 0x7F) shl 8) or (buf.get().toInt() and 0xFF)
            }
            val ba = ByteArray(byteLen)
            buf.get(ba)
            buf.get() // trailing 0
            String(ba, Charsets.UTF_8)
        } else {
            var len = buf.short.toInt() and 0xFFFF
            if (len and 0x8000 != 0) {
                len = ((len and 0x7FFF) shl 16) or (buf.short.toInt() and 0xFFFF)
            }
            val ba = ByteArray(len * 2)
            buf.get(ba)
            buf.short // trailing 0x0000
            String(ba, Charsets.UTF_16LE)
        }
    }

    private fun str(idx: Int): String =
        if (idx in strings.indices) strings[idx] else ""

    private fun parseStartTag(out: StringBuilder, depth: Int) {
        buf.int // lineNumber
        buf.int // comment
        val ns = buf.int       // namespace uri
        val name = buf.int     // name index
        buf.short // attrStart
        buf.short // attrSize
        val attrCount = (buf.short.toInt() and 0xFFFF)
        buf.short // idIndex
        buf.short // classIndex
        buf.short // styleIndex

        val indent = "  ".repeat(depth)
        out.append("$indent<${str(name)}")
        for (i in 0 until attrCount) {
            val attrNs = buf.int
            val attrName = buf.int
            val rawValue = buf.int
            // typed value
            buf.short // size
            buf.get() // res0
            val dataType = buf.get().toInt() and 0xFF
            val data = buf.int

            val v = formatValue(rawValue, dataType, data)
            val nsStr = str(attrNs)
            val nameStr = str(attrName)
            val display = if (nsStr.isEmpty()) nameStr else "$nsStr:$nameStr"
            out.append(" $display=\"$v\"")
        }
        out.append(">\n")
    }

    private fun parseEndTag(out: StringBuilder, depth: Int) {
        buf.int // lineNumber
        buf.int // comment
        val ns = buf.int
        val name = buf.int
        val indent = "  ".repeat(depth)
        out.append("$indent</${str(name)}>\n")
    }

    private fun formatValue(rawValue: Int, dataType: Int, data: Int): String {
        return when (dataType) {
            0x03 -> str(rawValue)                    // TYPE_STRING
            0x10 -> data.toString()                  // TYPE_INT_DEC
            0x11 -> "0x${data.toLong().and(0xFFFFFFFFL).toString(16)}" // INT_HEX
            0x12 -> if (data != 0) "true" else "false" // INT_BOOLEAN
            0x01 -> "@0x${data.toLong().and(0xFFFFFFFFL).toString(16)}" // REFERENCE
            0x02 -> "?0x${data.toLong().and(0xFFFFFFFFL).toString(16)}" // ATTRIBUTE
            0x04 -> "float(${Float.fromBits(data)})" // TYPE_FLOAT
            0x05 -> dimToString(data)                // DIMENSION
            0x06 -> fractionToString(data)           // FRACTION
            else -> "0x${data.toLong().and(0xFFFFFFFFL).toString(16)} (type=$dataType)"
        }
    }

    private fun dimToString(data: Int): String {
        val valUnit = intArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10)
        val unit = data and 0x0F
        val v = data ushr 4
        val unitStr = when (unit) {
            0x01 -> "px"; 0x02 -> "dip"; 0x03 -> "sp"; 0x04 -> "pt"
            0x05 -> "in"; 0x06 -> "mm"; else -> "u$unit"
        }
        return "$v$unitStr"
    }

    private fun fractionToString(data: Int): String {
        val v = data ushr 4
        val type = data and 0x0F
        return if (type == 0) "$v%" else "$v%p"
    }
}
