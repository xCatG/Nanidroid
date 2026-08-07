package com.cattailsw.nanidroid.llmghost.evaluation

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
)

object CanonicalSimilarity {
    const val NEAR_COPY_THRESHOLD = 0.90

    fun evaluate(
        generatedTurns: List<GeneratedTurn>,
        canonicalTalks: List<CanonicalTalk>,
    ): List<SimilarityFinding> = generatedTurns.mapNotNull { generatedTurn ->
        val generated = normalize(generatedTurn.text)
        var strongest: SimilarityFinding? = null
        canonicalTalks.forEach { talk ->
            talk.turns.forEach { canonicalTurn ->
                val canonical = normalize(canonicalTurn.text)
                val ratio = similarityRatio(generated, canonical)
                if (strongest == null || ratio > strongest!!.ratio) {
                    strongest = SimilarityFinding(
                        generatedTurn = generatedTurn,
                        canonicalTalkId = talk.id,
                        canonicalTurn = canonicalTurn,
                        exact = generated == canonical,
                        ratio = ratio,
                    )
                }
            }
        }
        strongest
    }

    private fun normalize(text: String): List<Int> {
        val compact = NORMALIZED_SEPARATORS.replace(text.lowercase(), "")
        val scalars = ArrayList<Int>(compact.length)
        var index = 0
        while (index < compact.length) {
            val first = compact[index]
            if (first in HIGH_SURROGATES && compact.getOrNull(index + 1) in LOW_SURROGATES) {
                val second = compact[index + 1]
                scalars += 0x10000 + ((first.code - 0xD800) shl 10) + (second.code - 0xDC00)
                index += 2
            } else {
                scalars += first.code
                index++
            }
        }
        return scalars
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
                current[index + 1] = if (row == column) {
                    previous[index] + 1
                } else {
                    maxOf(previous[index + 1], current[index])
                }
            }
            val swap = previous
            previous = current
            current = swap
            current.fill(0)
        }
        return previous[columns.size]
    }

    private val NORMALIZED_SEPARATORS = Regex("[\\p{P}\\p{Z}\\s]+")
    private val HIGH_SURROGATES = '\uD800'..'\uDBFF'
    private val LOW_SURROGATES = '\uDC00'..'\uDFFF'
}
