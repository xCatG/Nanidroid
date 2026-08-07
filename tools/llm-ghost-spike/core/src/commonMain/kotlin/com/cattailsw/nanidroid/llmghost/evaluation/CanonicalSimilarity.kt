package com.cattailsw.nanidroid.llmghost.evaluation

import com.cattailsw.nanidroid.llmghost.generation.UnicodeExpansionLimitException
import com.cattailsw.nanidroid.llmghost.generation.UnicodeNfkdData
import com.cattailsw.nanidroid.llmghost.generation.UnicodeSecurity
import com.cattailsw.nanidroid.llmghost.model.CanonicalTalk
import com.cattailsw.nanidroid.llmghost.model.CanonicalTurn
import com.cattailsw.nanidroid.llmghost.model.GeneratedTurn
import kotlinx.serialization.Serializable

@Serializable
data class SimilarityFinding(
    val generatedTurn: GeneratedTurn,
    val canonicalTalkId: String,
    val canonicalTurn: CanonicalTurn,
    val exact: Boolean,
    val ratio: Double,
    val generatedTurnStartIndex: Int = 0,
    val generatedTurnEndIndex: Int = generatedTurnStartIndex,
)

data class SimilarityBudget(
    val maxComparisons: Int = 250_000,
    val maxDpCells: Long = 50_000_000,
    val maxCanonicalRawScalars: Int = 2_000_000,
    val maxCanonicalNormalizedScalars: Int = 3_000_000,
    val maxRetainedCanonicalScalars: Int = 1_000_000,
    val maxRetainedCanonicalEntries: Int = 50_000,
    val maxGeneratedNormalizedScalars: Int = 4_000,
    val maxGeneratedEntries: Int = 8,
    val maxGeneratedRawScalarsPerTurn: Int = 500,
)

class SimilarityBudgetExceededException(
    val completedComparisons: Int,
    val completedDpCells: Long,
    val limitCode: String = "dp-work",
    val canonicalRawScalarsExamined: Int = 0,
    val canonicalNormalizedScalars: Int = 0,
    val retainedCanonicalScalars: Int = 0,
    val retainedCanonicalEntries: Int = 0,
    val generatedRawScalarsExamined: Int = 0,
) : Exception(
    "Canonical similarity budget '$limitCode' exceeded after $completedComparisons comparisons, " +
        "$completedDpCells DP cells, $canonicalRawScalarsExamined raw canonical scalars, " +
        "$canonicalNormalizedScalars normalized canonical scalars, " +
        "$retainedCanonicalScalars retained canonical scalars, and " +
        "$retainedCanonicalEntries retained canonical entries.",
)

class UnsafeSimilarityTextException(
    val sourceCode: String,
    detail: String,
) : Exception(
    detail,
) {
    constructor(codePoint: Int) : this(
        sourceCode = "U+${codePoint.toString(16).uppercase()}",
        detail = "Canonical similarity cannot safely normalize scalar " +
            "U+${codePoint.toString(16).uppercase()}.",
    )
}

object CanonicalSimilarity {
    const val NEAR_COPY_THRESHOLD = 0.90
    private const val MAX_ADJACENT_WINDOW = 8

    fun evaluate(
        generatedTurns: List<GeneratedTurn>,
        canonicalTalks: List<CanonicalTalk>,
        budget: SimilarityBudget = SimilarityBudget(),
    ): List<SimilarityFinding> {
        require(budget.maxComparisons >= 0) { "maxComparisons must not be negative." }
        require(budget.maxDpCells >= 0) { "maxDpCells must not be negative." }
        require(budget.maxCanonicalRawScalars >= 0) { "maxCanonicalRawScalars must not be negative." }
        require(budget.maxCanonicalNormalizedScalars >= 0) {
            "maxCanonicalNormalizedScalars must not be negative."
        }
        require(budget.maxRetainedCanonicalScalars >= 0) {
            "maxRetainedCanonicalScalars must not be negative."
        }
        require(budget.maxRetainedCanonicalEntries >= 0) {
            "maxRetainedCanonicalEntries must not be negative."
        }
        require(budget.maxGeneratedNormalizedScalars >= 0) {
            "maxGeneratedNormalizedScalars must not be negative."
        }
        require(budget.maxGeneratedEntries >= 0) { "maxGeneratedEntries must not be negative." }
        require(budget.maxGeneratedRawScalarsPerTurn >= 0) {
            "maxGeneratedRawScalarsPerTurn must not be negative."
        }

        val work = SimilarityWork(budget)
        val canonicalByText = linkedMapOf<String, CanonicalCandidate>()
        canonicalTalks.forEach { talk ->
            talk.turns.forEach { turn ->
                work.examineCanonicalRaw(turn.text)
                val normalized = normalize(turn.text, work)
                val key = scalarKey(normalized)
                if (key !in canonicalByText) {
                    work.retainCanonical(normalized.size)
                    canonicalByText[key] = CanonicalCandidate(
                        source = CanonicalSource(talk.id, turn),
                        scalars = normalized,
                    )
                }
            }
        }
        if (canonicalByText.isEmpty()) return emptyList()

        if (generatedTurns.size > budget.maxGeneratedEntries) work.exceeded("generated-entries")
        var generatedScalarCount = 0
        val normalizedGenerated = generatedTurns.map { turn ->
            work.examineGeneratedRaw(turn.text)
            normalize(turn.text).also { normalized ->
                if (normalized.size > budget.maxGeneratedNormalizedScalars - generatedScalarCount) {
                    work.exceeded("generated-normalized-scalars")
                }
                generatedScalarCount += normalized.size
            }
        }
        val findings = mutableListOf<SimilarityFinding>()
        for (start in normalizedGenerated.indices) {
            var joined = IntArray(0)
            val lastEnd = minOf(normalizedGenerated.lastIndex, start + MAX_ADJACENT_WINDOW - 1)
            for (end in start..lastEnd) {
                joined = joined.concat(normalizedGenerated[end])
                findings += evaluateWindow(
                    generatedTurns = generatedTurns,
                    start = start,
                    end = end,
                    generated = joined,
                    canonicalByText = canonicalByText,
                    work = work,
                )
            }
        }
        return findings
    }

    private fun evaluateWindow(
        generatedTurns: List<GeneratedTurn>,
        start: Int,
        end: Int,
        generated: IntArray,
        canonicalByText: Map<String, CanonicalCandidate>,
        work: SimilarityWork,
    ): SimilarityFinding {
        val exact = canonicalByText[scalarKey(generated)]
        if (exact != null) {
            return finding(generatedTurns, start, end, exact.source, exact = true, ratio = 1.0)
        }

        var strongest = canonicalByText.values.first()
        var strongestRatio = 0.0
        canonicalByText.values.forEach { canonical ->
            if (!canReachNearCopyThreshold(generated.size, canonical.scalars.size)) return@forEach
            val cells = generated.size.toLong() * canonical.scalars.size.toLong()
            work.consumeDp(cells)
            val ratio = similarityRatio(generated, canonical.scalars)
            if (ratio > strongestRatio) {
                strongest = canonical
                strongestRatio = ratio
            }
        }
        return finding(generatedTurns, start, end, strongest.source, exact = false, ratio = strongestRatio)
    }

    private fun finding(
        generatedTurns: List<GeneratedTurn>,
        start: Int,
        end: Int,
        source: CanonicalSource,
        exact: Boolean,
        ratio: Double,
    ) = SimilarityFinding(
        generatedTurn = generatedTurns[start],
        canonicalTalkId = source.talkId,
        canonicalTurn = source.turn,
        exact = exact,
        ratio = ratio,
        generatedTurnStartIndex = start,
        generatedTurnEndIndex = end,
    )

    private fun canReachNearCopyThreshold(leftSize: Int, rightSize: Int): Boolean {
        val denominator = leftSize.toLong() + rightSize.toLong()
        if (denominator == 0L) return true
        val maximumRatio = 2.0 * minOf(leftSize, rightSize) / denominator
        return maximumRatio >= NEAR_COPY_THRESHOLD
    }

    private fun normalize(text: String, work: SimilarityWork? = null): IntArray {
        val decomposed = ArrayList<Int>(text.length)
        UnicodeSecurity.forEachScalar(text) { scalar ->
            val previousSize = decomposed.size
            val aggregateRemaining = work?.let {
                it.budget.maxCanonicalNormalizedScalars - it.canonicalNormalizedScalars
            } ?: MAX_NORMALIZED_SCALARS
            val maximumSize = minOf(MAX_NORMALIZED_SCALARS, previousSize + aggregateRemaining)
            try {
                UnicodeNfkdData.appendDecomposed(scalar, decomposed, maximumSize)
            } catch (limit: UnicodeExpansionLimitException) {
                if (work != null) {
                    work.canonicalNormalizedScalars += decomposed.size - previousSize
                    if (aggregateRemaining <= MAX_NORMALIZED_SCALARS) {
                        work.exceeded("canonical-normalized-scalars")
                    }
                }
                throw UnsafeSimilarityTextException(
                    sourceCode = "nfkd-expansion-limit",
                    detail = limit.message ?: "NFKD expansion limit exceeded.",
                )
            }
            work?.let { it.canonicalNormalizedScalars += decomposed.size - previousSize }
        }
        val scalars = ArrayList<Int>(decomposed.size)
        decomposed.forEach { scalar ->
            if (UnicodeSecurity.isInvisibleFormat(scalar)) return@forEach
            if (scalar.isSeparatorOrPunctuation()) return@forEach
            scalars += scalar
        }
        reorderCanonically(scalars)
        scalars.indices.forEach { index -> scalars[index] = scalars[index].foldLatinCase() }
        return scalars.toIntArray()
    }

    private fun reorderCanonically(scalars: MutableList<Int>) {
        var segmentStart = 0
        while (segmentStart < scalars.size) {
            val marksStart = if (UnicodeNfkdData.combiningClass(scalars[segmentStart]) == 0) {
                segmentStart + 1
            } else {
                segmentStart
            }
            var segmentEnd = marksStart
            while (segmentEnd < scalars.size && UnicodeNfkdData.combiningClass(scalars[segmentEnd]) != 0) {
                segmentEnd++
            }
            reorderMarks(scalars, marksStart, segmentEnd)
            segmentStart = segmentEnd
        }
    }

    private fun reorderMarks(scalars: MutableList<Int>, start: Int, end: Int) {
        if (end - start < 2) return
        val counts = IntArray(256)
        for (index in start until end) counts[UnicodeNfkdData.combiningClass(scalars[index])]++
        var next = 0
        for (combiningClass in counts.indices) {
            val count = counts[combiningClass]
            counts[combiningClass] = next
            next += count
        }
        val ordered = IntArray(end - start)
        for (index in start until end) {
            val scalar = scalars[index]
            val combiningClass = UnicodeNfkdData.combiningClass(scalar)
            ordered[counts[combiningClass]++] = scalar
        }
        ordered.forEachIndexed { offset, scalar -> scalars[start + offset] = scalar }
    }

    private fun similarityRatio(left: IntArray, right: IntArray): Double {
        val denominator = left.size + right.size
        if (denominator == 0) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return 2.0 * longestCommonSubsequenceLength(left, right) / denominator
    }

    private fun longestCommonSubsequenceLength(left: IntArray, right: IntArray): Int {
        val columns = if (left.size <= right.size) left else right
        val rows = if (left.size <= right.size) right else left
        var previous = IntArray(columns.size + 1)
        var current = IntArray(columns.size + 1)
        rows.forEach { row ->
            columns.forEachIndexed { index, column ->
                current[index + 1] = if (row == column) previous[index] + 1
                else maxOf(previous[index + 1], current[index])
            }
            val swap = previous
            previous = current
            current = swap
            current.fill(0)
        }
        return previous[columns.size]
    }

    private fun Int.foldLatinCase(): Int {
        if (this > Char.MAX_VALUE.code) return this
        val character = toChar()
        val lowercase = character.lowercaseChar()
        return if (character.isLatinCharacter() || lowercase.isLatinCharacter()) lowercase.code else this
    }

    private fun Char.isLatinCharacter(): Boolean = isLetter() && (
        this in 'A'..'Z' || this in 'a'..'z' || this in '\u00C0'..'\u024F' ||
            this in '\u1D00'..'\u1DBF' || this in '\u1E00'..'\u1EFF' ||
            this in '\u2C60'..'\u2C7F' || this in '\uA720'..'\uA7FF' ||
            this in '\uAB30'..'\uAB6F' || this in '\uFB00'..'\uFB06' ||
            this in '\uFF21'..'\uFF5A'
        )

    private fun Int.isSeparatorOrPunctuation(): Boolean {
        if (this > Char.MAX_VALUE.code) return false
        val character = toChar()
        return character.isWhitespace() || NORMALIZED_SEPARATORS.matches(character.toString())
    }

    private data class CanonicalSource(val talkId: String, val turn: CanonicalTurn)

    private data class CanonicalCandidate(
        val source: CanonicalSource,
        val scalars: IntArray,
    )

    private class SimilarityWork(val budget: SimilarityBudget) {
        var completedComparisons = 0
        var completedDpCells = 0L
        var canonicalRawScalarsExamined = 0
        var canonicalNormalizedScalars = 0
        var retainedCanonicalScalars = 0
        var retainedCanonicalEntries = 0
        var generatedRawScalarsExamined = 0

        fun examineCanonicalRaw(text: String) {
            UnicodeSecurity.forEachScalar(text) {
                if (canonicalRawScalarsExamined >= budget.maxCanonicalRawScalars) {
                    exceeded("canonical-raw-scalars")
                }
                canonicalRawScalarsExamined++
            }
        }

        fun retainCanonical(scalarCount: Int) {
            if (retainedCanonicalEntries >= budget.maxRetainedCanonicalEntries) {
                exceeded("canonical-retained-entries")
            }
            if (scalarCount > budget.maxRetainedCanonicalScalars - retainedCanonicalScalars) {
                exceeded("canonical-retained-scalars")
            }
            retainedCanonicalEntries++
            retainedCanonicalScalars += scalarCount
        }

        fun examineGeneratedRaw(text: String) {
            var turnScalars = 0
            UnicodeSecurity.forEachScalar(text) {
                if (turnScalars >= budget.maxGeneratedRawScalarsPerTurn) {
                    exceeded("generated-raw-scalars")
                }
                turnScalars++
                generatedRawScalarsExamined++
            }
        }

        fun consumeDp(cells: Long) {
            if (completedComparisons >= budget.maxComparisons ||
                cells > budget.maxDpCells - completedDpCells
            ) {
                exceeded("dp-work")
            }
            completedComparisons++
            completedDpCells += cells
        }

        fun exceeded(limitCode: String): Nothing = throw SimilarityBudgetExceededException(
            completedComparisons = completedComparisons,
            completedDpCells = completedDpCells,
            limitCode = limitCode,
            canonicalRawScalarsExamined = canonicalRawScalarsExamined,
            canonicalNormalizedScalars = canonicalNormalizedScalars,
            retainedCanonicalScalars = retainedCanonicalScalars,
            retainedCanonicalEntries = retainedCanonicalEntries,
            generatedRawScalarsExamined = generatedRawScalarsExamined,
        )
    }

    private fun scalarKey(scalars: IntArray): String = buildString(scalars.size) {
        scalars.forEach { scalar ->
            if (scalar <= Char.MAX_VALUE.code) {
                append(scalar.toChar())
            } else {
                val value = scalar - 0x10000
                append((0xD800 + (value shr 10)).toChar())
                append((0xDC00 + (value and 0x3FF)).toChar())
            }
        }
    }

    private fun IntArray.concat(other: IntArray): IntArray =
        IntArray(size + other.size).also { joined ->
            copyInto(joined)
            other.copyInto(joined, destinationOffset = size)
        }

    private const val MAX_NORMALIZED_SCALARS = 1_500_000
    private val NORMALIZED_SEPARATORS = Regex("[\\p{P}\\p{Z}\\s]")
}
