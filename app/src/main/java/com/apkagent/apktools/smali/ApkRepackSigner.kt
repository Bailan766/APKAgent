package com.apkagent.apktools.smali

import com.apkagent.i18n.L
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * APK 重打包 + 签名引擎。
 *
 * 修复说明：
 * 1. 重打包时对 resources.arsc 强制使用 ZipEntry.STORED（不压缩）并按 4 字节对齐，
 *    解决 Android 11+ (targetSdk >= 30) 因 resources.arsc 被压缩或未对齐导致的安装失败。
 * 2. 支持 v1 + v2 + v3 签名方案。
 */
object ApkRepackSigner {

    private val STORED_EXTENSIONS = setOf(
        "arsc", "png", "jpg", "jpeg", "gif", "webp", "mp3", "mp4", "ogg", "wav", "flac"
    )

    /**
     * 重打包：将源目录内容打包为 APK，排除 META-INF 旧签名，自动对齐 resources.arsc 等未压缩资源。
     */
    fun repack(srcDir: File, outputApk: File): RepackResult {
        if (!srcDir.exists() || !srcDir.isDirectory)
            return RepackResult(false, L("源目录不存在或不是目录", "Source directory does not exist or is not a directory"), 0)
        outputApk.parentFile?.mkdirs()
        var count = 0
        try {
            val countingOut = CountingOutputStream(FileOutputStream(outputApk).buffered())
            ZipOutputStream(countingOut).use { zos ->
                srcDir.walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.path }
                    .forEach { f ->
                        val rel = srcDir.toURI().relativize(f.toURI()).path
                        // 排除旧签名
                        val upper = rel.uppercase()
                        if (upper.startsWith("META-INF/") &&
                            (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") ||
                             upper.endsWith(".EC") || upper.endsWith(".MF"))) return@forEach

                        val ext = f.extension.lowercase()
                        val shouldStore = rel == "resources.arsc" || ext in STORED_EXTENSIONS
                        val entry = ZipEntry(rel)

                        if (shouldStore) {
                            val bytes = f.readBytes()
                            entry.method = ZipEntry.STORED
                            entry.size = bytes.size.toLong()
                            entry.compressedSize = bytes.size.toLong()
                            val crc = CRC32()
                            crc.update(bytes)
                            entry.crc = crc.value

                            zos.flush()
                            val nameBytesLen = rel.toByteArray(Charsets.UTF_8).size
                            val headerBase = countingOut.bytesWritten + 30L + nameBytesLen
                            val misalignment = (headerBase % 4L).toInt()
                            val padding = if (misalignment == 0) 0 else (4 - misalignment)
                            if (padding > 0) {
                                entry.extra = ByteArray(padding)
                            }
                            zos.putNextEntry(entry)
                            zos.write(bytes)
                            zos.closeEntry()
                        } else {
                            entry.method = ZipEntry.DEFLATED
                            zos.putNextEntry(entry)
                            f.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                        count++
                    }
            }
            return RepackResult(true, "重打包完成（已自动 ZipAlign 对齐 resources.arsc），包含 $count 个文件", count)
        } catch (e: Throwable) {
            return RepackResult(false, "重打包失败：${e.message}", 0)
        }
    }

    /**
     * 从已有 APK 提取所有条目到目录（用于修改后重打包）。
     */
    fun unpackApk(apkFile: File, outDir: File): RepackResult {
        if (!apkFile.exists()) return RepackResult(false, L("APK 不存在", "APK does not exist"), 0)
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
     * 签名 APK：支持 v1 + v2 + v3 签名方案。
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
        if (!inputApk.exists()) return SignResult(false, L("输入 APK 不存在", "Input APK does not exist"), null, null)

        val ks: File
        val alias: String
        val kPass: String
        val ksPass: String
        if (keystoreFile != null) {
            ks = keystoreFile
            alias = keyAlias ?: "key0"
            kPass = keyPass ?: (keystorePass ?: "android")
            ksPass = keystorePass ?: "android"
        } else {
            val dbg = DebugKeyProvider.getOrCreate()
            ks = dbg.keystoreFile
            alias = dbg.alias
            kPass = dbg.keyPass
            ksPass = dbg.storePass
        }

        return try {
            outputApk.parentFile?.mkdirs()
            val keyStore = KeyStore.getInstance("PKCS12").also {
                FileInputStream(ks).use { fis -> it.load(fis, ksPass.toCharArray()) }
            }
            val key = keyStore.getKey(alias, kPass.toCharArray())
                ?: return SignResult(false, "未找到别名 $alias 的密钥", null, null)
            val privateKey = key as java.security.PrivateKey
            val certs: List<X509Certificate> = keyStore.getCertificateChain(alias)
                ?.mapNotNull { it as? X509Certificate }
                ?: return SignResult(false, L("未找到证书链", "Certificate chain not found"), null, null)
            if (certs.isEmpty()) return SignResult(false, L("证书链为空", "Certificate chain is empty"), null, null)

            val signerConfig = ApkSigner.SignerConfig.Builder(
                "apkagent", privateKey, certs
            ).build()

            val signer = ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setV1SigningEnabled(v1Enabled)
                .setV2SigningEnabled(v2Enabled)
                .setV3SigningEnabled(v2Enabled)
                .build()

            signer.sign()

            val verifier = ApkVerifier.Builder(outputApk).build()
            val result = verifier.verify()
            val schemes = mutableListOf<String>()
            if (result.isVerifiedUsingV1Scheme) schemes.add("v1")
            if (result.isVerifiedUsingV2Scheme) schemes.add("v2")
            if (result.isVerifiedUsingV3Scheme) schemes.add("v3")

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

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            bytesWritten += 1
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            bytesWritten += len
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
