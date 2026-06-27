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
import kotlin.math.min

/**
 * 文件日志系统：同时输出到 logcat 和 Downloads/APKAgent/logs/ 目录。
 *
 * 每次 App 启动创建新的日志文件，文件名包含时间戳。
 * 日志文件可发送给开发者用于排查问题。
 */
object Logger {

    private const val TAG = "APKAgent"
    private const val LOG_DIR_NAME = "APKAgent/logs"
    private const val MAX_LOG_FILES = 20  // 最多保留最近 20 个日志文件

    @Volatile private var logFile: File? = null
    @Volatile private var writer: PrintWriter? = null
    private val writeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "APKAgent-Logger").apply { isDaemon = true }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    val currentLogFile: File? get() = logFile

    /** 初始化日志文件（App 启动时调用一次） */
    fun init() {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val logDir = File(downloadsDir, LOG_DIR_NAME)
            if (!logDir.exists()) logDir.mkdirs()

            if (logDir.exists()) {
                // 清理旧日志，保留最近 20 个
                val oldLogs = logDir.listFiles { f -> f.name.endsWith(".log") }
                    ?.sortedByDescending { it.lastModified() }
                oldLogs?.drop(MAX_LOG_FILES)?.forEach { it.delete() }

                // 创建新日志文件
                val name = "apkagent_${dateFormat.format(Date())}.log"
                logFile = File(logDir, name)
                writer = logFile?.let { PrintWriter(FileWriter(it, true), true) }
                w("Logger", "══════════ APKAgent 日志开始 ${dateFormat.format(Date())} ══════════")
                w("Logger", "日志目录: ${logDir.absolutePath}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "日志系统初始化失败", e)
        }
    }

    /** Debug 级别日志 */
    fun d(tag: String, msg: String) {
        Log.d(TAG, "[$tag] $msg")
        writeToFile("D", tag, msg)
    }

    /** Info 级别日志 */
    fun i(tag: String, msg: String) {
        Log.i(TAG, "[$tag] $msg")
        writeToFile("I", tag, msg)
    }

    /** Warning 级别日志 */
    fun w(tag: String, msg: String) {
        Log.w(TAG, "[$tag] $msg")
        writeToFile("W", tag, msg)
    }

    /** Error 级别日志（含异常堆栈） */
    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        Log.e(TAG, "[$tag] $msg", throwable)
        writeToFile("E", tag, msg)
        if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            writeToFile("E", tag, sw.toString())
        }
    }

    /** 导出当前日志文件路径（用于发送给开发者） */
    fun getLogPath(): String? = logFile?.absolutePath

    private fun writeToFile(level: String, tag: String, msg: String) {
        writeExecutor.execute {
            try {
                val w = writer ?: return@execute
                val time = timeFormat.format(Date())
                val truncated = msg.take(2000)
                w.println("$time $level [$tag] $truncated")
                w.flush()
            } catch (_: Throwable) {}
        }
    }

    /** 关闭日志文件 */
    fun close() {
        try {
            writeToFile("I", "Logger", "══════════ 日志结束 ══════════")
            writeExecutor.shutdown()
            writeExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
            writer?.flush()
            writer?.close()
        } catch (_: Throwable) {}
        writer = null
        logFile = null
    }
}
