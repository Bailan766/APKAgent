package com.apkagent.tools

import com.apkagent.agent.Tool
import com.apkagent.agent.ToolContext
import com.apkagent.agent.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * 代码混淆分析工具
 * 自动识别 ProGuard / R8 / 自定义混淆特征
 */
class AnalyzeObfuscationTool : Tool {
    override val name = "analyze_obfuscation"
    override val description = """分析代码混淆情况：检测 ProGuard / R8 / 自定义混淆特征。
参数：workspace（解包目录路径）
输出：混淆类型、混淆强度评估、短类名比例、字符串加密检测、资源混淆检测。"""
    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "workspace" to mapOf("type" to "string", "description" to "解包目录路径")
        ),
        "required" to listOf("workspace")
    )
    override val sensitive = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val workspace = args["workspace"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.err("缺少 workspace")

        val dir = File(workspace)
        if (!dir.exists()) return ToolResult.err("工作区不存在")

        val report = StringBuilder()
        report.appendLine("🔍 混淆分析报告")
        report.appendLine("═".repeat(40))

        // 1. 检测 smali 文件中的混淆特征
        val smaliFiles = dir.walkTopDown().filter { it.extension == "smali" }.toList()
        var totalClasses = 0
        var shortNameClasses = 0  // 单字母类名
        var packageRenamed = 0    // 重命名的包（a/b/c 等）
        val classPatterns = mutableMapOf<String, Int>()

        for (file in smaliFiles) {
            totalClasses++
            val name = file.nameWithoutExtension
            if (name.length <= 2) shortNameClasses++
            
            val parentName = file.parentFile?.name ?: ""
            if (parentName.length <= 2 && parentName.all { it.isLetter() }) {
                packageRenamed++
            }
        }

        // 2. 检测 mapping.txt（ProGuard 特征）
        val mappingFile = dir.resolve("META-INF/proguard/mapping.txt")
        val hasMapping = mappingFile.exists()
        
        // 3. 检测 proguard 规则
        val proguardRules = dir.walkTopDown().filter { 
            it.name.contains("proguard") || it.name.contains("r8") || it.name == "mapping.txt"
        }.toList()

        // 4. 检测字符串加密
        val encryptedStrings = smaliFiles.count { file ->
            val content = file.readText()
            content.contains("Ljava/lang/StringBuilder;->append") &&
            content.count { it == '\'' } > 20  // 大量单字符 append
        }

        // 5. 检测资源混淆（resguard）
        val resDir = dir.resolve("res")
        val resObfuscated = if (resDir.exists()) {
            resDir.listFiles()?.count { it.name.length <= 2 } ?: 0
        } else 0

        // 输出报告
        val obfuscationRatio = if (totalClasses > 0) shortNameClasses * 100 / totalClasses else 0
        
        report.appendLine()
        report.appendLine("📊 统计数据：")
        report.appendLine("  总 smali 文件数: $totalClasses")
        report.appendLine("  短类名(≤2字符): $shortNameClasses ($obfuscationRatio%)")
        report.appendLine("  重命名包目录: $packageRenamed")
        report.appendLine("  字符串加密文件: $encryptedStrings")
        report.appendLine("  资源混淆目录: $resObfuscated")
        report.appendLine()
        
        report.appendLine("🔎 混淆类型判断：")
        when {
            hasMapping -> report.appendLine("  ✅ 检测到 ProGuard/R8 mapping 文件")
            obfuscationRatio > 50 -> report.appendLine("  ⚠️ 高度疑似 ProGuard/R8 混淆（短类名比例 >50%）")
            obfuscationRatio > 20 -> report.appendLine("  ⚠️ 疑似轻度混淆")
            else -> report.appendLine("  ℹ️ 未检测到明显混淆特征")
        }
        
        if (encryptedStrings > smaliFiles.size / 4) {
            report.appendLine("  ⚠️ 疑似字符串加密（${encryptedStrings} 个文件有特征）")
        }
        if (resObfuscated > 5) {
            report.appendLine("  ⚠️ 疑似资源混淆（ResGuard）")
        }
        
        if (proguardRules.isNotEmpty()) {
            report.appendLine()
            report.appendLine("📋 ProGuard/R8 相关文件：")
            proguardRules.forEach { report.appendLine("  - ${it.relativeTo(dir)}") }
        }

        report.appendLine()
        val strength = when {
            obfuscationRatio > 70 && encryptedStrings > 10 -> "🔴 强混淆"
            obfuscationRatio > 40 -> "🟡 中等混淆"
            obfuscationRatio > 15 -> "🟢 轻度混淆"
            else -> "⚪ 无明显混淆"
        }
        report.appendLine("💪 混淆强度: $strength")

        return ToolResult.ok(report.toString())
    }
}
