package com.apkagent.tools

import com.apkagent.i18n.L
import com.apkagent.agent.*
import com.apkagent.apktools.PythonRunner
import com.apkagent.apktools.Sandbox
import com.apkagent.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.File

/**
 * 动态脚本创建与执行工具 — 让 AI 能够自己写脚本解决未知问题
 *
 * 修复说明：
 * 1. 修复 CreateScriptTool 保存到 workspace/scripts/ 而 RunScriptTool 只查找 workspace/ 根目录导致文件找不到的路径不一致 Bug。
 * 2. 修复 RunScriptTool 先 waitFor() 后读取 inputStream 导致输出超过 64KB 管道缓冲区时死锁的 Bug。
 * 3. 在 Shizuku 模式下追加 2>&1 重定向，确保 Python/Node 异常堆栈（stderr）能完整返回给 AI。
 */
class CreateScriptTool : Tool {
    companion object : Tool by CreateScriptTool()

    override val name = "create_script"
    override val description get() = L(
        "在工作区创建脚本文件。支持 Python/Node.js/Bash。参数：filename（文件名如 script.py）、content（脚本内容）、scriptType（python/node/bash）。",
        "Creates a script file in the workspace. Supports Python/Node.js/Bash. Parameters: filename, content, scriptType."
    )

    override val parameters: JsonObject get() = schemaObject(
        properties = mapOf(
            "filename" to strProp(L("脚本文件名（如 analyze.py、hook.js、scan.sh）", "Script file name (e.g. analyze.py, hook.js, scan.sh)")),
            "content" to strProp(L("脚本完整内容", "Full script source code")),
            "scriptType" to strProp(L("脚本类型", "Script type"), listOf("python", "node", "bash")),
            "description" to strProp(L("脚本用途说明（可选）", "Script description (optional)"))
        ),
        required = listOf("filename", "content")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val filename = (args["filename"]?.jsonPrimitive?.contentOrNull
            ?: args["script_name"]?.jsonPrimitive?.contentOrNull
            ?: args["name"]?.jsonPrimitive?.contentOrNull)?.trim()
            ?: return ToolResult.err(L("缺少 filename（试传: filename/script_name/name）", "Missing filename (try: filename/script_name/name)"))
        val content = args["content"]?.jsonPrimitive?.contentOrNull
            ?: args["script_content"]?.jsonPrimitive?.contentOrNull
            ?: args["code"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.err(L("缺少 content（试传: content/script_content/code）", "Missing content (try: content/script_content/code)"))
        val scriptType = args["scriptType"]?.jsonPrimitive?.contentOrNull ?: guessType(filename)

        val scriptFile = if (filename.contains('/') || filename.contains('\\')) {
            Sandbox.resolve(File(filename), ctx.workspace)
        } else {
            val scriptDir = File(ctx.workspace, "scripts")
            scriptDir.mkdirs()
            File(scriptDir, filename)
        }
        scriptFile.parentFile?.mkdirs()

        return try {
            val finalContent = if (scriptType == "bash" && !content.startsWith("#!")) {
                "#!/bin/sh\n$content"
            } else {
                content
            }
            scriptFile.writeText(finalContent)
            scriptFile.setExecutable(true)
            if (ShizukuManager.isAuthorized()) {
                ShizukuManager.chmod(scriptFile.absolutePath, "755")
            }
            ToolResult.ok(
                "✅ 脚本已创建：${scriptFile.absolutePath}\n类型：$scriptType\n大小：${finalContent.length} 字符\n\n使用 run_script 工具执行此脚本。"
            )
        } catch (e: Exception) {
            ToolResult.err("创建脚本失败: ${e.message}")
        }
    }

    private fun guessType(filename: String): String = when {
        filename.endsWith(".py", ignoreCase = true) -> "python"
        filename.endsWith(".js", ignoreCase = true) -> "node"
        filename.endsWith(".sh", ignoreCase = true) || filename.endsWith(".bash", ignoreCase = true) -> "bash"
        else -> "bash"
    }
}

class RunScriptTool : Tool {
    companion object : Tool by RunScriptTool()

    override val name = "run_script"
    override val description get() = L(
        "执行工作区内的脚本文件（支持 .py/.js/.sh）。参数：scriptPath（脚本路径或文件名）、args（命令行参数）、timeout（超时秒数，默认60）。",
        "Execute a script file (.py/.js/.sh) in the workspace. Parameters: scriptPath, args, timeout."
    )

    override val parameters: JsonObject get() = schemaObject(
        properties = mapOf(
            "scriptPath" to strProp(L("脚本文件路径（工作区内、scripts/ 下或绝对路径）", "Script file path (in workspace, scripts/, or absolute path)")),
            "script_path" to strProp(L("脚本路径（兼容别名）", "Script path (alias)")),
            "args" to strProp(L("命令行参数（可选）", "Command line arguments (optional)")),
            "timeout" to intProp(L("超时秒数，默认 60", "Timeout in seconds, default 60"))
        )
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val rawScriptPath = (args["scriptPath"]?.jsonPrimitive?.contentOrNull
            ?: args["script_path"]?.jsonPrimitive?.contentOrNull
            ?: args["script"]?.jsonPrimitive?.contentOrNull
            ?: args["path"]?.jsonPrimitive?.contentOrNull)?.trim()
            ?: return@withContext ToolResult.err(L("缺少 scriptPath（试传: scriptPath/script_path/path）", "Missing scriptPath (try: scriptPath/script_path/path)"))
        val scriptArgs = args["args"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
        val timeoutSec = (args["timeout"]?.jsonPrimitive?.intOrNull ?: 60).coerceIn(5, 600)

        // 防御：AI 有时把解释器名（sh/bash/python3/node）当 scriptPath 传入
        val knownInterpreters = setOf("sh", "bash", "python3", "python", "node")
        var actualScriptPath = rawScriptPath
        var actualArgs = scriptArgs
        if (rawScriptPath in knownInterpreters && scriptArgs.isNotBlank()) {
            val parts = scriptArgs.split(Regex("""\s+"""))
            actualScriptPath = parts.firstOrNull() ?: scriptArgs
            actualArgs = parts.drop(1).joinToString(" ")
        }

        val candidates = listOf(
            Sandbox.resolve(File(actualScriptPath), ctx.workspace),
            File(ctx.workspace, "scripts/$actualScriptPath"),
            File(ctx.workspace, "scripts/${File(actualScriptPath).name}")
        )
        val scriptFile = candidates.firstOrNull { it.exists() && it.isFile }
            ?: return@withContext ToolResult.err("脚本不存在: $actualScriptPath")

        // 如果无命令行参数且是 .py 脚本，优先走 PythonRunner（支持自动部署到 /data/local/tmp）
        if (scriptFile.extension.equals("py", ignoreCase = true) && actualArgs.isBlank()) {
            val pyRes = PythonRunner.execute(scriptFile, timeoutSeconds = timeoutSec, workDir = ctx.workspace)
            if (pyRes.success || pyRes.output != null) {
                return@withContext ToolResult(
                    pyRes.success,
                    "脚本执行完成 (${scriptFile.name})\n\n输出:\n${pyRes.combined.take(10000)}"
                )
            }
        }

        val interpreter = when {
            scriptFile.name.endsWith(".py", ignoreCase = true) -> {
                PythonRunner.findPython() ?: "python3"
            }
            scriptFile.name.endsWith(".js", ignoreCase = true) -> {
                listOf(
                    "/data/local/tmp/apkagent_node/bin/node",
                    "/data/data/com.termux/files/usr/bin/node",
                    "/data/data/com.termux.nix/files/usr/bin/node",
                    "/sdcard/APKAgent/node/bin/node"
                ).firstOrNull { File(it).exists() } ?: "node"
            }
            scriptFile.name.endsWith(".sh", ignoreCase = true) || scriptFile.name.endsWith(".bash", ignoreCase = true) -> "sh"
            scriptFile.canExecute() -> scriptFile.absolutePath
            else -> "sh"
        }

        val argsStr = if (actualArgs.isNotBlank()) " $actualArgs" else ""
        val cmd = "'$interpreter' '${scriptFile.absolutePath}'$argsStr 2>&1"

        try {
            withTimeout(timeoutSec * 1000L) {
                val process = if (ShizukuManager.isAuthorized()) {
                    val workDir = ctx.workspace.absolutePath
                    ShizukuManager.execShizuku("cd '$workDir' && $cmd")
                } else {
                    val cmdList = mutableListOf(interpreter, scriptFile.absolutePath)
                    if (actualArgs.isNotBlank()) {
                        cmdList.addAll(actualArgs.split(Regex("""\s+""")).filter { it.isNotBlank() })
                    }
                    ProcessBuilder(cmdList)
                        .directory(ctx.workspace)
                        .apply {
                            environment()["APKAGENT_WORKSPACE"] = ctx.workspace.absolutePath
                            ctx.openApk?.let { environment()["APKAGENT_APK"] = it.absolutePath }
                        }
                        .redirectErrorStream(true)
                        .start()
                } ?: return@withTimeout ToolResult.err(L("无法启动脚本进程", "Unable to start script process"))

                // 先完整读取输出流再 waitFor，防止超过 64KB 管道缓冲区死锁
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    ToolResult.ok("✅ 脚本执行成功 (exit=$exitCode):\n\n${output.take(10000)}")
                } else {
                    ToolResult.err("❌ 脚本执行失败 (exit=$exitCode):\n\n${output.take(10000)}")
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            ToolResult.err("脚本执行超时（${timeoutSec}秒）")
        } catch (e: Exception) {
            ToolResult.err("执行异常: ${e.message}")
        }
    }
}
