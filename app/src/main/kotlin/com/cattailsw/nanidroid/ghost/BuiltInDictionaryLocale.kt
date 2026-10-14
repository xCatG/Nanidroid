package com.cattailsw.nanidroid.ghost

import java.util.Locale

/** Folder selection for bundled dictionaries, with legacy authored languages on imports. */
internal object BuiltInDictionaryLocale {
    fun bundledFolder(locale: Locale): String = when {
        locale.script.equals("Hans", true) -> "ja"
        locale.language == "en" -> "en"
        locale.language == "zh" && (
            locale.script.equals("Hant", true) ||
                locale.country in setOf("TW", "HK", "MO")
            ) -> "zh-TW"
        else -> "ja"
    }

    fun importedFolders(locale: Locale): List<String> {
        val language = locale.language.takeIf { it.matches(Regex("[A-Za-z]{2,8}")) } ?: "ja"
        if (language == "zh") {
            if (locale.script.equals("Hans", true)) return listOf("ja")
            return if (bundledFolder(locale) == "zh-TW") listOf("zh-TW", "zh", "ja")
            else listOf("zh", "ja")
        }
        val selected = if (language in setOf("en", "ja")) bundledFolder(locale) else language
        return listOf(selected, "ja").distinct()
    }
}
