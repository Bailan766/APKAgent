package com.apkagent.tools

import com.apkagent.agent.*
import com.apkagent.shizuku.ShizukuManager
import kotlinx.serialization.json.*
import java.io.File

/**
 * 外部文件读取 — 通过 Shizuku shell 权限读取设备上任意文件
 */
class ExternalFileReadTool : Tool {
    override val name = "ext_file_read"
    override val description = "读取设备上任意位置的文件（通过 Shizuku shell 权限）。可读 /sdcard/、/data/、系统文件等。参数：path（绝对路径）、max_chars（最大字符数）。"
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

        // 优先用 Shizuku shell 权限读取
        if (ShizukuManager.isAuthorized()) {
            val content = ShizukuManager.readFile(path)
            if (content != null) {
                val output = if (content.length > maxChars) content.take(maxChars) + "\n...(共 ${content.length} 字符，已截断)" else content
                val size = ShizukuManager.fileSize(path)
                return ToolResult.ok("\ud83d\udcc4 ${File(path).name} ($size 字节):\n\n$output")
            }
            // Shizuku 失败时可能文件不存在
            if (!ShizukuManager.fileExists(path)) return ToolResult.err("文件不存在: $path")
            return ToolResult.err("Shizuku 读取失败: $path")
        }

        // 回退到直接读取
        val file = File(path)
        if (!file.exists()) return ToolResult.err("文件不存在: $path")
        if (file.isDirectory) return ToolResult.err("是目录不是文件: $path")
        if (!file.canRead()) return ToolResult.err("无读取权限（建议安装 Shizuku 获取 shell 权限）: $path")

        return try {
            val text = file.readText()
            val output = if (text.length > maxChars) text.take(maxChars) + "\n...(共 ${text.length} 字符，已截断)" else text
            ToolResult.ok("\ud83d\udcc4 ${file.name} (${file.length()} 字节):\n\n$output")
        } catch (e: Exception) {
            ToolResult.err("读取失败: ${e.message}")
        }
    }
}

/**
 * 外部文件写入 — 通过 Shizuku shell 权限写入任意位置
 */
class ExternalFileWriteTool : Tool {
    override val name = "ext_file_write"
    override val description = "向设备上任意位置写入文件（通过 Shizuku shell 权限）。可写 /sdcard/、/data/、系统目录等。参数：path（绝对路径）、content（文本内容）。"
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

        // 优先用 Shizuku shell 权限写入
        if (ShizukuManager.isAuthorized()) {
            // 自动创建父目录
            ShizukuManager.mkdirs(File(path).parent ?: "/")
            val ok = ShizukuManager.writeFile(path, content)
            return if (ok) {
                ToolResult.ok("\u2705 已写入 $path（${content.length} 字符，Shizuku shell 权限）")
            } else {
                ToolResult.err("Shizuku 写入失败: $path")
            }
        }

        // 回退到直接写入
        val file = File(path)
        return try {
            file.parentFile?.mkdirs()
            file.writeText(content)
            ToolResult.ok("\u2705 已写入 ${file.absolutePath}（${content.length} 字符）")
        } catch (e: Exception) {
            ToolResult.err("写入失败（建议安装 Shizuku 获取 shell 权限）: ${e.message}")
        }
    }
}

/**
 * 外部文件列表 — 通过 Shizuku shell 权限列出任意目录
 */
class ExternalFileListTool : Tool {
    override val name = "ext_file_list"
    override val description = "列出设备上任意目录的文件（通过 Shizuku shell 权限）。参数：path（目录绝对路径）。"
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "path" to strProp("目录绝对路径（如 /sdcard/、/data/data/、/）")
        ),
        required = listOf("path")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.err("缺少 path 参数")

        // 优先用 Shizuku shell 权限
        if (ShizukuManager.isAuthorized()) {
            val output = ShizukuManager.listDir(path)
            if (output != null) {
                if (output.contains("No such file") || output.contains("cannot access")) {
                    return ToolResult.err("目录不存在: $path")
                }
                return ToolResult.ok("\ud83d\udcc1 $path\n\n$output")
            }
            return ToolResult.err("Shizuku 列目录失败: $path")
        }

        // 回退
        val dir = File(path)
        if (!dir.exists()) return ToolResult.err("目录不存在: $path")
        if (!dir.isDirectory) return ToolResult.err("不是目录: $path")
        if (!dir.canRead()) return ToolResult.err("无读取权限（建议安装 Shizuku）: $path")

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
