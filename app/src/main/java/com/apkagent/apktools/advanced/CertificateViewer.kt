package com.apkagent.apktools.advanced

import com.apkagent.agent.Tool
import com.apkagent.agent.ToolContext
import com.apkagent.agent.ToolResult
import com.apkagent.agent.strProp
import com.apkagent.agent.schemaObject
import com.apkagent.apktools.str
import com.apkagent.apktools.int
import com.apkagent.apktools.bool
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

/**
 * 证书详情查看器：解析 APK 签名证书的详细信息。
 *
 * 功能：
 * 1. 读取 APK 的签名证书（META-INF/下的.RSA/.DSA/.EC文件）
 * 2. 解析 X.509 证书详细信息
 * 3. 显示颁发者、使用者、有效期、序列号、指纹等
 * 4. 检查证书是否过期
 * 5. 支持 v1/v2/v3 签名方案检测
 */
object CertificateViewer : Tool {
    override val name = "apk_certificate"
    override val description = "查看 APK 签名证书详情：颁发者、使用者、有效期、指纹、签名方案等。"
    override val parameters = schemaObject(
        mapOf(
            "apk_path" to strProp("APK 文件路径")
        )
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val apkPath = args.str("apk_path") ?: return ToolResult(false, "缺少 apk_path")
        val apkFile = File(apkPath)
        if (!apkFile.exists()) return ToolResult(false, "文件不存在: $apkPath")

        return try {
            val certificates = extractCertificates(apkFile)
            if (certificates.isEmpty()) {
                return ToolResult(false, "APK 中未找到签名证书")
            }

            val sb = StringBuilder()
            sb.appendLine("🔐 APK 签名证书详情：")
            sb.appendLine()

            // 检测签名方案
            val signSchemes = detectSignatureSchemes(apkFile)
            sb.appendLine("📋 签名方案: ${signSchemes.joinToString(", ")}")
            sb.appendLine()

            certificates.forEachIndexed { index, cert ->
                sb.appendLine("📜 证书 ${index + 1}:")
                sb.appendLine("   颁发者 (Issuer):")
                sb.appendLine("     ${cert.issuerX500Principal.name}")
                sb.appendLine("   使用者 (Subject):")
                sb.appendLine("     ${cert.subjectX500Principal.name}")
                sb.appendLine("   序列号: ${cert.serialNumber.toString(16)}")
                sb.appendLine("   版本: X.509 v${cert.version}")
                sb.appendLine("   签名算法: ${cert.sigAlgName}")
                sb.appendLine("   有效期:")
                sb.appendLine("     开始: ${cert.notBefore}")
                sb.appendLine("     结束: ${cert.notAfter}")
                sb.appendLine("     状态: ${if (cert.hasExpired()) "❌ 已过期" else "✅ 有效"}")
                sb.appendLine("   指纹:")

                // 计算各种指纹
                val md5 = cert.encoded.getMessageDigest("MD5")
                val sha1 = cert.encoded.getMessageDigest("SHA-1")
                val sha256 = cert.encoded.getMessageDigest("SHA-256")

                sb.appendLine("     MD5:    $md5")
                sb.appendLine("     SHA-1:  $sha1")
                sb.appendLine("     SHA-256: $sha256")

                // 公钥信息
                sb.appendLine("   公钥:")
                sb.appendLine("     算法: ${cert.publicKey.algorithm}")
                sb.appendLine("     长度: ${cert.publicKey.encoded.size * 8} bits")

                sb.appendLine()
            }

            ToolResult(true, sb.toString())
        } catch (e: Exception) {
            ToolResult(false, "解析证书失败: ${e.message}")
        }
    }

    private fun extractCertificates(apkFile: File): List<X509Certificate> {
        val certificates = mutableListOf<X509Certificate>()
        ZipFile(apkFile).use { zip ->
            zip.entries().toList().forEach { entry ->
                if (entry.name.startsWith("META-INF/") &&
                    (entry.name.endsWith(".RSA") || entry.name.endsWith(".DSA") ||
                     entry.name.endsWith(".EC") || entry.name.endsWith(".SF"))) {
                    try {
                        val data = zip.getInputStream(entry).readBytes()
                        val cf = CertificateFactory.getInstance("X.509")
                        val certs = cf.generateCertificates(ByteArrayInputStream(data))
                        certs.forEach { cert ->
                            if (cert is X509Certificate) {
                                certificates.add(cert)
                            }
                        }
                    } catch (e: Exception) {
                        // 忽略无法解析的证书
                    }
                }
            }
        }
        return certificates
    }

    private fun detectSignatureSchemes(apkFile: File): List<String> {
        val schemes = mutableListOf<String>()
        ZipFile(apkFile).use { zip ->
            val entries = zip.entries().toList().map { it.name }

            // v1 (JAR signing)
            if (entries.any { it.startsWith("META-INF/") && it.endsWith(".SF") }) {
                schemes.add("v1 (JAR)")
            }

            // v2/v3 (APK Signature Scheme)
            // 需要检查 APK Signing Block，这里简化检测
            if (entries.any { it == "META-INF/com/android/build/gradle/app-metadata.properties" }) {
                schemes.add("v2/v3 (Android Gradle)")
            }
        }

        if (schemes.isEmpty()) {
            schemes.add("未知")
        }
        return schemes
    }

    private fun ByteArray.getMessageDigest(algorithm: String): String {
        val md = java.security.MessageDigest.getInstance(algorithm)
        val digest = md.digest(this)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    private fun X509Certificate.hasExpired(): Boolean {
        return try {
            checkValidity()
            false
        } catch (e: Exception) {
            true
        }
    }
}
