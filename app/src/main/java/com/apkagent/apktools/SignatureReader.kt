package com.apkagent.apktools

import com.apkagent.i18n.L
import com.android.apksig.ApkVerifier
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.jar.JarFile

/**
 * APK 签名信息读取器。
 * - v1 (JAR signing): 解析 META-INF 下的 .RSA/.DSA/.EC 签名块，提取 X.509 证书。
 * - v2/v3 (APK Signature Scheme): 定位 APK Signing Block 提取证书链，并结合 ApkVerifier 校验。
 *
 * 修复说明：
 * 1. 修复 V3_ID (0xf05368c0) 因 readIntLE().toLong() 符号位扩展为负数导致永远无法匹配 V3 签名的 Bug。
 * 2. 修复 APK Signing Block 起始偏移量计算偏差 8 字节（sizeOfBlock 已含尾部 size 8B + magic 16B，
 *    pairs 起点应为 cdOffset - sizeOfBlock）以及误加 8 字节对齐循环导致首轮即中断的 Bug。
 * 3. 增加 Google 官方 apksig ApkVerifier 兜底提取，支持 v1/v2/v3/v3.1 全方案证书读取。
 * 4. readV2V3 改为 RandomAccessFile 流式只读取 EOCD 尾部与 APK Signing Block 区段，
 *    避免 apk.readBytes() 把大 APK 全量读入内存导致 OOM。
 */
object SignatureReader {

    private const val SIG_BLOCK_MAGIC = "APK Sig Block 42"
    private const val V2_ID = 0x7109871aL
    private const val V3_ID = 0xf05368c0L
    private const val V31_ID = 0x1b93ad61L
    private const val MAX_SIGNING_BLOCK_BYTES = 64L * 1024 * 1024

    data class SignerInfo(
        val scheme: String,
        val certs: List<X509Certificate>
    )

    fun read(apk: File): String {
        val sb = StringBuilder()
        sb.appendLine(L("APK 签名分析：${apk.name}", "APK signature analysis: ${apk.name}"))
        sb.appendLine(L("文件大小：${apk.length()} 字节", "File size: ${apk.length()} bytes"))
        sb.appendLine()

        val results = mutableListOf<SignerInfo>()

        // v1
        try {
            val v1 = readV1(apk)
            if (v1 != null) results.add(v1)
        } catch (e: Throwable) {
            sb.appendLine(L("[v1] 读取失败：${e.message}", "[v1] Read failed: ${e.message}"))
        }

        // v2/v3
        try {
            val v2v3 = readV2V3(apk)
            results.addAll(v2v3)
        } catch (e: Throwable) {
            sb.appendLine(L("[v2/v3] 读取失败：${e.message}", "[v2/v3] Read failed: ${e.message}"))
        }

        // 若手动解析未提取到证书，使用 apksig ApkVerifier 权威兜底
        if (results.isEmpty() || results.all { it.certs.isEmpty() }) {
            runCatching {
                val vRes = ApkVerifier.Builder(apk).build().verify()
                val certs = vRes.signerCertificates
                val schemes = buildList {
                    if (vRes.isVerifiedUsingV1Scheme) add("v1")
                    if (vRes.isVerifiedUsingV2Scheme) add("v2")
                    if (vRes.isVerifiedUsingV3Scheme) add("v3")
                }
                if (certs.isNotEmpty() || schemes.isNotEmpty()) {
                    results.clear()
                    results.add(SignerInfo(schemes.joinToString("+").ifBlank { "APK Signature" }, certs))
                }
            }
        }

        if (results.isEmpty()) {
            sb.appendLine(L("⚠ 未检测到任何签名（v1/v2/v3）。该 APK 未签名或签名格式无法识别。", "⚠ No signatures detected (v1/v2/v3). The APK is unsigned or unrecognized."))
            return sb.toString()
        }

        results.forEach { r ->
            sb.appendLine(L("── 签名方案：${r.scheme} ──", "── Signature scheme: ${r.scheme} ──"))
            if (r.certs.isEmpty()) {
                sb.appendLine(L("  （检测到方案但未提取到证书）", "  (scheme detected but no certificate extracted)"))
            } else {
                r.certs.forEachIndexed { i, c ->
                    sb.appendLine("  证书 #${i + 1}")
                    sb.appendLine("    Subject : ${c.subjectX500Principal}")
                    sb.appendLine("    Issuer  : ${c.issuerX500Principal}")
                    sb.appendLine("    Serial  : ${c.serialNumber}")
                    sb.appendLine(L("    算法    : ${c.sigAlgName}", "    Algorithm: ${c.sigAlgName}"))
                    sb.appendLine(L("    有效期  : ${c.notBefore} ~ ${c.notAfter}", "    Validity : ${c.notBefore} ~ ${c.notAfter}"))
                }
            }
            sb.appendLine()
        }

        if (results.none { it.scheme.contains("v2") || it.scheme.contains("v3") }) {
            sb.appendLine(L("ℹ 仅含 v1 签名，在 Android 7.0+ 建议补充 v2/v3 签名以保证完整性校验。", "ℹ v1-only signature; on Android 7.0+ consider adding v2/v3 for integrity checks."))
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

    internal fun readV2V3(apk: File): List<SignerInfo> {
        val magicBytes = SIG_BLOCK_MAGIC.toByteArray(Charsets.US_ASCII)
        return try {
            RandomAccessFile(apk, "r").use { raf ->
                val fileLen = raf.length()
                if (fileLen < 22) return@use emptyList<SignerInfo>()

                // 只读取 ZIP 尾部 EOCD 搜索窗口，避免把整个 APK 读进内存（大 APK OOM）
                val tailLen = minOf(fileLen, 65557L).toInt()
                val tail = ByteArray(tailLen)
                raf.seek(fileLen - tailLen)
                raf.readFully(tail)
                val cdOffset = (findCentralDirectoryOffset(tail) ?: return@use emptyList<SignerInfo>()).toLong() and 0xFFFFFFFFL
                if (cdOffset <= 16 || cdOffset > fileLen) return@use emptyList<SignerInfo>()

                val magicPos = cdOffset - 16
                val magicBuf = ByteArray(16)
                raf.seek(magicPos)
                raf.readFully(magicBuf)
                if (!matchesAt(magicBuf, 0, magicBytes)) return@use emptyList<SignerInfo>()

                val sizeBuf = ByteArray(8)
                raf.seek(magicPos - 8)
                raf.readFully(sizeBuf)
                val sizeOfBlock = readLongLE(sizeBuf, 0)
                if (sizeOfBlock < 24 || sizeOfBlock > cdOffset) return@use emptyList<SignerInfo>()

                val pairsStart = cdOffset - sizeOfBlock
                val pairsEnd = magicPos - 8
                val pairsLen = pairsEnd - pairsStart
                // 仅读取 APK Signing Block 的 pairs 区段；设上限防止畸形包把内存吃满
                if (pairsStart < 8 || pairsLen <= 0 || pairsLen > MAX_SIGNING_BLOCK_BYTES || pairsEnd > fileLen) {
                    return@use emptyList<SignerInfo>()
                }
                val pairs = ByteArray(pairsLen.toInt())
                raf.seek(pairsStart)
                raf.readFully(pairs)

                val out = mutableListOf<SignerInfo>()
                var p = 0
                while (p + 12 <= pairs.size) {
                    val pairLen = readLongLE(pairs, p)
                    if (pairLen < 4 || p + 8 + pairLen > pairs.size) break
                    val id = readIntLE(pairs, p + 8).toLong() and 0xFFFFFFFFL
                    val valueStart = p + 12
                    val valueEnd = (p + 8 + pairLen).toInt()
                    val value = pairs.copyOfRange(valueStart, valueEnd)
                    when (id) {
                        V2_ID -> extractSigners(value, "v2 (APK Signature Scheme v2)")?.let { out.add(it) }
                        V3_ID -> extractSigners(value, "v3 (APK Signature Scheme v3)")?.let { out.add(it) }
                        V31_ID -> extractSigners(value, "v3.1 (APK Signature Scheme v3.1)")?.let { out.add(it) }
                    }
                    p = valueEnd
                }
                return@use out
            }
        } catch (_: Throwable) {
            // 读取/解析异常按「未检测到 v2/v3」处理，交由上层 ApkVerifier 兜底
            emptyList()
        }
    }

    private fun extractSigners(value: ByteArray, scheme: String): SignerInfo? {
        val buf = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        val signersBlock = readLenPrefixed(buf) ?: return null
        val signer = readLenPrefixed(signersBlock) ?: return null
        val signedData = readLenPrefixed(signer) ?: return null
        readLenPrefixed(signedData) // skip digests
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
            } catch (_: Throwable) {}
        }
        return SignerInfo(scheme, certs)
    }

    private fun readLenPrefixed(buf: ByteBuffer): ByteBuffer? {
        if (buf.remaining() < 4) return null
        val len = buf.int
        if (len < 0 || len > buf.remaining()) return null
        val slice = buf.slice().order(ByteOrder.LITTLE_ENDIAN)
        slice.limit(len)
        buf.position(buf.position() + len)
        return slice
    }

    private fun findCentralDirectoryOffset(data: ByteArray): Int? {
        val minPos = maxOf(0, data.size - 65557)
        for (i in data.size - 22 downTo minPos) {
            if (data[i] == 0x50.toByte() && data[i + 1] == 0x4B.toByte() &&
                data[i + 2] == 0x05.toByte() && data[i + 3] == 0x06.toByte()
            ) {
                return readIntLE(data, i + 16)
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
        (readIntLE(data, off).toLong() and 0xFFFFFFFFL) or
            ((readIntLE(data, off + 4).toLong() and 0xFFFFFFFFL) shl 32)

    private fun matchesAt(data: ByteArray, off: Int, pattern: ByteArray): Boolean {
        if (off < 0 || off + pattern.size > data.size) return false
        for (i in pattern.indices) if (data[off + i] != pattern[i]) return false
        return true
    }
}
