package com.apkagent.terminal

import com.apkagent.i18n.L

import com.apkagent.util.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * 内置终端管理器
 * 支持执行 shell 命令、交互式会话
 */
class TerminalSession(
    private val workDir: File = File("/data/data/com.apkagent/files")
) {
    // 这些引用会被 IO 协程与调用线程共同访问，用 @Volatile 保证可见性
    @Volatile private var process: Process? = null
    @Volatile private var writer: OutputStreamWriter? = null
    @Volatile private var readerJob: Job? = null

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val outputBuffer = StringBuilder()

    fun start(): Boolean {
        // 先停掉旧会话，避免直接覆盖 process / readerJob 泄漏旧进程
        stop()
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-l")
            pb.directory(workDir)
            pb.environment().apply {
                put("HOME", workDir.absolutePath)
                put("PATH", "/system/bin:/system/xbin:/vendor/bin")
                put("TERM", "xterm-256color")
            }
            pb.redirectErrorStream(true)
            // 用局部 val 持有本会话进程，协程内不再读可变字段 process!!
            val proc = pb.start()
            process = proc
            writer = OutputStreamWriter(proc.outputStream)
            _isRunning.value = true

            readerJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    val buffer = CharArray(1024)
                    while (isActive) {
                        val len = reader.read(buffer)
                        if (len == -1) break
                        val text = String(buffer, 0, len)
                        synchronized(outputBuffer) {
                            outputBuffer.append(text)
                            // 保留最近 50KB 输出
                            if (outputBuffer.length > 50_000) {
                                val trim = outputBuffer.length - 40_000
                                outputBuffer.delete(0, trim)
                            }
                            _output.value = outputBuffer.toString()
                        }
                    }
                } catch (e: Exception) {
                    Logger.e("Terminal", L("读取输出失败", "Reading output failed"), e)
                }
                // 仅当仍是当前会话时才置停止，避免被 stop() 取消的旧任务覆盖新会话状态
                if (process === proc) _isRunning.value = false
            }

            Logger.i("Terminal", L("会话启动: ${workDir.absolutePath}", "Session started: ${workDir.absolutePath}"))
            true
        } catch (e: Exception) {
            Logger.e("Terminal", L("启动失败", "Starting Failed"), e)
            false
        }
    }

    fun exec(command: String) {
        val w = writer ?: return
        try {
            w.write("$command\n")
            w.flush()
            Logger.d("Terminal", "执行: $command")
        } catch (e: Exception) {
            Logger.e("Terminal", L("写入失败", "Writing Failed"), e)
        }
    }

    fun stop() {
        try {
            writer?.close()
            process?.destroy()
            readerJob?.cancel()
        } catch (_: Exception) {}
        // 清空所有引用，避免 exec() 再拿到已关闭的 writer
        writer = null
        process = null
        readerJob = null
        _isRunning.value = false
    }

    fun clearOutput() {
        synchronized(outputBuffer) {
            outputBuffer.clear()
            _output.value = ""
        }
    }
}

/**
 * 单命令执行（非交互式）
 */
object CommandExecutor {

    suspend fun execute(
        command: String,
        workDir: File? = null,
        env: Map<String, String> = emptyMap(),
        timeoutSeconds: Int = 120
    ): ExecResult = withContext(Dispatchers.IO) {
        // proc 提到 try 外，保证超时 / 异常时 finally 一定能销毁进程
        var proc: Process? = null
        try {
            withTimeout(timeoutSeconds * 1000L + 10_000L) {
                val pb = ProcessBuilder("/system/bin/sh", "-c", command)
                workDir?.let { if (it.isDirectory) pb.directory(it) }
                pb.environment().putAll(env)
                pb.redirectErrorStream(true)
                proc = pb.start()
                val p = proc!!
                val output = StringBuffer()
                // 输出在独立线程读取，主线程用带超时 waitFor 等待，避免阻塞 readText 不响应取消
                val reader = Thread {
                    try {
                        BufferedReader(InputStreamReader(p.inputStream)).use { br ->
                            val buf = CharArray(4096)
                            while (true) {
                                val n = br.read(buf)
                                if (n == -1) break
                                output.append(buf, 0, n)
                            }
                        }
                    } catch (_: Throwable) {
                        // 进程销毁后管道关闭，读取线程以 EOF / 异常结束
                    }
                }
                reader.isDaemon = true
                reader.start()
                val timeoutMs = timeoutSeconds * 1000L
                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                    reader.join(1_000L)
                    return@withTimeout ExecResult(false, output.toString().take(16000), "超时(${timeoutSeconds}s)")
                }
                reader.join(2_000L)
                val exit = p.exitValue()
                val stdout = output.toString()
                ExecResult(exit == 0, stdout.take(16000), if (exit != 0) "exit=$exit" else null)
            }
        } catch (e: TimeoutCancellationException) {
            ExecResult(false, null, "超时(${timeoutSeconds}s)")
        } catch (e: Exception) {
            ExecResult(false, null, e.message ?: L("执行失败", "Execution failed"))
        } finally {
            proc?.destroyForcibly()
        }
    }

    data class ExecResult(
        val success: Boolean,
        val output: String?,
        val error: String?
    ) {
        val combined: String get() = buildString {
            if (!output.isNullOrBlank()) append(output)
            if (!error.isNullOrBlank()) {
                if (isNotEmpty()) append("\n")
                append(error)
            }
        }
    }
}
