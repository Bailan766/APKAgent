package com.apkagent.agent

import org.junit.Assert.*
import org.junit.Test

class CircuitBreakerTest {

    @Test
    fun `starts in closed state`() {
        val cb = CircuitBreaker(failureThreshold = 3)
        assertEquals(CircuitBreaker.State.CLOSED, cb.state)
    }

    @Test
    fun `reset restores closed state`() {
        val cb = CircuitBreaker(failureThreshold = 1)
        cb.reset()
        assertEquals(CircuitBreaker.State.CLOSED, cb.state)
    }

    @Test
    fun `getStatus returns info string`() {
        val cb = CircuitBreaker(failureThreshold = 3)
        val status = cb.getStatus()
        assertTrue(status.contains("CLOSED"))
        assertTrue(status.contains("0"))
    }
}
