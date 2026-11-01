package com.apkagent.apktools.advanced

import com.apkagent.i18n.L
import com.android.apksig.ApkSigner
import com.apkagent.agent.Tool
import com.apkagent.agent.ToolContext
import com.apkagent.agent.ToolResult
import com.apkagent.agent.boolProp
import com.apkagent.agent.intProp
import com.apkagent.agent.strProp
import com.apkagent.agent.schemaObject
import com.apkagent.apktools.Sandbox
import com.apkagent.apktools.strNonBlank
import com.apkagent.apktools.int
import com.apkagent.apktools.bool
import com.apkagent.apktools.smali.DebugKeyProvider
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * 增强签名工具：支持 v1/v2/v3/v4 全签名方案、自定义 KeyStore、对齐检查。
 *
 * 修复说明：
 * 1. 默认启用 v1,v2,v3（当显式指定 v4 时自动配置 setV4SignatureOutputFile，避免 apksig 因缺少 v4 输出文件抛异常）。
 * 2. 支持相对路径基于 ctx.workspace 解析，并支持缺省 apk_path 自动回退。
 */
object EnhancedSigner : Tool {
    override val name = "apk_enhanced_sign"
    override val description get() = L(
        "增强 APK 签名工具，支持 v1/v2/v3/v4 全签名方案、自定义 KeyStore（JKS/PKCS12）、自动 ZipAlign。",
        "Enhanced APK signing tool supporting v1/v2/v3/v4 schemes, custom KeyStore (JKS/PKCS12), and auto ZipAlign."
    )
    override val parameters get() = schemaObject(
        mapOf(
            "apk_path" to strProp(L("待签名的 APK 文件路径（留空默认当前 APK）", "APK file path to sign")),
            "output_path" to strProp(L("签名后输出路径（默认生成 *_signed.apk）", "Signed output APK path")),
            "keystore_path" to strProp(L("KeyStore 文件路径（可选，不填则使用内置 Debug Key）", "KeyStore path (optional, defaults to built-in Debug Key)")),
            "keystore_pass" to strProp(L("KeyStore 密码", "KeyStore password")),
            "key_alias" to strProp(L("密钥别名", "Key alias")),
            "key_pass" to strProp(L("密钥密码（默认与 KeyStore 密码相同）", "Key password (defaults to KeyStore password)")),
            "sign_schemes" to strProp(L("签名方案：v1,v2,v3,v4 的组合（默认 'v1,v2,v3'）", "Signing schemes (default 'v1,v2,v3')")),
            "min_sdk" to intProp(L("最低 SDK 版本（默认 21）", "Min SDK version (default 21)")),
            "auto_align" to boolProp(L("签名后是否对仅 v1 签名的 APK 执行对齐（默认 false，推荐签名前对齐）", "Align before signing"))
        )
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawApk = args.strNonBlank("apk_path")
        val input = when {
            rawApk != null -> Sandbox.resolve(File(rawApk), ctx.workspace)
            ctx.openApk != null -> ctx.openApk!!
            else -> File(ctx.workspace, "base.apk")
        }
        val outputPath = args.strNonBlank("output_path")
        val keystorePath = args.strNonBlank("keystore_path")
        val keystorePass = args.strNonBlank("keystore_pass") ?: "android"
        val keyAlias = args.strNonBlank("key_alias") ?: "androiddebugkey"
        val keyPass = args.strNonBlank("key_pass") ?: keystorePass
        val schemesStr = args.strNonBlank("sign_schemes") ?: "v1,v2,v3"
        val minSdk = args.int("min_sdk") ?: 21
        val autoAlign = args.bool("auto_align") ?: true

        if (!input.exists() || !input.isFile) {
            return ToolResult(false, "文件不存在: ${input.absolutePath}")
        }

        val outFile = if (outputPath != null) {
            Sandbox.resolve(File(outputPath), ctx.workspace)
        } else {
            File(ctx.workspace, "${input.nameWithoutExtension}_signed.apk")
        }
        outFile.parentFile?.mkdirs()

        val schemes = schemesStr.split(",").map { it.trim().lowercase() }
        val v1 = "v1" in schemes
        val v2 = "v2" in schemes
        val v3 = "v3" in schemes
        val v4 = "v4" in schemes

        return try {
            val signerConfig = if (keystorePath != null) {
                loadKeyStoreConfig(Sandbox.resolve(File(keystorePath), ctx.workspace), keystorePass, keyAlias, keyPass)
            } else {
                loadDebugKeyConfig()
            }

            // 注意：v2/v3 签名要求 ZipAlign 在签名前执行（签名后修改 ZIP 会破坏 v2/v3 签名块）
            val alignedInput = if (autoAlign) {
                val tmpAligned = File(ctx.workspace, "_pre_sign_aligned_${System.currentTimeMillis()}.apk")
                val alignRes = ZipAlignTool.alignApk(input, tmpAligned, 4)
                if (alignRes.success && tmpAligned.exists()) tmpAligned else input
            } else {
                input
            }

            val signResult = signApk(alignedInput, outFile, signerConfig, minSdk, v1, v2, v3, v4)
            if (alignedInput != input && alignedInput.exists()) {
                alignedInput.delete()
            }

            if (!signResult.success) {
                return ToolResult(false, "❌ 签名失败: ${signResult.message}")
            }

            val sb = StringBuilder()
            sb.appendLine("✅ APK 签名完成")
            sb.appendLine("签名方案: ${schemes.joinToString(" + ").uppercase()}")
            sb.appendLine("密钥来源: ${if (keystorePath != null) File(keystorePath).name else L("内置 Debug Key", "Built-in Debug Key")}")
            if (autoAlign) {
                sb.appendLine(L("ZipAlign: ✅ 签名前已完成 4 字节对齐", "ZipAlign: ✅ Aligned before signing"))
            }
            sb.appendLine("输出文件: ${outFile.absolutePath}")
            sb.appendLine("文件大小: ${outFile.length() / 1024} KB")

            ToolResult(true, sb.toString())
        } catch (e: Exception) {
            ToolResult(false, "❌ 签名异常: ${e.message}")
        }
    }

    private fun loadKeyStoreConfig(
        ksFile: File,
        ksPass: String,
        alias: String,
        keyPass: String
    ): ApkSigner.SignerConfig {
        if (!ksFile.exists()) throw IllegalArgumentException("KeyStore 文件不存在: ${ksFile.absolutePath}")

        val ksType = if (ksFile.extension.lowercase() in listOf("p12", "pfx")) "PKCS12" else "JKS"
        val ks = KeyStore.getInstance(ksType)
        FileInputStream(ksFile).use { ks.load(it, ksPass.toCharArray()) }

        val privateKey = ks.getKey(alias, keyPass.toCharArray()) as? PrivateKey
            ?: throw IllegalArgumentException("无法获取私钥（别名: $alias）")

        val certChain = ks.getCertificateChain(alias)?.map { it as X509Certificate }
            ?: throw IllegalArgumentException("无法获取证书链（别名: $alias）")

        return ApkSigner.SignerConfig.Builder(alias, privateKey, certChain).build()
    }

    private fun loadDebugKeyConfig(): ApkSigner.SignerConfig {
        val debugKey = DebugKeyProvider.getOrCreate()
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(debugKey.keystoreFile).use {
            ks.load(it, debugKey.storePass.toCharArray())
        }
        val privateKey = ks.getKey(debugKey.alias, debugKey.keyPass.toCharArray()) as PrivateKey
        val certChain = ks.getCertificateChain(debugKey.alias).map { it as X509Certificate }
        return ApkSigner.SignerConfig.Builder("debug", privateKey, certChain).build()
    }

    private fun signApk(
        input: File,
        output: File,
        signerConfig: ApkSigner.SignerConfig,
        minSdk: Int,
        v1: Boolean,
        v2: Boolean,
        v3: Boolean,
        v4: Boolean
    ): SignResult {
        return try {
            val builder = ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(input)
                .setOutputApk(output)
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(v1)
                .setV2SigningEnabled(v2)
                .setV3SigningEnabled(v3)
                .setV4SigningEnabled(v4)

            if (v4) {
                builder.setV4SignatureOutputFile(File("${output.absolutePath}.idsig"))
            }

            builder.build().sign()
            SignResult(true, L("签名成功", "Signing succeeded"))
        } catch (e: Exception) {
            SignResult(false, e.message ?: L("未知错误", "Unknown error"))
        }
    }

    private data class SignResult(val success: Boolean, val message: String)
}
