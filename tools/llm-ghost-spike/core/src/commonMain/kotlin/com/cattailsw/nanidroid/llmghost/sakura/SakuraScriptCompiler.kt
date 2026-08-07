package com.cattailsw.nanidroid.llmghost.sakura

import com.cattailsw.nanidroid.llmghost.generation.TrustedDialogue
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId

data class SakuraScriptCompilation(val script: String)

class SakuraScriptCompiler {
    fun compile(dialogue: TrustedDialogue): SakuraScriptCompilation = SakuraScriptCompilation(
        script = buildString {
            dialogue.turns.forEach { turn ->
                append(
                    when (turn.speaker) {
                        GhostSpeakerId.SAKURA -> "\\0"
                        GhostSpeakerId.KERO -> "\\1"
                    },
                )
                append("\\s[")
                append(turn.surface)
                append(']')
                append(turn.text.replace("\\", "\\\\"))
                append("\\_w[")
                append(turn.waitAfterMs)
                append(']')
            }
            append("\\e")
        },
    )
}
