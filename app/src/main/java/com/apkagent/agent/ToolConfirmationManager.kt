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
 *
 * 并发的敏感工具会排队串行确认：同一时刻只对外发布一个请求，
 * 当前请求被决策（或被取消）后自动发布下一个，避免单槽 StateFlow
 * 互相覆盖、被覆盖请求的 deferred 永不完成导致 Agent 挂起。
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

    // 串行化锁：保护待决队列、当前请求与 StateFlow 的发布
    private val lock = Any()
    private val queue = mutableListOf<ConfirmationRequest>()
    private var current: ConfirmationRequest? = null

    suspend fun awaitDecision(call: PendingToolCall, riskLevel: ToolRiskLevel): Boolean {
        val request = ConfirmationRequest(
            call = call,
            riskLevel = riskLevel,
            summary = summarizeArguments(call.arguments)
        )
        // 入队并在空闲时发布队首，保证同一时刻只有一个请求对外可见
        synchronized(lock) {
            queue.add(request)
            publishNextLocked()
        }
        return try {
            request.deferred.await()
        } finally {
            // 协程被取消或异常结束时回收请求并补发下一个，避免后续请求永久饿死
            synchronized(lock) {
                if (current === request) {
                    current = null
                    publishNextLocked()
                } else {
                    queue.remove(request)
                }
            }
        }
    }

    /** 必须在持有 lock 时调用：空闲时把队首请求发布出去 */
    private fun publishNextLocked() {
        if (current != null) return
        val next = if (queue.isEmpty()) null else queue.removeAt(0)
        current = next
        _pendingRequest.value = next
    }

    private fun decide(approved: Boolean) {
        var finished: ConfirmationRequest? = null
        synchronized(lock) {
            finished = current
            if (finished != null) {
                current = null
                publishNextLocked()
            }
        }
        // 锁外完成 deferred，避免恢复的协程与锁互相等待
        finished?.let {
            if (!it.deferred.isCompleted) it.deferred.complete(approved)
        }
    }

    fun approve() = decide(true)

    fun deny() = decide(false)

    fun clearPending() {
        val pending = mutableListOf<ConfirmationRequest>()
        synchronized(lock) {
            current?.let { pending.add(it) }
            pending.addAll(queue)
            queue.clear()
            current = null
            _pendingRequest.value = null
        }
        // 锁外逐个以「拒绝」完成，保证没有任何 awaitDecision 永久挂起
        pending.forEach {
            if (!it.deferred.isCompleted) it.deferred.complete(false)
        }
    }

    private fun summarizeArguments(raw: String): String {
        val compact = raw.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= 280) compact else compact.take(277) + "..."
    }
}
