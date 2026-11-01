package com.apkagent.tools

import com.apkagent.i18n.L
import com.apkagent.agent.*
import com.apkagent.apktools.DexParser
import com.apkagent.apktools.Sandbox
import kotlinx.serialization.json.*
import java.io.File
import java.util.zip.ZipFile

/**
 * 混淆强度分析工具 — 快速判断 APK 是否混淆、混淆程度、使用的混淆器
 *
 * 修复说明：
 * 1. 支持 workspace 相对路径解析与默认回退至 ctx.workspace。
 * 2. 修复当工作区尚未反编译出 .smali 文件时统计出 0 个类并误报“⚪ 无明显混淆”的 Bug，
 *    自动回退直接解析当前打开 APK 内的所有 DEX 类名与特征。
 */
object AnalyzeObfuscationTool : Tool {
    override val name = "analyze_obfuscation"
    override val description get() = L(
        "分析 APK 的混淆情况（ProGuard/R8/DexGuard/商业加固），输出混淆率、短类名比例、字符串加密检测",
        "Analyze APK obfuscation (ProGuard/R8/DexGuard/packers), reporting obfuscation rate, short class name ratio, and string encryption"
    )

    override val parameters get() = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("workspace") {
                put("type", "string")
                put("description", L("APK 工作目录路径（留空默认当前工作区）", "APK workspace directory (defaults to current workspace)"))
            }
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawWs = args["workspace"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val wsDir = if (rawWs != null) Sandbox.resolve(File(rawWs), ctx.workspace) else ctx.workspace

        val smaliFiles = if (wsDir.exists()) {
            wsDir.walkTopDown()
                .onEnter { it.name != ".snapshots" && it.name != "audit" && it.name != "projects" }
                .filter { it.isFile && it.extension == "smali" }
                .take(2000)
                .toList()
        } else emptyList()

        var totalClasses = 0
        var shortClassNames = 0
        var singleCharNames = 0
        var totalMethods = 0
        var shortMethodNames = 0
        var encryptedStrings = 0
        var reflectionCalls = 0
        var sourceLabel = "Smali 工作区"

        if (smaliFiles.isNotEmpty()) {
            smaliFiles.forEach { file ->
                totalClasses++
                val className = file.nameWithoutExtension
                if (className.length <= 2) shortClassNames++
                if (className.length == 1) singleCharNames++

                val content = runCatching { file.readText() }.getOrDefault("")
                val methods = Regex("""\.method\s+.*?(\S+)\(""").findAll(content)
                methods.forEach { match ->
                    totalMethods++
                    val methodName = match.groupValues[1]
                    if (methodName.length <= 2 && methodName != "<init>" && methodName != "<clinit>") {
                        shortMethodNames++
                    }
                }

                if (content.contains("javax/crypto/Cipher") || content.contains("MessageDigest")) {
                    encryptedStrings++
                }
                if (content.contains("java/lang/reflect/Method;->invoke") || content.contains("Class;->forName")) {
                    reflectionCalls++
                }
            }
        } else {
            // 回退：直接从 APK 的 classes*.dex 解析类信息与字符串池
            val apk = ctx.openApk
                ?: File(wsDir, "source/base.apk").takeIf { it.exists() }
                ?: File(wsDir, "base.apk").takeIf { it.exists() }
                ?: return ToolResult(false, L("未找到 .smali 文件或 APK 文件，请先打开 APK", "No .smali or APK file found"))

            sourceLabel = "DEX 直接分析 (${apk.name})"
            runCatching {
                ZipFile(apk).use { zip ->
                    val dexEntries = zip.entries().asSequence().filter { it.name.endsWith(".dex") }.toList()
                    for (entry in dexEntries) {
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        val info = DexParser.parseCached(bytes)
                        totalMethods += info.methodIdsSize
                        for (fullClass in info.classes) {
                            totalClasses++
                            val simple = fullClass.substringAfterLast('.').substringAfterLast('$')
                            if (simple.length <= 2) shortClassNames++
                            if (simple.length == 1) singleCharNames++
                        }
                        for (s in info.strings) {
                            if (s == "Ljavax/crypto/Cipher;" || s == "Ljava/security/MessageDigest;") encryptedStrings++
                            if (s == "Ljava/lang/reflect/Method;" || s == "forName") reflectionCalls++
                        }
                    }
                }
            }
        }

        val classObfuscationRate = if (totalClasses > 0) (shortClassNames * 100 / totalClasses) else 0
        val methodObfuscationRate = if (totalMethods > 0 && smaliFiles.isNotEmpty()) (shortMethodNames * 100 / totalMethods) else classObfuscationRate

        val obfuscationLevel = when {
            classObfuscationRate > 60 || methodObfuscationRate > 60 -> L("🔴 重度混淆", "🔴 Heavy Obfuscation")
            classObfuscationRate > 25 || methodObfuscationRate > 25 -> L("🟡 中度混淆", "🟡 Moderate Obfuscation")
            classObfuscationRate > 8 || methodObfuscationRate > 8 -> L("🟢 轻度混淆", "🟢 Light Obfuscation")
            else -> L("⚪ 无明显混淆", "⚪ No Obvious Obfuscation")
        }

        val obfuscatorType = when {
            singleCharNames > totalClasses * 0.4 && encryptedStrings > 5 -> L("疑似 DexGuard 或商业混淆器", "Suspected DexGuard or commercial obfuscator")
            shortClassNames > totalClasses * 0.25 -> "ProGuard / R8"
            else -> L("未混淆或仅开启部分优化", "Unobfuscated or partial shrinking only")
        }

        val report = buildString {
            appendLine("# 混淆分析报告")
            appendLine("数据源: $sourceLabel")
            appendLine()
            appendLine("## 总体评估")
            appendLine("- **混淆等级**: $obfuscationLevel")
            appendLine("- **混淆器类型**: $obfuscatorType")
            appendLine()
            appendLine("## 详细指标")
            appendLine("- 扫描类总数: $totalClasses")
            appendLine("- 短类名 (≤2字符): $shortClassNames ($classObfuscationRate%)")
            appendLine("- 单字符类名: $singleCharNames")
            appendLine("- 方法总数: $totalMethods")
            if (smaliFiles.isNotEmpty()) {
                appendLine("- 短方法名 (≤2字符): $shortMethodNames ($methodObfuscationRate%)")
            }
            appendLine("- 加密相关引用: $encryptedStrings")
            appendLine("- 反射调用特征: $reflectionCalls")
            appendLine()
            appendLine("## 逆向建议")
            when {
                classObfuscationRate > 60 -> {
                    appendLine("- 类名重度混淆，建议通过字符串常量、资源 ID、Manifest 组件入口定位关键逻辑")
                    appendLine("- 使用 `smart_search` 搜索明文 URL/API/错误提示，或结合 Frida Hook 动态追踪")
                }
                classObfuscationRate > 25 -> {
                    appendLine("- 存在标准 ProGuard/R8 混淆，四大组件与接口类通常保留原名，可从 Activity/Service 顺藤摸瓜")
                }
                else -> {
                    appendLine("- 代码可读性良好，可直接通过类名与方法名快速定位业务逻辑")
                }
            }
        }

        return ToolResult(true, report)
    }
}
