package com.cattailsw.nanidroid.surface

import java.util.Locale

data class SurfaceSelection(
    val included: LinkedHashSet<Int>,
    val excluded: Set<Int>,
)

enum class SurfaceBlockMode { DEFINE, APPEND_EXISTING }

data class SurfaceSelectorResult(
    val selection: SurfaceSelection,
    val mode: SurfaceBlockMode,
    val diagnostics: List<SurfaceParseDiagnostic>,
    val exhausted: Boolean = false,
)

class SurfaceSelector {
    fun parse(source: SourceLine): SurfaceSelectorResult = parse(source, null)

    internal fun parse(
        source: SourceLine,
        sharedBudget: SurfaceSelectorWorkBudget?,
    ): SurfaceSelectorResult {
        val diagnostics = mutableListOf<SurfaceParseDiagnostic>()
        val declaration = source.text.substringBefore('{').substringBefore("//").trim()
        val lower = declaration.lowercase(Locale.ROOT)
        val (mode, body) = when {
            lower.startsWith(APPEND_PREFIX) ->
                SurfaceBlockMode.APPEND_EXISTING to declaration.substring(APPEND_PREFIX.length)
            lower.startsWith(NORMAL_PREFIX) ->
                SurfaceBlockMode.DEFINE to declaration.substring(NORMAL_PREFIX.length)
            else -> {
                diagnostics.addBounded(source.diagnostic())
                return SurfaceSelectorResult(
                    SurfaceSelection(linkedSetOf(), emptySet()),
                    SurfaceBlockMode.DEFINE,
                    diagnostics,
                )
            }
        }

        val included = linkedSetOf<Int>()
        val excluded = linkedSetOf<Int>()
        var work = 0L
        var exhausted = false
        var localExhaustionReported = false
        fun charge(amount: Long): SurfaceBudgetCharge {
            if (amount < 0L || work + amount > MAX_SELECTOR_WORK) {
                val report = !localExhaustionReported
                localExhaustionReported = true
                return SurfaceBudgetCharge(accepted = false, report = report)
            }
            val sharedCharge = sharedBudget?.charge(amount)
            if (sharedCharge != null && !sharedCharge.accepted) return sharedCharge
            work += amount
            return SurfaceBudgetCharge(accepted = true)
        }
        for (rawToken in body.splitToSequence(',')) {
            val tokenCharge = charge(1L)
            if (!tokenCharge.accepted) {
                exhausted = true
                if (tokenCharge.report) diagnostics.addBounded(source.diagnostic(rawToken.trim()))
                break
            }
            var token = rawToken.trim()
            val isExclusion = token.startsWith('!')
            if (isExclusion) token = token.substring(1).trim()
            if (token.lowercase(Locale.ROOT).startsWith(NORMAL_PREFIX)) {
                token = token.substring(NORMAL_PREFIX.length)
            }
            val range = parseRange(token)
            if (range == null) {
                diagnostics.addBounded(source.diagnostic(rawToken.trim()))
                continue
            }
            val size = range.last.toLong() - range.first.toLong() + 1L
            val expansionWork = size - 1L
            val expansionCharge = charge(expansionWork)
            if (size <= 0L || !expansionCharge.accepted) {
                if (!expansionCharge.accepted) exhausted = true
                if (size <= 0L || expansionCharge.report) {
                    diagnostics.addBounded(source.diagnostic(rawToken.trim()))
                }
                continue
            }
            range.forEach { id ->
                if (isExclusion) {
                    excluded += id
                    included -= id
                } else if (id !in excluded) {
                    included += id
                }
            }
        }
        return SurfaceSelectorResult(
            SurfaceSelection(included, excluded),
            mode,
            diagnostics,
            exhausted,
        )
    }

    private fun parseRange(token: String): IntRange? {
        val match = RANGE.matchEntire(token) ?: return null
        val first = match.groupValues[1].toIntOrNull() ?: return null
        val last = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: first
        return if (first <= last) first..last else null
    }

    private fun SourceLine.diagnostic(value: String = text) =
        SurfaceParseDiagnostic(file, number, value, SurfaceDiagnosticReason.SELECTOR)

    private fun MutableList<SurfaceParseDiagnostic>.addBounded(value: SurfaceParseDiagnostic) {
        if (size < MAX_DIAGNOSTICS) add(value)
    }

    private companion object {
        const val NORMAL_PREFIX = "surface"
        const val APPEND_PREFIX = "surface.append"
        const val MAX_SELECTOR_WORK = 10_000L
        const val MAX_DIAGNOSTICS = 256
        val RANGE = Regex("^(\\d+)(?:-(\\d+))?$")
    }
}

internal fun interface SurfaceSelectorWorkBudget {
    fun charge(amount: Long): SurfaceBudgetCharge
}

internal data class SurfaceBudgetCharge(
    val accepted: Boolean,
    val report: Boolean = false,
)
