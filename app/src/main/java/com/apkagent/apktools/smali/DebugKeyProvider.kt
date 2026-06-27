package com.apkagent.apktools.smali

import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date

/**
 * 内置调试密钥提供者：自动生成自签名 RSA 证书并打包为 PKCS12 keystore。
 *
 * Android 平台自带 BouncyCastle provider（android version），但部分 API 类
 * 在高版本被移除。这里优先用系统 BC，失败则尝试加载内置 org.bouncycastle。
 * 若都不可用，则用 KeyStore.PrivateKeyEntry 直接构造（无需 X509 构建器）。
 */
object DebugKeyProvider {

    data class DebugKey(
        val keystoreFile: File,
        val alias: String,
        val keyPass: String,
        val storePass: String
    )

    private const val ALIAS = "androiddebugkey"
    private const val STORE_PASS = "android"
    private const val KEY_PASS = "android"

    fun getOrCreate(): DebugKey {
        val ksDir = File(System.getProperty("java.io.tmpdir", "/tmp"))
        val ksFile = File(ksDir, "apkagent_debug.p12")
        if (ksFile.exists() && ksFile.length() > 0)
            return DebugKey(ksFile, ALIAS, KEY_PASS, STORE_PASS)
        try {
            createDebugKeystore(ksFile)
        } catch (e: Throwable) {
            // 降级：用更基础的方式
            createDebugKeystoreFallback(ksFile)
        }
        return DebugKey(ksFile, ALIAS, KEY_PASS, STORE_PASS)
    }

    private fun createDebugKeystore(out: File) {
        // 确保 BC provider 可用
        if (Security.getProvider("BC") == null) {
            try {
                Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            } catch (_: Throwable) {
                // Android 系统可能已有 android version BC
            }
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()

        val notBefore = Date(System.currentTimeMillis() - 365L * 24 * 3600 * 1000)
        val notAfter = Date(System.currentTimeMillis() + 3650L * 24 * 3600 * 1000)

        val cert = SelfSignedCertGenerator.generate(
            keyPair, "CN=Android Debug,O=Android,C=US", notBefore, notAfter
        )

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, keyPair.private, KEY_PASS.toCharArray(), arrayOf(cert))
        FileOutputStream(out).use { fos -> ks.store(fos, STORE_PASS.toCharArray()) }
    }

    private fun createDebugKeystoreFallback(out: File) {
        // 降级方案：用 KeyStore 自带能力，证书用 X.509 v1 自签名（更简单）
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()
        val cert = SelfSignedCertGenerator.generateSimple(keyPair)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, keyPair.private, KEY_PASS.toCharArray(), arrayOf(cert))
        FileOutputStream(out).use { fos -> ks.store(fos, STORE_PASS.toCharArray()) }
    }
}
