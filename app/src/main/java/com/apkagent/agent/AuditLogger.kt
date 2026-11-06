package com.apkagent.agent

import com.apkagent.i18n.L
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.*

/**
 * 操作审计日志。
 * 记录所有工具调用的完整信息：工具名、参数、结果摘要、耗时、成功/失败。
 * 全部方法加锁保证并发工具执行时的线程安全。
 */
object AuditLogger {
    private const val TAG = "AuditLog"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024L // 5MB
    private const val MAX_BUFFER_ENTRIES = 500 // 内存缓冲上限，避免只增不减

    @Serializable
    data class AuditEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val sessionId: String,
        val round: Int,
        val toolName: String,
        val arguments: String,
        val resultSummary: String,
        val success: Boolean,
        val durationMs: Long,
        val riskLevel: String
    )

    private val json = Json { prettyPrint = false; encodeDefaults = true }
    private var logFile: File? = null
    private var workspaceDir: File? = null
    private var sessionId: String = UUID.randomUUID().toString().take(8)
    private var currentRound = 0
    private val buffer = mutableListOf<AuditEntry>()

    @Synchronized
    fun init(workspaceDir: File) {
        this.workspaceDir = workspaceDir
        rebuildLogFile()
        try { Log.i(TAG, "审计日志初始化: ${logFile?.absolutePath}") } catch (_: Throwable) {}
    }

    /** 按当前 sessionId 重建日志文件，保证文件名与条目里的 sessionId 一致 */
    private fun rebuildLogFile() {
        val dir = workspaceDir ?: return
        val auditDir = File(dir, "audit").apply { mkdirs() }
        logFile = File(auditDir, "audit_${sessionId}.jsonl")
    }

    /**
     * 写入一条审计日志。
     * 返回是否成功落盘：失败时以 Log.e 暴露并把结果交给调用方决定如何处理，
     * 不再静默吞掉 appendText 异常。
     */
    @Synchronized
    fun log(
        toolName: String,
        arguments: String,
        resultSummary: String,
        success: Boolean,
        durationMs: Long,
        riskLevel: ToolRiskLevel
    ): Boolean {
        val entry = AuditEntry(
            sessionId = sessionId,
            round = currentRound,
            toolName = toolName,
            arguments = arguments.take(500),
            resultSummary = resultSummary.take(200),
            success = success,
            durationMs = durationMs,
            riskLevel = riskLevel.name
        )

        // 缓冲设上限，防止长时间运行无限增长
        if (buffer.size >= MAX_BUFFER_ENTRIES) buffer.removeAt(0)
        buffer.add(entry)

        val file = logFile
        if (file == null) {
            try { Log.e(TAG, "审计日志未初始化，写入失败: $toolName") } catch (_: Throwable) {}
            return false
        }

        return try {
            val line = json.encodeToString(entry)
            file.appendText(line + "\n")
            if (file.length() > MAX_LOG_SIZE) rotateLog(file)
            true
        } catch (e: Exception) {
            try {
                Log.e(TAG, L("写入审计日志失败: ${e.message}", "Writing to Audit Log failed: ${e.message}"), e)
            } catch (_: Throwable) {}
            false
        }
    }

    @Synchronized
    fun nextRound() { currentRound++ }

    @Synchronized
    fun getSessionId(): String = sessionId

    @Synchronized
    fun newSession() {
        sessionId = UUID.randomUUID().toString().take(8)
        currentRound = 0
        buffer.clear()
        // 若已初始化则同步重建 logFile，避免文件名沿用旧 sessionId 与条目不一致
        if (workspaceDir != null) rebuildLogFile()
    }

    @Synchronized
    fun recent(n: Int = 20): List<AuditEntry> = buffer.takeLast(n)

    @Synchronized
    fun stats(): String {
        val total = buffer.size
        val success = buffer.count { it.success }
        val failed = total - success
        val avgDuration = if (total > 0) buffer.sumOf { it.durationMs } / total else 0
        return "审计: $total 次调用, $success 成功, $failed 失败, 平均 ${avgDuration}ms"
    }

    private fun rotateLog(file: File) {
        try {
            // 基于 logFile 自身名称（内嵌 sessionId）命名，确保轮转文件与会话一致
            val rotated = File(file.parent, "${file.nameWithoutExtension}_${System.currentTimeMillis()}.jsonl")
            file.renameTo(rotated)
            try { Log.i(TAG, "日志轮转: ${rotated.name}") } catch (_: Throwable) {}
        } catch (e: Exception) {
            try { Log.e(TAG, "日志轮转失败: ${e.message}") } catch (_: Throwable) {}
        }
    }
}
