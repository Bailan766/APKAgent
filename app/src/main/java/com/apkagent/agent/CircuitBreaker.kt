package com.apkagent.agent

import com.apkagent.i18n.L
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 不可重试的客户端异常（例如 HTTP 400/401/403/404），不触发断路器熔断也不重试。
 */
class NonRetryableApiException(val httpCode: Int, message: String) : IllegalStateException(message)

/**
 * 断路器 + 指数退避。
 * 连续失败达到阈值后熔断，短路后续请求，快速失败。
 * 经过冷却期后半开，允许一次试探请求。
 */
class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val cooldownMs: Long = 30_000L,
    private val maxBackoffMs: Long = 60_000L
) {
    private val TAG = "CircuitBreaker"

    enum class State { CLOSED, OPEN, HALF_OPEN }

    private val _state = AtomicReference(State.CLOSED)
    var state: State
        get() = _state.get()
        private set(value) { _state.set(value) }

    private val consecutiveFailures = AtomicInteger(0)
    private val lastFailureTime = AtomicLong(0L)
    @Volatile private var backoffMs = 1000L
    /** HALF_OPEN 状态下是否已允许试探请求通过（CAS 保证只放行一次） */
    private val halfOpenProbeIssued = AtomicReference(false)

    /**
     * 在断路器保护下执行操作。
     * CLOSED  — 正常执行
     * OPEN    — 快速失败（冷却期内）
     * HALF_OPEN — 允许一次试探
     */
    suspend fun <T> execute(operation: suspend () -> T): T {
        when (state) {
            State.OPEN -> {
                val elapsed = System.currentTimeMillis() - lastFailureTime.get()
                if (elapsed >= cooldownMs) {
                    Log.i(TAG, L("冷却结束，进入半开状态", "Cooldown finished; entering half-open state"))
                    _state.set(State.HALF_OPEN)
                    halfOpenProbeIssued.set(false)
                } else {
                    val remaining = cooldownMs - elapsed
                    throw CircuitOpenException(
                        "断路器已熔断，${remaining / 1000}s 后重试 (连续失败 ${consecutiveFailures.get()} 次)"
                    )
                }
            }
            State.HALF_OPEN -> {
                // 只允许一次试探请求通过，后续请求快速失败直到试探完成
                if (!halfOpenProbeIssued.compareAndSet(false, true)) {
                    throw CircuitOpenException("断路器半开状态，正在等待试探请求结果")
                }
            }
            State.CLOSED -> { /* 正常 */ }
        }

        return try {
            val result = operation()
            onSuccess()
            result
        } catch (e: NonRetryableApiException) {
            // 4xx 客户端配置错误（如 Key 错误/模型不存在）不应计入服务端熔断
            throw e
        } catch (e: CancellationException) {
            // 协程取消不是业务失败，不应触发熔断
            throw e
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
                throw e
            } catch (e: NonRetryableApiException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    val wait = calculateBackoff(attempt)
                    Log.w(TAG, "重试 ${attempt + 1}/$maxRetries，等待 ${wait}ms: ${e.message}")
                    delay(wait)
                }
            }
        }
        throw lastException ?: IllegalStateException(L("重试耗尽", "Retries exhausted"))
    }

    private fun onSuccess() {
        consecutiveFailures.set(0)
        backoffMs = 1000L
        if (_state.get() == State.HALF_OPEN) {
            Log.i(TAG, L("试探成功，恢复正常", "Test successful. Get back to normal."))
            _state.set(State.CLOSED)
            halfOpenProbeIssued.set(false)
        }
    }

    private fun onFailure() {
        consecutiveFailures.incrementAndGet()
        lastFailureTime.set(System.currentTimeMillis())
        backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)

        if (consecutiveFailures.get() >= failureThreshold) {
            Log.w(TAG, "连续失败 ${consecutiveFailures.get()} 次，熔断！")
            _state.set(State.OPEN)
            halfOpenProbeIssued.set(false)
        }
    }

    private fun calculateBackoff(attempt: Int): Long {
        val exponential = 1000L * (1L shl attempt) // 1s, 2s, 4s
        val jitter = (Math.random() * 500).toLong()
        return (exponential + jitter).coerceAtMost(maxBackoffMs)
    }

    fun reset() {
        _state.set(State.CLOSED)
        consecutiveFailures.set(0)
        backoffMs = 1000L
        halfOpenProbeIssued.set(false)
    }

    fun getStatus(): String = "状态=${state}, 连续失败=${consecutiveFailures.get()}, 退避=${backoffMs}ms"
}

class CircuitOpenException(message: String) : Exception(message)
