package com.cattailsw.nanidroid.runtime.dialogue

import com.cattailsw.nanidroid.runtime.GhostSpeaker

internal data class SakuraScriptTokenization(
    val contents: List<DialogueContent>,
    val interactions: List<SakuraScriptInteraction>,
)

internal data class SakuraScriptInteraction(
    val sourceEnd: Int,
    val scope: Int,
    val speaker: GhostSpeaker,
    val action: DialogueAction,
)

internal fun SakuraScriptTokenizer.tokenizeWithInteractions(
    script: String,
    onDiagnostic: (String) -> Unit = {},
): SakuraScriptTokenization = tokenizeInternal(script, false, onDiagnostic)
