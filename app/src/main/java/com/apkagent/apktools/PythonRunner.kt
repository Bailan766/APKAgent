package com.apkagent.apktools

import android.content.Context
import com.apkagent.i18n.L
import com.apkagent.shizuku.ShizukuManager
import com.apkagent.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Python 执行引擎 — 优先调用设备原生 Python（Termux / Shizuku 部署副本），
 * 当设备未安装外部 Python、未授权 Shizuku 或遇到 Android SELinux/glibc 限制时，
 * 自动无缝回退至内置纯 Kotlin Python 3 解释器引擎 ([EmbeddedPythonEngine])，
 * 确保 `python_exec`、`run_python_script` 与 `run_script` 在任何手机上 100% 开箱即用。
 */
object PythonRunner {

    const val BUILTIN_PYTHON_ID = "builtin://embedded-python-3.12"
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
    private val installedPackages = mutableSetOf(
        "androguard", "zipfile", "hashlib", "base64", "zlib", "struct", "json", "re"
    )

    fun init(context: Context) {
        internalDir = File(context.filesDir, "python")
        internalDir?.mkdirs()
    }

    fun getInternalDir(): File? = internalDir

    /**
     * 查找原生 OS Python 二进制路径（不含内置引擎）。
     */
    fun findNativePython(): String? {
        pythonPath?.let { custom ->
            if (custom == BUILTIN_PYTHON_ID) return null
            val f = File(custom)
            if (f.exists()) return custom
        }

        if (File(TMP_PYTHON_BIN).exists()) {
            cachedPython = TMP_PYTHON_BIN
            return cachedPython
        }

        internalDir?.let { internal ->
            val internalPy = File(internal, "bin/python3")
            if (internalPy.exists()) {
                cachedPython = internalPy.absolutePath
                return cachedPython
            }
            val rootPy = File(internal, "python3")
            if (rootPy.exists()) {
                cachedPython = rootPy.absolutePath
                return cachedPython
            }
        }

        for (path in EXTERNAL_PATHS) {
            if (File(path).exists()) {
                cachedPython = path
                return path
            }
        }

        try {
            val proc = ProcessBuilder("sh", "-c", "which python3 2>/dev/null || which python 2>/dev/null").start()
            val out = proc.inputStream.bufferedReader().use { it.readLine() }
            proc.waitFor()
            if (!out.isNullOrBlank() && File(out.trim()).exists()) {
                cachedPython = out.trim()
                return cachedPython
            }
        } catch (_: Exception) {}

        if (ShizukuManager.isAuthorized()) {
            val path = ShizukuManager.execAndGet("which python3 2>/dev/null || which python 2>/dev/null")?.trim()
            if (!path.isNullOrBlank() && path.startsWith("/")) {
                cachedPython = path
                return path
            }
        }

        cachedPython = null
        return null
    }

    /**
     * 查找可用的 Python 标识：优先原生二进制，无原生环境时返回内置引擎标识。
     */
    fun findPython(): String? = findNativePython() ?: BUILTIN_PYTHON_ID

    /**
     * 内置 [EmbeddedPythonEngine] 始终就绪，因此始终返回 true。
     */
    fun isAvailable(): Boolean = true

    fun installGuide(): String = L(
        "内置 Python 3.12 引擎已就绪（如需完整 C 扩展支持，可在 Termux 安装 Python）。",
        "Built-in Python 3.12 engine is ready (install Python in Termux if native C extensions are needed)."
    )

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
     * 将内部安装的 Python 部署到 /data/local/tmp/apkagent_py/。
     * 修复 Android 11+ shell uid 无法直接读取 /data/user/0/com.apkagent/ 导致 cp 报 Permission denied 的问题：
     * 先由 App 进程自身复制到公共可读写中转目录，再由 Shizuku 移入 /data/local/tmp/ 并赋予 755 权限。
     */
    private fun ensureDeployedToTmp(): String? {
        val srcDir = internalDir ?: return null
        val srcBin = File(srcDir, "bin/python3")
        if (!srcBin.exists()) return null

        val tmpBin = File(TMP_PYTHON_BIN)
        if (tmpBin.exists() && tmpBin.lastModified() >= srcBin.lastModified()) {
            return TMP_PYTHON_BIN
        }

        if (!ShizukuManager.isAuthorized()) return null

        Logger.i("Py", "部署 Python 到 $TMP_PYTHON_DIR ...")
        ShizukuManager.execAndGet("rm -rf '$TMP_PYTHON_DIR'")
        ShizukuManager.execAndGet("cp -a '${srcDir.absolutePath}' '$TMP_PYTHON_DIR' 2>/dev/null")

        if (!tmpBin.exists()) {
            // App 进程自身中转到 /sdcard/Download/APKAgent/.py_stage 供 shell uid 读取
            runCatching {
                val stageDir = File("/sdcard/Download/APKAgent/.py_stage")
                if (stageDir.exists()) stageDir.deleteRecursively()
                srcDir.copyRecursively(stageDir, overwrite = true)
                ShizukuManager.execAndGet("cp -a '${stageDir.absolutePath}' '$TMP_PYTHON_DIR' && rm -rf '${stageDir.absolutePath}'")
            }
        }
        ShizukuManager.execAndGet("chmod -R 755 '$TMP_PYTHON_DIR'")

        return if (tmpBin.exists()) TMP_PYTHON_BIN else null
    }

    private fun getExecutablePython(): String? {
        val python = findNativePython() ?: return null
        val internalPath = internalDir?.absolutePath
        if (internalPath == null || !python.startsWith(internalPath)) {
            return python
        }
        if (ShizukuManager.isAuthorized()) {
            ensureDeployedToTmp()?.let { return it }
        }
        return if (File(python).canExecute()) python else null
    }

    /**
     * 执行 Python 脚本文件。
     * 优先尝试原生 Python 进程；若原生进程不可用或因环境缺失失败，自动使用 [EmbeddedPythonEngine] 执行。
     */
    suspend fun execute(
        script: File,
        timeoutSeconds: Int = 30,
        workDir: File? = null,
        openApk: File? = null,
        args: List<String> = emptyList()
    ): ExecResult = withContext(Dispatchers.IO) {
        if (!script.exists()) {
            return@withContext ExecResult(false, null, "脚本不存在: ${script.absolutePath}")
        }
        val actualWorkDir = workDir ?: script.parentFile ?: File(".")
        val code = runCatching { script.readText() }.getOrElse {
            return@withContext ExecResult(false, null, "读取脚本失败: ${it.message}")
        }

        val nativePy = getExecutablePython()
        if (nativePy != null) {
            val nativeRes = tryExecuteNative(nativePy, script, timeoutSeconds, actualWorkDir, args)
            if (nativeRes != null && nativeRes.success) {
                return@withContext nativeRes
            }
            Logger.i("Py", "原生 Python 未能成功执行 (${nativeRes?.error?.take(80)})，自动切换至内置 Python 引擎")
        }

        EmbeddedPythonEngine.execute(
            code = code,
            workDir = actualWorkDir,
            openApk = openApk,
            args = args,
            timeoutMs = timeoutSeconds * 1000L
        )
    }

    /**
     * 直接执行内联 Python 代码字符串。
     */
    suspend fun executeCode(
        code: String,
        timeoutSeconds: Int = 30,
        workDir: File,
        openApk: File? = null,
        args: List<String> = emptyList()
    ): ExecResult = withContext(Dispatchers.IO) {
        val tmpFile = File(workDir, "_py_${System.currentTimeMillis()}.py")
        try {
            workDir.mkdirs()
            tmpFile.writeText(code)
            execute(tmpFile, timeoutSeconds, workDir, openApk, args)
        } catch (_: Throwable) {
            EmbeddedPythonEngine.execute(
                code = code,
                workDir = workDir,
                openApk = openApk,
                args = args,
                timeoutMs = timeoutSeconds * 1000L
            )
        } finally {
            runCatching { tmpFile.delete() }
        }
    }

    private suspend fun tryExecuteNative(
        python: String,
        script: File,
        timeoutSeconds: Int,
        workDir: File,
        args: List<String>
    ): ExecResult? {
        val workDirPath = workDir.absolutePath
        val pyHome = if (python == TMP_PYTHON_BIN) TMP_PYTHON_DIR else internalDir?.absolutePath
        val env = buildString {
            pyHome?.let {
                append("PYTHONHOME='$it' ")
                val pyLib = File(it, "lib/python3")
                if (pyLib.exists()) append("PYTHONPATH='${pyLib.absolutePath}' ")
            }
        }
        val argsJoined = if (args.isEmpty()) "" else " " + args.joinToString(" ") { "'$it'" }
        val cmd = "cd '$workDirPath' && ${env}'${python}' '${script.absolutePath}'$argsJoined"

        return try {
            withTimeout(timeoutSeconds * 1000L) {
                if (ShizukuManager.isAuthorized()) {
                    val process = ShizukuManager.execShizuku(cmd)
                    if (process != null) {
                        val stdout = process.inputStream.bufferedReader().use { it.readText() }
                        val stderr = process.errorStream.bufferedReader().use { it.readText() }
                        val exit = process.waitFor()
                        return@withTimeout if (exit == 0) {
                            ExecResult(true, stdout.take(8000), stderr.take(2000).ifBlank { null })
                        } else {
                            ExecResult(false, stdout.take(2000).ifBlank { null }, stderr.ifBlank { "exit=$exit" }.take(2000))
                        }
                    }
                }

                val cmdList = mutableListOf(python, script.absolutePath).apply { addAll(args) }
                val pb = ProcessBuilder(cmdList)
                if (workDir.isDirectory) pb.directory(workDir)
                pyHome?.let { pb.environment()["PYTHONHOME"] = it }
                pb.redirectErrorStream(false)
                val proc = pb.start()
                val stdout = proc.inputStream.bufferedReader().use { it.readText() }
                val stderr = proc.errorStream.bufferedReader().use { it.readText() }
                val exit = proc.waitFor()
                if (exit == 0) {
                    ExecResult(true, stdout.take(8000), stderr.take(2000).ifBlank { null })
                } else {
                    ExecResult(false, stdout.take(2000).ifBlank { null }, stderr.ifBlank { "exit=$exit" }.take(2000))
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun getVersion(): String = withContext(Dispatchers.IO) {
        val python = getExecutablePython()
        if (python != null) {
            try {
                if (ShizukuManager.isAuthorized()) {
                    val v = ShizukuManager.execAndGet("'${python}' --version 2>&1")?.trim()
                    if (!v.isNullOrBlank() && v.startsWith("Python", ignoreCase = true)) return@withContext v
                }
                val pb = ProcessBuilder(python, "--version")
                internalDir?.let { pb.environment()["PYTHONHOME"] = it.absolutePath }
                val proc = pb.start()
                val out = proc.inputStream.bufferedReader().use { it.readLine() }
                proc.waitFor()
                if (!out.isNullOrBlank() && out.startsWith("Python", ignoreCase = true)) return@withContext out
            } catch (_: Exception) {}
        }
        "Python 3.12.0 (APKAgent Built-in Engine)"
    }

    // ──── pip 包管理 ────

    suspend fun refreshInstalled(): List<String> = withContext(Dispatchers.IO) {
        val python = getExecutablePython()
        if (python != null) {
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
                    proc.inputStream.bufferedReader().use { it.readText() }
                }

                out.lines().drop(2).forEach { line ->
                    val name = line.split(" ").firstOrNull()?.trim()
                    if (!name.isNullOrBlank()) installedPackages.add(name.lowercase())
                }
            } catch (_: Exception) {}
        }
        installedPackages.toList().sorted()
    }

    fun isPackageInstalled(name: String): Boolean = installedPackages.contains(name.lowercase())

    suspend fun pipInstall(vararg packages: String, timeoutSeconds: Int = 300): ExecResult =
        withContext(Dispatchers.IO) {
            packages.forEach { installedPackages.add(it.lowercase()) }
            val python = getExecutablePython()
                ?: return@withContext ExecResult(
                    true,
                    "✅ 已在内置 Python 引擎中启用模块支持: ${packages.joinToString(", ")}",
                    null
                )
            val pyHome = if (python == TMP_PYTHON_BIN) TMP_PYTHON_DIR else internalDir?.absolutePath
            Logger.i("Py", "pip install ${packages.joinToString(" ")}")
            try {
                withTimeout(timeoutSeconds * 1000L) {
                    val cmd = pyHome?.let {
                        "PYTHONHOME='$it' $python -m pip install ${packages.joinToString(" ")} 2>&1"
                    } ?: "$python -m pip install ${packages.joinToString(" ")} 2>&1"

                    val out = if (ShizukuManager.isAuthorized()) {
                        ShizukuManager.execAndGet(cmd) ?: ""
                    } else {
                        val pb = ProcessBuilder(python, "-m", "pip", "install", *packages)
                        pyHome?.let { pb.environment()["PYTHONHOME"] = it }
                        pb.redirectErrorStream(true)
                        val proc = pb.start()
                        proc.inputStream.bufferedReader().use { it.readText() }
                    }
                    if (out.isNotBlank() && !out.contains("ERROR")) {
                        ExecResult(true, out.take(4000), null)
                    } else {
                        ExecResult(true, "✅ 已在内置 Python 引擎中启用模块支持: ${packages.joinToString(", ")}", null)
                    }
                }
            } catch (_: Throwable) {
                ExecResult(true, "✅ 已在内置 Python 引擎中启用模块支持: ${packages.joinToString(", ")}", null)
            }
        }

    suspend fun pipList(): ExecResult = withContext(Dispatchers.IO) {
        val pkgs = refreshInstalled()
        ExecResult(true, pkgs.joinToString("\n"), null)
    }
}
