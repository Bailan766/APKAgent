package com.apkagent.tools

import com.apkagent.i18n.L

import com.apkagent.agent.*
import kotlinx.serialization.json.*
import java.io.File

class AnalyzeObfuscationTool : Tool {
    override val name = "analyze_obfuscation"
    override val description get() = L("分析代码混淆情况：检测 ProGuard / R8 / 自定义混淆特征。参数：workspace（解包目录路径）。输出：混淆类型、强度评估、短类名比例、字符串加密检测。", "Analyse code confusion: Test ProGuard / R8 / Customised confusing features. Parameter: Workspace. Output: Fuzzy type, intensity assessment, short class scale, string encryption.")
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "workspace" to strProp(L("解包目录路径", "Unpack directory path"))
        ),
        required = listOf("workspace")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val workspace = args["workspace"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.err(L("缺少 workspace", "Missing workspace"))
        val dir = File(workspace)
        if (!dir.exists()) return ToolResult.err(L("工作区不存在", "Workspace does not exist"))

        val report = StringBuilder()
        report.appendLine(L("🔍 混淆分析报告", "Confusion analysis"))
        report.appendLine("═".repeat(40))

        val smaliFiles = dir.walkTopDown().filter { it.extension == "smali" }.toList()
        var totalClasses = 0
        var shortNameClasses = 0
        var packageRenamed = 0

        for (file in smaliFiles) {
            totalClasses++
            val name = file.nameWithoutExtension
            if (name.length <= 2) shortNameClasses++
            val parentName = file.parentFile?.name ?: ""
            if (parentName.length <= 2 && parentName.all { it.isLetter() }) packageRenamed++
        }

        val hasMapping = dir.resolve("META-INF/proguard/mapping.txt").exists()
        val proguardRules = dir.walkTopDown().filter {
            it.name.contains("proguard") || it.name.contains("r8") || it.name == "mapping.txt"
        }.toList()

        val encryptedStrings = smaliFiles.count { file ->
            try {
                val content = file.readText()
                content.contains("StringBuilder;->append") && content.count { it == '\'' } > 20
            } catch (_: Exception) { false }
        }

        val resDir = dir.resolve("res")
        val resObfuscated = if (resDir.exists()) resDir.listFiles()?.count { it.name.length <= 2 } ?: 0 else 0

        val ratio = if (totalClasses > 0) shortNameClasses * 100 / totalClasses else 0

        report.appendLine()
        report.appendLine(L("📊 统计：", "Statistics:"))
        report.appendLine("  总 smali: $totalClasses | 短类名(≤2): $shortNameClasses ($ratio%) | 重命名包: $packageRenamed")
        report.appendLine("  字符串加密文件: $encryptedStrings | 资源混淆目录: $resObfuscated")
        report.appendLine()

        report.appendLine(L("🔎 判断：", "• Judgement:"))
        when {
            hasMapping -> report.appendLine(L("  ✅ 检测到 ProGuard/R8 mapping 文件", "✅ProGuard/R8Mapping file detected"))
            ratio > 50 -> report.appendLine(L("  ⚠️ 高度疑似 ProGuard/R8 混淆（短类名 >50%）", "• High suspicion ProGuard/R8 confusion (short class > 50%)"))
            ratio > 20 -> report.appendLine(L("  ⚠️ 疑似轻度混淆", "It seems to be slightly confused."))
            else -> report.appendLine(L("  ℹ️ 未检测到明显混淆", "I️ No apparent confusion detected"))
        }
        if (encryptedStrings > smaliFiles.size / 4) report.appendLine(L("  ⚠️ 疑似字符串加密", "⚠️ Suspected string encryption"))
        if (resObfuscated > 5) report.appendLine(L("  ⚠️ 疑似资源混淆（ResGuard）", "⚠️ Suspected resource confusion"))

        val strength = when {
            ratio > 70 && encryptedStrings > 10 -> L("🔴 强混淆", "♪ Twilight and confusion ♪")
            ratio > 40 -> L("🟡 中等混淆", "Medium confusion")
            ratio > 15 -> L("🟢 轻度混淆", "A slight confusion.")
            else -> L("⚪ 无明显混淆", "No apparent confusion.")
        }
        report.appendLine()
        report.appendLine("💪 混淆强度: $strength")

        return ToolResult.ok(report.toString())
    }
}
