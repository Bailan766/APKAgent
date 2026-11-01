package com.apkagent.apktools.advanced

import com.apkagent.i18n.L
import com.android.apksig.ApkVerifier
import com.apkagent.agent.Tool
import com.apkagent.agent.ToolContext
import com.apkagent.agent.ToolResult
import com.apkagent.agent.strProp
import com.apkagent.agent.schemaObject
import com.apkagent.apktools.Sandbox
import com.apkagent.apktools.SignatureReader
import com.apkagent.apktools.strNonBlank
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipFile

/**
 * APK 证书详情查看工具。
 *
 * 修复说明：
 * 1. 支持通过 ApkVerifier 与 APK Signing Block 提取纯 v2/v3 签名 APK 的证书（不仅限于 META-INF v1）。
 * 2. 修复 detectSignatureSchemes 误用 app-metadata.properties 判断 v2/v3 的 Bug，改为使用 ApkVerifier 精确校验。
 * 3. 支持 apk_path 缺省回退至当前打开的 APK 并基于 workspace 解析相对路径。
 */
object CertificateViewer : Tool {
    override val name = "apk_certificate"
    override val description get() = L(
        "查看 APK 签名证书的完整信息（颁发者、有效期、指纹、公钥算法、签名方案版本）。",
        "View full APK signing certificate details (Subject, Issuer, validity, fingerprints, public key, and signature schemes)."
    )
    override val parameters get() = schemaObject(
        mapOf(
            "apk_path" to strProp(L("APK 文件路径（留空默认当前打开的 APK）", "APK file path (defaults to currently open APK)"))
        )
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawPath = args.strNonBlank("apk_path")
        val apkFile = when {
            rawPath != null -> Sandbox.resolve(File(rawPath), ctx.workspace)
            ctx.openApk != null -> ctx.openApk!!
            else -> File(ctx.workspace, "base.apk")
        }
        if (!apkFile.exists() || !apkFile.isFile) {
            return ToolResult(false, "文件不存在: ${apkFile.absolutePath}")
        }

        return try {
            val verifyResult = runCatching { ApkVerifier.Builder(apkFile).build().verify() }.getOrNull()
            val certs = extractCertificates(apkFile, verifyResult)
            if (certs.isEmpty()) {
                return ToolResult(false, L("未找到签名证书（APK 可能未签名）", "No signing certificate found (APK may be unsigned)"))
            }

            val schemes = detectSignatureSchemes(apkFile, verifyResult)
            val sb = StringBuilder()
            sb.appendLine("🔐 APK 签名证书详情：${apkFile.name}")
            sb.appendLine("签名方案: ${schemes.joinToString(", ").ifBlank { "未知" }}")
            if (verifyResult != null) {
                sb.appendLine("签名校验状态: ${if (verifyResult.isVerified) "✅ 校验通过" else "⚠️ 校验未通过"}")
            }
            sb.appendLine()

            certs.forEachIndexed { index, cert ->
                if (certs.size > 1) {
                    sb.appendLine("━━ 证书 #${index + 1} ━━")
                }
                sb.appendLine(formatCertificate(cert))
                sb.appendLine()
            }

            ToolResult(true, sb.toString())
        } catch (e: Exception) {
            ToolResult(false, "读取证书失败: ${e.message}")
        }
    }

    private fun extractCertificates(apkFile: File, verifyResult: ApkVerifier.Result?): List<X509Certificate> {
        if (verifyResult != null && verifyResult.signerCertificates.isNotEmpty()) {
            return verifyResult.signerCertificates
        }

        val v2v3Certs = runCatching {
            SignatureReader.readV2V3(apkFile).flatMap { it.certs }
        }.getOrDefault(emptyList())
        if (v2v3Certs.isNotEmpty()) return v2v3Certs.distinct()

        val certs = mutableListOf<X509Certificate>()
        ZipFile(apkFile).use { zip ->
            zip.entries().toList().forEach { entry ->
                val upper = entry.name.uppercase()
                if (upper.startsWith("META-INF/") &&
                    (upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"))
                ) {
                    try {
                        zip.getInputStream(entry).use { input ->
                            val cf = CertificateFactory.getInstance("X.509")
                            val certCollection = cf.generateCertificates(input)
                            certCollection.forEach { cert ->
                                if (cert is X509Certificate) {
                                    certs.add(cert)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        return certs
    }

    private fun detectSignatureSchemes(apkFile: File, verifyResult: ApkVerifier.Result?): List<String> {
        val schemes = mutableListOf<String>()
        if (verifyResult != null) {
            if (verifyResult.isVerifiedUsingV1Scheme) schemes.add("v1 (JAR Signing)")
            if (verifyResult.isVerifiedUsingV2Scheme) schemes.add("v2 (APK Signature Scheme v2)")
            if (verifyResult.isVerifiedUsingV3Scheme) schemes.add("v3 (APK Signature Scheme v3)")
            if (schemes.isNotEmpty()) return schemes
        }

        ZipFile(apkFile).use { zip ->
            val hasV1 = zip.entries().toList().any {
                val u = it.name.uppercase()
                u.startsWith("META-INF/") && (u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".EC"))
            }
            if (hasV1) schemes.add("v1 (JAR Signing)")
        }

        runCatching {
            SignatureReader.readV2V3(apkFile).forEach { info ->
                if (info.scheme !in schemes) schemes.add(info.scheme)
            }
        }
        return schemes
    }

    private fun formatCertificate(cert: X509Certificate): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val encoded = cert.encoded

        val md5 = digest(encoded, "MD5")
        val sha1 = digest(encoded, "SHA-1")
        val sha256 = digest(encoded, "SHA-256")

        val sb = StringBuilder()
        sb.appendLine("📋 基本信息:")
        sb.appendLine("  主题 (Subject): ${cert.subjectX500Principal.name}")
        sb.appendLine("  颁发者 (Issuer): ${cert.issuerX500Principal.name}")
        sb.appendLine("  序列号: ${cert.serialNumber.toString(16).uppercase()}")
        sb.appendLine("  版本: v${cert.version}")
        sb.appendLine()

        sb.appendLine("📅 有效期:")
        sb.appendLine("  开始时间: ${dateFormat.format(cert.notBefore)}")
        sb.appendLine("  结束时间: ${dateFormat.format(cert.notAfter)}")
        val now = System.currentTimeMillis()
        val isValid = now >= cert.notBefore.time && now <= cert.notAfter.time
        sb.appendLine("  状态: ${if (isValid) L("✅ 有效", "✅ Valid") else L("❌ 已过期或未生效", "❌ Expired or not yet valid")}")
        sb.appendLine()

        sb.appendLine("🔑 密钥信息:")
        sb.appendLine("  签名算法: ${cert.sigAlgName}")
        sb.appendLine("  公钥算法: ${cert.publicKey.algorithm}")
        sb.appendLine("  公钥长度: ${getKeySize(cert)} bits")
        sb.appendLine()

        sb.appendLine("🔍 证书指纹:")
        sb.appendLine("  MD5:    $md5")
        sb.appendLine("  SHA-1:  $sha1")
        sb.appendLine("  SHA-256: $sha256")

        if (cert.subjectX500Principal.name.contains("Android Debug", ignoreCase = true)) {
            sb.appendLine()
            sb.appendLine(L("⚠️ 这是 Android Debug 调试证书（非正式发布签名）", "⚠️ This is an Android Debug certificate"))
        }

        return sb.toString()
    }

    private fun digest(data: ByteArray, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        val hash = md.digest(data)
        return hash.joinToString(":") { "%02X".format(it) }
    }

    private fun getKeySize(cert: X509Certificate): Int {
        return try {
            when (val pk = cert.publicKey) {
                is java.security.interfaces.RSAPublicKey -> pk.modulus.bitLength()
                is java.security.interfaces.ECPublicKey -> pk.params.order.bitLength()
                is java.security.interfaces.DSAPublicKey -> pk.params.p.bitLength()
                else -> -1
            }
        } catch (_: Exception) {
            -1
        }
    }
}
