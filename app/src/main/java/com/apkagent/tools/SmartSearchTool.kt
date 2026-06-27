package com.apkagent.tools

import com.apkagent.agent.*
import kotlinx.serialization.json.*
import java.io.File

class SmartSearchTool : Tool {
    override val name = "smart_search"
    override val description = "全局智能搜索：在 APK 工作区内多维度搜索。参数：pattern（正则）、searchType（all/strings/classes/methods/resources/urls/keys）。可搜索 URL、密钥、算法名、类名模式等。"
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "pattern" to strProp("搜索模式（正则表达式）"),
            "searchType" to strProp("搜索类型", listOf("all", "strings", "classes", "methods", "resources", "urls", "keys"))
        ),
        required = listOf("pattern")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val pattern = args["pattern"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.err("缺少 pattern 参数")
        val searchType = args["searchType"]?.jsonPrimitive?.contentOrNull ?: "all"

        return try {
            val workspace = File(ctx.workspace)
            if (!workspace.exists()) return ToolResult.err("工作区不存在")

            val results = mutableListOf<String>()
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)

            if (searchType in listOf("all", "strings", "classes", "methods")) {
                workspace.walkTopDown()
                    .filter { it.extension in listOf("smali", "xml", "json", "java") }
                    .forEach { file ->
                        val matches = file.readLines()
                            .mapIndexedNotNull { idx, line ->
                                if (regex.containsMatchIn(line)) "  ${file.name}:${idx+1}: ${line.trim().take(120)}" else null
                            }
                        if (matches.isNotEmpty()) {
                            results.add("📄 ${file.relativeTo(workspace)}")
                            results.addAll(matches.take(5))
                        }
                    }
            }

            if (searchType in listOf("all", "resources")) {
                workspace.walkTopDown()
                    .filter { it.extension in listOf("xml", "json", "properties", "cfg", "txt") }
                    .forEach { file ->
                        try {
                            val text = file.readText()
                            if (regex.containsMatchIn(text)) {
                                results.add("📄 ${file.relativeTo(workspace)} (resource match)")
                            }
                        } catch (_: Exception) {}
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
