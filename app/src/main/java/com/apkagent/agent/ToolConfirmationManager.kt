package com.apkagent.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 敏感工具确认队列。
 *
 * AgentLoop 在执行 sensitive 工具前会调用 awaitDecision()；
 * UI 侧观察 pendingRequest 并展示确认弹窗，随后调用 approve()/deny()。
 */
class ToolConfirmationManager {

    data class ConfirmationRequest(
        val call: PendingToolCall,
        val riskLevel: ToolRiskLevel,
        val summary: String,
        val deferred: CompletableDeferred<Boolean> = CompletableDeferred()
    )

    private val _pendingRequest = MutableStateFlow<ConfirmationRequest?>(null)
    val pendingRequest: StateFlow<ConfirmationRequest?> = _pendingRequest.asStateFlow()

    suspend fun awaitDecision(call: PendingToolCall, riskLevel: ToolRiskLevel): Boolean {
        val request = ConfirmationRequest(
            call = call,
            riskLevel = riskLevel,
            summary = summarizeArguments(call.arguments)
        )
        _pendingRequest.value = request
        return try {
            request.deferred.await()
        } finally {
            if (_pendingRequest.value === request) {
                _pendingRequest.value = null
            }
        }
    }

    fun approve() {
        val request = _pendingRequest.value ?: return
        if (!request.deferred.isCompleted) request.deferred.complete(true)
        _pendingRequest.value = null
    }

    fun deny() {
        val request = _pendingRequest.value ?: return
        if (!request.deferred.isCompleted) request.deferred.complete(false)
        _pendingRequest.value = null
    }

    fun clearPending() {
        val request = _pendingRequest.value ?: return
        if (!request.deferred.isCompleted) request.deferred.complete(false)
        _pendingRequest.value = null
    }

    private fun summarizeArguments(raw: String): String {
        val compact = raw.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= 280) compact else compact.take(277) + "..."
    }
}
