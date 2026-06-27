package com.apkagent.apktools

import com.apkagent.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Android 原生 Python 执行引擎 + 包管理。
 * - 自动探测 Termux / QPython / 系统 Python
 * - pip install 自安装 + 缓存追踪（不重复安装）
 * - 超时控制、工作区目录
 */
object PythonRunner {

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
    @Volatile var pythonPath: String? = null

    /** 已安装 pip 包的缓存（内存中，避免重复安装） */
    private val installedPackages = mutableSetOf<String>()
    private var pipChecked = false

    fun findPython(): String? {
        pythonPath?.let { custom ->
            val f = File(custom)
            if (f.exists() && f.canExecute()) {
                cachedPython = custom; cachedAvailable = true; return custom
            }
        }
        if (cachedAvailable) return cachedPython
        if (cachedPython != null) return cachedPython
        for (path in SEARCH_PATHS) {
            val f = File(path)
            if (f.exists() && f.canExecute()) {
                cachedPython = path; cachedAvailable = true
                Logger.i("Py", "找到: $path"); return path
            }
        }
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", "which python3 2>/dev/null || which python 2>/dev/null"))
            val out = proc.inputStream.bufferedReader().readLine()
            proc.waitFor()
            if (!out.isNullOrBlank()) {
                cachedPython = out.trim(); cachedAvailable = true
                Logger.i("Py", "which: $cachedPython"); return cachedPython
            }
        } catch (_: Exception) {}
        cachedAvailable = false; return null
    }

    fun isAvailable(): Boolean = findPython() != null

    fun installGuide(): String = """
未找到 Python 环境。请安装：
1. Termux → pkg install python
2. QPython (Play商店)
3. 在设置页手动配置路径
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
     * 执行 Python 脚本。
     */
    suspend fun execute(script: File, timeoutSeconds: Int = 30, workDir: File? = null): ExecResult =
        withContext(Dispatchers.IO) {
            val python = findPython() ?: return@withContext ExecResult(false, null, installGuide())
            if (!script.exists() || !script.canRead())
                return@withContext ExecResult(false, null, "脚本不存在: ${script.absolutePath}")

            Logger.i("Py", "执行: ${script.name} timeout=${timeoutSeconds}s")
            try {
                withTimeout(timeoutSeconds * 1000L) {
                    val pb = ProcessBuilder(python, script.absolutePath)
                    if (workDir?.isDirectory == true) pb.directory(workDir)
                    pb.redirectErrorStream(false)
                    val proc = pb.start()
                    val stdout = proc.inputStream.bufferedReader().use { it.readText() }
                    val stderr = proc.errorStream.bufferedReader().use { it.readText() }
                    val exit = proc.waitFor()
                    Logger.i("Py", "exit=$exit")
                    if (exit == 0) ExecResult(true, stdout.take(8000), stderr.take(2000).ifBlank { null })
                    else ExecResult(false, stdout.take(2000).ifBlank { null },
                        stderr.ifBlank { "exit=$exit" }.take(2000))
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Logger.w("Py", "超时"); ExecResult(false, null, "超时(${timeoutSeconds}s)")
            } catch (e: Throwable) {
                Logger.e("Py", "异常", e); ExecResult(false, null, "执行失败: ${e.message}")
            }
        }

    suspend fun getVersion(): String? = withContext(Dispatchers.IO) {
        val python = findPython() ?: return@withContext null
        try {
            val proc = ProcessBuilder(python, "--version").start()
            val out = proc.inputStream.bufferedReader().readLine()
            proc.waitFor(); out
        } catch (_: Exception) { null }
    }

    // ──── pip 包管理 ────

    /** 刷新已安装包列表 */
    suspend fun refreshInstalled(): List<String> = withContext(Dispatchers.IO) {
        val python = findPython() ?: return@withContext emptyList()
        try {
            val pb = ProcessBuilder(python, "-m", "pip", "list", "--format=columns")
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            installedPackages.clear()
            out.lines().drop(2).forEach { line ->
                val name = line.split(" ").firstOrNull()?.trim()
                if (!name.isNullOrBlank()) installedPackages.add(name.lowercase())
            }
            pipChecked = true
            Logger.i("Py", "pip list: ${installedPackages.size} packages")
        } catch (e: Throwable) {
            Logger.w("Py", "pip list failed: ${e.message}")
        }
        installedPackages.toList()
    }

    /** 检查包是否已安装 */
    suspend fun isPkgInstalled(name: String): Boolean {
        if (!pipChecked) refreshInstalled()
        return installedPackages.contains(name.lowercase())
    }

    /**
     * pip install 安装包（自动跳过已安装，支持超时）。
     * 返回安装输出。
     */
    suspend fun pipInstall(packageName: String, timeoutSeconds: Int = 120): ExecResult =
        withContext(Dispatchers.IO) {
            val python = findPython()
                ?: return@withContext ExecResult(false, null, installGuide())

            // 检查是否已安装
            if (isPkgInstalled(packageName)) {
                return@withContext ExecResult(true, "✅ $packageName 已安装（跳过）", null)
            }

            Logger.i("Py", "pip install $packageName")
            try {
                withTimeout(timeoutSeconds * 1000L) {
                    val pb = ProcessBuilder(python, "-m", "pip", "install", "--no-color", packageName)
                    pb.redirectErrorStream(true)
                    // 设置 PIP 缓存目录到工作区外，避免占用
                    pb.environment()["PIP_CACHE_DIR"] = File(python).parentFile?.parentFile?.resolve("pip-cache")?.absolutePath ?: ""
                    val proc = pb.start()
                    val out = proc.inputStream.bufferedReader().use { it.readText() }
                    val exit = proc.waitFor()
                    if (exit == 0) {
                        installedPackages.add(packageName.lowercase())
                        Logger.i("Py", "pip installed $packageName")
                        ExecResult(true, "✅ pip install $packageName 成功\n${out.take(4000)}", null)
                    } else {
                        ExecResult(false, null, "pip install 失败 (exit=$exit):\n${out.take(3000)}")
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Logger.w("Py", "pip install timeout")
                ExecResult(false, null, "pip install 超时(${timeoutSeconds}s)。可重试或手动在 Termux 中安装:\npip install $packageName")
            } catch (e: Throwable) {
                Logger.e("Py", "pip install failed", e)
                ExecResult(false, null, "pip install 异常: ${e.message}\n手动安装:\npip install $packageName")
            }
        }

    /**
     * 批量安装。先检查全部，再安装缺失的。
     */
    suspend fun pipInstallBulk(packages: List<String>, timeoutSeconds: Int = 180): ExecResult =
        withContext(Dispatchers.IO) {
            if (!pipChecked) refreshInstalled()

            val missing = packages.filter { !installedPackages.contains(it.lowercase()) }
            if (missing.isEmpty()) return@withContext ExecResult(true, "✅ 所有包已安装: ${packages.joinToString(", ")}", null)

            val python = findPython()
                ?: return@withContext ExecResult(false, null, installGuide())

            val sb = StringBuilder()
            var okCount = 0
            Logger.i("Py", "pip install bulk: ${missing.size} packages")
            try {
                withTimeout(timeoutSeconds * 1000L) {
                    for (pkg in missing) {
                        sb.appendLine("── $pkg ──")
                        val r = pipInstall(pkg, maxOf(timeoutSeconds / missing.size, 30))
                        sb.appendLine(r.combined.take(2000))
                        if (r.success) okCount++ else sb.appendLine("❌ 失败")
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                sb.appendLine("⏰ 批量安装超时")
            }
            ExecResult(okCount == missing.size, sb.toString(), null)
        }
}
