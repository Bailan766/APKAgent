package com.apkagent.installer

import android.content.Context
import com.apkagent.apktools.PythonRunner
import com.apkagent.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 内部环境安装器
 * 直接下载 Python/Node.js 静态编译包到 app 内部目录
 */
object InternalInstaller {

    // python-build-standalone — 国内镜像 + GitHub 备选
    private const val PYTHON_URL_AARCH64 = "https://npmmirror.com/mirrors/python-build-standalone/20250311/cpython-3.12.9+202****0311-aarch64-unknown-linux-gnu-install_only_stripped.tar.gz"
    private const val PYTHON_URL_X86_64 = "https://npmmirror.com/mirrors/python-build-standalone/20250311/cpython-3.12.9+202****0311-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz"
    private val PYTHON_FALLBACK_AARCH64 = listOf(
        PYTHON_URL_AARCH64,
        "https://github.com/astral-sh/python-build-standalone/releases/download/20250311/cpython-3.12.9+202****0311-aarch64-unknown-linux-gnu-install_only_stripped.tar.gz",
        "https://ghp.ci/https://github.com/astral-sh/python-build-standalone/releases/download/20250311/cpython-3.12.9+202****0311-aarch64-unknown-linux-gnu-install_only_stripped.tar.gz",
    )
    private val PYTHON_FALLBACK_X86_64 = listOf(
        PYTHON_URL_X86_64,
        "https://github.com/astral-sh/python-build-standalone/releases/download/20250311/cpython-3.12.9+202****0311-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz",
        "https://ghp.ci/https://github.com/astral-sh/python-build-standalone/releases/download/20250311/cpython-3.12.9+202****0311-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz",
    )

    // Node.js — 国内镜像 + 官方备选
    private const val NODE_URL_AARCH64 = "https://npmmirror.com/mirrors/node/v20.18.1/node-v20.18.1-linux-arm64.tar.gz"
    private const val NODE_URL_X86_64 = "https://npmmirror.com/mirrors/node/v20.18.1/node-v20.18.1-linux-x64.tar.gz"
    private val NODE_FALLBACK_AARCH64 = listOf(
        NODE_URL_AARCH64,
        "https://nodejs.org/dist/v20.18.1/node-v20.18.1-linux-arm64.tar.gz",
    )
    private val NODE_FALLBACK_X86_64 = listOf(
        NODE_URL_X86_64,
        "https://nodejs.org/dist/v20.18.1/node-v20.18.1-linux-x64.tar.gz",
    )

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
            onProgress(InstallProgress("done", 1.0f, "Python 已安装"))
            PythonRunner.pythonPath = pythonBin.absolutePath
            return@withContext true
        }

        try {
            // 1. 检测架构
            onProgress(InstallProgress("arch", 0.05f, "检测设备架构..."))
            val arch = getArch()
            val urls = if (arch == "aarch64") PYTHON_FALLBACK_AARCH64 else PYTHON_FALLBACK_X86_64
            Logger.i("Installer", "架构: $arch")

            // 2. 下载
            onProgress(InstallProgress("download", 0.1f, "下载 Python 3.12..."))
            val cacheFile = File(context.cacheDir, "python.tar.gz")

            if (!cacheFile.exists() || cacheFile.length() < 1_000_000) {
                downloadWithFallback(urls, cacheFile) { downloaded, total ->
                    val pct = if (total > 0) downloaded.toFloat() / total else 0f
                    onProgress(InstallProgress("download", 0.1f + pct * 0.5f,
                        "下载中... ${downloaded / 1024 / 1024}MB"))
                }
            }

            // 3. 解压
            onProgress(InstallProgress("extract", 0.65f, "解压中..."))
            targetDir.mkdirs()
            extractTarGz(cacheFile, targetDir)

            // 4. 设置权限
            onProgress(InstallProgress("chmod", 0.85f, "设置执行权限..."))
            setExecutable(binDir)

            // 5. 验证
            onProgress(InstallProgress("verify", 0.9f, "验证安装..."))
            if (pythonBin.exists()) {
                pythonBin.setExecutable(true)
                PythonRunner.pythonPath = pythonBin.absolutePath
                Logger.i("Installer", "Python 安装成功: ${pythonBin.absolutePath}")

                // 清理缓存
                cacheFile.delete()

                onProgress(InstallProgress("done", 1.0f, "✅ Python 安装成功"))
                true
            } else {
                onProgress(InstallProgress("error", 0f, "❌ 安装失败：文件不存在"))
                false
            }
        } catch (e: Exception) {
            Logger.e("Installer", "Python 安装失败", e)
            onProgress(InstallProgress("error", 0f, "❌ 安装失败: ${e.message}"))
            false
        }
    }

    /**
     * 安装 Node.js（使用预编译的 static binary）
     */
    suspend fun installNode(
        context: Context,
        onProgress: (InstallProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val targetDir = File(context.filesDir, "nodejs")
        val nodeBin = File(targetDir, "bin/node")

        if (nodeBin.exists() && nodeBin.canExecute()) {
            onProgress(InstallProgress("done", 1.0f, "Node.js 已安装"))
            return@withContext true
        }

        try {
            val arch = getArch()
            val urls = if (arch == "aarch64") NODE_FALLBACK_AARCH64 else NODE_FALLBACK_X86_64

            onProgress(InstallProgress("download", 0.1f, "下载 Node.js..."))
            val cacheFile = File(context.cacheDir, "node.tar.gz")

            if (!cacheFile.exists() || cacheFile.length() < 1_000_000) {
                downloadWithFallback(urls, cacheFile) { downloaded, total ->
                    val pct = if (total > 0) downloaded.toFloat() / total else 0f
                    onProgress(InstallProgress("download", 0.1f + pct * 0.5f,
                        "下载中... ${downloaded / 1024 / 1024}MB"))
                }
            }

            onProgress(InstallProgress("extract", 0.65f, "解压中..."))
            targetDir.mkdirs()
            extractTarGz(cacheFile, targetDir)

            onProgress(InstallProgress("chmod", 0.85f, "设置权限..."))
            setExecutable(File(targetDir, "bin"))

            if (nodeBin.exists()) {
                nodeBin.setExecutable(true)
                cacheFile.delete()
                onProgress(InstallProgress("done", 1.0f, "✅ Node.js 安装成功"))
                true
            } else {
                onProgress(InstallProgress("error", 0f, "❌ 安装失败"))
                false
            }
        } catch (e: Exception) {
            Logger.e("Installer", "Node.js 安装失败", e)
            onProgress(InstallProgress("error", 0f, "❌ 安装失败: ${e.message}"))
            false
        }
    }

    /**
     * 通过内置终端安装 Python（使用 Termux 的 pkg）
     * 如果 Termux 已安装且有 python，直接链接
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

    private fun getArch(): String {
        return try {
            val proc = ProcessBuilder("uname", "-m").start()
            val arch = proc.inputStream.bufferedReader().readLine()?.trim() ?: "aarch64"
            proc.waitFor()
            arch
        } catch (_: Exception) {
            System.getProperty("os.arch") ?: "aarch64"
        }
    }

    /**
     * 带 fallback 的下载 — 尝试多个源
     */
    private fun downloadWithFallback(urls: List<String>, dest: File, onProgress: (Long, Long) -> Unit) {
        var lastError: Exception? = null
        for (url in urls) {
            try {
                Logger.i("Installer", "尝试下载: $url")
                downloadFile(url, dest, onProgress)
                if (dest.exists() && dest.length() > 10_000) {
                    Logger.i("Installer", "下载成功: $url (${dest.length() / 1024}KB)")
                    return
                }
            } catch (e: Exception) {
                Logger.w("Installer", "下载失败: $url → ${e.message}")
                lastError = e
                dest.delete()
            }
        }
        throw lastError ?: IOException("所有下载源均失败")
    }

    private fun downloadFile(urlStr: String, dest: File, onProgress: (Long, Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("User-Agent", "APKAgent/3.5")

        // Follow redirects
        var currentConn = conn
        for (i in 0..5) {
            val code = currentConn.responseCode
            if (code in 301..308) {
                val location = currentConn.getHeaderField("Location") ?: break
                currentConn.disconnect()
                currentConn = URL(location).openConnection() as HttpURLConnection
                currentConn.connectTimeout = 30_000
                currentConn.readTimeout = 120_000
            } else break
        }

        val totalSize = currentConn.contentLength.toLong()
        var downloaded = 0L

        currentConn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(8192)
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    output.write(buffer, 0, len)
                    downloaded += len
                    onProgress(downloaded, totalSize)
                }
            }
        }
    }

    private fun extractTarGz(archive: File, targetDir: File) {
        Logger.i("Installer", "解压 ${archive.name} → ${targetDir.absolutePath}")
        val proc = ProcessBuilder(
            "sh", "-c",
            "tar xzf '${archive.absolutePath}' -C '${targetDir.absolutePath}' --strip-components=1 2>&1"
        )
        proc.redirectErrorStream(true)
        val p = proc.start()
        val out = p.inputStream.bufferedReader().readText()
        val exit = p.waitFor()
        if (exit != 0) {
            Logger.w("Installer", "tar exit=$exit: $out")
            throw IOException("解压失败: $out")
        }
    }

    /**
     * 解压 .deb 包（ar 归档 → data.tar.xz → 解压）
     * deb 结构: debian-binary + control.tar.gz + data.tar.xz
     */
    private fun extractDeb(debFile: File, targetDir: File) {
        Logger.i("Installer", "解压 deb: ${debFile.name}")
        val tmpDir = File(targetDir.parentFile, "deb_tmp")
        tmpDir.mkdirs()

        try {
            // 1. 用 ar 解包 deb
            var proc = ProcessBuilder("sh", "-c", "cd '${tmpDir.absolutePath}' && ar x '${debFile.absolutePath}' 2>&1")
            proc.redirectErrorStream(true)
            var p = proc.start()
            var out = p.inputStream.bufferedReader().readText()
            var exit = p.waitFor()

            if (exit != 0) {
                // ar 不可用，尝试用 tar 直接解（某些 Android 支持）
                Logger.w("Installer", "ar 不可用，尝试 tar: $out")
                proc = ProcessBuilder("sh", "-c", "tar xf '${debFile.absolutePath}' -C '${tmpDir.absolutePath}' 2>&1")
                proc.redirectErrorStream(true)
                p = proc.start()
                out = p.inputStream.bufferedReader().readText()
                exit = p.waitFor()
            }

            // 2. 找到 data.tar.* 并解压
            val dataTar = tmpDir.listFiles()?.find { it.name.startsWith("data.tar") }
            if (dataTar != null) {
                proc = ProcessBuilder(
                    "sh", "-c",
                    "tar xf '${dataTar.absolutePath}' -C '${targetDir.absolutePath}' 2>&1"
                )
                proc.redirectErrorStream(true)
                p = proc.start()
                out = p.inputStream.bufferedReader().readText()
                exit = p.waitFor()

                if (exit != 0) throw IOException("data.tar 解压失败: $out")

                // Node.js deb 的结构是 data.tar.xz/usr/bin/node...
                // 移动到 targetDir 根目录
                val usrDir = File(targetDir, "usr")
                if (usrDir.exists()) {
                    usrDir.copyRecursively(targetDir, overwrite = true)
                    usrDir.deleteRecursively()
                }
            } else {
                throw IOException("未找到 data.tar 文件")
            }
        } finally {
            tmpDir.deleteRecursively()
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
