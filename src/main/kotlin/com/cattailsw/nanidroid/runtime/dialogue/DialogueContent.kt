package com.cattailsw.nanidroid.runtime.dialogue

import com.cattailsw.nanidroid.runtime.GhostSpeaker

sealed interface DialogueAction {
    data class Normal(
        val label: String,
        val id: String,
        val extraReferences: List<String>,
    ) : DialogueAction

    data class DirectEvent(
        val label: String,
        val eventId: String,
        val references: List<String>,
    ) : DialogueAction

    data class Script(val label: String, val sakuraScript: String) : DialogueAction
}

sealed interface AnchorAction {
    data class Normal(
        val label: String,
        val id: String,
        val extraReferences: List<String>,
    ) : AnchorAction

    data class DirectEvent(
        val label: String,
        val eventId: String,
        val references: List<String>,
    ) : AnchorAction
}

sealed interface InputDispatch {
    data class Normal(val id: String) : InputDispatch
    data class DirectEvent(val eventId: String) : InputDispatch
}

enum class InputBehavior { PASSWORD, MULTILINE, NO_EMPTY, NO_CANCEL }

data class InputBoxSpec(
    val dispatch: InputDispatch,
    val timeoutMillis: Long?,
    val initialText: String,
    val behaviorOptions: Set<InputBehavior>,
    val supplement: String,
    val extraReferences: List<String>,
    val unknownOptions: List<String>,
)

sealed interface DialogueSegment {
    data class Text(val value: String) : DialogueSegment
    data object NewLine : DialogueSegment
    data class Wait(val millis: Long) : DialogueSegment
    data object Clear : DialogueSegment
    /** Clears a re-entered speaker visually without erasing authored control inventory. */
    data object SpeakerChangeClear : DialogueSegment
    data class Choice(val action: DialogueAction) : DialogueSegment
    data class Anchor(val action: AnchorAction) : DialogueSegment
    data class ExternalUrl(val label: String, val uri: String) : DialogueSegment
    data class InputBox(val spec: InputBoxSpec) : DialogueSegment
    data class PassiveMode(val entering: Boolean) : DialogueSegment
}

data class DialogueContent(val speaker: GhostSpeaker, val segments: List<DialogueSegment>)

/** Immutable runtime-owned action state; UI hosts only observe a snapshot. */
data class DialogueRuntimeState(
    val revision: Long = 0L,
    /** Changes whenever the live ghost/session clears dialogue ownership. */
    val incarnation: Long = 0L,
    /** Changes only when the runner starts a new authored dialogue payload. */
    val talkId: Long = 0L,
    val contents: List<DialogueContent> = emptyList(),
    val pendingChoices: List<DialogueAction> = emptyList(),
    val pendingInput: PendingInputState? = null,
)

data class PendingInputState(
    val generation: Long,
    val spec: InputBoxSpec,
    val deadlineElapsedMillis: Long,
    /** Stable speaker ownership survives unrelated authored talks that carry this capability. */
    val owner: GhostSpeaker? = null,
)
