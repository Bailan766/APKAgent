package com.apkagent.i18n

import java.util.Locale

/**
 * App UI / agent reply language preference.
 * SYSTEM follows the device locale (zh* → Chinese, otherwise English).
 */
enum class AppLanguage(val id: String) {
    SYSTEM("system"),
    ZH("zh"),
    EN("en");

    companion object {
        fun fromId(id: String?): AppLanguage =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }

    /** Resolved concrete language used for string catalogs and agent prompts. */
    fun resolve(): ResolvedLanguage = when (this) {
        ZH -> ResolvedLanguage.ZH
        EN -> ResolvedLanguage.EN
        SYSTEM -> {
            val lang = Locale.getDefault().language.lowercase(Locale.ROOT)
            if (lang.startsWith("zh")) ResolvedLanguage.ZH else ResolvedLanguage.EN
        }
    }
}

enum class ResolvedLanguage { ZH, EN }
