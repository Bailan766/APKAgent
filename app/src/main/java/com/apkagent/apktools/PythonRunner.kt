package com.apkagent.apktools

import com.apkagent.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Android 原生 Python 脚本执行引擎。
 * 自动探测设备上的 Python 环境（Termux / QPython / 系统内置），
 * 支持超时控制、工作区目录、结果截断。
 */
object PythonRunner {

    /** 按优先级搜索 Python 二进制 */
    private val SEARCH_PATHS = listOf(
        "/data/data/com.termux/files/usr/bin/python3",
        "/data/data/com.termux/files/usr/bin/python",
        "/data/data/org.qpython.qpy3/files/bin/python3",
        "/data/data/org.qpython.qpy/files/bin/python",
        "/system/bin/python3",
        "/system/bin/python",
        "/sdcard/python3/bin/python3",
    )

    @Volatile private var cachedPython: String? = null
    @Volatile private var cachedAvailable: Boolean = false
    @Volatile var pythonPath: String? = null // 用户自定义路径

    /** 探测并缓存 Python 二进制路径 */
    fun findPython(): String? {
        // 优先用户自定义路径
        pythonPath?.let { custom ->
            val f = File(custom)
            if (f.exists() && f.canExecute()) {
                cachedPython = custom
                cachedAvailable = true
                return custom
            }
        }
        if (cachedAvailable) return cachedPython
        if (cachedPython != null) return cachedPython  // 已缓存但不可用

        for (path in SEARCH_PATHS) {
            val f = File(path)
            if (f.exists() && f.canExecute()) {
                cachedPython = path
                cachedAvailable = true
                Logger.i("PythonRunner", "找到 Python: $path")
                return path
            }
        }
        // 尝试 which
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", "which python3 2>/dev/null || which python 2>/dev/null"))
            val out = proc.inputStream.bufferedReader().readLine()
            proc.waitFor()
            if (!out.isNullOrBlank()) {
                cachedPython = out.trim()
                cachedAvailable = true
                Logger.i("PythonRunner", "which 找到: $cachedPython")
                return cachedPython
            }
        } catch (_: Exception) {}

        cachedAvailable = false
        return null
    }

    fun isAvailable(): Boolean = findPython() != null

    /** 获取安装指导文本 */
    fun installGuide(): String = """
未找到 Python 环境。请安装以下任一方案：
1. Termux: pkg install python && pip install frida-tools androguard
2. QPython (Play商店下载，apk逆向专用)
3. 在设置页手动配置 Python 路径
""".trimIndent()

    data class ExecResult(
        val success: Boolean,
        val output: String?,
        val error: String?
    ) {
        val combined: String get() = buildString {
            if (!output.isNullOrBlank()) append(output)
            if (!error.isNullOrBlank()) {
                if (isNotEmpty()) append("\n── STDERR ──\n")
                append(error)
            }
        }
    }

    /**
     * 执行 Python 脚本文件。
     * @param script .py 脚本文件
     * @param timeoutSeconds 超时秒数
     * @param workDir 工作目录（可选）
     */
    suspend fun execute(
        script: File,
        timeoutSeconds: Int = 30,
        workDir: File? = null
    ): ExecResult = withContext(Dispatchers.IO) {
        val python = findPython()
            ?: return@withContext ExecResult(false, null, installGuide())

        if (!script.exists() || !script.canRead()) {
            return@withContext ExecResult(false, null, "脚本文件不存在或不可读: ${script.absolutePath}")
        }

        Logger.i("PythonRunner", "执行: ${script.name} (timeout=${timeoutSeconds}s)")
        val startMs = System.currentTimeMillis()

        try {
            withTimeout(timeoutSeconds * 1000L) {
                val pb = ProcessBuilder(python, script.absolutePath)
                if (workDir != null && workDir.isDirectory) {
                    pb.directory(workDir)
                }
                pb.redirectErrorStream(false)
                val proc = pb.start()

                val stdout = proc.inputStream.bufferedReader().use { it.readText() }
                val stderr = proc.errorStream.bufferedReader().use { it.readText() }
                val exitCode = proc.waitFor()

                val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
                Logger.i("PythonRunner", "退出码=$exitCode, ${elapsed}s")

                if (exitCode == 0) {
                    ExecResult(true, stdout.take(8000), stderr.take(2000).ifBlank { null })
                } else {
                    val errMsg = buildString {
                        if (!stderr.isBlank()) append(stderr.take(2000))
                        else append("退出码=$exitCode")
                        if (!stdout.isBlank()) {
                            append("\n── 部分输出 ──\n")
                            append(stdout.take(2000))
                        }
                    }
                    ExecResult(false, stdout.take(2000).ifBlank { null }, errMsg)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Logger.w("PythonRunner", "超时: ${script.name}")
            ExecResult(false, null, "Python 脚本执行超时（${timeoutSeconds}秒）")
        } catch (e: Throwable) {
            Logger.e("PythonRunner", "执行异常", e)
            ExecResult(false, null, "执行失败: ${e.message}")
        }
    }

    /** 获取 Python 版本信息 */
    suspend fun getVersion(): String? = withContext(Dispatchers.IO) {
        val python = findPython() ?: return@withContext null
        try {
            val proc = ProcessBuilder(python, "--version").start()
            val out = proc.inputStream.bufferedReader().readLine()
            proc.waitFor()
            out
        } catch (_: Exception) {
            null
        }
    }
}
