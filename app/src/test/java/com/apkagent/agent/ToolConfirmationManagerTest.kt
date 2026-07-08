package com.apkagent.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolConfirmationManagerTest {

    @Test
    fun confirmation_request_published_and_approved() = runBlocking {
        val manager = ToolConfirmationManager()
        val call = PendingToolCall(
            id = "1",
            name = "apk_sign",
            arguments = "{\"path\":\"/tmp/a.apk\"}",
            parsedArgs = null
        )

        val waiting = async { manager.awaitDecision(call, ToolRiskLevel.DANGER) }
        delay(20)

        val request = manager.pendingRequest.value
        assertNotNull(request)
        assertEquals("apk_sign", request?.call?.name)
        assertTrue(request?.summary?.contains("/tmp/a.apk") == true)

        manager.approve()
        assertTrue(waiting.await())
        assertEquals(null, manager.pendingRequest.value)
    }

    @Test
    fun confirmation_request_can_be_denied() = runBlocking {
        val manager = ToolConfirmationManager()
        val call = PendingToolCall(
            id = "2",
            name = "file_write",
            arguments = "{\"path\":\"/tmp/x\"}",
            parsedArgs = null
        )

        val waiting = async { manager.awaitDecision(call, ToolRiskLevel.DANGER) }
        delay(20)

        assertNotNull(manager.pendingRequest.value)
        manager.deny()
        assertFalse(waiting.await())
        assertEquals(null, manager.pendingRequest.value)
    }
}
