package com.cattailsw.nanidroid.runtime.dialogue

import com.cattailsw.nanidroid.runtime.GhostSpeaker

internal data class SakuraScriptTokenization(
    val contents: List<DialogueContent>,
    val interactions: List<SakuraScriptInteraction>,
    val occurrences: List<SakuraScriptOccurrence>,
    val sourceVisits: Int,
)

internal data class SakuraScriptInteraction(
    val sourceEnd: Int,
    val scope: Int,
    val speaker: GhostSpeaker,
    val action: DialogueAction,
)

internal sealed interface SakuraScriptOccurrence {
    val sourceEnd: Int
    val speaker: GhostSpeaker

    data class Choice(
        override val sourceEnd: Int,
        override val speaker: GhostSpeaker,
        val action: DialogueAction,
    ) : SakuraScriptOccurrence

    data class Anchor(
        override val sourceEnd: Int,
        override val speaker: GhostSpeaker,
        val action: AnchorAction,
    ) : SakuraScriptOccurrence

    data class Input(
        override val sourceEnd: Int,
        override val speaker: GhostSpeaker,
        val spec: InputBoxSpec,
    ) : SakuraScriptOccurrence
}

internal fun SakuraScriptTokenizer.tokenizeWithInteractions(
    script: String,
    onDiagnostic: (String) -> Unit = {},
): SakuraScriptTokenization = tokenizeInternal(script, false, onDiagnostic)
