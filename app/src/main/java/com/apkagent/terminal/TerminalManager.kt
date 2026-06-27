package com.apkagent.terminal

import com.apkagent.util.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * 内置终端管理器
 * 支持执行 shell 命令、交互式会话
 */
class TerminalSession(
    private val workDir: File = File("/data/data/com.apkagent/files")
) {
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var readerJob: Job? = null

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val outputBuffer = StringBuilder()

    fun start(): Boolean {
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-l")
            pb.directory(workDir)
            pb.environment().apply {
                put("HOME", workDir.absolutePath)
                put("PATH", "/system/bin:/system/xbin:/vendor/bin")
                put("TERM", "xterm-256color")
            }
            pb.redirectErrorStream(true)
            process = pb.start()
            writer = OutputStreamWriter(process!!.outputStream)
            _isRunning.value = true

            readerJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val reader = BufferedReader(InputStreamReader(process!!.inputStream))
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
                    Logger.e("Terminal", "读取输出失败", e)
                }
                _isRunning.value = false
            }

            Logger.i("Terminal", "会话启动: ${workDir.absolutePath}")
            true
        } catch (e: Exception) {
            Logger.e("Terminal", "启动失败", e)
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
            Logger.e("Terminal", "写入失败", e)
        }
    }

    fun stop() {
        try {
            writer?.close()
            process?.destroy()
            readerJob?.cancel()
        } catch (_: Exception) {}
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
        try {
            withTimeout(timeoutSeconds * 1000L) {
                val pb = ProcessBuilder("/system/bin/sh", "-c", command)
                workDir?.let { if (it.isDirectory) pb.directory(it) }
                pb.environment().putAll(env)
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val stdout = proc.inputStream.bufferedReader().use { it.readText() }
                val exit = proc.waitFor()
                ExecResult(exit == 0, stdout.take(16000), if (exit != 0) "exit=$exit" else null)
            }
        } catch (e: TimeoutCancellationException) {
            ExecResult(false, null, "超时(${timeoutSeconds}s)")
        } catch (e: Exception) {
            ExecResult(false, null, e.message ?: "执行失败")
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
