package com.apkagent.apktools.smali

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * APK 重打包 + 签名引擎。
 *
 * 重打包：将解包目录（含修改后的 DEX/资源）重新压缩为 APK，排除旧签名。
 * 签名：使用 apksig 库实现 v1(JAR/PKCS#7) + v2(APK Signature Scheme v2) 签名。
 *
 * 默认使用内置调试密钥（与 Android Studio debug 签名一致），
 * 也可指定自定义 keystore。
 */
object ApkRepackSigner {

    /**
     * 重打包：将源目录内容打包为 APK，排除 META-INF 旧签名。
     */
    fun repack(srcDir: File, outputApk: File): RepackResult {
        if (!srcDir.exists() || !srcDir.isDirectory)
            return RepackResult(false, "源目录不存在或不是目录", 0)
        outputApk.parentFile?.mkdirs()
        var count = 0
        try {
            ZipOutputStream(FileOutputStream(outputApk)).use { zos ->
                srcDir.walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.path } // 稳定顺序
                    .forEach { f ->
                        val rel = srcDir.toURI().relativize(f.toURI()).path
                        // 排除旧签名
                        if (rel.startsWith("META-INF/") &&
                            (rel.endsWith(".SF") || rel.endsWith(".RSA") || rel.endsWith(".DSA") ||
                             rel.endsWith(".EC") || rel.endsWith(".MF"))) return@forEach
                        zos.putNextEntry(ZipEntry(rel))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        count++
                    }
            }
            return RepackResult(true, "重打包完成，包含 $count 个文件", count)
        } catch (e: Throwable) {
            return RepackResult(false, "重打包失败：${e.message}", 0)
        }
    }

    /**
     * 从已有 APK 提取所有条目到目录（用于修改后重打包）。
     */
    fun unpackApk(apkFile: File, outDir: File): RepackResult {
        if (!apkFile.exists()) return RepackResult(false, "APK 不存在", 0)
        outDir.mkdirs()
        var count = 0
        try {
            ZipFile(apkFile).use { zip ->
                zip.entries().toList().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val outFile = File(outDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { input.copyTo(it) }
                    }
                    count++
                }
            }
            return RepackResult(true, "解包 $count 个文件", count)
        } catch (e: Throwable) {
            return RepackResult(false, "解包失败：${e.message}", 0)
        }
    }

    /**
     * 签名 APK：支持 v1 + v2 签名方案。
     * 使用内置调试密钥（自动生成临时 keystore）或自定义 keystore。
     */
    fun signApk(
        inputApk: File,
        outputApk: File,
        v1Enabled: Boolean = true,
        v2Enabled: Boolean = true,
        keystoreFile: File? = null,
        keystorePass: String? = null,
        keyAlias: String? = null,
        keyPass: String? = null
    ): SignResult {
        if (!inputApk.exists()) return SignResult(false, "输入 APK 不存在", null, null)

        val ks: File
        val alias: String
        val kPass: String
        val ksPass: String
        if (keystoreFile != null) {
            ks = keystoreFile
            alias = keyAlias ?: "key0"
            kPass = keyPass ?: keystorePass!!
            ksPass = keystorePass!!
        } else {
            val dbg = DebugKeyProvider.getOrCreate()
            ks = dbg.keystoreFile
            alias = dbg.alias
            kPass = dbg.keyPass
            ksPass = dbg.storePass
        }

        return try {
            val keyStore = KeyStore.getInstance("PKCS12" /* 兼容 JKS/BKS */).also {
                FileInputStream(ks).use { fis -> it.load(fis, ksPass.toCharArray()) }
            }
            val key = keyStore.getKey(alias, kPass.toCharArray())
                ?: return SignResult(false, "未找到别名 $alias 的密钥", null, null)
            val privateKey = key as java.security.PrivateKey
            val certs: List<X509Certificate> = keyStore.getCertificateChain(alias)
                ?.mapNotNull { it as? X509Certificate }
                ?: return SignResult(false, "未找到证书链", null, null)
            if (certs.isEmpty()) return SignResult(false, "证书链为空", null, null)

            val signerConfig = ApkSigner.SignerConfig.Builder(
                "apkagent", privateKey, certs
            ).build()

            val signer = ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setV1SigningEnabled(v1Enabled)
                .setV2SigningEnabled(v2Enabled)
                .setV3SigningEnabled(false) // v3 可选，暂不启用
                .build()

            signer.sign()

            // 验证签名
            val verifier = ApkVerifier.Builder(outputApk).build()
            val result = verifier.verify()
            val schemes = mutableListOf<String>()
            if (result.isVerifiedUsingV1Scheme) schemes.add("v1")
            if (result.isVerifiedUsingV2Scheme) schemes.add("v2")

            SignResult(
                success = true,
                message = "签名成功，生效方案：${schemes.joinToString("+")}",
                schemes = schemes,
                certInfo = certs.firstOrNull()?.let {
                    "Subject: ${it.subjectX500Principal}\nIssuer: ${it.issuerX500Principal}"
                }
            )
        } catch (e: Throwable) {
            SignResult(false, "签名失败：${e.message}", null, null)
        }
    }

    data class RepackResult(val success: Boolean, val message: String, val fileCount: Int)
    data class SignResult(
        val success: Boolean,
        val message: String,
        val schemes: List<String>?,
        val certInfo: String?
    )
}
