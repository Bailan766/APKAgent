package com.apkagent.apktools.smali

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
 *
 * 修复说明：
 * 1. keystore 放到应用私有目录（拿不到时退化为进程独立临时目录），不再复用固定全局 tmp 路径。
 * 2. getOrCreate 加 @Synchronized，避免并发创建时返回半写文件。
 * 3. 先写临时文件 + 写完校验可读 + 原子替换，异常时只清理临时文件，不留下半成品。
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
    private const val KEY_FILE = "apkagent_debug.p12"

    @Volatile private var ksDirCache: File? = null

    /** 应用私有目录；反射拿不到 Application 时退化为进程独立临时目录，避免全局固定路径复用。 */
    private fun keystoreDir(): File {
        ksDirCache?.let { return it }
        val base = appFilesDir()?.let { File(it, "keystore") }
            ?: File(System.getProperty("java.io.tmpdir", "/tmp"), "apkagent_ks_${System.nanoTime()}")
        base.mkdirs()
        ksDirCache = base
        return base
    }

    /** 通过隐藏 API 反射获取当前 Application 的 Context（不引入跨模块新 API）。 */
    private fun appFilesDir(): File? {
        val suppliers = listOf<() -> Any?>(
            { Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) },
            { Class.forName("android.app.AppGlobals").getMethod("getInitialApplication").invoke(null) }
        )
        for (supply in suppliers) {
            val ctx = runCatching { supply() as? android.content.Context }.getOrNull()
            if (ctx != null) return ctx.filesDir
        }
        return null
    }

    @Synchronized
    fun getOrCreate(): DebugKey {
        val ksFile = File(keystoreDir(), KEY_FILE)
        if (ksFile.exists() && ksFile.length() > 0 && isReadableKeystore(ksFile)) {
            return DebugKey(ksFile, ALIAS, KEY_PASS, STORE_PASS)
        }

        // 始终写入同目录临时文件，成功并校验可读后再原子替换，异常时只留下可用的旧文件或无半成品
        val tmp = File(ksFile.parentFile, "${KEY_FILE}.${System.nanoTime()}.tmp")
        try {
            try {
                createDebugKeystore(tmp)
            } catch (e: Throwable) {
                // 降级：用更基础的方式
                tmp.delete()
                createDebugKeystoreFallback(tmp)
            }
            if (!isReadableKeystore(tmp)) {
                tmp.delete()
                throw IllegalStateException("调试 keystore 写入后校验失败")
            }
            replaceAtomically(tmp, ksFile)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
        return DebugKey(ksFile, ALIAS, KEY_PASS, STORE_PASS)
    }

    /** 校验 keystore 可被加载且包含目标别名，避免返回半写文件。 */
    private fun isReadableKeystore(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return try {
            val ks = KeyStore.getInstance("PKCS12")
            FileInputStream(file).use { ks.load(it, STORE_PASS.toCharArray()) }
            ks.containsAlias(ALIAS) && ks.getKey(ALIAS, KEY_PASS.toCharArray()) != null
        } catch (_: Throwable) {
            false
        }
    }

    /** 临时文件原子替换到目标；优先 rename，失败时退化为覆盖复制。 */
    private fun replaceAtomically(tmp: File, target: File) {
        if (tmp.renameTo(target)) return
        if (target.exists()) target.delete()
        if (tmp.renameTo(target)) return
        tmp.copyTo(target, overwrite = true)
        tmp.delete()
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
