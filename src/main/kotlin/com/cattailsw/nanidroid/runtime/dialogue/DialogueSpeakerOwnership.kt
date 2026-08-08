package com.cattailsw.nanidroid.runtime.dialogue

import com.cattailsw.nanidroid.runtime.GhostSpeaker
import java.util.IdentityHashMap

/**
 * Immutable UI projection that keeps runtime-owned action instances intact.
 * Data-class equality is deliberately never used as an action capability.
 */
class DialogueSpeakerOwnership private constructor(
    private val contents: Map<GhostSpeaker, DialogueContent>,
    private val pendingChoices: List<DialogueAction>,
    private val choiceOwners: Map<DialogueAction, GhostSpeaker>,
    private val pendingInputState: PendingInputState?,
    val pendingInputOwner: GhostSpeaker?,
) {
    fun content(speaker: GhostSpeaker): DialogueContent =
        contents[speaker] ?: DialogueContent(speaker, emptyList())

    fun pendingChoices(speaker: GhostSpeaker): List<DialogueAction> =
        pendingChoices.filter { action -> choiceOwner(action) == speaker }

    fun currentChoiceOrNull(action: DialogueAction): DialogueAction? =
        pendingChoices.firstOrNull { candidate -> candidate === action }

    fun pendingInput(speaker: GhostSpeaker): PendingInputState? =
        pendingInputState?.takeIf { pendingInputOwner == speaker }

    private fun choiceOwner(action: DialogueAction): GhostSpeaker? =
        choiceOwners[action]

    companion object {
        fun from(state: DialogueRuntimeState): DialogueSpeakerOwnership {
            val contentBySpeaker = GhostSpeaker.entries.associateWith { speaker ->
                DialogueContent(
                    speaker,
                    state.contents.asSequence()
                        .filter { it.speaker == speaker }
                        .flatMap { it.segments.asSequence() }
                        .fold(mutableListOf<DialogueSegment>()) { visible, segment ->
                            if (segment is DialogueSegment.Clear || segment is DialogueSegment.SpeakerChangeClear) {
                                visible.clear()
                            } else {
                                visible += segment
                            }
                            visible
                        },
                )
            }
            val choiceOwners = IdentityHashMap<DialogueAction, GhostSpeaker>().apply {
                state.contents.forEach { content ->
                    content.segments.forEach { segment ->
                        val action = (segment as? DialogueSegment.Choice)?.action ?: return@forEach
                        put(action, content.speaker)
                    }
                }
            }
            val input = state.pendingInput
            val inputOwner = input?.let { pending ->
                pending.owner ?: state.contents.firstNotNullOfOrNull { content ->
                    content.speaker.takeIf {
                        content.segments.any { segment ->
                            (segment as? DialogueSegment.InputBox)?.spec === pending.spec
                        }
                    }
                }
            }
            return DialogueSpeakerOwnership(
                contents = contentBySpeaker,
                pendingChoices = state.pendingChoices.toList(),
                choiceOwners = choiceOwners,
                pendingInputState = input,
                pendingInputOwner = inputOwner,
            )
        }
    }
}
