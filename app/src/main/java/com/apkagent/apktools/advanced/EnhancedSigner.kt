package com.apkagent.apktools.advanced

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.apkagent.agent.Tool
import com.apkagent.agent.ToolContext
import com.apkagent.agent.ToolResult
import com.apkagent.agent.strProp
import com.apkagent.agent.schemaObject
import com.apkagent.apktools.str
import com.apkagent.apktools.int
import com.apkagent.apktools.bool
import com.apkagent.apktools.smali.DebugKeyProvider
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.KeyStore

/**
 * 增强版签名工具：支持 v1/v2/v3/v4 签名方案。
 *
 * 基于 AEE 的 APKSigner 实现，扩展支持：
 * 1. v1 签名（JAR/PKCS#7）
 * 2. v2 签名（APK Signature Scheme v2）
 * 3. v3 签名（APK Signature Scheme v3，支持密钥轮换）
 * 4. v4 签名（APK Signature Scheme v4，增量安装）
 * 5. 自定义 keystore 支持
 * 6. 签名验证
 * 7. 多种签名方案组合
 */
object EnhancedSigner : Tool {
    override val name = "apk_enhanced_sign"
    override val description = "增强版 APK 签名，支持 v1/v2/v3/v4 签名方案，支持自定义 keystore 和签名验证。"
    override val parameters = schemaObject(
        mapOf(
            "apk_path" to strProp("APK 文件路径"),
            "output_path" to strProp("输出路径（默认覆盖原文件）"),
            "keystore_path" to strProp("Keystore 文件路径（可选，默认使用内置调试密钥）"),
            "keystore_pass" to strProp("Keystore 密码（可选）"),
            "key_alias" to strProp("密钥别名（可选）"),
            "key_pass" to strProp("密钥密码（可选）"),
            "sign_schemes" to strProp("签名方案，逗号分隔（默认 v1,v2,v3,v4）"),
            "verify_only" to strProp("仅验证签名，不重新签名（true/false）")
        )
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val apkPath = args.str("apk_path") ?: return ToolResult(false, "缺少 apk_path")
        val outputPath = args.str("output_path")
        val keystorePath = args.str("keystore_path")
        val keystorePass = args.str("keystore_pass")
        val keyAlias = args.str("key_alias")
        val keyPass = args.str("key_pass")
        val signSchemesStr = args.str("sign_schemes") ?: "v1,v2,v3,v4"
        val verifyOnly = args.str("verify_only")?.toBoolean() ?: false

        val inputApk = File(apkPath)
        if (!inputApk.exists()) return ToolResult(false, "文件不存在: $apkPath")

        // 验证模式
        if (verifyOnly) {
            return verifySignature(inputApk)
        }

        // 签名模式
        val outputApk = if (outputPath != null) File(outputPath) else File(inputApk.parent, "${inputApk.nameWithoutExtension}_signed.apk")

        // 解析签名方案
        val schemes = signSchemesStr.split(",").map { it.trim().lowercase() }
        val v1 = schemes.contains("v1")
        val v2 = schemes.contains("v2")
        val v3 = schemes.contains("v3")
        val v4 = schemes.contains("v4")

        return try {
            val result = signApk(inputApk, outputApk, v1, v2, v3, v4, keystorePath, keystorePass, keyAlias, keyPass)
            if (result.success) {
                ToolResult(true, "✅ 签名完成\n${result.message}\n输出: ${outputApk.absolutePath}")
            } else {
                ToolResult(false, "❌ 签名失败: ${result.message}")
            }
        } catch (e: Exception) {
            ToolResult(false, "❌ 签名异常: ${e.message}")
        }
    }

    private fun signApk(
        inputApk: File,
        outputApk: File,
        v1: Boolean,
        v2: Boolean,
        v3: Boolean,
        v4: Boolean,
        keystorePath: String?,
        keystorePass: String?,
        keyAlias: String?,
        keyPass: String?
    ): SignResult {
        try {
            // 准备密钥
            val ks: KeyStore
            val alias: String
            val kPass: String
            val ksPass: String

            if (keystorePath != null && keystorePass != null && keyAlias != null) {
                // 使用自定义 keystore
                val ksFile = File(keystorePath)
                if (!ksFile.exists()) return SignResult(false, "Keystore 文件不存在: $keystorePath")

                ks = KeyStore.getInstance(ksFile, keystorePass.toCharArray())
                alias = keyAlias
                kPass = keyPass ?: keystorePass
                ksPass = keystorePass
            } else {
                // 使用内置调试密钥
                val debugKey = DebugKeyProvider.getOrCreate()
                ks = java.security.KeyStore.getInstance(debugKey.keystoreFile, debugKey.storePass.toCharArray())
                alias = debugKey.alias
                kPass = debugKey.keyPass
                ksPass = debugKey.storePass
            }

            // 获取私钥和证书链
            val privateKey = ks.getKey(alias, kPass.toCharArray()) as java.security.PrivateKey
            val certChain = ks.getCertificateChain(alias).map { it as java.security.cert.X509Certificate }

            // 配置签名
            val signerConfig = ApkSigner.SignerConfig.Builder(
                "APKAgent",
                privateKey,
                certChain
            ).build()

            val builder = ApkSigner.Builder(listOf(signerConfig))
            builder.setInputApk(inputApk)
            builder.setOutputApk(outputApk)
            builder.setCreatedBy("APKAgent v3.16.0")

            // 设置签名方案
            builder.setV1SigningEnabled(v1)
            builder.setV2SigningEnabled(v2)
            builder.setV3SigningEnabled(v3)
            builder.setV4SigningEnabled(v4)

            builder.setMinSdkVersion(-1) // 使用原始 APK 的 minSdkVersion

            // 执行签名
            val signer = builder.build()
            signer.sign()

            // 验证签名
            val verifier = ApkVerifier.Builder(outputApk).build()
            val result = verifier.verify()

            if (result.isVerified) {
                val sb = StringBuilder()
                sb.appendLine("签名方案:")
                if (v1) sb.appendLine("  • v1 (JAR/PKCS#7)")
                if (v2) sb.appendLine("  • v2 (APK Signature Scheme v2)")
                if (v3) sb.appendLine("  • v3 (APK Signature Scheme v3)")
                if (v4) sb.appendLine("  • v4 (APK Signature Scheme v4)")
                sb.appendLine("证书信息:")
                sb.appendLine("  颁发者: ${certChain[0].issuerX500Principal.name}")
                sb.appendLine("  使用者: ${certChain[0].subjectX500Principal.name}")
                sb.appendLine("  有效期: ${certChain[0].notBefore} - ${certChain[0].notAfter}")

                return SignResult(true, sb.toString())
            } else {
                return SignResult(false, "签名验证失败: ${result.warnings}")
            }
        } catch (e: Exception) {
            return SignResult(false, "签名失败: ${e.message}")
        }
    }

    private fun verifySignature(apkFile: File): ToolResult {
        return try {
            val verifier = ApkVerifier.Builder(apkFile).build()
            val result = verifier.verify()

            val sb = StringBuilder()
            sb.appendLine("🔍 APK 签名验证结果：")
            sb.appendLine()

            if (result.isVerified) {
                sb.appendLine("✅ 签名验证通过")
                sb.appendLine()

                // 签名方案信息
                sb.appendLine("📋 签名方案:")
                val schemes = mutableListOf<String>()
                if (result.isVerifiedUsingV1Scheme) schemes.add("v1 (JAR/PKCS#7)")
                if (result.isVerifiedUsingV2Scheme) schemes.add("v2 (APK Signature Scheme v2)")
                if (result.isVerifiedUsingV3Scheme) schemes.add("v3 (APK Signature Scheme v3)")
                if (result.isVerifiedUsingV4Scheme) schemes.add("v4 (APK Signature Scheme v4)")
                schemes.forEach { sb.appendLine("  • $it ✅") }

                ToolResult(true, sb.toString())
            } else {
                sb.appendLine("❌ 签名验证失败")
                result.errors.forEach { error ->
                    sb.appendLine("  • $error")
                }

                ToolResult(false, sb.toString())
            }
        } catch (e: Exception) {
            ToolResult(false, "验证失败: ${e.message}")
        }
    }

    private data class SignResult(val success: Boolean, val message: String)
}
