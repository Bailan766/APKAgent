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
 * Python 执行引擎 — 通过 Shizuku shell 权限执行
 *
 * Android 11+ app 私有目录对 shell uid 不可访问（目录级权限），
 * 必须将 Python 复制到 /data/local/tmp/apkagent_py/ 才能执行。
 */
object PythonRunner {

    private var internalDir: File? = null
    private const val TMP_PYTHON_DIR = "/data/local/tmp/apkagent_py"
    private const val TMP_PYTHON_BIN = "$TMP_PYTHON_DIR/bin/python3"

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

        // 内部安装 — 先检查 /data/local/tmp/ 中已部署的副本
        if (File(TMP_PYTHON_BIN).exists()) {
            cachedPython = TMP_PYTHON_BIN
            Logger.i("Py", "tmp: $TMP_PYTHON_BIN")
            return cachedPython
        }

        // 内部安装 — 原始位置
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
     * 确保 Python 已部署到 /data/local/tmp/ （只需做一次，除非源文件更新）
     *
     * Android 11+ /data/user/0/com.apkagent/ 对 shell uid 不可访问，
     * 必须复制到 /data/local/tmp/apkagent_py/ 才能通过 Shizuku 执行。
     */
    private fun ensureDeployedToTmp(): String? {
        val srcDir = internalDir ?: return null
        val srcBin = File(srcDir, "bin/python3")
        if (!srcBin.exists()) return null

        val tmpBin = File(TMP_PYTHON_BIN)
        val tmpDir = File(TMP_PYTHON_DIR)

        // 如果已部署且源文件没有更新，直接返回
        if (tmpBin.exists() && tmpBin.lastModified() >= srcBin.lastModified()) {
            Logger.i("Py", "已部署到 tmp，跳过复制")
            return TMP_PYTHON_BIN
        }

        // 通过 Shizuku 复制整个 python 目录到 /data/local/tmp/
        Logger.i("Py", "部署 Python 到 $TMP_PYTHON_DIR ...")
        ShizukuManager.execAndGet("rm -rf '$TMP_PYTHON_DIR'")
        ShizukuManager.execAndGet("cp -a '${srcDir.absolutePath}' '$TMP_PYTHON_DIR'")
        ShizukuManager.execAndGet("chmod -R 755 '$TMP_PYTHON_DIR'")

        return if (tmpBin.exists()) {
            Logger.i("Py", "部署成功: $TMP_PYTHON_BIN")
            TMP_PYTHON_BIN
        } else {
            Logger.e("Py", "部署失败")
            null
        }
    }

    /**
     * 获取可执行的 Python 路径。
     * 内部安装的 Python 必须通过 /data/local/tmp/ 中转执行。
     */
    private fun getExecutablePython(): String? {
        val python = findPython() ?: return null

        // 外部 Python（Termux 等）可以直接执行
        if (!python.startsWith(internalDir?.absolutePath ?: "\u0000")) {
            return python
        }

        // 内部 Python 必须部署到 /data/local/tmp/
        if (!ShizukuManager.isAuthorized()) {
            Logger.w("Py", "内部 Python 需要 Shizuku 授权才能执行")
            return null
        }
        return ensureDeployedToTmp()
    }

    /**
     * 执行 Python 脚本。
     * 通过 Shizuku shell 以 /data/local/tmp/ 中的 Python 副本执行。
     */
    suspend fun execute(script: File, timeoutSeconds: Int = 30, workDir: File? = null): ExecResult =
        withContext(Dispatchers.IO) {
            val python = getExecutablePython()
                ?: return@withContext ExecResult(false, null, installGuide())
            if (!script.exists()) return@withContext ExecResult(false, null, "脚本不存在: ${script.absolutePath}")

            Logger.i("Py", "执行: ${script.name} python=$python timeout=${timeoutSeconds}s")

            val workDirPath = workDir?.absolutePath ?: script.parentFile?.absolutePath ?: "."
            // PYTHONHOME 指向 tmp 中的副本（不是原始 filesDir）
            val pyHome = if (python == TMP_PYTHON_BIN) TMP_PYTHON_DIR else internalDir?.absolutePath
            val env = buildString {
                pyHome?.let {
                    append("PYTHONHOME='$it' ")
                    val pyLib = File(it, "lib/python3")
                    if (pyLib.exists()) append("PYTHONPATH='${pyLib.absolutePath}' ")
                }
            }
            val cmd = "cd '$workDirPath' && ${env}'${python}' '${script.absolutePath}'"

            try {
                withTimeout(timeoutSeconds * 1000L) {
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

                    // 回退到普通 ProcessBuilder（仅外部 Python 有效）
                    Logger.i("Py", "通过 ProcessBuilder 执行")
                    val pb = ProcessBuilder(python, script.absolutePath)
                    if (workDir?.isDirectory == true) pb.directory(workDir)
                    pyHome?.let { pb.environment()["PYTHONHOME"] = it }
                    pb.environment()["PYTHONPATH"] = File(pyHome ?: "", "lib/python3").let {
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
        val python = getExecutablePython() ?: return@withContext null
        try {
            if (ShizukuManager.isAuthorized()) {
                val v = ShizukuManager.execAndGet("'${python}' --version 2>&1")?.trim()
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
        val python = getExecutablePython() ?: return@withContext emptyList()
        val pyHome = if (python == TMP_PYTHON_BIN) TMP_PYTHON_DIR else internalDir?.absolutePath
        try {
            val cmd = pyHome?.let {
                "PYTHONHOME='$it' $python -m pip list --format=columns"
            } ?: "$python -m pip list --format=columns"

            val out = if (ShizukuManager.isAuthorized()) {
                ShizukuManager.execAndGet(cmd) ?: ""
            } else {
                val pb = ProcessBuilder(python, "-m", "pip", "list", "--format=columns")
                pyHome?.let { pb.environment()["PYTHONHOME"] = it }
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
            val python = getExecutablePython()
                ?: return@withContext ExecResult(false, null, installGuide())
            val pyHome = if (python == TMP_PYTHON_BIN) TMP_PYTHON_DIR else internalDir?.absolutePath
            Logger.i("Py", "pip install ${packages.joinToString(" ")}")
            try {
                withTimeout(timeoutSeconds * 1000L) {
                    val cmd = pyHome?.let {
                        "PYTHONHOME='$it' $python -m pip install ${packages.joinToString(" ")} 2>&1"
                    } ?: "$python -m pip install ${packages.joinToString(" ")} 2>&1"

                    val out = if (ShizukuManager.isAuthorized()) {
                        ShizukuManager.execAndGet(cmd) ?: "Shizuku 执行失败"
                    } else {
                        val pb = ProcessBuilder(python, "-m", "pip", "install", *packages)
                        pyHome?.let { pb.environment()["PYTHONHOME"] = it }
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
        val python = getExecutablePython()
            ?: return@withContext ExecResult(false, null, installGuide())
        val pyHome = if (python == TMP_PYTHON_BIN) TMP_PYTHON_DIR else internalDir?.absolutePath
        try {
            val cmd = pyHome?.let {
                "PYTHONHOME='$it' $python -m pip list 2>&1"
            } ?: "$python -m pip list 2>&1"

            val out = if (ShizukuManager.isAuthorized()) {
                ShizukuManager.execAndGet(cmd) ?: "Shizuku 执行失败"
            } else {
                val pb = ProcessBuilder(python, "-m", "pip", "list")
                pyHome?.let { pb.environment()["PYTHONHOME"] = it }
                pb.redirectErrorStream(true)
                val proc = pb.start()
                proc.inputStream.bufferedReader().readText()
            }
            ExecResult(true, out.take(4000), null)
        } catch (e: Throwable) {
            ExecResult(false, null, "pip list 失败: ${e.message}")
        }
    }
}
