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

    // python-build-standalone 静态编译包（GitHub releases）
    // 选择最小的 embeddable 版本
    private const val PYTHON_URL = "https://github.com/astral-sh/python-build-standalone/releases/download/20241002/cpython-3.12.7+20241002-aarch64-unknown-linux-gnu-install_only_stripped.tar.gz"
    private const val PYTHON_URL_X86 = "https://github.com/astral-sh/python-build-standalone/releases/download/20241002/cpython-3.12.7+20241002-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz"

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
            val url = if (arch == "aarch64") PYTHON_URL else PYTHON_URL_X86
            Logger.i("Installer", "架构: $arch, URL: $url")

            // 2. 下载
            onProgress(InstallProgress("download", 0.1f, "下载 Python 3.12..."))
            val cacheFile = File(context.cacheDir, "python.tar.gz")

            if (!cacheFile.exists() || cacheFile.length() < 1_000_000) {
                downloadFile(url, cacheFile) { downloaded, total ->
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
            // 使用 unofficial-builds 的 static binary
            val version = "v20.11.1"
            val url = if (arch == "aarch64") {
                "https://unofficial-builds.nodejs.org/download/release/$version/node-$version-linux-arm64.tar.gz"
            } else {
                "https://unofficial-builds.nodejs.org/download/release/$version/node-$version-linux-x64.tar.gz"
            }

            onProgress(InstallProgress("download", 0.1f, "下载 Node.js $version..."))
            val cacheFile = File(context.cacheDir, "node.tar.gz")

            if (!cacheFile.exists() || cacheFile.length() < 1_000_000) {
                downloadFile(url, cacheFile) { downloaded, total ->
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
        // 使用系统 tar 命令（Android 自带）
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
