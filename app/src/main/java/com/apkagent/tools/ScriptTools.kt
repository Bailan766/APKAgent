package com.apkagent.tools

import com.apkagent.agent.*
import com.apkagent.shizuku.ShizukuManager
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
        // 兼容 AI 传不同的参数名
        val filename = args["filename"]?.jsonPrimitive?.contentOrNull
            ?: args["script_name"]?.jsonPrimitive?.contentOrNull
            ?: args["name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.err("缺少 filename（试传: filename/script_name/name）")
        val content = args["content"]?.jsonPrimitive?.contentOrNull
            ?: args["script_content"]?.jsonPrimitive?.contentOrNull
            ?: args["code"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.err("缺少 content（试传: content/script_content/code）")
        val scriptType = args["scriptType"]?.jsonPrimitive?.contentOrNull ?: guessType(filename)

        val scriptDir = File(ctx.workspace, "scripts")
        scriptDir.mkdirs()
        val scriptFile = File(scriptDir, filename)
        scriptFile.writeText(content)

        // 自动添加 shebang 和执行权限
        if (scriptType == "bash" && !content.startsWith("#!")) {
            scriptFile.writeText("#!/bin/sh\n$content")
        }
        scriptFile.setExecutable(true)

        // Shizuku 确保权限
        if (ShizukuManager.isAuthorized()) {
            ShizukuManager.chmod(scriptFile.absolutePath, "755")
        }

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
 * 执行任意脚本文件 — 优先通过 Shizuku shell 权限执行
 */
class RunScriptTool : Tool {
    override val name = "run_script"
    override val description = "执行工作区内的脚本文件。通过 Shizuku shell 权限执行，绕过权限限制。参数：scriptPath（脚本路径）、args（命令行参数）、timeout（超时秒数，默认60）。"
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
            ?: args["script_path"]?.jsonPrimitive?.contentOrNull
            ?: args["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.err("缺少 scriptPath（试传: scriptPath/script_path/path）")
        val scriptArgs = args["args"]?.jsonPrimitive?.contentOrNull ?: ""
        val timeout = (args["timeout"]?.jsonPrimitive?.intOrNull ?: 60) * 1000L

        // 防御：AI 有时把解释器名（sh/bash/python3/node）当 scriptPath 传入
        val knownInterpreters = setOf("sh", "bash", "python3", "python", "node")
        var actualScriptPath = scriptPath
        var actualArgs = scriptArgs
        if (scriptPath in knownInterpreters && scriptArgs.isNotBlank()) {
            // AI 传反了：scriptPath 是解释器，args 才是真正的脚本路径
            actualScriptPath = scriptArgs.split(Regex("""\s+""")).firstOrNull() ?: scriptArgs
            actualArgs = scriptArgs.split(Regex("""\s+""")).drop(1).joinToString(" ")
        }

        val scriptFile = File(actualScriptPath).let { f ->
            if (f.isAbsolute) f else File(ctx.workspace, actualScriptPath)
        }
        if (!scriptFile.exists()) return ToolResult.err("脚本不存在: ${scriptFile.absolutePath}")

        // 确定解释器 — 使用完整路径，Shizuku shell PATH 很有限
        val interpreter = when {
            scriptFile.name.endsWith(".py") -> {
                PythonRunner.findPython() ?: "python3"
            }
            scriptFile.name.endsWith(".js") -> {
                // 优先找 NodeRunner 的路径
                val nodePath = listOf(
                    "/data/local/tmp/apkagent_node/bin/node",
                    "/data/data/com.termux/files/usr/bin/node",
                    "/data/data/com.termux.nix/files/usr/bin/node",
                    "/sdcard/APKAgent/node/bin/node"
                ).firstOrNull { File(it).exists() }
                nodePath ?: "node"
            }
            scriptFile.name.endsWith(".sh") || scriptFile.name.endsWith(".bash") -> "sh"
            scriptFile.canExecute() -> scriptFile.absolutePath
            else -> "sh"
        }

        val argsStr = if (actualArgs.isNotBlank()) " $actualArgs" else ""
        val cmd = "$interpreter '${scriptFile.absolutePath}'$argsStr"

        return try {
            // 优先用 Shizuku shell 权限执行
            if (ShizukuManager.isAuthorized()) {
                val workDir = ctx.workspace.absolutePath
                val fullCmd = "cd '$workDir' && $cmd"
                val process = ShizukuManager.execShizuku(fullCmd)
                    ?: return ToolResult.err("Shizuku 执行失败")

                val finished = process.waitFor(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (finished == false) {
                    process.destroyForcibly()
                    return ToolResult.err("脚本执行超时（${timeout/1000}秒）")
                }

                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.exitValue()

                return if (exitCode == 0) {
                    ToolResult.ok("\u2705 脚本执行成功 (exit=$exitCode, Shizuku shell):\n\n${output.take(10000)}")
                } else {
                    ToolResult.err("\u274c 脚本执行失败 (exit=$exitCode):\n\n${output.take(10000)}")
                }
            }

            // 回退到普通 ProcessBuilder
            val pb = ProcessBuilder(interpreter, scriptFile.absolutePath, *actualArgs.split(" ").filter { it.isNotBlank() }.toTypedArray())
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
