package com.cattailsw.nanidroid.runtime

/** Non-presentation effects emitted by a Sakura Script interaction command. */
sealed interface SakuraScriptInteractionEffect {
    data class OpenInputBox(val id: String) : SakuraScriptInteractionEffect

    data class ShowSelection(
        val labels: List<String>,
        val ids: List<String>,
    ) : SakuraScriptInteractionEffect
}

/**
 * Script text with interaction commands removed or normalized for presentation,
 * plus the effects that the runtime must dispatch to the host UI.
 */
data class SakuraScriptInteractionResult(
    val presentationScript: String,
    val effects: List<SakuraScriptInteractionEffect>,
)

/** Pure extraction of legacy input-box and choice effects. */
object SakuraScriptInteractionInterpreter {
    private val choice = Regex("\\\\q\\[([^,]*),([^,\\]]*),?([^\\]]*?)\\]")
    private val input = Regex("\\\\!\\[open,inputbox,(.*?)]")

    @JvmStatic
    fun extract(script: String): SakuraScriptInteractionResult {
        val effects = mutableListOf<SakuraScriptInteractionEffect>()
        val labels = mutableListOf<String>()
        val ids = mutableListOf<String>()
        val withoutChoices = choice.replace(script) { match ->
            labels += match.groupValues[1]
            ids += match.groupValues[2]
            match.groupValues[1]
        }
        if (labels.isNotEmpty()) {
            effects += SakuraScriptInteractionEffect.ShowSelection(labels, ids)
        }
        val presentationScript = input.replace(withoutChoices) { match ->
            effects += SakuraScriptInteractionEffect.OpenInputBox(match.groupValues[1])
            ""
        }
        return SakuraScriptInteractionResult(presentationScript, effects)
    }
}
