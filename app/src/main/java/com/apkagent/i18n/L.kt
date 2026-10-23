package com.apkagent.i18n

/**
 * Pick Chinese or English text based on [LocaleManager].
 *
 * Both arguments may be Kotlin string templates — they are evaluated
 * eagerly, which is fine for tool/backend messages.
 *
 * Prefer this over growing [AppStrings] for the large apktools surface.
 */
fun L(zh: String, en: String): String =
    if (LocaleManager.isEnglish()) en else zh
