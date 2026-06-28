package com.apkagent.agent

import android.util.Log
import kotlinx.coroutines.delay

/**
 * 断路器 + 指数退避。
 * 连续失败达到阈值后"熔断"，短路后续请求，快速失败。
 * 经过冷却期后半开，允许一次试探请求。
 */
class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val cooldownMs: Long = 30_000L,
    private val maxBackoffMs: Long = 60_000L
) {
    private val TAG = "CircuitBreaker"

    enum class State { CLOSED, OPEN, HALF_OPEN }

    var state: State = State.CLOSED
        private set

    private var consecutiveFailures = 0
    private var lastFailureTime = 0L
    private var backoffMs = 1000L

    /**
     * 在断路器保护下执行操作。
     * CLOSED  — 正常执行
     * OPEN    — 快速失败（冷却期内）
     * HALF_OPEN — 允许一次试探
     */
    suspend fun <T> execute(operation: suspend () -> T): T {
        when (state) {
            State.OPEN -> {
                val elapsed = System.currentTimeMillis() - lastFailureTime
                if (elapsed >= cooldownMs) {
                    Log.i(TAG, "冷却结束，进入半开状态")
                    state = State.HALF_OPEN
                } else {
                    val remaining = cooldownMs - elapsed
                    throw CircuitOpenException(
                        "断路器已熔断，${remaining / 1000}s 后重试 (连续失败 $consecutiveFailures 次)"
                    )
                }
            }
            State.HALF_OPEN -> { /* 允许一次试探 */ }
            State.CLOSED -> { /* 正常 */ }
        }

        return try {
            val result = operation()
            onSuccess()
            result
        } catch (e: Exception) {
            onFailure()
            throw e
        }
    }

    /**
     * 带指数退避的重试。
     */
    suspend fun <T> retryWithBackoff(
        maxRetries: Int = 3,
        operation: suspend () -> T
    ): T {
        var lastException: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                return execute(operation)
            } catch (e: CircuitOpenException) {
                throw e // 断路器熔断不重试
            } catch (e: Exception) {
                lastException = e
                val wait = calculateBackoff(attempt)
                Log.w(TAG, "重试 ${attempt + 1}/$maxRetries，等待 ${wait}ms: ${e.message}")
                delay(wait)
            }
        }
        throw lastException ?: IllegalStateException("重试耗尽")
    }

    private fun onSuccess() {
        consecutiveFailures = 0
        backoffMs = 1000L
        if (state == State.HALF_OPEN) {
            Log.i(TAG, "试探成功，恢复正常")
            state = State.CLOSED
        }
    }

    private fun onFailure() {
        consecutiveFailures++
        lastFailureTime = System.currentTimeMillis()
        backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)

        if (consecutiveFailures >= failureThreshold) {
            Log.w(TAG, "连续失败 $consecutiveFailures 次，熔断！")
            state = State.OPEN
        }
    }

    private fun calculateBackoff(attempt: Int): Long {
        val exponential = 1000L * (1L shl attempt) // 1s, 2s, 4s
        val jitter = (Math.random() * 500).toLong()
        return (exponential + jitter).coerceAtMost(maxBackoffMs)
    }

    fun reset() {
        state = State.CLOSED
        consecutiveFailures = 0
        backoffMs = 1000L
    }

    fun getStatus(): String = "状态=$state, 连续失败=$consecutiveFailures, 退避=${backoffMs}ms"
}

class CircuitOpenException(message: String) : Exception(message)
