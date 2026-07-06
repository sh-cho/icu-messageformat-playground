package com.joebrothers.icuplayground

import com.ibm.icu.util.ULocale

/**
 * A curated shortlist of common locales for the dropdown. The backend accepts
 * any BCP-47 tag, so the UI also allows free input — this is just convenience.
 */
object Locales {
    private val TAGS = listOf(
        "en-US", "en-GB", "ko-KR", "ja-JP", "zh-CN", "zh-TW",
        "fr-FR", "de-DE", "es-ES", "es-MX", "pt-BR", "it-IT",
        "ru-RU", "ar-SA", "hi-IN", "th-TH", "vi-VN", "id-ID",
        "tr-TR", "pl-PL", "nl-NL", "sv-SE", "uk-UA", "cs-CZ",
    )

    val list: List<LocaleInfo> by lazy {
        TAGS.map { tag ->
            val u = ULocale.forLanguageTag(tag)
            LocaleInfo(tag = tag, displayName = u.getDisplayName(ULocale.ENGLISH))
        }
    }
}
