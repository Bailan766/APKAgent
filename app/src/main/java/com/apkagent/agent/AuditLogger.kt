package com.apkagent.agent

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 操作审计日志。
 * 记录所有工具调用的完整信息：工具名、参数、结果摘要、耗时、成功/失败。
 * 用于事后分析、调试、复现问题。
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

    /**
     * 初始化审计日志文件。
     */
    fun init(workspaceDir: File) {
        val auditDir = File(workspaceDir, "audit").apply { mkdirs() }
        logFile = File(auditDir, "audit_${sessionId}.jsonl")
        Log.i(TAG, "审计日志初始化: ${logFile?.absolutePath}")
    }

    /**
     * 记录工具调用。
     */
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

        // 异步写入文件
        try {
            val line = json.encodeToString(entry)
            logFile?.appendText(line + "\n")

            // 日志文件大小检查
            logFile?.let { file ->
                if (file.length() > MAX_LOG_SIZE) {
                    rotateLog(file)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "写入审计日志失败: ${e.message}")
        }
    }

    fun nextRound() { currentRound++ }

    fun getSessionId(): String = sessionId

    fun newSession() {
        sessionId = UUID.randomUUID().toString().take(8)
        currentRound = 0
        buffer.clear()
    }

    /**
     * 获取最近 N 条审计条目。
     */
    fun recent(n: Int = 20): List<AuditEntry> = buffer.takeLast(n)

    /**
     * 获取当前会话统计。
     */
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
            Log.i(TAG, "日志轮转: ${rotated.name}")
        } catch (e: Exception) {
            Log.w(TAG, "日志轮转失败: ${e.message}")
        }
    }
}
