package com.apkagent.tools

import com.apkagent.agent.Tool
import com.apkagent.agent.ToolContext
import com.apkagent.agent.ToolResult
import com.apkagent.util.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * 全局智能搜索工具
 * 在 APK 工作区内多维度搜索：字符串、类名、方法名、资源
 */
class SmartSearchTool : Tool {
    override val name = "smart_search"
    override val description = """全局智能搜索：在 APK 工作区内多维度搜索。
参数：pattern（搜索模式，支持正则）、searchType（all/strings/classes/methods/resources/urls/keys）
可搜索 URL、密钥、算法名、错误提示、类名模式、方法名模式等。
返回匹配结果及所在文件位置。"""
    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "pattern" to mapOf("type" to "string", "description" to "搜索模式（正则表达式）"),
            "searchType" to mapOf("type" to "string", "description" to "搜索类型：all/strings/classes/methods/resources/urls/keys", "default" to "all")
        ),
        "required" to listOf("pattern")
    )
    override val sensitive = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val pattern = args["pattern"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.err("缺少 pattern 参数")
        val searchType = args["searchType"]?.jsonPrimitive?.contentOrNull ?: "all"

        return try {
            val workspace = File(ctx.workspace)
            if (!workspace.exists()) return ToolResult.err("工作区不存在")

            val results = mutableListOf<String>()
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)

            // 搜索 smali 文件
            if (searchType in listOf("all", "strings", "classes", "methods")) {
                workspace.walkTopDown()
                    .filter { it.extension == "smali" || it.extension == "xml" || it.extension == "json" }
                    .forEach { file ->
                        val matches = file.readLines()
                            .mapIndexedNotNull { idx, line ->
                                if (regex.containsMatchIn(line)) "  ${file.name}:${idx+1}: ${line.trim()}" else null
                            }
                        if (matches.isNotEmpty()) {
                            results.add("📄 ${file.relativeTo(workspace)}")
                            results.addAll(matches.take(5))
                        }
                    }
            }

            // 搜索资源文件
            if (searchType in listOf("all", "resources")) {
                workspace.walkTopDown()
                    .filter { it.extension in listOf("xml", "json", "properties", "cfg") }
                    .forEach { file ->
                        val text = file.readText()
                        if (regex.containsMatchIn(text)) {
                            results.add("📄 ${file.relativeTo(workspace)} (resource match)")
                        }
                    }
            }

            if (results.isEmpty()) {
                ToolResult.ok("未找到匹配: $pattern")
            } else {
                ToolResult.ok("搜索结果（${results.size} 条）:\n${results.take(50).joinToString("\n")}")
            }
        } catch (e: Exception) {
            ToolResult.err("搜索失败: ${e.message}")
        }
    }
}
