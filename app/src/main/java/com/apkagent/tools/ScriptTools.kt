package com.apkagent.tools

import com.apkagent.agent.*
import kotlinx.serialization.json.*
import java.io.File

/**
 * AI 自建脚本 — 创建并执行任意脚本（Python/Node/Bash）
 */
class CreateScriptTool : Tool {
    override val name = "create_script"
    override val description = "在工作区创建脚本文件。支持 Python/Node.js/Bash。参数：filename（文件名如 script.py）、content（脚本内容）、scriptType（python/node/bash）。"
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "filename" to strProp("脚本文件名（如 analyze.py、hook.js、scan.sh）"),
            "content" to strProp("脚本完整内容"),
            "scriptType" to strProp("脚本类型", listOf("python", "node", "bash"))
        ),
        required = listOf("filename", "content")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val filename = args["filename"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.err("缺少 filename")
        val content = args["content"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.err("缺少 content")
        val scriptType = args["scriptType"]?.jsonPrimitive?.contentOrNull ?: guessType(filename)

        val scriptDir = File(ctx.workspace, "scripts")
        scriptDir.mkdirs()
        val scriptFile = File(scriptDir, filename)
        scriptFile.writeText(content)

        // 自动添加 shebang 和执行权限
        if (scriptType == "bash" && !content.startsWith("#!")) {
            scriptFile.writeText("#!/bin/bash\n$content")
        }
        scriptFile.setExecutable(true)

        return ToolResult.ok("\u2705 脚本已创建：${scriptFile.absolutePath}\n类型：$scriptType\n大小：${content.length} 字符\n\n使用 run_script 工具执行此脚本。")
    }

    private fun guessType(filename: String): String = when {
        filename.endsWith(".py") -> "python"
        filename.endsWith(".js") -> "node"
        filename.endsWith(".sh") || filename.endsWith(".bash") -> "bash"
        else -> "bash"
    }
}

/**
 * 执行任意脚本文件
 */
class RunScriptTool : Tool {
    override val name = "run_script"
    override val description = "执行工作区内的脚本文件。参数：scriptPath（脚本路径）、args（命令行参数）、timeout（超时秒数，默认60）。支持 Python/Node/Bash。"
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "scriptPath" to strProp("脚本文件路径（工作区内或绝对路径）"),
            "args" to strProp("命令行参数（可选）"),
            "timeout" to intProp("超时秒数，默认 60")
        ),
        required = listOf("scriptPath")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val scriptPath = args["scriptPath"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.err("缺少 scriptPath")
        val scriptArgs = args["args"]?.jsonPrimitive?.contentOrNull ?: ""
        val timeout = (args["timeout"]?.jsonPrimitive?.intOrNull ?: 60) * 1000L

        val scriptFile = File(scriptPath).let { f ->
            if (f.isAbsolute) f else File(ctx.workspace, scriptPath)
        }
        if (!scriptFile.exists()) return ToolResult.err("脚本不存在: ${scriptFile.absolutePath}")

        // 确定解释器
        val interpreter = when {
            scriptFile.name.endsWith(".py") -> listOf("python3")
            scriptFile.name.endsWith(".js") -> listOf("node")
            scriptFile.name.endsWith(".sh") || scriptFile.name.endsWith(".bash") -> listOf("bash")
            scriptFile.canExecute() -> listOf(scriptFile.absolutePath)
            else -> listOf("bash")
        }

        val cmd = interpreter + scriptFile.absolutePath + scriptArgs.split(" ").filter { it.isNotBlank() }

        return try {
            val pb = ProcessBuilder(cmd)
            pb.directory(ctx.workspace)
            pb.environment()["APKAGENT_WORKSPACE"] = ctx.workspace.absolutePath
            ctx.openApk?.let { pb.environment()["APKAGENT_APK"] = it.absolutePath }
            pb.redirectErrorStream(true)

            val process = pb.start()
            val finished = process.waitFor(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (finished == false) {
                process.destroyForcibly()
                return ToolResult.err("脚本执行超时（${timeout/1000}秒）")
            }

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.exitValue()

            if (exitCode == 0) {
                ToolResult.ok("\u2705 脚本执行成功 (exit=$exitCode):\n\n${output.take(10000)}")
            } else {
                ToolResult.err("\u274c 脚本执行失败 (exit=$exitCode):\n\n${output.take(10000)}")
            }
        } catch (e: Exception) {
            ToolResult.err("执行异常: ${e.message}")
        }
    }
}
