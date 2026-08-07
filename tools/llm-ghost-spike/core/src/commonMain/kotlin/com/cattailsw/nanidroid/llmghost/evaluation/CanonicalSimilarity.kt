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
)

class SimilarityBudgetExceededException(
    val completedComparisons: Int,
    val completedDpCells: Long,
) : Exception(
    "Canonical similarity budget exceeded after $completedComparisons comparisons " +
        "and $completedDpCells DP cells.",
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

        val canonicalByText = linkedMapOf<List<Int>, CanonicalSource>()
        canonicalTalks.forEach { talk ->
            talk.turns.forEach { turn ->
                canonicalByText.putIfAbsent(normalize(turn.text), CanonicalSource(talk.id, turn))
            }
        }
        if (canonicalByText.isEmpty()) return emptyList()

        val normalizedGenerated = generatedTurns.map { normalize(it.text) }
        var completedComparisons = 0
        var completedDpCells = 0L
        val findings = mutableListOf<SimilarityFinding>()

        normalizedGenerated.forEachIndexed { generatedIndex, generated ->
            val exactSource = canonicalByText[generated]
            if (exactSource != null) {
                findings += finding(generatedTurns, generatedIndex, generatedIndex, exactSource, true, 1.0)
                return@forEachIndexed
            }

            var strongestSource = canonicalByText.values.first()
            var strongestRatio = 0.0
            canonicalByText.forEach { (canonical, source) ->
                if (!canReachNearCopyThreshold(generated.size, canonical.size)) return@forEach
                val cells = generated.size.toLong() * canonical.size.toLong()
                if (completedComparisons >= budget.maxComparisons ||
                    cells > budget.maxDpCells - completedDpCells
                ) {
                    throw SimilarityBudgetExceededException(completedComparisons, completedDpCells)
                }
                completedComparisons++
                completedDpCells += cells
                val ratio = similarityRatio(generated, canonical)
                if (ratio > strongestRatio) {
                    strongestSource = source
                    strongestRatio = ratio
                }
            }
            findings += finding(
                generatedTurns,
                generatedIndex,
                generatedIndex,
                strongestSource,
                exact = false,
                ratio = strongestRatio,
            )
        }

        for (start in normalizedGenerated.indices) {
            val joined = mutableListOf<Int>()
            val lastEnd = minOf(normalizedGenerated.lastIndex, start + MAX_ADJACENT_WINDOW - 1)
            for (end in start..lastEnd) {
                joined += normalizedGenerated[end]
                if (end == start) continue
                val source = canonicalByText[joined] ?: continue
                findings += finding(generatedTurns, start, end, source, exact = true, ratio = 1.0)
            }
        }
        return findings
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

    private fun normalize(text: String): List<Int> {
        val decomposed = ArrayList<Int>(text.length)
        try {
            UnicodeSecurity.forEachScalar(text) { scalar ->
                UnicodeNfkdData.appendDecomposed(scalar, decomposed, MAX_NORMALIZED_SCALARS)
            }
        } catch (limit: UnicodeExpansionLimitException) {
            throw UnsafeSimilarityTextException(
                sourceCode = "nfkd-expansion-limit",
                detail = limit.message ?: "NFKD expansion limit exceeded.",
            )
        }
        val scalars = ArrayList<Int>(decomposed.size)
        decomposed.forEach { scalar ->
            if (UnicodeSecurity.isInvisibleFormat(scalar)) return@forEach
            if (scalar.isSeparatorOrPunctuation()) return@forEach
            scalars += scalar
        }
        reorderCanonically(scalars)
        scalars.indices.forEach { index -> scalars[index] = scalars[index].foldLatinCase() }
        return scalars
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

    private fun similarityRatio(left: List<Int>, right: List<Int>): Double {
        val denominator = left.size + right.size
        if (denominator == 0) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return 2.0 * longestCommonSubsequenceLength(left, right) / denominator
    }

    private fun longestCommonSubsequenceLength(left: List<Int>, right: List<Int>): Int {
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

    private const val MAX_NORMALIZED_SCALARS = 1_500_000
    private val NORMALIZED_SEPARATORS = Regex("[\\p{P}\\p{Z}\\s]")
}
