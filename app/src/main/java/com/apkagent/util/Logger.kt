package com.apkagent.util

import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 文件日志系统：输出到 logcat + Downloads/APKAgent/logs/
 *
 * 格式：[MM-DD HH:mm:ss.SSS] [tid] LEVEL/TAG | message
 *
 * - ERROR 级别：同步写入（防止崩溃时丢失）
 * - INFO/DEBUG：异步批量写入
 * - 每次启动创建新文件，保留最近 20 个
 */
object Logger {

    private const val TAG = "APKAgent"
    private const val LOG_DIR_NAME = "APKAgent/logs"
    private const val MAX_LOG_FILES = 20

    @Volatile private var logFile: File? = null
    @Volatile private var writer: PrintWriter? = null
    @Volatile private var startTime: Long = 0

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "log-writer").apply { isDaemon = true }
    }
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    val currentLogFile: File? get() = logFile

    /** 初始化（App.onCreate 调用） */
    fun init() {
        startTime = System.currentTimeMillis()
        try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val logDir = File(downloads, LOG_DIR_NAME)
            if (!logDir.exists()) logDir.mkdirs()

            if (logDir.exists()) {
                // 清理旧日志
                logDir.listFiles { f -> f.name.endsWith(".log") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(MAX_LOG_FILES)
                    ?.forEach { it.delete() }

                val name = "apkagent_${dateFmt.format(Date())}.log"
                logFile = File(logDir, name)
                writer = PrintWriter(FileWriter(logFile!!, true), true)
                sync("Logger", "══════════ 启动 ${dateFmt.format(Date())} ══════════")
                sync("Logger", "日志目录: ${logDir.absolutePath}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "日志初始化失败", e)
        }
    }

    /** 设置全局崩溃处理器 */
    fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            sync("CRASH", "━━━ 未捕获异常 ━━━")
            sync("CRASH", "线程: ${thread.name} (id=${thread.id})")
            sync("CRASH", "异常: ${throwable.javaClass.name}: ${throwable.message}")
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            sync("CRASH", sw.toString())
            sync("CRASH", "━━━ 应用即将崩溃 ━━━")
            flushSync()
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    // ── 公开 API ──

    fun d(tag: String, msg: String) {
        Log.d(TAG, "[$tag] $msg")
        async("D", tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i(TAG, "[$tag] $msg")
        async("I", tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(TAG, "[$tag] $msg")
        async("W", tag, msg)
    }

    /** ERROR 级别：同步写入保证不丢失 */
    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        Log.e(TAG, "[$tag] $msg", throwable)
        sync("E", tag, msg)
        if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            sync("E", tag, sw.toString())
        }
    }

    /** 心跳日志：证明应用还活着 */
    fun heartbeat(tag: String) {
        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "[$tag] heartbeat +${elapsed}ms")
        async("♥", tag, "alive +${elapsed}ms")
    }

    fun getLogPath(): String? = logFile?.absolutePath

    /** 获取已运行时间 */
    fun elapsed(): Long = System.currentTimeMillis() - startTime

    fun close() {
        async("I", "Logger", "══════════ 关闭 +${elapsed()}ms ══════════")
        flushSync()
        try {
            executor.shutdown()
            executor.awaitTermination(3, TimeUnit.SECONDS)
            writer?.flush()
            writer?.close()
        } catch (_: Throwable) {}
        writer = null
        logFile = null
    }

    // ── 内部 ──

    private fun format(level: String, tag: String): String {
        val elapsed = System.currentTimeMillis() - startTime
        val time = timeFmt.format(Date())
        val thread = Thread.currentThread()
        val tname = "${thread.name}:${thread.id}"
        return "$time [$tname] $level/$tag"
    }

    /** 同步写入（用于 error 和关键事件，保证不丢失） */
    private fun sync(tag: String, msg: String) = sync("I", tag, msg)
    private fun sync(level: String, tag: String, msg: String) {
        val header = format(level, tag)
        try {
            val w = writer ?: return
            for (line in msg.split('\n')) {
                w.println("$header | $line")
            }
            w.flush()
        } catch (_: Throwable) {}
    }

    /** 异步写入 */
    private fun async(level: String, tag: String, msg: String) {
        executor.execute {
            try {
                val w = writer ?: return@execute
                val header = format(level, tag)
                for (line in msg.split('\n')) {
                    w.println("$header | $line")
                }
                w.flush()
            } catch (_: Throwable) {}
        }
    }

    /** 强制刷新异步队列 */
    private fun flushSync() {
        try {
            writer?.flush()
        } catch (_: Throwable) {}
    }
}
