package com.apkagent.agent

import org.junit.Assert.*
import org.junit.Test

class ToolRiskLevelTest {

    @Test
    fun `danger tools are classified correctly`() {
        assertEquals(ToolRiskLevel.DANGER, ToolRiskLevel.infer("apk_sign"))
        assertEquals(ToolRiskLevel.DANGER, ToolRiskLevel.infer("apk_install"))
        assertEquals(ToolRiskLevel.DANGER, ToolRiskLevel.infer("apk_repack"))
        assertEquals(ToolRiskLevel.DANGER, ToolRiskLevel.infer("file_write"))
        assertEquals(ToolRiskLevel.DANGER, ToolRiskLevel.infer("dex_edit"))
        assertEquals(ToolRiskLevel.DANGER, ToolRiskLevel.infer("smali_replace"))
        assertEquals(ToolRiskLevel.DANGER, ToolRiskLevel.infer("smart_patch_apply"))
        assertEquals(ToolRiskLevel.DANGER, ToolRiskLevel.infer("apk_patch_manifest"))
        assertEquals(ToolRiskLevel.DANGER, ToolRiskLevel.infer("auto_patch"))
        assertEquals(ToolRiskLevel.DANGER, ToolRiskLevel.infer("file_delete"))
    }

    @Test
    fun `caution tools are classified correctly`() {
        assertEquals(ToolRiskLevel.CAUTION, ToolRiskLevel.infer("file_read"))
        assertEquals(ToolRiskLevel.CAUTION, ToolRiskLevel.infer("apk_read_manifest"))
        assertEquals(ToolRiskLevel.CAUTION, ToolRiskLevel.infer("dex_disasm"))
        assertEquals(ToolRiskLevel.CAUTION, ToolRiskLevel.infer("hex_view"))
        assertEquals(ToolRiskLevel.CAUTION, ToolRiskLevel.infer("apk_search_strings"))
        assertEquals(ToolRiskLevel.CAUTION, ToolRiskLevel.infer("apk_list_files"))
    }

    @Test
    fun `safe tools are classified correctly`() {
        assertEquals(ToolRiskLevel.SAFE, ToolRiskLevel.infer("unknown_tool"))
        assertEquals(ToolRiskLevel.SAFE, ToolRiskLevel.infer("risk_scan"))
    }
}
