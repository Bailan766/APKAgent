package com.apkagent.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.system.Os
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Shizuku 管理器：提供系统级 ADB 权限，用于绕过 SAF 限制直接读写文件。
 *
 * 使用 Shizuku 可以：
 * - 直接访问 /data/app/ /data/data/ 等受限目录
 * - 执行 shell 命令（等同于 adb shell）
 * - 绕过 ScopedStorage 限制读写任意文件
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"

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
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        onPermissionResult(requestCode, grantResult)
    }

    fun init() {
        try {
            Shizuku.addBinderReceivedListenerSticky(listenerAttach)
            Shizuku.addBinderDeadListener(listenerDead)
            Shizuku.addRequestPermissionResultListener(permissionListener)

            if (Shizuku.pingBinder()) {
                checkStatus()
            } else {
                status = Status.Installed
            }
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

    /** 检查 Shizuku 是否已安装 */
    fun isAvailable(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** 检查 Shizuku 是否运行中 */
    fun isRunning(): Boolean {
        return try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    }

    /** 检查是否已获得授权 */
    fun isAuthorized(): Boolean = status is Status.Authorized

    /** 请求 Shizuku 权限（suspend，默认超时 30 秒） */
    suspend fun requestPermission(timeoutMs: Long = 30_000L): Boolean {
        if (!isRunning()) return false
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            checkStatus()
            return true
        }

        val deferred = CompletableDeferred<Boolean>()
        permissionDeferred = deferred

        return try {
            withTimeout(timeoutMs) {
                Shizuku.requestPermission(0)
                deferred.await()
            }
        } catch (e: Throwable) {
            false
        } finally {
            permissionDeferred = null
        }
    }

    /** 执行 shell 命令（需要 Shizuku 授权） */
    fun exec(vararg cmd: String): Process? {
        if (!isRunning()) return null
        return try {
            val builder = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
            builder.start()
        } catch (e: Throwable) {
            Log.e(TAG, "exec 失败", e)
            null
        }
    }

    /** 执行 shell 命令并获取输出文本 */
    fun execAndGet(command: String): String? {
        val process = exec("sh", "-c", command) ?: return null
        return try {
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            output
        } catch (e: Throwable) {
            Log.e(TAG, "execAndGet 失败", e)
            null
        }
    }

    /** 通过 Shizuku 复制文件（绕过权限限制） */
    fun copyFileShizuku(src: File, dst: File): Boolean {
        if (!isAuthorized()) return false
        val result = execAndGet("cp -f '${src.absolutePath}' '${dst.absolutePath}' 2>&1")
        val success = result != null && !result.contains("Permission denied") && !result.contains("No such file")
        if (!success && result != null) {
            Log.w(TAG, "Shizuku cp 失败: $result")
        }
        return success
    }

    /** 通过 Shizuku 读取文件（绕过权限限制） */
    fun readFileShizuku(path: String): String? {
        if (!isAuthorized()) return null
        return execAndGet("cat '$path' 2>&1")?.let {
            if (it.contains("Permission denied") || it.contains("No such file")) null else it
        }
    }

    /** 通过 Shizuku 直接以系统权限打开 InputStream */
    fun openInputStreamShizuku(path: String): java.io.InputStream? {
        return try {
            val process = exec("sh", "-c", "cat '$path'") ?: return null
            process.inputStream
        } catch (e: Throwable) {
            null
        }
    }

    /** 是否是特权进程（拥有系统级权限） */
    fun isPrivileged(): Boolean = isAuthorized()

    // -- private helpers --

    private fun onBinderReceived() {
        checkStatus()
    }

    private fun onBinderDead() {
        status = Status.Installed
        permissionDeferred?.complete(false)
        permissionDeferred = null
    }

    private fun onPermissionResult(requestCode: Int, grantResult: Int) {
        val allowed = grantResult == PackageManager.PERMISSION_GRANTED
        if (allowed) checkStatus()
        permissionDeferred?.complete(allowed)
        permissionDeferred = null
    }

    private fun checkStatus() {
        if (!isRunning()) {
            status = Status.Installed
            return
        }
        val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        status = if (granted) {
            val uid = try { Shizuku.getUid() } catch (_: Throwable) { -1 }
            Status.Authorized(uid)
        } else {
            Status.Running
        }
    }
}
