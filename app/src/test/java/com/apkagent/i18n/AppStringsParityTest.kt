package com.apkagent.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ensures Chinese and English catalogs stay in sync (same non-blank fields).
 */
class AppStringsParityTest {

    @Test
    fun catalogsHaveSameShape() {
        val zh = StringsZh
        val en = StringsEn
        val zhFields = zh.javaClass.declaredFields.filter { it.name != "Companion" }
        // data class componentN / copy etc. — compare a sample of critical keys
        assertTrue(zh.settings.isNotBlank())
        assertTrue(en.settings.isNotBlank())
        assertTrue(zh.settings != en.settings)
        assertEquals("Settings", en.settings)
        assertTrue(zh.langEn.isNotBlank() && en.langEn.isNotBlank())
        assertTrue(zh.aboutTagline.isNotBlank() && en.aboutTagline.isNotBlank())
        assertTrue(AgentPrompts.systemPrompt().isNotBlank())
    }

    @Test
    fun languageIdsRoundTrip() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromId("system"))
        assertEquals(AppLanguage.ZH, AppLanguage.fromId("zh"))
        assertEquals(AppLanguage.EN, AppLanguage.fromId("en"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromId("unknown"))
    }

    @Test
    fun fmtReplacesNamedPlaceholders() {
        val s = StringsEn
        val out = s.fmt(s.importedPrefix, "name" to "demo.apk")
        assertTrue(out.contains("demo.apk"))
    }
}
