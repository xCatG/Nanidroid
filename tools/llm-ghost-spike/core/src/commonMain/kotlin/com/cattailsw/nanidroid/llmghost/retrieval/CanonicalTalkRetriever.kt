package com.cattailsw.nanidroid.llmghost.retrieval

import com.cattailsw.nanidroid.llmghost.model.CanonicalTalk
import com.cattailsw.nanidroid.llmghost.model.GenerationScenario
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId
import com.cattailsw.nanidroid.llmghost.model.ScenarioKind
import com.cattailsw.nanidroid.llmghost.model.TalkCategory

data class RetrievedExample(
    val talk: CanonicalTalk,
    val score: Int,
    val reasons: List<String>,
)

class CanonicalTalkRetriever {
    fun retrieve(
        talks: List<CanonicalTalk>,
        scenario: GenerationScenario,
        limit: Int,
    ): List<RetrievedExample> {
        if (limit <= 0) return emptyList()

        val ranked = talks
            .map { score(it, scenario) }
            .sortedWith(
                compareByDescending<RetrievedExample> { it.score }
                    .thenBy { it.talk.sourcePath }
                    .thenBy { it.talk.sourceLine }
                    .thenBy { it.talk.id },
            )

        val selected = mutableListOf<RetrievedExample>()
        val selectedIds = mutableSetOf<String>()
        val sourceCounts = mutableMapOf<String, Int>()
        ranked.forEach { example ->
            if (selected.size < limit && sourceCounts.getOrDefault(example.talk.sourcePath, 0) < 2) {
                selected += example
                selectedIds += example.talk.id
                sourceCounts[example.talk.sourcePath] = sourceCounts.getOrDefault(example.talk.sourcePath, 0) + 1
            }
        }
        ranked.forEach { example ->
            if (selected.size < limit && example.talk.id !in selectedIds) {
                selected += example
                selectedIds += example.talk.id
            }
        }
        return selected
    }

    private fun score(talk: CanonicalTalk, scenario: GenerationScenario): RetrievedExample {
        var score = 0
        val reasons = mutableListOf<String>()

        if (talk.category == scenario.category()) {
            score += CATEGORY_SCORE
            reasons += "category"
        }
        if (talk.touchSpeaker != null && talk.touchSpeaker == scenario.touchSpeaker) {
            score += TOUCH_SPEAKER_SCORE
            reasons += "touch-speaker"
        }
        if (talk.touchRegion != null && talk.touchRegion == scenario.touchRegion) {
            score += TOUCH_REGION_SCORE
            reasons += "touch-region"
        }
        if (scenario.kind == ScenarioKind.CONTINUATION && talk.id == scenario.canonicalTalkId) {
            score += CONTINUATION_SOURCE_SCORE
            reasons += "continuation-source"
        }
        topicTokens(scenario.topic).forEach { token ->
            if (talk.searchableText().contains(token)) {
                score += TOPIC_TOKEN_SCORE
                reasons += "topic-token:$token"
            }
        }
        if (talk.turns.map { it.speaker }.toSet().containsAll(SPEAKERS)) {
            score += BOTH_SPEAKER_SCORE
            reasons += "both-speakers"
        }

        return RetrievedExample(talk, score, reasons)
    }

    private fun GenerationScenario.category(): TalkCategory? = when (kind) {
        ScenarioKind.IDLE -> TalkCategory.IDLE
        ScenarioKind.POINTER_EVENT -> TalkCategory.TOUCH
        ScenarioKind.CONTINUATION -> null
    }

    private fun CanonicalTalk.searchableText(): String = buildString {
        append(heading.orEmpty())
        turns.forEach { append(' ').append(it.text) }
    }.normalized()

    private fun topicTokens(topic: String): List<String> = topic
        .normalized()
        .split(TOKEN_BOUNDARY)
        .filter { it.isNotEmpty() }
        .distinct()

    private fun String.normalized(): String = lowercase()

    private companion object {
        const val CATEGORY_SCORE = 100
        const val TOUCH_SPEAKER_SCORE = 80
        const val TOUCH_REGION_SCORE = 80
        const val CONTINUATION_SOURCE_SCORE = 120
        const val TOPIC_TOKEN_SCORE = 10
        const val BOTH_SPEAKER_SCORE = 20

        val TOKEN_BOUNDARY = Regex("[^\\p{L}\\p{N}]+")
        val SPEAKERS = setOf(GhostSpeakerId.SAKURA, GhostSpeakerId.KERO)
    }
}
