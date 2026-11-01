package com.apkagent.agent

import com.apkagent.service.KeepAliveService
import com.apkagent.store.AgentConfig
import com.apkagent.store.ExecutionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionModeAndFluidCloudTest {

    @Test
    fun executionMode_default_is_fast() {
        val defaultCfg = AgentConfig()
        assertEquals(ExecutionMode.FAST.id, defaultCfg.executionMode)
        assertEquals(ExecutionMode.FAST, ExecutionMode.fromId("fast"))
        assertEquals(ExecutionMode.SAFE, ExecutionMode.fromId("safe"))
        assertEquals(ExecutionMode.FAST, ExecutionMode.fromId("invalid_mode"))
    }

    @Test
    fun keepAliveService_humanReadableTool_mappings() {
        val patchText = KeepAliveService.humanReadableTool("smart_patch_apply")
        assertTrue(patchText.contains("补丁") || patchText.contains("Patch"))

        val dexText = KeepAliveService.humanReadableTool("dex_edit")
        assertTrue(dexText.contains("Smali") || dexText.contains("DEX"))

        val pythonText = KeepAliveService.humanReadableTool("run_python_script")
        assertTrue(pythonText.contains("Python"))

        val apkText = KeepAliveService.humanReadableTool("apk_unpack")
        assertTrue(apkText.contains("解包") || apkText.contains("APK"))

        val signText = KeepAliveService.humanReadableTool("apk_sign")
        assertTrue(signText.contains("签名"))

        val riskText = KeepAliveService.humanReadableTool("risk_scan")
        assertTrue(riskText.contains("扫描") || riskText.contains("漏洞"))
    }

    @Test
    fun executionMode_toggle_roundtrip() {
        var mode = ExecutionMode.FAST
        assertEquals("快速模式", mode.titleZh)
        mode = ExecutionMode.SAFE
        assertEquals("安全模式", mode.titleZh)
    }
}
