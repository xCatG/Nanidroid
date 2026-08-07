package com.cattailsw.nanidroid.corpus

import com.cattailsw.nanidroid.surface.SourceLine
import com.cattailsw.nanidroid.surface.SurfaceBlockMode
import com.cattailsw.nanidroid.surface.SurfaceSelector
import java.util.Locale

internal object NarCorpusSourceSyntaxInspector {
    data class Declaration(
        val isAppend: Boolean,
        val hasCommaSelectors: Boolean,
        val hasRangeSelectors: Boolean,
        val hasExclusionSelectors: Boolean,
        val includedIds: Set<Int>,
        val excludedIds: Set<Int>,
    )

    fun inspect(
        file: String,
        lineNumber: Int,
        rawLine: String,
    ): Declaration? {
        val declaration = rawLine.substringBefore("//").trim()
        if (declaration.isBlank()) return null

        val lowerDeclaration = declaration.lowercase(Locale.ROOT)
        val prefix = when {
            lowerDeclaration.startsWith(SURFACE_APPEND_PREFIX) -> SURFACE_APPEND_PREFIX
            lowerDeclaration.startsWith(SURFACE_SELECTOR_PREFIX) -> SURFACE_SELECTOR_PREFIX
            else -> return null
        }
        val selectorBody = declaration.substring(prefix.length).substringBefore("{")
        val tokens = selectorBody
            .split(",")
            .map(String::trim)
            .filter(String::isNotBlank)
        val parsed = SurfaceSelector().parse(SourceLine(file, lineNumber, rawLine))

        return Declaration(
            isAppend = parsed.mode == SurfaceBlockMode.APPEND_EXISTING,
            hasCommaSelectors = selectorBody.contains(","),
            hasRangeSelectors = tokens.any { token -> token.removePrefix("!").contains("-") },
            hasExclusionSelectors = tokens.any { token -> token.startsWith("!") },
            includedIds = parsed.selection.included,
            excludedIds = parsed.selection.excluded,
        )
    }

    private const val SURFACE_SELECTOR_PREFIX = "surface"
    private const val SURFACE_APPEND_PREFIX = "surface.append"
}
