package com.apkagent.agent

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AgentLoopTest {

    @Test
    fun `ToolRegistry exposes all registered tools`() {
        val registry = ToolRegistry()
        assertEquals(0, registry.all().size)
    }

    @Test
    fun `ReversePhase progresses properly from tools used`() {
        assertEquals(ReversePhase.PREPARE, ReversePhase.fromToolsUsed(emptySet()))
        assertEquals(ReversePhase.STATIC_ANALYSIS, ReversePhase.fromToolsUsed(setOf("apk_apk_info")))
        assertEquals(ReversePhase.MODIFICATION, ReversePhase.fromToolsUsed(setOf("dex_disasm")))
        assertEquals(ReversePhase.REPACK, ReversePhase.fromToolsUsed(setOf("dex_edit")))
        assertEquals(ReversePhase.DONE, ReversePhase.fromToolsUsed(setOf("apk_sign")))
    }
}
