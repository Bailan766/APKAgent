package com.apkagent.tools

import com.apkagent.agent.*
import kotlinx.serialization.json.*
import java.io.File

/**
 * 外部文件读取 — 不受沙箱限制，可读取设备上任意文件
 */
class ExternalFileReadTool : Tool {
    override val name = "ext_file_read"
    override val description = "读取设备上任意位置的文件（不受沙箱限制）。可读 /sdcard/、/data/、系统文件等。参数：path（绝对路径）、max_chars（最大字符数）。"
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "path" to strProp("文件绝对路径（如 /sdcard/Download/test.txt）"),
            "max_chars" to intProp("最大读取字符数，默认 10000")
        ),
        required = listOf("path")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.err("缺少 path 参数")
        val maxChars = args["max_chars"]?.jsonPrimitive?.intOrNull ?: 10000

        val file = File(path)
        if (!file.exists()) return ToolResult.err("文件不存在: $path")
        if (file.isDirectory) return ToolResult.err("是目录不是文件: $path")
        if (!file.canRead()) return ToolResult.err("无读取权限: $path")

        return try {
            val text = file.readText()
            val output = if (text.length > maxChars) {
                text.take(maxChars) + "\n...(共 ${text.length} 字符，已截断)"
            } else text
            ToolResult.ok("\ud83d\udcc4 ${file.name} (${file.length()} 字节):\n\n$output")
        } catch (e: Exception) {
            ToolResult.err("读取失败: ${e.message}")
        }
    }
}

/**
 * 外部文件写入 — 不受沙箱限制，可写入设备上任意位置
 */
class ExternalFileWriteTool : Tool {
    override val name = "ext_file_write"
    override val description = "向设备上任意位置写入文件（不受沙箱限制）。可写 /sdcard/、应用目录等。参数：path（绝对路径）、content（文本内容）。"
    override val sensitive = true
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "path" to strProp("目标文件绝对路径"),
            "content" to strProp("要写入的文本内容")
        ),
        required = listOf("path", "content")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.err("缺少 path 参数")
        val content = args["content"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.err("缺少 content 参数")

        val file = File(path)
        return try {
            file.parentFile?.mkdirs()
            file.writeText(content)
            ToolResult.ok("\u2705 已写入 ${file.absolutePath}（${content.length} 字符）")
        } catch (e: Exception) {
            ToolResult.err("写入失败: ${e.message}")
        }
    }
}

/**
 * 外部文件列表 — 列出设备上任意目录
 */
class ExternalFileListTool : Tool {
    override val name = "ext_file_list"
    override val description = "列出设备上任意目录的文件（不受沙箱限制）。参数：path（目录绝对路径）。"
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "path" to strProp("目录绝对路径（如 /sdcard/、/data/data/）")
        ),
        required = listOf("path")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.err("缺少 path 参数")

        val dir = File(path)
        if (!dir.exists()) return ToolResult.err("目录不存在: $path")
        if (!dir.isDirectory) return ToolResult.err("不是目录: $path")
        if (!dir.canRead()) return ToolResult.err("无读取权限: $path")

        val sb = StringBuilder()
        sb.appendLine("\ud83d\udcc1 ${dir.absolutePath}")
        sb.appendLine()
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            val icon = if (f.isDirectory) "\ud83d\udcc1" else "\ud83d\udcc4"
            val size = if (f.isFile) " ${f.length()}B" else ""
            sb.appendLine("$icon ${f.name}$size")
        } ?: sb.appendLine("(空目录或无权限)")
        return ToolResult.ok(sb.toString())
    }
}
