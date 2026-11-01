package com.apkagent.project

import com.apkagent.i18n.L
import com.apkagent.apktools.DexParser
import java.io.File
import java.util.zip.ZipFile

object ProjectIntelligence {

    data class PackerResult(
        val isPacked: Boolean,
        val detectedPackers: List<String>,
        val protectionScore: Int,
        val details: List<String>
    )

    data class TechStackResult(
        val frameworks: List<String>,
        val sdks: List<String>
    )

    data class NativeSummary(
        val abis: Map<String, List<String>>,
        val exportedJniMethods: List<String>,
        val hasJniOnLoad: Boolean
    )

    private val PACKER_SIGNATURES = mapOf(
        "360加固" to listOf("libprotectClass.so", "libjiagu.so", "libjiagu_x86.so", "libjiagu_art.so", "libjiagukr.so"),
        "腾讯乐固" to listOf("libshell-super.so", "libtxAppProtect.so", "libshella-", "libshellx-", "libtup.so"),
        "梆梆安全" to listOf("libDexHelper.so", "libSecShell.so", "libDexHelper-x86.so", "libsecexe.so"),
        "爱加密" to listOf("libexec.so", "libexecmain.so", "libijiami.so"),
        "网易易盾" to listOf("libnesec.so", "libnesl.so"),
        "通付盾" to listOf("libtfd.so"),
        "娜迦信息" to listOf("libchaosvmp.so", "libddog.so"),
        "几维安全" to listOf("libkwscmm.so", "libkwssl.so"),
        "APKProtect" to listOf("libAPKProtect.so"),
        "DexProtector" to listOf("libdexprotector.so"),
        "百度加固" to listOf("libbaiduprotect.so"),
        "阿里聚安全" to listOf("libsgmain.so", "libaliprotect.so"),
        "顶象加固" to listOf("libDXRisk.so")
    )

    private val FRAMEWORK_RULES = mapOf(
        "Flutter" to listOf("io.flutter.", "libflutter.so"),
        "React Native" to listOf("com.facebook.react.", "libreactnativejni.so", "libhermes.so"),
        "Unity" to listOf("com.unity3d.player.", "libunity.so", "libil2cpp.so"),
        "Cocos2d" to listOf("org.cocos2dx.", "libcocos2d.so", "libcocos2djs.so"),
        "UniApp / DCloud" to listOf("io.dcloud.", "libweexcore.so"),
        "Kotlin Multiplatform" to listOf("kotlinx.coroutines.")
    )

    private val SDK_RULES = mapOf(
        "微信 OpenSDK" to listOf("com.tencent.mm.opensdk."),
        "支付宝 SDK" to listOf("com.alipay.sdk."),
        "腾讯 Bugly" to listOf("com.tencent.bugly."),
        "友盟统计/推送" to listOf("com.umeng."),
        "极光推送 JPush" to listOf("cn.jpush.android."),
        "个推" to listOf("com.igexin."),
        "穿山甲广告" to listOf("com.bytedance.sdk.openadsdk."),
        "腾讯优量汇" to listOf("com.qq.e.ads."),
        "OkHttp" to listOf("okhttp3."),
        "Retrofit" to listOf("retrofit2."),
        "MMKV" to listOf("com.tencent.mmkv."),
        "Gson" to listOf("com.google.gson."),
        "Shizuku API" to listOf("rikka.shizuku.")
    )

    fun detectPacker(apkFile: File): PackerResult {
        val detected = mutableListOf<String>()
        val details = mutableListOf<String>()
        var score = 0

        try {
            ZipFile(apkFile).use { zip ->
                val entryNames = zip.entries().asSequence().map { it.name }.toList()
                val dexEntries = entryNames.filter { it.endsWith(".dex", ignoreCase = true) }

                for ((packer, sigs) in PACKER_SIGNATURES) {
                    for (sig in sigs) {
                        val matched = entryNames.any { n ->
                            val simple = n.substringAfterLast('/')
                            if (sig.endsWith("-")) simple.startsWith(sig)
                            else simple.equals(sig, ignoreCase = true)
                        }
                        if (matched) {
                            if (packer !in detected) detected.add(packer)
                            details.add("$packer (匹配特征: $sig)")
                            break
                        }
                    }
                }

                if (detected.isNotEmpty()) score += 60

                if (dexEntries.size == 1) {
                    val info = zip.getEntry(dexEntries.first())
                    if (info != null && info.size in 1..20_000) {
                        score += 30
                        details.add("主 DEX 异常小 (${info.size} 字节)，高度疑似外壳 DEX")
                    }
                }
            }
        } catch (e: Exception) {
            details.add("扫描异常: ${e.message}")
        }

        return PackerResult(
            isPacked = detected.isNotEmpty() || score >= 50,
            detectedPackers = detected,
            protectionScore = score.coerceAtMost(100),
            details = details
        )
    }

    fun detectTechStack(apkFile: File, classList: List<String>): TechStackResult {
        val detectedFrameworks = mutableListOf<String>()
        val detectedSdks = mutableListOf<String>()

        try {
            ZipFile(apkFile).use { zip ->
                val entryNames = zip.entries().asSequence().map { it.name }.toList()

                for ((framework, rules) in FRAMEWORK_RULES) {
                    val match = rules.any { rule ->
                        if (rule.endsWith(".so")) entryNames.any { it.endsWith(rule, ignoreCase = true) }
                        else classList.any { it.startsWith(rule) }
                    }
                    if (match) detectedFrameworks.add(framework)
                }

                for ((sdk, rules) in SDK_RULES) {
                    val match = rules.any { rule ->
                        classList.any { it.startsWith(rule) }
                    }
                    if (match) detectedSdks.add(sdk)
                }
            }
        } catch (_: Exception) {}

        return TechStackResult(
            frameworks = detectedFrameworks.distinct(),
            sdks = detectedSdks.distinct()
        )
    }

    fun analyzeNativeLibs(apkFile: File): NativeSummary {
        val abis = mutableMapOf<String, MutableList<String>>()
        val jniMethods = mutableListOf<String>()
        var hasJniOnLoad = false

        try {
            ZipFile(apkFile).use { zip ->
                val soEntries = zip.entries().asSequence()
                    .filter { it.name.startsWith("lib/") && it.name.endsWith(".so", ignoreCase = true) }
                    .toList()

                for (entry in soEntries) {
                    val parts = entry.name.split('/')
                    if (parts.size >= 3) {
                        val abi = parts[1]
                        val soName = parts.last()
                        abis.getOrPut(abi) { mutableListOf() }.add(soName)
                    }

                    // 扫描单个 SO 内部是否包含 JNI_OnLoad 与 Java_ 导出符号
                    if (entry.name.contains("arm64-v8a") || abis.size <= 1) {
                        zip.getInputStream(entry).use { stream ->
                            val bytes = stream.readBytes().take(100_000).toByteArray()
                            val content = String(bytes, Charsets.US_ASCII)
                            if (content.contains("JNI_OnLoad")) hasJniOnLoad = true

                            val regex = Regex("""Java_[a-zA-Z0-9_]+""")
                            regex.findAll(content).take(10).forEach { m ->
                                if (m.value !in jniMethods) jniMethods.add(m.value)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return NativeSummary(
            abis = abis,
            exportedJniMethods = jniMethods,
            hasJniOnLoad = hasJniOnLoad
        )
    }
}
