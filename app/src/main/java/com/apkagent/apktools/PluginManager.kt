package com.apkagent.apktools

import com.apkagent.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 插件系统 — AI 可自行下载/缓存/管理 APK 逆向分析插件。
 *
 * 插件目录结构： workspace/plugins/
 *   ├── catalog.json          # 内置插件清单
 *   ├── frida-scripts/         # Frida Hook 脚本
 *   ├── smali-patches/         # 通用 smali 补丁
 *   ├── deobfuscation/         # 反混淆脚本
 *   ├── unpackers/             # 加固壳脱壳脚本
 *   └── custom/                # 用户自定义
 */
object PluginManager {

    data class PluginInfo(
        val id: String,
        val name: String,
        val description: String,
        val category: String,
        val url: String?,
        val filename: String,
        val sizeKB: Int = 0,
        val installed: Boolean = false
    )

    /** 内置插件清单 — AI 不用联网就能看 */
    val BUILTIN_CATALOG: List<PluginInfo> = listOf(
        // Frida 脚本
        PluginInfo("frida-antiroot", "绕过 Root 检测", "Frida 脚本绕过常见 Root/Magisk 检测", "frida", null, "antiroot.js"),
        PluginInfo("frida-sslpin", "绕过 SSL Pinning", "Frida 脚本绕过 OkHttp/TrustManager/SSLSocket SSL Pinning", "frida", null, "ssl_unpin.js"),
        PluginInfo("frida-emucheck", "反模拟器检测", "Frida 脚本绕过模拟器/虚拟机检测", "frida", null, "antiemu.js"),
        PluginInfo("frida-tracer", "API 调用追踪", "Frida 脚本追踪指定类/方法的所有调用", "frida", null, "tracer.js"),
        PluginInfo("frida-classdump", "类名 Dump", "Frida 脚本 dump 运行时加载的所有类名", "frida", null, "classdump.js"),
        // Smali 补丁
        PluginInfo("smali-debug-true", "启用 Debuggable", "修改 Manifest 及 smali 使 APK 可调试", "smali", null, "debug_true.patch"),
        PluginInfo("smali-anti-tamper", "绕过完整性检测", "通用 smali patch 绕过 CRC/MD5/SHA 完整性校验", "smali", null, "anti_tamper.patch"),
        PluginInfo("smali-fake-sign", "伪造签名", "smali 层返回伪造的系统签名信息", "smali", null, "fake_sign.patch"),
        PluginInfo("smali-vip-bypass", "VIP 绕过模板", "通用 VIP/会员状态检查绕过模板", "smali", null, "vip_bypass.patch"),
        // 反混淆
        PluginInfo("deobfuscate-proguard", "ProGuard 映射恢复", "利用 mapping.txt 恢复混淆类名", "deobfuscation", null, "proguard_restore.py"),
        PluginInfo("deobfuscate-rename", "智能重命名", "Python 脚本基于调用关系重命名混淆类", "deobfuscation", null, "smart_rename.py"),
        // 脱壳
        PluginInfo("unpacker-frida", "Frida 通用脱壳", "Frida 脚本 dump DEX 从内存（通用脱壳）", "unpackers", null, "dex_dump.js"),
        PluginInfo("unpacker-inline", "Inline Hook 脱壳", "inline hook 脱壳脚本（需 root）", "unpackers", null, "inline_unpack.js"),
        // 分析工具
        PluginInfo("analyze-api", "API 端点提取", "Python 脚本提取 APK 中所有 HTTP/API 端点", "analysis", null, "extract_apis.py"),
        PluginInfo("analyze-hardcode", "硬编码扫描", "Python 扫描 APK 中硬编码的密钥/Token/URL", "analysis", null, "find_secrets.py"),
        PluginInfo("analyze-permissions", "权限分析", "分析 APK 权限 & 导出组件安全风险", "analysis", null, "perm_check.py"),
    )

    /** 获取内置插件的代码内容（内置的非下载插件用这个） */
    fun getBuiltinCode(pluginId: String): String? {
        return when (pluginId) {
            "antiroots" -> FRIDA_ANTI_ROOT
            "ssl_unpin" -> FRIDA_SSL_UNPIN
            "anti_tamper" -> SMALI_ANTI_TAMPER
            "vip_bypass" -> SMALI_VIP_BYPASS
            "debug_true" -> SMALI_DEBUG_TRUE
            else -> null
        }
    }

    /** 下载插件到 workspace/plugins/ 目录 */
    suspend fun download(url: String, destDir: File, timeoutSeconds: Int = 60): String = withContext(Dispatchers.IO) {
        val name = url.substringAfterLast('/').substringBefore('?')
        val dest = File(destDir, name)
        dest.parentFile?.mkdirs()
        Logger.i("Plugin", "下载: $url → $dest")
        try {
            withTimeout(timeoutSeconds * 1000L) {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000; conn.readTimeout = timeoutSeconds * 1000
                conn.setRequestProperty("User-Agent", "APKAgent/3.0")
                conn.connect()
                if (conn.responseCode != 200) return@withContext "HTTP ${conn.responseCode}"
                conn.inputStream.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
                // 如果是 zip，解压
                if (name.endsWith(".zip")) {
                    ZipInputStream(dest.inputStream()).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val entryFile = File(destDir, entry.name)
                            if (entry.isDirectory) entryFile.mkdirs()
                            else {
                                entryFile.parentFile?.mkdirs()
                                entryFile.outputStream().use { zis.copyTo(it) }
                            }
                            zis.closeEntry(); entry = zis.nextEntry
                        }
                    }
                    dest.delete(); "已下载并解压到 ${destDir.absolutePath}"
                } else {
                    "已下载: ${dest.absolutePath} (${dest.length()/1024}KB)"
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            "下载超时(${timeoutSeconds}s)"
        } catch (e: Throwable) {
            Logger.e("Plugin", "下载失败", e); "下载失败: ${e.message}"
        }
    }

    /** 工作区下的插件目录 */
    fun pluginDir(workspace: File): File = File(workspace, "plugins").apply { if (!exists()) mkdirs() }

    /** AI 可直接使用的内置插件内容 */
    fun installBuiltin(pluginId: String, pluginsDir: File): String? {
        val info = BUILTIN_CATALOG.find { it.id == pluginId } ?: return null
        val catDir = File(pluginsDir, info.category)
        catDir.mkdirs()
        val code = getBuiltinCode(pluginId) ?: return null
        val dest = File(catDir, info.filename)
        dest.writeText(code)
        Logger.i("Plugin", "安装内置: ${info.name} → ${dest.absolutePath}")
        return dest.absolutePath
    }

    // ──── 内置插件代码 ────

    private val FRIDA_ANTI_ROOT = """
// Frida: 绕过常见 Root/Magisk 检测
Java.perform(function() {
    console.log("[√] Anti-Root bypass loaded");
    // 伪造 Build.TAGS
    var Build = Java.use("android.os.Build");
    Build.TAGS.value = "release-keys";
    // Hook RootBeer 等检测库
    try {
        var RootBeer = Java.use("com.scottyab.rootbeer.RootBeer");
        RootBeer.isRooted.implementation = function() { console.log("[+] Spoofed isRooted=false"); return false; };
    } catch(e) {}
    // 常见检测方法
    var methods = ["checkRootMethod1", "checkRootMethod2", "checkRootMethod3", "isRooted", "detectRoot", "checkSuBinary"];
    methods.forEach(function(m) {
        try {
            var clz = Java.use("com.scottyab.rootbeer.RootBeer");
            if (clz[m]) clz[m].implementation = function() { return false; };
        } catch(e) {}
    });
    console.log("[√] Anti-Root hooks installed");
});
""".trimIndent()

    private val FRIDA_SSL_UNPIN = """
// Frida: 绕过 SSL Pinning (OkHttp/TrustManager)
Java.perform(function() {
    console.log("[√] SSL Unpin loaded");
    // OkHttp CertificatePinner bypass
    try {
        var CertPinner = Java.use("okhttp3.CertificatePinner");
        CertPinner.check.overload('java.lang.String', 'java.util.List').implementation = function(host, certs) {
            console.log("[+] Bypassed SSL pin for: " + host);
            return;
        };
    } catch(e) {}
    // TrustManager bypass
    try {
        var TrustManager = Java.use("javax.net.ssl.X509TrustManager");
        var sslCtx = Java.use("javax.net.ssl.SSLContext");
        var TrustMan = Java.registerClass({
            name: "com.apkagent.PinningTrustManager",
            implements: [TrustManager],
            methods: {
                checkClientTrusted: function(chain, authType) {},
                checkServerTrusted: function(chain, authType) {},
                getAcceptedIssuers: function() { return []; }
            }
        });
        var ssl = sslCtx.getInstance("TLS");
        ssl.init(null, [TrustMan.$new()], null);
    } catch(e) {}
    console.log("[√] SSL Unpin hooks installed");
});
""".trimIndent()

    private val SMALI_ANTI_TAMPER = """
# Smali Patch: 绕过 APK 完整性校验
# 搜索以下模式并 patch:
# 1. CRC32 校验 → 替换为返回预期值
# 2. MD5/SHA 签名校验 → 返回 true
# 3. APK 签名比对 → 绕过

# 使用方法: 反编译 DEX 后搜索以下关键调用:
# - java/util/zip/CRC32;->getValue()J
# - java/security/MessageDigest;->digest
# - android/content/pm/PackageManager;->getPackageInfo
# - java/security/Signature;->verify

# Patch 策略: 在关键比较点之前插入 const/4 v0, 0x1
""".trimIndent()

    private val SMALI_VIP_BYPASS = """
# Smali Patch: 通用 VIP/会员绕过模板
# 
# 搜索关键字:
# - "vip" / "is_vip" / "isVip" / "member_type"
# - "isPro" / "isPremium" / "user_level"
# - "getVipLevel" / "getMemberType" / "checkVip"
#
# Patch: 将返回 int 的方法改为返回 >0 的值
#       将返回 boolean 的方法改为返回 true
# 
# 示例:
# .method public isVip()Z
#     .locals 1
# +   const/4 v0, 0x1
# +   return v0
#     ... 原代码
# .end method
""".trimIndent()

    private val SMALI_DEBUG_TRUE = """
# Smali Patch: 启用 Debuggable
# 修改 AndroidManifest.xml 中 <application> 标签：
# android:debuggable="true"
# 
# 使用 apk_unpack 解包 → 修改 AndroidManifest → apk_repack → apk_sign
""".trimIndent()
}
