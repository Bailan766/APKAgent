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
    private var sessionId: String = UUID.randomUUID().toString().take(8)
    private var currentRound = 0
    private val buffer = mutableListOf<AuditEntry>()

    @Synchronized
    fun init(workspaceDir: File) {
        val auditDir = File(workspaceDir, "audit").apply { mkdirs() }
        logFile = File(auditDir, "audit_${sessionId}.jsonl")
        try { Log.i(TAG, "审计日志初始化: ${logFile?.absolutePath}") } catch (_: Throwable) {}
    }

    @Synchronized
    fun log(
        toolName: String,
        arguments: String,
        resultSummary: String,
        success: Boolean,
        durationMs: Long,
        riskLevel: ToolRiskLevel
    ) {
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

        buffer.add(entry)

        try {
            val line = json.encodeToString(entry)
            logFile?.appendText(line + "\n")

            logFile?.let { file ->
                if (file.length() > MAX_LOG_SIZE) {
                    rotateLog(file)
                }
            }
        } catch (e: Exception) {
            try { Log.w(TAG, L("写入审计日志失败: ${e.message}", "Writing to Audit Log failed: ${e.message}")) } catch (_: Throwable) {}
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
            val rotated = File(file.parent, "audit_${sessionId}_${System.currentTimeMillis()}.jsonl")
            file.renameTo(rotated)
            try { Log.i(TAG, "日志轮转: ${rotated.name}") } catch (_: Throwable) {}
        } catch (e: Exception) {
            try { Log.w(TAG, "日志轮转失败: ${e.message}") } catch (_: Throwable) {}
        }
    }
}
