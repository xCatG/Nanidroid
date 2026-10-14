package com.cattailsw.nanidroid.ghost

import java.util.Locale

/** Folder selection for bundled dictionaries, with legacy authored languages on imports. */
internal object BuiltInDictionaryLocale {
    fun bundledFolder(locale: Locale): String = when {
        locale.script.equals("Hans", true) -> "ja"
        locale.language == "en" -> "en"
        locale.language == "zh" && (locale.script.equals("Hant", true) ||
            locale.country in setOf("TW", "HK", "MO")) -> "zh-TW"
        else -> "ja"
    }

    fun importedFolder(locale: Locale): String = if (locale.language in setOf("en", "ja", "zh")) {
        bundledFolder(locale)
    } else locale.language.takeIf { it.matches(Regex("[A-Za-z]{2,8}")) } ?: "ja"
}
