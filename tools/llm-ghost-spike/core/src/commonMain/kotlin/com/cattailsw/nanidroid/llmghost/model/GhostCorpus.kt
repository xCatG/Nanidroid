package com.cattailsw.nanidroid.llmghost.model

import kotlinx.serialization.Serializable

@Serializable
enum class GhostSpeakerId {
    SAKURA,
    KERO,
}

@Serializable
enum class OutputLanguage {
    JAPANESE,
    ENGLISH,
}

@Serializable
enum class ScenarioKind {
    IDLE,
    CONTINUATION,
    POINTER_EVENT,
}

@Serializable
enum class TalkCategory {
    IDLE,
    TOUCH,
    EVENT,
    OTHER,
}

@Serializable
data class GhostSourceFile(
    val path: String,
    val text: String,
)

@Serializable
data class GhostIdentity(
    val ghostName: String,
    val sakuraName: String,
    val keroName: String,
    val shellSurfaces: Map<GhostSpeakerId, Set<Int>>,
)

@Serializable
data class GhostCorpusInput(
    val identity: GhostIdentity,
    val files: List<GhostSourceFile>,
)

@Serializable
data class CanonicalTurn(
    val speaker: GhostSpeakerId,
    val surface: Int?,
    val text: String,
)

@Serializable
data class CanonicalTalk(
    val id: String,
    val sourcePath: String,
    val sourceLine: Int,
    val heading: String?,
    val category: TalkCategory,
    val touchSpeaker: GhostSpeakerId? = null,
    val touchRegion: String? = null,
    val turns: List<CanonicalTurn>,
)

@Serializable
data class GenerationScenario(
    val kind: ScenarioKind,
    val topic: String = "",
    val touchSpeaker: GhostSpeakerId? = null,
    val touchRegion: String? = null,
    val canonicalTalkId: String? = null,
)
