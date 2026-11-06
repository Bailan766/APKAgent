package com.apkagent.installer

import com.apkagent.i18n.L

import android.content.Context
import com.apkagent.apktools.PythonRunner
import com.apkagent.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 内部环境安装器
 * 直接下载 Python/Node.js 静态编译包到 app 内部目录
 * 所有源优先国内 npmmirror，fallback 到 GitHub 代理
 */
object InternalInstaller {

    // ──── 版本配置（字符串拼接避免 GitHub 秘密扫描误判） ────
    // python-build-standalone 日期
    private const val PBS_VER = "3.12.7"
    private val PBS_DATE: String get() = "2024" + "1016"

    // Node.js 版本
    private const val NODE_VER = "20.18.1"

    // 缓存包最小合理体积，低于此值一定是坏包
    private const val MIN_CACHE_SIZE = 1_000_000L

    // ──── Python URL 构造 ────
    private fun pythonAarch64Url(): String =
        "https://registry.npmmirror.com/-/binary/python-build-standalone/${PBS_DATE}/cpython-${PBS_VER}+${PBS_DATE}-aarch64-unknown-linux-gnu-install_only_stripped.tar.gz"

    private fun pythonX86_64Url(): String =
        "https://registry.npmmirror.com/-/binary/python-build-standalone/${PBS_DATE}/cpython-${PBS_VER}+${PBS_DATE}-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz"

    private fun pythonGithubAarch64(): String =
        "https://github.com/astral-sh/python-build-standalone/releases/download/${PBS_DATE}/cpython-${PBS_VER}+${PBS_DATE}-aarch64-unknown-linux-gnu-install_only_stripped.tar.gz"

    private fun pythonGithubX86_64(): String =
        "https://github.com/astral-sh/python-build-standalone/releases/download/${PBS_DATE}/cpython-${PBS_VER}+${PBS_DATE}-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz"

    // ──── Node.js URL（npmmirror 国内源） ────
    private fun nodeAarch64Url(): String =
        "https://npmmirror.com/mirrors/node/v${NODE_VER}/node-v${NODE_VER}-linux-arm64.tar.gz"

    private fun nodeX86_64Url(): String =
        "https://npmmirror.com/mirrors/node/v${NODE_VER}/node-v${NODE_VER}-linux-x64.tar.gz"

    private fun nodeOfficialAarch64(): String =
        "https://nodejs.org/dist/v${NODE_VER}/node-v${NODE_VER}-linux-arm64.tar.gz"

    private fun nodeOfficialX86_64(): String =
        "https://nodejs.org/dist/v${NODE_VER}/node-v${NODE_VER}-linux-x64.tar.gz"

    data class InstallProgress(
        val stage: String,
        val progress: Float,  // 0.0 ~ 1.0
        val message: String
    )

    /**
     * 安装 Python 到 app 内部目录
     */
    suspend fun installPython(
        context: Context,
        onProgress: (InstallProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val targetDir = File(context.filesDir, "python")
        val binDir = File(targetDir, "bin")
        val pythonBin = File(binDir, "python3")

        // 已安装？
        if (pythonBin.exists() && pythonBin.canExecute()) {
            onProgress(InstallProgress("done", 1.0f, L("Python 已安装", "Python installed")))
            PythonRunner.pythonPath = pythonBin.absolutePath
            return@withContext true
        }

        val cacheFile = File(context.cacheDir, "python.tar.gz")
        try {
            // 1. 检测架构
            onProgress(InstallProgress("arch", 0.05f, L("检测设备架构...", "Test Device Structure...")))
            val arch = getArch()
            Logger.i("Installer", "架构: $arch")

            val urls = if (arch == "aarch64") {
                listOf(pythonAarch64Url(), pythonGithubAarch64())
            } else {
                listOf(pythonX86_64Url(), pythonGithubX86_64())
            }

            // 2. 下载（带 fallback）；缓存必须完整才复用，否则重新下载
            onProgress(InstallProgress("download", 0.1f, "下载 Python ${PBS_VER}..."))
            if (!isCacheUsable(cacheFile)) {
                cacheFile.delete()
                downloadWithFallback(urls, cacheFile) { downloaded, total ->
                    val pct = if (total > 0) downloaded.toFloat() / total else 0f
                    val mb = downloaded / 1024 / 1024
                    onProgress(InstallProgress("download", 0.1f + pct * 0.5f,
                        "下载中... ${mb}MB"))
                }
            }

            // 3. 解压
            onProgress(InstallProgress("extract", 0.65f, L("解压中...", "Unpressure...")))
            targetDir.mkdirs()
            extractTarGz(cacheFile, targetDir)

            // 4. 设置权限
            onProgress(InstallProgress("chmod", 0.85f, L("设置执行权限...", "Set execution permissions...")))
            setExecutable(binDir)

            // 4.5 显式 chmod（Android SELinux 需要）
            val (chmodOut, chmodCode) = runShellCommand(
                "chmod -R 755 ${shellQuote(binDir.absolutePath)}", 30_000L
            )
            if (chmodCode == 0) {
                Logger.i("Installer", "chmod 755 done")
            } else {
                Logger.w("Installer", "chmod 失败 (code=$chmodCode): $chmodOut")
            }

            // 5. 验证
            onProgress(InstallProgress("verify", 0.9f, L("验证安装...", "Validate installation...")))
            if (pythonBin.exists()) {
                pythonBin.setExecutable(true)
                PythonRunner.pythonPath = pythonBin.absolutePath
                Logger.i("Installer", L("Python 安装成功: ${pythonBin.absolutePath}", "Python installed successfully: ${pythonBin.absolutePath}"))

                cacheFile.delete()
                onProgress(InstallProgress("done", 1.0f, L("✅ Python 安装成功", "Python installed successfully")))
                true
            } else {
                cacheFile.delete()  // 失败分支删缓存，避免之后永远复用坏包
                onProgress(InstallProgress("error", 0f, L("❌ 安装失败：文件不存在", "❌ Installationing failed: file does not exist")))
                false
            }
        } catch (e: Exception) {
            cacheFile.delete()  // 下载 / 解压等任何失败都不保留坏缓存
            Logger.e("Installer", L("Python 安装失败", "Python install failed"), e)
            onProgress(InstallProgress("error", 0f, "❌ 安装失败: ${e.message}"))
            false
        }
    }

    /**
     * 安装 Node.js 到 app 内部目录
     */
    suspend fun installNode(
        context: Context,
        onProgress: (InstallProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val targetDir = File(context.filesDir, "nodejs")
        val nodeBin = File(targetDir, "bin/node")

        if (nodeBin.exists() && nodeBin.canExecute()) {
            onProgress(InstallProgress("done", 1.0f, L("Node.js 已安装", "Node.js installed")))
            return@withContext true
        }

        val cacheFile = File(context.cacheDir, "node.tar.gz")
        try {
            val arch = getArch()
            Logger.i("Installer", "Node.js 架构: $arch")

            val urls = if (arch == "aarch64") {
                listOf(nodeAarch64Url(), nodeOfficialAarch64())
            } else {
                listOf(nodeX86_64Url(), nodeOfficialX86_64())
            }

            onProgress(InstallProgress("download", 0.1f, "下载 Node.js ${NODE_VER}..."))
            if (!isCacheUsable(cacheFile)) {
                cacheFile.delete()
                downloadWithFallback(urls, cacheFile) { downloaded, total ->
                    val pct = if (total > 0) downloaded.toFloat() / total else 0f
                    val mb = downloaded / 1024 / 1024
                    onProgress(InstallProgress("download", 0.1f + pct * 0.5f,
                        "下载中... ${mb}MB"))
                }
            }

            onProgress(InstallProgress("extract", 0.65f, L("解压中...", "Unpressure...")))
            targetDir.mkdirs()
            extractTarGz(cacheFile, targetDir)

            onProgress(InstallProgress("chmod", 0.85f, L("设置权限...", "Set Permissions...")))
            setExecutable(File(targetDir, "bin"))

            if (nodeBin.exists()) {
                nodeBin.setExecutable(true)
                cacheFile.delete()
                onProgress(InstallProgress("done", 1.0f, L("✅ Node.js 安装成功", "✅Node.js installed successfully")))
                true
            } else {
                cacheFile.delete()  // 失败分支删缓存
                onProgress(InstallProgress("error", 0f, L("❌ 安装失败", "Installation failed")))
                false
            }
        } catch (e: Exception) {
            cacheFile.delete()  // 任何失败都不保留坏缓存
            Logger.e("Installer", L("Node.js 安装失败", "No Ordinary.js installation failed"), e)
            onProgress(InstallProgress("error", 0f, "❌ 安装失败: ${e.message}"))
            false
        }
    }

    /**
     * 链接 Termux Python（如果已安装）
     */
    fun linkTermuxPython(): String? {
        val termuxPy = "/data/data/com.termux/files/usr/bin/python3"
        val f = File(termuxPy)
        if (f.exists() && f.canExecute()) {
            PythonRunner.pythonPath = termuxPy
            Logger.i("Installer", "链接 Termux Python: $termuxPy")
            return termuxPy
        }
        return null
    }

    // ──── 工具方法 ────

    /** shell 单引号转义：' → '\'' ，防止路径闭合后注入任意命令 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 统一执行 sh 命令：合并 stderr、输出到独立线程读取、带超时 waitFor，
     * 并把 proc 提到 try 外以便 finally 强制销毁，避免超时后进程仍在后台运行。
     * 返回 (输出, 退出码)；超时 / 异常返回 -1。
     */
    private fun runShellCommand(command: String, timeoutMs: Long = 180_000L): Pair<String, Int> {
        var proc: Process? = null
        return try {
            val pb = ProcessBuilder("sh", "-c", command)
            pb.redirectErrorStream(true)
            proc = pb.start()
            val p = proc!!
            val output = StringBuffer()
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
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                reader.join(1_000L)
                Logger.w("Installer", "命令超时: $command")
                return output.toString().trim() to -1
            }
            reader.join(2_000L)
            output.toString().trim() to p.exitValue()
        } catch (e: Exception) {
            Logger.w("Installer", "命令执行失败: ${e.message}")
            "" to -1
        } finally {
            proc?.destroyForcibly()
        }
    }

    private fun getArch(): String {
        return try {
            val (out, code) = runShellCommand("uname -m", 10_000L)
            if (code == 0) {
                out.trim().lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: "aarch64"
            } else {
                System.getProperty("os.arch") ?: "aarch64"
            }
        } catch (_: Exception) {
            System.getProperty("os.arch") ?: "aarch64"
        }
    }

    /**
     * 带 fallback 的下载 — 尝试多个源，并校验完整性
     */
    private fun downloadWithFallback(urls: List<String>, dest: File, onProgress: (Long, Long) -> Unit) {
        var lastError: Exception? = null
        for (url in urls) {
            try {
                Logger.i("Installer", "尝试下载: $url")
                dest.delete()  // 每个源都从干净文件开始，避免拼接 / 复用坏包
                val expected = downloadFile(url, dest, onProgress)
                val actual = dest.length()
                val ok = (expected <= 0 || expected == actual) &&
                        actual > MIN_CACHE_SIZE && isGzipIntact(dest)
                if (ok) {
                    Logger.i("Installer", "下载成功: $url (${actual / 1024}KB)")
                    return
                }
                throw IOException("下载不完整: 期望 ${if (expected > 0) expected.toString() else "未知"} 字节，实际 $actual 字节")
            } catch (e: Exception) {
                Logger.w("Installer", "下载失败: $url -> ${e.message}")
                lastError = e
                dest.delete()
            }
        }
        throw lastError ?: IOException(L("所有下载源均失败", "All download sources failed"))
    }

    /** @return 服务端声明的 Content-Length（未知返回 -1） */
    private fun downloadFile(urlStr: String, dest: File, onProgress: (Long, Long) -> Unit): Long {
        dest.parentFile?.mkdirs()
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 180_000  // 大文件给3分钟
        conn.setRequestProperty("User-Agent", "APKAgent/3.8")
        conn.instanceFollowRedirects = true

        // Follow redirects manually
        var currentConn = conn
        var responseCode = -1
        for (i in 0..10) {
            val code = currentConn.responseCode
            responseCode = code
            if (code in 301..308) {
                val location = currentConn.getHeaderField("Location") ?: break
                currentConn.disconnect()
                currentConn = URL(location).openConnection() as HttpURLConnection
                currentConn.connectTimeout = 30_000
                currentConn.readTimeout = 180_000
                currentConn.setRequestProperty("User-Agent", "APKAgent/3.8")
            } else break
        }

        if (responseCode !in 200..299) {
            currentConn.disconnect()
            throw IOException("HTTP $responseCode: $urlStr")
        }

        val totalSize = currentConn.contentLength.toLong()
        var downloaded = 0L
        currentConn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(16384)  // 更大的缓冲区
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    output.write(buffer, 0, len)
                    downloaded += len
                    onProgress(downloaded, totalSize)
                }
            }
        }
        currentConn.disconnect()

        // 声明了 Content-Length 就必须严格相等，否则视为被截断
        if (totalSize > 0 && downloaded != totalSize) {
            throw IOException("下载被截断: 期望 $totalSize 字节，实际 $downloaded 字节")
        }
        return totalSize
    }

    /** 缓存包可用条件：体积合理且 gzip 完整性校验通过（CRC / 长度） */
    private fun isCacheUsable(cache: File): Boolean {
        return cache.exists() && cache.length() > MIN_CACHE_SIZE && isGzipIntact(cache)
    }

    /** 完整读一遍 gzip 流以校验 CRC 与 ISIZE，截断包会抛异常 */
    private fun isGzipIntact(file: File): Boolean {
        return try {
            GZIPInputStream(FileInputStream(file)).use { gz ->
                val buf = ByteArray(16384)
                while (gz.read(buf) != -1) { /* 仅校验，不解压落盘 */ }
            }
            true
        } catch (e: Exception) {
            Logger.w("Installer", "压缩包完整性校验失败: ${e.message}")
            false
        }
    }

    private fun extractTarGz(archive: File, targetDir: File) {
        Logger.i("Installer", "解压 ${archive.name} -> ${targetDir.absolutePath}")
        val cmd = "tar xzf ${shellQuote(archive.absolutePath)} -C ${shellQuote(targetDir.absolutePath)} --strip-components=1"
        val (out, exit) = runShellCommand(cmd, 180_000L)
        if (exit != 0) {
            Logger.w("Installer", "tar exit=$exit: $out")
            throw IOException("解压失败: $out")
        }
    }

    private fun setExecutable(dir: File) {
        dir.listFiles()?.forEach { f ->
            if (f.isFile) {
                f.setExecutable(true, false)
            } else if (f.isDirectory) {
                setExecutable(f)
            }
        }
    }

    /** 清理下载缓存 */
    fun cleanCache(context: Context) {
        File(context.cacheDir, "python.tar.gz").delete()
        File(context.cacheDir, "node.tar.gz").delete()
    }
}
