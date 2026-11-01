package com.apkagent.tools

import com.apkagent.i18n.L
import com.apkagent.agent.*
import com.apkagent.apktools.PythonRunner
import com.apkagent.apktools.Sandbox
import com.apkagent.runtime.RuntimeManager
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
object CreateScriptTool : Tool {
    override val name = "create_script"
    override val description get() = L(
        "创建自定义脚本（Python/JavaScript/Shell）用于解决特殊问题（如自定义解密算法、批量处理、Frida Hook）",
        "Create custom scripts (Python/JavaScript/Shell) for custom decryption, batch processing, or Frida hooks"
    )

    override val parameters get() = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("filename") {
                put("type", "string")
                put("description", L("脚本文件名（如 decrypt.py, hook.js, batch.sh）", "Script filename (e.g., decrypt.py, hook.js, batch.sh)"))
            }
            putJsonObject("content") {
                put("type", "string")
                put("description", L("脚本完整代码内容", "Full script source code"))
            }
            putJsonObject("description") {
                put("type", "string")
                put("description", L("脚本用途说明", "Script description"))
            }
        }
        putJsonArray("required") {
            add("filename")
            add("content")
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val filename = args["filename"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult(false, L("缺少 filename 参数", "Missing filename parameter"))
        val content = args["content"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(false, L("缺少 content 参数", "Missing content parameter"))
        val desc = args["description"]?.jsonPrimitive?.contentOrNull ?: ""

        val scriptFile = if (filename.contains('/') || filename.contains('\\')) {
            Sandbox.resolve(File(filename), ctx.workspace)
        } else {
            val scriptsDir = File(ctx.workspace, "scripts")
            scriptsDir.mkdirs()
            File(scriptsDir, filename)
        }
        scriptFile.parentFile?.mkdirs()

        return try {
            scriptFile.writeText(content)
            scriptFile.setExecutable(true)
            ToolResult(
                true,
                "✅ 脚本已创建: ${scriptFile.absolutePath}\n用途: $desc\n大小: ${content.length} 字节\n\n可使用 `run_script` 工具执行此脚本"
            )
        } catch (e: Exception) {
            ToolResult(false, "创建脚本失败: ${e.message}")
        }
    }
}

object RunScriptTool : Tool {
    override val name = "run_script"
    override val description get() = L(
        "执行工作区中的脚本（支持 .py/.js/.sh），可传递参数",
        "Execute scripts (.py/.js/.sh) in the workspace with optional arguments"
    )

    override val parameters get() = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("script_path") {
                put("type", "string")
                put("description", L("脚本路径（相对工作区或绝对路径，也可传入文件名）", "Script path (relative to workspace, filename in scripts/, or absolute path)"))
            }
            putJsonObject("script") {
                put("type", "string")
                put("description", L("脚本文件名（兼容别名）", "Script filename (alias for script_path)"))
            }
            putJsonObject("args") {
                put("type", "string")
                put("description", L("传递给脚本的命令行参数（可选）", "Command-line arguments passed to the script (optional)"))
            }
            putJsonObject("timeout") {
                put("type", "integer")
                put("description", L("超时时间（秒，默认 60）", "Timeout in seconds (default 60)"))
            }
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val actualScriptPath = (args["script_path"]?.jsonPrimitive?.contentOrNull
            ?: args["script"]?.jsonPrimitive?.contentOrNull)?.trim()
            ?: return@withContext ToolResult(false, L("缺少 script_path 参数", "Missing script_path parameter"))
        val scriptArgs = args["args"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
        val timeout = (args["timeout"]?.jsonPrimitive?.intOrNull ?: 60).coerceIn(5, 600)

        val candidates = listOf(
            Sandbox.resolve(File(actualScriptPath), ctx.workspace),
            File(ctx.workspace, "scripts/$actualScriptPath"),
            File(ctx.workspace, "scripts/${File(actualScriptPath).name}")
        )
        val scriptFile = candidates.firstOrNull { it.exists() && it.isFile }
            ?: return@withContext ToolResult(false, "脚本文件不存在: $actualScriptPath")

        // 如果无命令行参数且是 .py 脚本，优先走 PythonRunner（支持自动部署到 /data/local/tmp）
        if (scriptFile.extension.equals("py", ignoreCase = true) && scriptArgs.isBlank()) {
            val pyRes = PythonRunner.execute(scriptFile, timeoutSeconds = timeout, workDir = ctx.workspace)
            if (pyRes.success || pyRes.output != null) {
                return@withContext ToolResult(
                    pyRes.success,
                    "脚本执行完成 (${scriptFile.name})\n\n输出:\n${pyRes.combined.take(10000)}"
                )
            }
        }

        val runtimeManager = RuntimeManager(ctx.appContext)
        val command = when (scriptFile.extension.lowercase()) {
            "py" -> {
                val pythonCmd = PythonRunner.findPython() ?: runtimeManager.getPythonCommand()
                    ?: return@withContext ToolResult(false, L("Python 环境未安装，请先在设置中安装 Python 运行时", "Python runtime is not installed"))
                "'$pythonCmd' '${scriptFile.absolutePath}' $scriptArgs 2>&1"
            }
            "js" -> {
                val nodeCmd = runtimeManager.getNodeCommand()
                    ?: return@withContext ToolResult(false, L("Node.js 环境未安装，请先在设置中安装 Node.js 运行时", "Node.js runtime is not installed"))
                "'$nodeCmd' '${scriptFile.absolutePath}' $scriptArgs 2>&1"
            }
            "sh" -> "sh '${scriptFile.absolutePath}' $scriptArgs 2>&1"
            else -> return@withContext ToolResult(false, "不支持的脚本类型: .${scriptFile.extension}")
        }

        try {
            withTimeout(timeout * 1000L) {
                val process = if (ShizukuManager.isAuthorized()) {
                    ShizukuManager.execShizuku("cd '${ctx.workspace.absolutePath}' && $command")
                } else {
                    ProcessBuilder("sh", "-c", command)
                        .directory(ctx.workspace)
                        .redirectErrorStream(true)
                        .start()
                } ?: return@withTimeout ToolResult(false, L("无法启动脚本进程", "Unable to start script process"))

                // 先完整读取输出流再 waitFor，防止超过 64KB 缓冲区死锁
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()

                val output = buildString {
                    appendLine("脚本执行完成 (退出码: $exitCode)")
                    if (stdout.isNotBlank()) {
                        appendLine("\n输出:")
                        appendLine(stdout.take(10000))
                    }
                }
                ToolResult(exitCode == 0, output)
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            ToolResult(false, "脚本执行超时 (${timeout}秒)")
        } catch (e: Exception) {
            ToolResult(false, "执行脚本异常: ${e.message}")
        }
    }
}
