package com.apkagent.project

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectAnalyzerTest {

    @Test
    fun intelligence_hooks_exposed_for_future_deep_analysis() {
        val hooks = ProjectAnalyzer.intelligenceHooks()
        assertTrue(hooks.contains("string_index"))
        assertTrue(hooks.contains("sdk_fingerprint"))
    }

    @Test
    fun analyze_quick_handles_invalid_apk_with_fallback_summary() {
        val tmp = kotlin.io.path.createTempFile("apkagent-test", ".apk").toFile()
        tmp.writeBytes(
            byteArrayOf(
                0x50, 0x4B, 0x05, 0x06,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00
            )
        )
        val project = ReverseProject(
            id = "p1",
            name = "Broken",
            sourceType = ProjectSourceType.APK_FILE,
            sourcePath = tmp.absolutePath,
            importedApkPath = tmp.absolutePath
        )

        val result = ProjectAnalyzer.analyzeQuick(project)
        assertTrue(result.projectName == "Broken")
        assertTrue(result.riskSummary.isNotBlank())
    }
}
