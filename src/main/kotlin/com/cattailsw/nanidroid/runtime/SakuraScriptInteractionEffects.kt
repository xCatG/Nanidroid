package com.cattailsw.nanidroid.runtime

import java.util.Collections

/** Non-presentation effects emitted by a Sakura Script interaction command. */
sealed interface SakuraScriptInteractionEffect {
    data class OpenInputBox(val id: String) : SakuraScriptInteractionEffect

    class ShowSelection(labels: List<String>, ids: List<String>) : SakuraScriptInteractionEffect {
        val labels: List<String> = Collections.unmodifiableList(ArrayList(labels))
        val ids: List<String> = Collections.unmodifiableList(ArrayList(ids))
    }
}

/**
 * Script text with interaction commands removed or normalized for presentation,
 * plus the effects that the runtime must dispatch to the host UI.
 */
class SakuraScriptInteractionResult(
    val presentationScript: String,
    effects: List<SakuraScriptInteractionEffect>,
) {
    val effects: List<SakuraScriptInteractionEffect> =
        Collections.unmodifiableList(ArrayList(effects))
}

/** Pure extraction of legacy input-box and choice effects. */
object SakuraScriptInteractionInterpreter {
    private val choice = Regex("\\\\q\\[([^,]*),([^,\\]]*),?([^\\]]*?)\\]")
    // Interaction extraction is retained for legacy callers, but input controls
    // must be independently consumable rather than greedily swallowing later tags.
    private val input = Regex("\\\\!\\[open,inputbox,([^\\]]*)]")

    private data class PositionedEffect(
        val position: Int,
        val effect: SakuraScriptInteractionEffect,
    )

    @JvmStatic
    fun extract(script: String): SakuraScriptInteractionResult {
        val effects = mutableListOf<PositionedEffect>()
        val labels = mutableListOf<String>()
        val ids = mutableListOf<String>()
        val inputMatches = input.findAll(script).toList()
        val withoutChoices = choice.replace(script) { match ->
            if (inputMatches.any { inputMatch -> match.range.first in inputMatch.range }) {
                return@replace match.value
            }
            labels += match.groupValues[1]
            ids += match.groupValues[2]
            match.groupValues[1]
        }
        if (labels.isNotEmpty()) {
            val firstChoice = choice.find(script)!!.range.first
            effects += PositionedEffect(
                firstChoice,
                SakuraScriptInteractionEffect.ShowSelection(labels, ids),
            )
        }
        inputMatches.forEach { match ->
            effects += PositionedEffect(
                match.range.first,
                SakuraScriptInteractionEffect.OpenInputBox(match.groupValues[1]),
            )
        }
        val presentationScript = input.replace(withoutChoices, "")
        return SakuraScriptInteractionResult(
            presentationScript,
            effects.sortedBy { it.position }.map { it.effect },
        )
    }
}
