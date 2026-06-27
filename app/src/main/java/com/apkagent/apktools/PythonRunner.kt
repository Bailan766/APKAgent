package com.apkagent.apktools

import android.content.Context
import com.apkagent.shizuku.ShizukuManager
import com.apkagent.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Python 执行引擎 — 优先 Shizuku shell 权限执行，回退普通 ProcessBuilder
 */
object PythonRunner {

    private var internalDir: File? = null

    private val EXTERNAL_PATHS = listOf(
        "/data/data/com.termux/files/usr/bin/python3",
        "/data/data/com.termux/files/usr/bin/python",
        "/data/data/com.termux.nix/files/usr/bin/python3",
        "/data/data/org.qpython.qpy3/files/bin/python3",
        "/data/data/org.qpython.qpy/files/bin/python",
        "/sdcard/python3/bin/python3",
        "/sdcard/APKAgent/python/bin/python3",
    )

    @Volatile private var cachedPython: String? = null
    @Volatile var pythonPath: String? = null
    private val installedPackages = mutableSetOf<String>()

    fun init(context: Context) {
        internalDir = File(context.filesDir, "python")
        internalDir?.mkdirs()
    }

    fun getInternalDir(): File? = internalDir

    /**
     * 查找 Python 路径。
     * 优先找可执行的，找不到时也返回存在的路径（Shizuku 可以执行它）。
     */
    fun findPython(): String? {
        // 用户手动指定
        pythonPath?.let { custom ->
            val f = File(custom)
            if (f.exists()) return custom
        }

        // 内部安装
        internalDir?.let { internal ->
            val internalPy = File(internal, "bin/python3")
            if (internalPy.exists()) {
                cachedPython = internalPy.absolutePath
                Logger.i("Py", "内部: ${internalPy.absolutePath}")
                return cachedPython
            }
            val rootPy = File(internal, "python3")
            if (rootPy.exists()) {
                cachedPython = rootPy.absolutePath
                Logger.i("Py", "内部: ${rootPy.absolutePath}")
                return cachedPython
            }
        }

        // 外部路径（只需 exists，Shizuku 可以执行）
        for (path in EXTERNAL_PATHS) {
            if (File(path).exists()) {
                cachedPython = path
                Logger.i("Py", "外部: $path")
                return path
            }
        }

        // which 探测
        try {
            val proc = ProcessBuilder("sh", "-c", "which python3 2>/dev/null || which python 2>/dev/null").start()
            val out = proc.inputStream.bufferedReader().readLine()
            proc.waitFor()
            if (!out.isNullOrBlank() && File(out.trim()).exists()) {
                cachedPython = out.trim()
                Logger.i("Py", "which: $cachedPython")
                return cachedPython
            }
        } catch (_: Exception) {}

        // Shizuku shell 探测
        if (ShizukuManager.isAuthorized()) {
            val path = ShizukuManager.execAndGet("which python3 2>/dev/null || which python 2>/dev/null")?.trim()
            if (!path.isNullOrBlank() && path.startsWith("/")) {
                cachedPython = path
                Logger.i("Py", "shizuku which: $path")
                return path
            }
        }

        cachedPython = null
        return null
    }

    fun isAvailable(): Boolean = findPython() != null

    fun installGuide(): String = """
未找到 Python。请使用内置终端安装，或手动安装 Termux + Python。
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
     * 1) 优先尝试 Shizuku shell 权限执行（绕过权限限制）
     * 2) 回退到普通 ProcessBuilder
     */
    suspend fun execute(script: File, timeoutSeconds: Int = 30, workDir: File? = null): ExecResult =
        withContext(Dispatchers.IO) {
            val python = findPython() ?: return@withContext ExecResult(false, null, installGuide())
            if (!script.exists()) return@withContext ExecResult(false, null, "脚本不存在: ${script.absolutePath}")

            Logger.i("Py", "执行: ${script.name} timeout=${timeoutSeconds}s")

            // 构建命令
            val workDirPath = workDir?.absolutePath ?: script.parentFile?.absolutePath ?: "."
            val env = buildString {
                internalDir?.let {
                    append("PYTHONHOME='${it.absolutePath}' ")
                    val pyLib = File(it, "lib/python3")
                    if (pyLib.exists()) append("PYTHONPATH='${pyLib.absolutePath}' ")
                }
            }
            val cmd = "cd '$workDirPath' && ${env}${python} '${script.absolutePath}'"

            try {
                withTimeout(timeoutSeconds * 1000L) {
                    // 优先用 Shizuku shell 权限
                    if (ShizukuManager.isAuthorized()) {
                        Logger.i("Py", "通过 Shizuku shell 执行")
                        val process = ShizukuManager.execShizuku(cmd)
                        if (process != null) {
                            val stdout = process.inputStream.bufferedReader().use { it.readText() }
                            val stderr = process.errorStream.bufferedReader().use { it.readText() }
                            val exit = process.waitFor()
                            Logger.i("Py", "shizuku exit=$exit")
                            return@withTimeout if (exit == 0)
                                ExecResult(true, stdout.take(8000), stderr.take(2000).ifBlank { null })
                            else
                                ExecResult(false, stdout.take(2000).ifBlank { null },
                                    stderr.ifBlank { "exit=$exit" }.take(2000))
                        }
                    }

                    // 回退到普通 ProcessBuilder
                    Logger.i("Py", "通过 ProcessBuilder 执行")
                    val pb = ProcessBuilder(python, script.absolutePath)
                    if (workDir?.isDirectory == true) pb.directory(workDir)
                    internalDir?.let { pb.environment()["PYTHONHOME"] = it.absolutePath }
                    pb.environment()["PYTHONPATH"] = File(internalDir, "lib/python3").let {
                        if (it.exists()) it.absolutePath else ""
                    }
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
            // Shizuku 优先
            if (ShizukuManager.isAuthorized()) {
                val v = ShizukuManager.execAndGet("$python --version 2>&1")?.trim()
                if (!v.isNullOrBlank()) return@withContext v
            }
            val pb = ProcessBuilder(python, "--version")
            internalDir?.let { pb.environment()["PYTHONHOME"] = it.absolutePath }
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readLine()
            proc.waitFor(); out
        } catch (_: Exception) { null }
    }

    // ──── pip 包管理 ────

    suspend fun refreshInstalled(): List<String> = withContext(Dispatchers.IO) {
        val python = findPython() ?: return@withContext emptyList()
        try {
            val cmd = internalDir?.let {
                "PYTHONHOME='${it.absolutePath}' $python -m pip list --format=columns"
            } ?: "$python -m pip list --format=columns"

            val out = if (ShizukuManager.isAuthorized()) {
                ShizukuManager.execAndGet(cmd) ?: ""
            } else {
                val pb = ProcessBuilder(python, "-m", "pip", "list", "--format=columns")
                internalDir?.let { pb.environment()["PYTHONHOME"] = it.absolutePath }
                pb.redirectErrorStream(true)
                val proc = pb.start()
                proc.inputStream.bufferedReader().readText()
            }

            installedPackages.clear()
            out.lines().drop(2).forEach { line ->
                val name = line.split(" ").firstOrNull()?.trim()
                if (!name.isNullOrBlank()) installedPackages.add(name.lowercase())
            }
        } catch (_: Exception) {}
        installedPackages.toList()
    }

    fun isPackageInstalled(name: String): Boolean = installedPackages.contains(name.lowercase())

    suspend fun pipInstall(vararg packages: String, timeoutSeconds: Int = 300): ExecResult =
        withContext(Dispatchers.IO) {
            val python = findPython() ?: return@withContext ExecResult(false, null, installGuide())
            Logger.i("Py", "pip install ${packages.joinToString(" ")}")
            try {
                withTimeout(timeoutSeconds * 1000L) {
                    val cmd = internalDir?.let {
                        "PYTHONHOME='${it.absolutePath}' $python -m pip install ${packages.joinToString(" ")} 2>&1"
                    } ?: "$python -m pip install ${packages.joinToString(" ")} 2>&1"

                    val out = if (ShizukuManager.isAuthorized()) {
                        ShizukuManager.execAndGet(cmd) ?: "Shizuku 执行失败"
                    } else {
                        val pb = ProcessBuilder(python, "-m", "pip", "install", *packages)
                        internalDir?.let { pb.environment()["PYTHONHOME"] = it.absolutePath }
                        pb.redirectErrorStream(true)
                        val proc = pb.start()
                        proc.inputStream.bufferedReader().readText()
                    }
                    ExecResult(!out.contains("ERROR"), out.take(4000), null)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                ExecResult(false, null, "pip install 超时")
            } catch (e: Throwable) {
                ExecResult(false, null, "pip install 失败: ${e.message}")
            }
        }

    suspend fun pipList(): ExecResult = withContext(Dispatchers.IO) {
        val python = findPython() ?: return@withContext ExecResult(false, null, installGuide())
        try {
            val cmd = internalDir?.let {
                "PYTHONHOME='${it.absolutePath}' $python -m pip list 2>&1"
            } ?: "$python -m pip list 2>&1"

            val out = if (ShizukuManager.isAuthorized()) {
                ShizukuManager.execAndGet(cmd) ?: "Shizuku 执行失败"
            } else {
                val pb = ProcessBuilder(python, "-m", "pip", "list")
                internalDir?.let { pb.environment()["PYTHONHOME"] = it.absolutePath }
                pb.redirectErrorStream(true)
                val proc = pb.start()
                proc.inputStream.bufferedReader().readText()
            }
            ExecResult(true, out.take(4000), null)
        } catch (e: Throwable) {
            ExecResult(false, null, "pip list 失败: ${e.message}")
        }
    }

    /**
     * 确保 Python 二进制有执行权限（chmod 755）
     */
    private fun ensureExecutable(pythonPath: String) {
        try {
            val bin = File(pythonPath)
            if (!bin.canExecute()) {
                bin.setExecutable(true, false)
                Logger.i("Py", "chmod 755: $pythonPath")
            }
            // 也给 pip 设置权限
            val pip = File(bin.parentFile, "pip3")
            if (pip.exists() && !pip.canExecute()) {
                pip.setExecutable(true, false)
            }
        } catch (e: Exception) {
            Logger.w("Py", "chmod 失败: ${e.message}")
        }
    }
}
