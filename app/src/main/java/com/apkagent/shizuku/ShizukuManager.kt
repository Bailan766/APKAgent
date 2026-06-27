package com.apkagent.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Shizuku 管理器：通过 ADB shell 权限执行系统级操作。
 *
 * 核心能力：
 * - 以 shell (uid 2000) 身份执行任意命令
 * - 读写 /sdcard/ /data/data/ /data/app/ 等任意路径
 * - 绕过 ScopedStorage、SELinux 常规限制
 * - 安装/卸载 APK、修改文件权限
 */
object ShizukuManager {

    private const val TAG = "Shizuku"

    sealed class Status {
        data object Unavailable : Status()
        data object Installed : Status()
        data object Running : Status()
        data class Authorized(val uid: Int) : Status()
        data class Error(val message: String) : Status()
    }

    @Volatile
    var status: Status = Status.Unavailable
        private set

    private var permissionDeferred: CompletableDeferred<Boolean>? = null
    private val listenerAttach = Shizuku.OnBinderReceivedListener { onBinderReceived() }
    private val listenerDead = Shizuku.OnBinderDeadListener { onBinderDead() }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        onPermissionResult(grantResult)
    }

    fun init() {
        try {
            Shizuku.addBinderReceivedListenerSticky(listenerAttach)
            Shizuku.addBinderDeadListener(listenerDead)
            Shizuku.addRequestPermissionResultListener(permissionListener)
            if (Shizuku.pingBinder()) checkStatus() else status = Status.Installed
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku 初始化失败", e)
            status = Status.Unavailable
        }
    }

    fun destroy() {
        try {
            Shizuku.removeBinderReceivedListener(listenerAttach)
            Shizuku.removeBinderDeadListener(listenerDead)
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (_: Throwable) {}
    }

    fun isAvailable(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }
    }

    fun isRunning(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    fun isAuthorized(): Boolean = status is Status.Authorized

    suspend fun requestPermission(timeoutMs: Long = 30_000L): Boolean {
        if (!isRunning()) return false
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            checkStatus(); return true
        }
        val deferred = CompletableDeferred<Boolean>()
        permissionDeferred = deferred
        return try {
            withTimeout(timeoutMs) {
                Shizuku.requestPermission(0)
                deferred.await()
            }
        } catch (_: Throwable) { false } finally { permissionDeferred = null }
    }

    // ══════════════════════════════════════════
    //  核心：通过 Shizuku 以 shell 权限执行命令
    // ══════════════════════════════════════════

    /**
     * 通过 Shizuku 以 shell (uid 2000) 权限执行命令。
     * 这是真正提权的执行方式，不是普通 ProcessBuilder。
     */
    fun execShizuku(command: String): Process? {
        if (!isAuthorized()) {
            Log.w(TAG, "Shizuku 未授权，无法执行: $command")
            return null
        }
        return try {
            // Shizuku.newProcess 在 Shizuku 服务端 fork 进程
            // 以 shell 用户 (uid 2000) 身份运行，拥有 ADB 全部权限
            Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        } catch (e: Throwable) {
            Log.e(TAG, "Shizuku exec 失败: $command", e)
            null
        }
    }

    /**
     * 执行命令并获取完整输出
     */
    fun execAndGet(command: String): String? {
        val process = execShizuku(command) ?: return null
        return try {
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            output.trim()
        } catch (e: Throwable) {
            Log.e(TAG, "execAndGet 失败", e)
            null
        }
    }

    /**
     * 执行命令并获取退出码
     */
    fun execAndGetCode(command: String): Pair<String?, Int> {
        val process = execShizuku(command) ?: return null to -1
        return try {
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            val code = process.waitFor()
            output.trim() to code
        } catch (e: Throwable) {
            Log.e(TAG, "execAndGetCode 失败", e)
            null to -1
        }
    }

    // ══════════════════════════════════════════
    //  文件操作（全部通过 Shizuku shell 权限）
    // ══════════════════════════════════════════

    /** 读取任意文件（shell 权限） */
    fun readFile(path: String): String? {
        return execAndGet("cat '${path}'")
    }

    /** 读取任意文件的字节（base64 编码） */
    fun readFileBytes(path: String): ByteArray? {
        val b64 = execAndGet("base64 '${path}'") ?: return null
        return try { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) } catch (_: Throwable) { null }
    }

    /** 写入任意文件（shell 权限） */
    fun writeFile(path: String, content: String): Boolean {
        // 用 base64 避免引号/特殊字符问题
        val b64 = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
        val result = execAndGet("echo '$b64' | base64 -d > '${path}' 2>&1")
        return result != null && !result.contains("denied") && !result.contains("No such file")
    }

    /** 写入字节到文件 */
    fun writeFileBytes(path: String, bytes: ByteArray): Boolean {
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val result = execAndGet("echo '$b64' | base64 -d > '${path}' 2>&1")
        return result != null
    }

    /** 复制文件 */
    fun copyFile(src: String, dst: String): Boolean {
        val result = execAndGet("cp -f '${src}' '${dst}' 2>&1")
        return result != null && !result.contains("denied") && !result.contains("No such file")
    }

    /** 移动/重命名文件 */
    fun moveFile(src: String, dst: String): Boolean {
        val result = execAndGet("mv -f '${src}' '${dst}' 2>&1")
        return result != null && !result.contains("denied")
    }

    /** 删除文件 */
    fun deleteFile(path: String): Boolean {
        val result = execAndGet("rm -rf '${path}' 2>&1")
        return result != null && !result.contains("denied")
    }

    /** 创建目录 */
    fun mkdirs(path: String): Boolean {
        execAndGet("mkdir -p '${path}'")
        return true
    }

    /** 列出目录内容 */
    fun listDir(path: String): String? {
        return execAndGet("ls -la '${path}' 2>&1")
    }

    /** 检查文件是否存在 */
    fun fileExists(path: String): Boolean {
        val result = execAndGet("[ -e '${path}' ] && echo yes || echo no")
        return result?.contains("yes") == true
    }

    /** 获取文件大小 */
    fun fileSize(path: String): Long {
        val result = execAndGet("stat -c %s '${path}' 2>/dev/null || wc -c < '${path}'") ?: return -1
        return result.trim().toLongOrNull() ?: -1
    }

    /** 修改文件权限 */
    fun chmod(path: String, mode: String): Boolean {
        val result = execAndGet("chmod $mode '${path}' 2>&1")
        return result != null && !result.contains("denied")
    }

    /** 递归修改目录权限 */
    fun chmodR(path: String, mode: String): Boolean {
        val result = execAndGet("chmod -R $mode '${path}' 2>&1")
        return result != null && !result.contains("denied")
    }

    /** 修改文件所有者 */
    fun chown(path: String, owner: String): Boolean {
        val result = execAndGet("chown $owner '${path}' 2>&1")
        return result != null && !result.contains("denied")
    }

    // ══════════════════════════════════════════
    //  高级操作
    // ══════════════════════════════════════════

    /** 解压 zip 文件 */
    fun unzip(zipPath: String, destDir: String): Boolean {
        mkdirs(destDir)
        val result = execAndGet("unzip -o '${zipPath}' -d '${destDir}' 2>&1")
        return result != null && !result.contains("error") && !result.contains("cannot")
    }

    /** 解压 tar.gz */
    fun tarXzf(tarPath: String, destDir: String): Boolean {
        mkdirs(destDir)
        val result = execAndGet("tar xzf '${tarPath}' -C '${destDir}' 2>&1")
        return result != null && !result.contains("error")
    }

    /** 安装 APK */
    fun installApk(apkPath: String): String? {
        return execAndGet("pm install -r -d '${apkPath}' 2>&1")
    }

    /** 获取已安装包信息 */
    fun getPackageInfo(packageName: String): String? {
        return execAndGet("dumpsys package '$packageName' 2>&1 | head -50")
    }

    /** 结束进程 */
    fun killProcess(processName: String): Boolean {
        val result = execAndGet("am force-stop '$processName' 2>&1")
        return true
    }

    /** 清除应用数据 */
    fun clearAppData(packageName: String): Boolean {
        val result = execAndGet("pm clear '$packageName' 2>&1")
        return result?.contains("Success") == true
    }

    /** 获取系统属性 */
    fun getProp(key: String): String? {
        return execAndGet("getprop '$key'")
    }

    // ══════════════════════════════════════════
    //  兼容旧接口
    // ══════════════════════════════════════════

    /** @deprecated 用 execShizuku 代替 */
    fun exec(vararg cmd: String): Process? {
        val command = cmd.joinToString(" ")
        return execShizuku(command)
    }

    /** @deprecated 用 readFile 代替 */
    fun readFileShizuku(path: String): String? = readFile(path)

    /** @deprecated 用 copyFile 代替 */
    fun copyFileShizuku(src: File, dst: File): Boolean = copyFile(src.absolutePath, dst.absolutePath)

    /** @deprecated 用 execShizuku 代替 */
    fun openInputStreamShizuku(path: String): java.io.InputStream? {
        return try {
            val process = execShizuku("cat '$path'") ?: return null
            process.inputStream
        } catch (_: Throwable) { null }
    }

    fun isPrivileged(): Boolean = isAuthorized()

    // -- private --

    private fun onBinderReceived() = checkStatus()

    private fun onBinderDead() {
        status = Status.Installed
        permissionDeferred?.complete(false)
        permissionDeferred = null
    }

    private fun onPermissionResult(grantResult: Int) {
        val allowed = grantResult == PackageManager.PERMISSION_GRANTED
        if (allowed) checkStatus()
        permissionDeferred?.complete(allowed)
        permissionDeferred = null
    }

    private fun checkStatus() {
        if (!isRunning()) { status = Status.Installed; return }
        val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        status = if (granted) {
            Status.Authorized(try { Shizuku.getUid() } catch (_: Throwable) { -1 })
        } else Status.Running
    }
}
