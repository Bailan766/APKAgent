package com.apkagent.tools

import com.apkagent.agent.*
import kotlinx.serialization.json.*
import java.io.File

class AnalyzeObfuscationTool : Tool {
    override val name = "analyze_obfuscation"
    override val description = "分析代码混淆情况：检测 ProGuard / R8 / 自定义混淆特征。参数：workspace（解包目录路径）。输出：混淆类型、强度评估、短类名比例、字符串加密检测。"
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "workspace" to strProp("解包目录路径")
        ),
        required = listOf("workspace")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val workspace = args["workspace"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.err("缺少 workspace")
        val dir = File(workspace)
        if (!dir.exists()) return ToolResult.err("工作区不存在")

        val report = StringBuilder()
        report.appendLine("🔍 混淆分析报告")
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
        report.appendLine("📊 统计：")
        report.appendLine("  总 smali: $totalClasses | 短类名(≤2): $shortNameClasses ($ratio%) | 重命名包: $packageRenamed")
        report.appendLine("  字符串加密文件: $encryptedStrings | 资源混淆目录: $resObfuscated")
        report.appendLine()

        report.appendLine("🔎 判断：")
        when {
            hasMapping -> report.appendLine("  ✅ 检测到 ProGuard/R8 mapping 文件")
            ratio > 50 -> report.appendLine("  ⚠️ 高度疑似 ProGuard/R8 混淆（短类名 >50%）")
            ratio > 20 -> report.appendLine("  ⚠️ 疑似轻度混淆")
            else -> report.appendLine("  ℹ️ 未检测到明显混淆")
        }
        if (encryptedStrings > smaliFiles.size / 4) report.appendLine("  ⚠️ 疑似字符串加密")
        if (resObfuscated > 5) report.appendLine("  ⚠️ 疑似资源混淆（ResGuard）")

        val strength = when {
            ratio > 70 && encryptedStrings > 10 -> "🔴 强混淆"
            ratio > 40 -> "🟡 中等混淆"
            ratio > 15 -> "🟢 轻度混淆"
            else -> "⚪ 无明显混淆"
        }
        report.appendLine()
        report.appendLine("💪 混淆强度: $strength")

        return ToolResult.ok(report.toString())
    }
}
