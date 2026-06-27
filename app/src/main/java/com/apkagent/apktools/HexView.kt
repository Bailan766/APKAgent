package com.apkagent.apktools

/**
 * 十六进制查看工具：把字节格式化为 offset + hex + ASCII。
 */
object HexView {
    fun dump(bytes: ByteArray, maxBytes: Int = 4096, offset: Int = 0): String {
        if (bytes.isEmpty()) return "<空>"
        val start = offset.coerceAtLeast(0)
        val n = minOf(bytes.size - start, maxBytes).coerceAtLeast(0)
        if (n == 0) return "<无数据>"
        val sb = StringBuilder()
        var i = 0
        while (i < n) {
            val end = minOf(i + 16, n)
            val hex = StringBuilder()
            val ascii = StringBuilder()
            for (j in i until end) {
                val b = bytes[start + j].toInt() and 0xFF
                hex.append("%02X ".format(b))
                ascii.append(if (b in 32..126) b.toChar() else '.')
            }
            // padding
            for (j in end until i + 16) hex.append("   ")
            sb.append("%08X  %s %s\n".format(start + i, hex.toString().trimEnd(), ascii))
            i += 16
        }
        if (start + n < bytes.size) {
            sb.append("\n…(文件共 ${bytes.size} 字节，从偏移 $start 显示了 $n 字节)")
        }
        return sb.toString()
    }
}
