package com.apkagent.apktools

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.jar.JarFile

/**
 * APK 签名信息读取器。
 * - v1 (JAR signing): 解析 META-INF 下的 .RSA/.DSA/.EC 签名块，提取 X.509 证书。
 * - v2/v3 (APK Signature Scheme): 手动定位 APK Signing Block，提取证书链。
 */
object SignatureReader {

    private const val SIG_BLOCK_MAGIC = "APK Sig Block 42"
    private const val V2_ID = 0x7109871aL
    private const val V3_ID = 0xf05368c0L

    data class SignerInfo(
        val scheme: String,
        val certs: List<X509Certificate>
    )

    fun read(apk: File): String {
        val sb = StringBuilder()
        sb.appendLine("APK 签名分析：${apk.name}")
        sb.appendLine("文件大小：${apk.length()} 字节")
        sb.appendLine()

        val results = mutableListOf<SignerInfo>()

        // v1
        try {
            val v1 = readV1(apk)
            if (v1 != null) results.add(v1)
        } catch (e: Throwable) {
            sb.appendLine("[v1] 读取失败：${e.message}")
        }

        // v2/v3
        try {
            val v2v3 = readV2V3(apk)
            results.addAll(v2v3)
        } catch (e: Throwable) {
            sb.appendLine("[v2/v3] 读取失败：${e.message}")
        }

        if (results.isEmpty()) {
            sb.appendLine("⚠ 未检测到任何签名（v1/v2/v3）。该 APK 未签名或签名格式无法识别。")
            return sb.toString()
        }

        results.forEach { r ->
            sb.appendLine("── 签名方案：${r.scheme} ──")
            if (r.certs.isEmpty()) {
                sb.appendLine("  （检测到方案但未提取到证书）")
            } else {
                r.certs.forEachIndexed { i, c ->
                    sb.appendLine("  证书 #${i + 1}")
                    sb.appendLine("    Subject : ${c.subjectX500Principal}")
                    sb.appendLine("    Issuer  : ${c.issuerX500Principal}")
                    sb.appendLine("    Serial  : ${c.serialNumber}")
                    sb.appendLine("    算法    : ${c.sigAlgName}")
                    sb.appendLine("    有效期  : ${c.notBefore} ~ ${c.notAfter}")
                }
            }
            sb.appendLine()
        }

        if (results.none { it.scheme.startsWith("v2") || it.scheme.startsWith("v3") }) {
            sb.appendLine("ℹ 仅含 v1 签名，在 Android 7.0+ 建议补充 v2 签名以保证完整性校验。")
        }
        return sb.toString()
    }

    private fun readV1(apk: File): SignerInfo? {
        val certs = mutableListOf<X509Certificate>()
        JarFile(apk).use { jar ->
            val entries = jar.entries().toList()
            entries.forEach { e ->
                val n = e.name.uppercase()
                if (n.startsWith("META-INF/") &&
                    (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC") || n.endsWith(".ECRSA"))
                ) {
                    jar.getInputStream(e).use { input ->
                        val cf = CertificateFactory.getInstance("X.509")
                        cf.generateCertificates(input).forEach { c ->
                            (c as? X509Certificate)?.let { certs.add(it) }
                        }
                    }
                }
            }
        }
        return if (certs.isEmpty()) null else SignerInfo("v1 (JAR)", certs)
    }

    private fun readV2V3(apk: File): List<SignerInfo> {
        val data = apk.readBytes()
        val cdOffset = findCentralDirectoryOffset(data) ?: return emptyList()
        // APK Signing Block 在 CD 之前
        // magic 位于 block 尾部 16 字节
        val magicBytes = SIG_BLOCK_MAGIC.toByteArray(Charsets.US_ASCII)
        // magic 位于 APK Signing Block 尾部 16 字节，紧邻 Central Directory 之前
        var pos = cdOffset - 16
        if (pos < 0) return emptyList()
        if (!matchesAt(data, pos, magicBytes)) return emptyList()
        // size_of_block (8 bytes long LE) 在 magic 之前
        val sizeOfBlock = readLongLE(data, pos - 8)
        val blockStart = pos - 8 - sizeOfBlock.toInt() + 8 // blockStart = (pos-8) - sizeOfBlock + 8
        // block 内容：size_of_block(8) + pairs + size_of_block(8) + magic(16)
        // pairs 起点 = blockStart + 8
        var p = blockStart + 8
        val out = mutableListOf<SignerInfo>()
        while (p + 12 <= pos - 8) {
            val pairLen = readLongLE(data, p).toInt() // length of (id+value)
            if (pairLen <= 0 || p + 8 + pairLen > pos - 8) break
            val id = readIntLE(data, p + 8)
            val valueStart = p + 12
            val valueEnd = p + 8 + pairLen
            val value = data.copyOfRange(valueStart, valueEnd)
            when (id.toLong()) {
                V2_ID -> extractSigners(value, "v2 (APK Signature Scheme v2)")?.let { out.add(it) }
                V3_ID -> extractSigners(value, "v3 (APK Signature Scheme v3)")?.let { out.add(it) }
            }
            p = valueEnd
            // pairs 按 4 字节对齐？v2 pair 是 8 字节对齐，这里粗略处理
            while (p < pos - 8 && (p - blockStart) % 8 != 0) p++
        }
        return out
    }

    private fun extractSigners(value: ByteArray, scheme: String): SignerInfo? {
        // value = length-prefixed sequence of signers
        val buf = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        val signersBlock = readLenPrefixed(buf) ?: return null
        val signer = readLenPrefixed(signersBlock) ?: return null
        // signer = signed_data(len-prefixed) + signatures(len-prefixed) + public_key(len-prefixed)
        val signedData = readLenPrefixed(signer) ?: return null
        // signed_data = digests(seq) + certificates(seq) + additional_attributes(seq)
        val digests = readLenPrefixed(signedData) // skip
        val certsBlock = readLenPrefixed(signedData) ?: return SignerInfo(scheme, emptyList())
        val cf = CertificateFactory.getInstance("X.509")
        val certs = mutableListOf<X509Certificate>()
        while (certsBlock.remaining() >= 4) {
            val cert = readLenPrefixed(certsBlock) ?: break
            val der = ByteArray(cert.remaining())
            cert.get(der)
            try {
                val x = cf.generateCertificate(ByteArrayInputStream(der)) as? X509Certificate
                x?.let { certs.add(it) }
            } catch (_: Throwable) { }
        }
        return SignerInfo(scheme, certs)
    }

    private fun readLenPrefixed(buf: ByteBuffer): ByteBuffer? {
        if (buf.remaining() < 4) return null
        val len = buf.int
        if (len <= 0 || len > buf.remaining()) return null
        val slice = buf.slice().order(ByteOrder.LITTLE_ENDIAN)
        slice.limit(len)
        buf.position(buf.position() + len)
        return slice
    }

    private fun findCentralDirectoryOffset(data: ByteArray): Int? {
        // EOCD magic 0x06054b50, 从末尾向前找
        val eocd = intArrayOf(0x50, 0x4B, 0x05, 0x06)
        val minPos = maxOf(0, data.size - 65557)
        for (i in data.size - 22 downTo minPos) {
            if (data[i] == 0x50.toByte() && data[i + 1] == 0x4B.toByte() &&
                data[i + 2] == 0x05.toByte() && data[i + 3] == 0x06.toByte()
            ) {
                // CD offset at EOCD + 16
                val off = readIntLE(data, i + 16)
                return off
            }
        }
        return null
    }

    private fun readIntLE(data: ByteArray, off: Int): Int =
        (data[off].toInt() and 0xFF) or
            ((data[off + 1].toInt() and 0xFF) shl 8) or
            ((data[off + 2].toInt() and 0xFF) shl 16) or
            ((data[off + 3].toInt() and 0xFF) shl 24)

    private fun readLongLE(data: ByteArray, off: Int): Long =
        readIntLE(data, off).toLong() and 0xFFFFFFFFL or
            ((readIntLE(data, off + 4).toLong() and 0xFFFFFFFFL) shl 32)

    private fun matchesAt(data: ByteArray, off: Int, pattern: ByteArray): Boolean {
        if (off < 0 || off + pattern.size > data.size) return false
        for (i in pattern.indices) if (data[off + i] != pattern[i]) return false
        return true
    }
}
