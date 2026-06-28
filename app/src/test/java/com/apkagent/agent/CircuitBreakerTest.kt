package com.apkagent.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CircuitBreakerTest {

    @Test
    fun `starts in closed state`() {
        val cb = CircuitBreaker(failureThreshold = 3)
        assertEquals(CircuitBreaker.State.CLOSED, cb.state)
    }

    @Test
    fun `opens after threshold failures`() = runBlocking {
        val cb = CircuitBreaker(failureThreshold = 3, cooldownMs = 60_000)
        repeat(3) {
            try {
                cb.execute<String> { throw RuntimeException("fail") }
            } catch (_: Exception) {}
        }
        assertEquals(CircuitBreaker.State.OPEN, cb.state)
    }

    @Test
    fun `successful call keeps closed`() = runBlocking {
        val cb = CircuitBreaker(failureThreshold = 3)
        val result = cb.execute { "ok" }
        assertEquals("ok", result)
        assertEquals(CircuitBreaker.State.CLOSED, cb.state)
    }

    @Test(expected = CircuitOpenException::class)
    fun `open circuit throws fast`() = runBlocking {
        val cb = CircuitBreaker(failureThreshold = 1, cooldownMs = 60_000)
        try { cb.execute<String> { throw RuntimeException("fail") } } catch (_: Exception) {}
        cb.execute<String> { "should not reach" }
    }

    @Test
    fun `reset restores closed state`() = runBlocking {
        val cb = CircuitBreaker(failureThreshold = 1)
        try { cb.execute<String> { throw RuntimeException("fail") } } catch (_: Exception) {}
        assertEquals(CircuitBreaker.State.OPEN, cb.state)
        cb.reset()
        assertEquals(CircuitBreaker.State.CLOSED, cb.state)
    }
}
