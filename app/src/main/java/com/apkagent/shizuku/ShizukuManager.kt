package com.apkagent.shizuku

import com.apkagent.i18n.L

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    /** 权限请求码：listener 据此过滤，避免响应无关回调 */
    private const val PERMISSION_REQUEST_CODE = 0

    /** 单条命令最长执行时间，避免子进程卡死导致调用线程永久阻塞 */
    private const val EXEC_TIMEOUT_MS = 300_000L

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

    // 回调可能来自 Binder 线程 / 协程线程，用 AtomicReference 保证可见性与原子清理
    private val permissionDeferred = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val listenerAttach = Shizuku.OnBinderReceivedListener { onBinderReceived() }
    private val listenerDead = Shizuku.OnBinderDeadListener { onBinderDead() }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == PERMISSION_REQUEST_CODE) onPermissionResult(grantResult)
    }

    fun init() {
        try {
            Shizuku.addBinderReceivedListenerSticky(listenerAttach)
            Shizuku.addBinderDeadListener(listenerDead)
            Shizuku.addRequestPermissionResultListener(permissionListener)
            if (Shizuku.pingBinder()) checkStatus() else status = Status.Installed
        } catch (e: Throwable) {
            Log.w(TAG, L("Shizuku 初始化失败", "Shi Around initialisation failed"), e)
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

        // 已有请求在等待：复用同一个 deferred，避免重复点击覆盖导致旧协程永久挂起
        val inFlight = permissionDeferred.get()
        if (inFlight != null) {
            return try {
                withTimeout(timeoutMs) { inFlight.await() }
            } catch (_: Throwable) { false }
        }

        val deferred = CompletableDeferred<Boolean>()
        if (!permissionDeferred.compareAndSet(null, deferred)) {
            // 并发下被其它请求抢占，改为等待它
            return try {
                withTimeout(timeoutMs) { permissionDeferred.get()?.await() ?: false }
            } catch (_: Throwable) { false }
        }

        return try {
            withTimeout(timeoutMs) {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
                deferred.await()
            }
        } catch (_: Throwable) {
            false
        } finally {
            // 只清理本次放入的 deferred，避免 finally 置 null 后新请求 / 回调落空
            permissionDeferred.compareAndSet(deferred, null)
        }
    }

    // ══════════════════════════════════════════
    //  核心：通过 Shizuku 以 shell 权限执行命令
    // ══════════════════════════════════════════

    /**
     * 通过 Shizuku 以 shell (uid 2000) 权限执行命令。
     * 使用反射调用 Shizuku.newProcess()（在某些版本中标记为 library-private）。
     */
    fun execShizuku(command: String): Process? {
        if (!isAuthorized()) {
            Log.w(TAG, "Shizuku 未授权，无法执行: $command")
            return null
        }
        return try {
            val method = Shizuku::class.java.getMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
        } catch (e: Throwable) {
            Log.e(TAG, "Shizuku newProcess 反射失败，降级为普通权限执行（相关操作将以退出码判定失败）", e)
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            } catch (e2: Throwable) {
                Log.e(TAG, "普通执行也失败: $command", e2)
                null
            }
        }
    }

    /**
     * 统一执行命令：把 stderr 合并进 stdout，避免 stderr 管道写满后子进程阻塞；
     * 读取放在独立线程，主线程用带超时的 waitFor 等待，结束时强制销毁进程。
     * 返回 (输出, 退出码)；无法启动 / 超时返回 (已读输出, -1)，-1 不会被误判为成功。
     */
    private fun runCommand(command: String, timeoutMs: Long = EXEC_TIMEOUT_MS): Pair<String?, Int> {
        val process = execShizuku("$command 2>&1") ?: return null to -1
        val output = StringBuffer()
        val reader = Thread {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { br ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = br.read(buf)
                        if (n == -1) break
                        output.append(buf, 0, n)
                    }
                }
            } catch (_: Throwable) {
                // 进程被销毁时管道关闭，读取线程以 EOF / 异常结束，属正常收尾
            }
        }
        reader.isDaemon = true
        reader.start()
        return try {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()  // 先关闭管道让读取线程退出
                Log.w(TAG, "命令超时(${timeoutMs}ms)，已强制终止: $command")
                reader.join(2_000L)
                output.toString().trim().takeIf { it.isNotEmpty() } to -1
            } else {
                reader.join(2_000L)
                output.toString().trim() to process.exitValue()
            }
        } catch (e: Throwable) {
            Log.e(TAG, L("命令执行失败", "Command execution failed"), e)
            null to -1
        } finally {
            process.destroyForcibly()
        }
    }

    /** 执行命令并获取完整输出 */
    fun execAndGet(command: String): String? = runCommand(command).first

    /** 执行命令并获取退出码 */
    fun execAndGetCode(command: String): Pair<String?, Int> = runCommand(command)

    // ══════════════════════════════════════════
    //  文件操作（全部通过 Shizuku shell 权限）
    // ══════════════════════════════════════════

    /** 读取任意文件（shell 权限） */
    fun readFile(path: String): String? {
        return execAndGet("cat ${shellQuote(path)}")
    }

    /** 读取任意文件的字节（base64 编码） */
    fun readFileBytes(path: String): ByteArray? {
        val b64 = execAndGet("base64 ${shellQuote(path)}") ?: return null
        return try { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) } catch (_: Throwable) { null }
    }

    /** 写入任意文件（shell 权限） */
    fun writeFile(path: String, content: String): Boolean {
        // 用 base64 避免引号/特殊字符问题
        val b64 = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
        return execOk("echo ${shellQuote(b64)} | base64 -d > ${shellQuote(path)} 2>&1")
    }

    /** 写入字节到文件 */
    fun writeFileBytes(path: String, bytes: ByteArray): Boolean {
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return execOk("echo ${shellQuote(b64)} | base64 -d > ${shellQuote(path)} 2>&1")
    }

    /** 复制文件 */
    fun copyFile(src: String, dst: String): Boolean {
        return execOk("cp -f ${shellQuote(src)} ${shellQuote(dst)} 2>&1")
    }

    /** 移动/重命名文件 */
    fun moveFile(src: String, dst: String): Boolean {
        return execOk("mv -f ${shellQuote(src)} ${shellQuote(dst)} 2>&1")
    }

    /** 删除文件 */
    fun deleteFile(path: String): Boolean {
        return execOk("rm -rf ${shellQuote(path)} 2>&1")
    }

    /** 创建目录 */
    fun mkdirs(path: String): Boolean {
        return execOk("mkdir -p ${shellQuote(path)} 2>&1")
    }

    /** 列出目录内容 */
    fun listDir(path: String): String? {
        return execAndGet("ls -la ${shellQuote(path)} 2>&1")
    }

    /** 检查文件是否存在 */
    fun fileExists(path: String): Boolean {
        val result = execAndGet("[ -e ${shellQuote(path)} ] && echo yes || echo no")
        return result?.trim() == "yes"
    }

    /** 获取文件大小 */
    fun fileSize(path: String): Long {
        val result = execAndGet("stat -c %s ${shellQuote(path)} 2>/dev/null || wc -c < ${shellQuote(path)}") ?: return -1
        return result.trim().toLongOrNull() ?: -1
    }

    /** 修改文件权限 */
    fun chmod(path: String, mode: String): Boolean {
        return execOk("chmod ${shellQuote(mode)} ${shellQuote(path)} 2>&1")
    }

    /** 递归修改目录权限 */
    fun chmodR(path: String, mode: String): Boolean {
        return execOk("chmod -R ${shellQuote(mode)} ${shellQuote(path)} 2>&1")
    }

    /** 修改文件所有者 */
    fun chown(path: String, owner: String): Boolean {
        return execOk("chown ${shellQuote(owner)} ${shellQuote(path)} 2>&1")
    }

    // ══════════════════════════════════════════
    //  高级操作
    // ══════════════════════════════════════════

    /** 解压 zip 文件 */
    fun unzip(zipPath: String, destDir: String): Boolean {
        if (!mkdirs(destDir)) return false
        return execOk("unzip -o ${shellQuote(zipPath)} -d ${shellQuote(destDir)} 2>&1")
    }

    /** 解压 tar.gz */
    fun tarXzf(tarPath: String, destDir: String): Boolean {
        if (!mkdirs(destDir)) return false
        return execOk("tar xzf ${shellQuote(tarPath)} -C ${shellQuote(destDir)} 2>&1")
    }

    /** 安装 APK */
    fun installApk(apkPath: String): String? {
        return execAndGet("pm install -r -d ${shellQuote(apkPath)} 2>&1")
    }

    /** 获取已安装包信息 */
    fun getPackageInfo(packageName: String): String? {
        return execAndGet("dumpsys package ${shellQuote(packageName)} 2>&1 | head -50")
    }

    /** 结束进程 */
    fun killProcess(processName: String): Boolean {
        return execOk("am force-stop ${shellQuote(processName)} 2>&1")
    }

    /** 清除应用数据 */
    fun clearAppData(packageName: String): Boolean {
        return execOk("pm clear ${shellQuote(packageName)} 2>&1")
    }

    /** 获取系统属性 */
    fun getProp(key: String): String? {
        return execAndGet("getprop ${shellQuote(key)}")
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
            val process = execShizuku("cat ${shellQuote(path)}") ?: return null
            process.inputStream
        } catch (_: Throwable) { null }
    }

    fun isPrivileged(): Boolean = isAuthorized()

    // -- private --

    /** shell 单引号转义：' → '\'' ，防止路径闭合后被注入任意命令 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 以退出码判定成功；进程无法启动 / 超时返回 -1，不会被当成成功 */
    private fun execOk(command: String): Boolean = runCommand(command).second == 0

    private fun onBinderReceived() = checkStatus()

    private fun onBinderDead() {
        status = Status.Installed
        permissionDeferred.getAndSet(null)?.complete(false)
    }

    private fun onPermissionResult(grantResult: Int) {
        val allowed = grantResult == PackageManager.PERMISSION_GRANTED
        if (allowed) checkStatus()
        permissionDeferred.getAndSet(null)?.complete(allowed)
    }

    private fun checkStatus() {
        if (!isRunning()) { status = Status.Installed; return }
        val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        status = if (granted) {
            Status.Authorized(try { Shizuku.getUid() } catch (_: Throwable) { -1 })
        } else Status.Running
    }
}
