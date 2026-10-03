package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.engine.ShioriEvent

data class ChoiceItem(val key: Long, val label: String, val choiceId: String, val textOffset: Int,
    val references: List<String> = emptyList()) {
    /** Case-sensitive On-prefixed IDs raise their own event; others go through OnChoiceSelect. */
    val event: ShioriEvent
        get() = if (isEvent(choiceId)) ShioriEvent(choiceId, references)
            else ShioriEvent("OnChoiceSelect", listOf(choiceId))

    companion object {
        fun isEvent(choiceId: String): Boolean = choiceId.startsWith("On")
    }
}

data class InputRequest(val key: Long, val boxId: String)

data class AnimationRequest(val speaker: Int, val animationId: Int)

data class SurfaceChange(val sakuraId: Int, val keroId: Int)

object ScriptInput {
    /** Input-box text is one line of printable characters. */
    fun isAllowed(text: String): Boolean =
        text.none { Character.isISOControl(it) || it == '\u2028' || it == '\u2029' }
}
