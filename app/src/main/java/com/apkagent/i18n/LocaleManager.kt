package com.apkagent.i18n

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide language preference. Updated from [com.apkagent.store.SettingsStore].
 * UI reads [strings] / [language]; agent code reads [resolved] for prompts.
 */
object LocaleManager {
    private val _language = MutableStateFlow(AppLanguage.SYSTEM)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    fun setLanguage(lang: AppLanguage) {
        _language.value = lang
    }

    val resolved: ResolvedLanguage
        get() = _language.value.resolve()

    val strings: AppStrings
        get() = when (resolved) {
            ResolvedLanguage.ZH -> StringsZh
            ResolvedLanguage.EN -> StringsEn
        }

    fun isEnglish(): Boolean = resolved == ResolvedLanguage.EN
}
