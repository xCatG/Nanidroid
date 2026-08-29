package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.ShioriRequestIntent
import com.cattailsw.nanidroid.ShioriResponse
import com.cattailsw.nanidroid.runtime.dialogue.AnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueActionKey
import com.cattailsw.nanidroid.runtime.dialogue.DialogueContent
import com.cattailsw.nanidroid.runtime.dialogue.DialogueRuntimeState
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.dialogue.InputDispatch
import com.cattailsw.nanidroid.runtime.dialogue.InputBoxSpec
import com.cattailsw.nanidroid.runtime.dialogue.PendingInputState
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeAnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeChoiceAction
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeInputAction
import com.cattailsw.nanidroid.runtime.dialogue.SakuraScriptCommandParser
import com.cattailsw.nanidroid.runtime.dialogue.SakuraScriptTokenizer
import com.cattailsw.nanidroid.runtime.dialogue.tokenizeWithInteractions
import java.util.Collections

internal data class PlayerPayload(
    val script: String,
    val parent: PlayerParent?,
)

internal data class PlayerCursor(
    val payload: PlayerPayload,
    val charIndex: Int,
    val adoptedElapsedMillis: Long,
    val speaker: GhostSpeaker,
    val waitMillis: Long,
    val wholeLine: Boolean,
    val quickSession: Boolean,
    val synchronizedSession: Boolean,
    val renderedFrameIndex: Int,
)

internal data class PlayerState(
    val generation: Long,
    val queue: List<PlayerPayload>,
    val current: PlayerCursor?,
    val presentation: RuntimePresentation,
    val dialogue: RuntimeDialogueSnapshot,
    val passive: Boolean,
    val authoredRequest: RuntimeRequestOrigin.Playback?,
    val playbackToken: Long,
    val nextActionId: Long,
    val talkingFrameIndex: Int,
) {
    companion object {
        fun initial(generation: Long): PlayerState = freeze(
            PlayerState(
                generation = generation,
                queue = emptyList(),
                current = null,
                presentation = RuntimePresentation(
                    sakura = RuntimeSpeakerPresentation("", "0", 0L, false),
                    kero = RuntimeSpeakerPresentation("", "10", 0L, false),
                    talkingAnimationEnabled = false,
                ),
                dialogue = RuntimeDialogueSnapshot(
                    state = DialogueRuntimeState(),
                    choices = emptyList(),
                    anchors = emptyList(),
                    input = null,
                ),
                passive = false,
                authoredRequest = null,
                playbackToken = 0L,
                nextActionId = 1L,
                talkingFrameIndex = 0,
            ),
        )
    }
}

internal sealed interface PlayerParent {
    data class Switch(val operationId: Long) : PlayerParent
    data class Exit(val operationId: Long) : PlayerParent
}

internal sealed interface PlayerCommand {
    data class Enqueue(val script: String, val parent: PlayerParent?) : PlayerCommand
    data class Advance(val token: Long, val elapsedMillis: Long) : PlayerCommand
    data class NativeResponse(val token: RuntimeRequestToken, val response: PlayerResponse) : PlayerCommand
    data class ActivateChoice(val key: DialogueActionKey) : PlayerCommand
    data class ActivateAnchor(val key: DialogueActionKey) : PlayerCommand
    data class SubmitInput(val key: DialogueActionKey, val value: String) : PlayerCommand
    data class DismissInput(val key: DialogueActionKey) : PlayerCommand
    data class InputExpired(val key: DialogueActionKey, val elapsedMillis: Long) : PlayerCommand
    data class Clear(val owner: PlayerParent?) : PlayerCommand
}

internal sealed interface PlayerResponse {
    data class Returned(val response: ShioriResponse) : PlayerResponse
    data object ReplayableFailure : PlayerResponse
    data object FatalFailure : PlayerResponse
    data object StaleGeneration : PlayerResponse
}

internal sealed interface PlayerEffect {
    data class SchedulePlayback(val token: Long, val delayMillis: Long) : PlayerEffect
    data class RequestShiori(
        val origin: RuntimeRequestOrigin,
        val intent: ShioriRequestIntent,
        val fallback: ShioriRequestIntent?,
    ) : PlayerEffect

    data class PresentationCue(
        val speaker: GhostSpeaker,
        val kind: RuntimeCueKind,
        val animationId: String?,
    ) : PlayerEffect

    data class ParentCompleted(val parent: PlayerParent) : PlayerEffect
    data class Failure(val parent: PlayerParent?, val reason: RuntimeNoticeCode) : PlayerEffect
}

internal data class PlayerTransition(val state: PlayerState, val effects: List<PlayerEffect>)

internal object SakuraScriptPlayer {
    private const val WAIT_UNIT = 50L
    private const val WAIT_YEN_E = 1_000L

    fun reduce(state: PlayerState, command: PlayerCommand): PlayerTransition = when (command) {
        is PlayerCommand.Enqueue -> enqueue(state, command)
        is PlayerCommand.Advance -> advance(state, command)
        is PlayerCommand.NativeResponse -> nativeResponse(state, command)
        is PlayerCommand.ActivateChoice -> activateChoice(state, command.key)
        is PlayerCommand.ActivateAnchor -> activateAnchor(state, command.key)
        is PlayerCommand.SubmitInput -> submitInput(state, command.key, command.value)
        is PlayerCommand.DismissInput -> cancelInput(state, command.key, "close", false)
        is PlayerCommand.InputExpired -> expireInput(state, command)
        is PlayerCommand.Clear -> clear(state, command.owner)
    }.frozen()

    private fun enqueue(state: PlayerState, command: PlayerCommand.Enqueue): PlayerTransition {
        val queued = state.queue + PlayerPayload(command.script, command.parent)
        if (state.current != null || state.authoredRequest != null || state.dialogue.input != null) {
            return transition(state.copy(queue = queued))
        }
        return schedule(state.copy(queue = queued), 0L)
    }

    private fun advance(state: PlayerState, command: PlayerCommand.Advance): PlayerTransition {
        if (command.token != state.playbackToken || state.authoredRequest != null || state.dialogue.input != null) {
            return transition(state)
        }
        return try {
            var adopted = state
            if (adopted.current == null) {
                if (adopted.queue.isEmpty()) return transition(adopted)
                adopted = adopt(adopted, command.elapsedMillis)
            } else if (adopted.current.charIndex >= adopted.current.payload.script.length) {
                val completed = completeCurrent(adopted, command.elapsedMillis)
                if (completed.state.current != null || completed.state.queue.isEmpty()) return completed
                adopted = adopt(completed.state, command.elapsedMillis)
                return schedule(adopted, WAIT_UNIT, completed.effects)
            }
            parseStep(adopted)
        } catch (_: Throwable) {
            failPlayback(state, state.current?.payload?.parent ?: state.queue.firstOrNull()?.parent)
        }
    }

    private fun adopt(
        state: PlayerState,
        elapsedMillis: Long,
    ): PlayerState {
        val payload = state.queue.first()
        val markers = actionMarkers(payload.script)
        val nextDialogue = state.dialogue.copy(
            state = DialogueRuntimeState(
                revision = state.dialogue.state.revision + 1,
                incarnation = state.dialogue.state.incarnation + 1,
                talkId = state.dialogue.state.talkId + 1,
                pendingInput = state.dialogue.state.pendingInput.takeIf {
                    markers.none { marker -> marker.kind == ActionKind.INPUT }
                },
            ),
            choices = emptyList(),
            anchors = emptyList(),
            input = state.dialogue.input.takeIf { markers.none { marker -> marker.kind == ActionKind.INPUT } },
        )
        return state.copy(
            queue = state.queue.drop(1),
            current = PlayerCursor(
                payload = payload,
                charIndex = 0,
                adoptedElapsedMillis = elapsedMillis,
                speaker = GhostSpeaker.SAKURA,
                waitMillis = WAIT_UNIT,
                wholeLine = false,
                quickSession = false,
                synchronizedSession = false,
                renderedFrameIndex = state.talkingFrameIndex,
            ),
            presentation = resetTransient(state.presentation),
            dialogue = nextDialogue,
            nextActionId = state.nextActionId + markers.size,
        ).let { projectDialogue(it, 0, 0) }
    }

    private fun parseStep(state: PlayerState): PlayerTransition {
        var next = state
        var cursor = requireNotNull(state.current)
        val script = cursor.payload.script
        val startIndex = cursor.charIndex
        var explicitCue: PlayerEffect.PresentationCue? = null
        var scheduledDelay: Long? = null
        var request: PlayerEffect.RequestShiori? = null
        val talkingFrame = cursor.renderedFrameIndex == 0

        while (cursor.charIndex < script.length && scheduledDelay == null && request == null) {
            val character = script[cursor.charIndex]
            cursor = cursor.copy(charIndex = cursor.charIndex + 1, waitMillis = WAIT_UNIT)
            if (character != '\\') {
                if (scopeAt(script, cursor.charIndex - 1) < 2) {
                    next = next.copy(presentation = append(next.presentation, cursor, character))
                }
                if (!cursor.wholeLine) scheduledDelay = WAIT_UNIT
                continue
            }
            if (cursor.charIndex >= script.length) {
                continue
            }
            val command = script[cursor.charIndex]
            cursor = cursor.copy(charIndex = cursor.charIndex + 1)
            val scope = scopeAt(script, cursor.charIndex - 2)
            when (command) {
                '\\' -> Unit
                '0', 'h' -> {
                    val previous = cursor.speaker
                    cursor = cursor.copy(speaker = GhostSpeaker.SAKURA)
                    if (previous == GhostSpeaker.KERO) {
                        next = next.copy(presentation = next.presentation.withText(GhostSpeaker.SAKURA, ""))
                    }
                }
                '1', 'u' -> {
                    cursor = cursor.copy(speaker = GhostSpeaker.KERO)
                    next = next.copy(presentation = next.presentation.withText(GhostSpeaker.KERO, ""))
                }
                'p' -> {
                    val parsed = SakuraScriptCommandParser.parseScope(script, cursor.charIndex)
                    if (parsed != null) {
                        val oldSpeaker = cursor.speaker
                        cursor = cursor.copy(
                            charIndex = parsed.second,
                            speaker = when (parsed.first) {
                                0 -> GhostSpeaker.SAKURA
                                1 -> GhostSpeaker.KERO
                                else -> oldSpeaker
                            },
                        )
                        if (parsed.first == 0 && oldSpeaker == GhostSpeaker.KERO) {
                            next = next.copy(presentation = next.presentation.withText(GhostSpeaker.SAKURA, ""))
                        } else if (parsed.first == 1 && oldSpeaker == GhostSpeaker.SAKURA) {
                            next = next.copy(presentation = next.presentation.withText(GhostSpeaker.KERO, ""))
                        }
                    } else if (script.getOrNull(cursor.charIndex) == '[') {
                        cursor = cursor.copy(charIndex = resumeAfterMalformed(script, cursor.charIndex))
                    }
                }
                's' -> {
                    val parsed = parseId(script, cursor.charIndex)
                    if (parsed != null) {
                        require(parsed.first.isNotEmpty())
                        cursor = cursor.copy(charIndex = parsed.second)
                        if (scope < 2) {
                            next = next.copy(presentation = changeSurface(next.presentation, cursor.speaker, parsed.first))
                            val origin = RuntimeRequestOrigin.Playback(next.playbackToken + 1)
                            request = PlayerEffect.RequestShiori(
                                origin,
                                ShioriRequestIntent.event(
                                    "OnSurfaceChange",
                                    listOf(next.presentation.sakura.surfaceId, next.presentation.kero.surfaceId),
                                ),
                                null,
                            )
                            next = next.copy(playbackToken = origin.playbackToken, authoredRequest = origin)
                        } else scheduledDelay = WAIT_UNIT
                    }
                }
                'i' -> {
                    val parsed = parseBracketId(script, cursor.charIndex)
                    if (parsed != null) {
                        require(parsed.first.isNotEmpty())
                        cursor = cursor.copy(charIndex = parsed.second)
                        if (scope < 2) {
                            explicitCue = PlayerEffect.PresentationCue(cursor.speaker, RuntimeCueKind.ONE_SHOT, parsed.first)
                            next = next.copy(presentation = next.presentation.copy(talkingAnimationEnabled = false))
                        }
                        scheduledDelay = WAIT_UNIT
                    }
                }
                'b' -> {
                    val parsed = parseId(script, cursor.charIndex)
                    if (parsed != null) {
                        cursor = cursor.copy(charIndex = parsed.second)
                        if (scope < 2) next = next.copy(
                            presentation = next.presentation.withBalloon(cursor.speaker, parsed.first != "-1"),
                        )
                        scheduledDelay = WAIT_UNIT
                    }
                }
                'e' -> {
                    cursor = cursor.copy(charIndex = script.length, waitMillis = WAIT_YEN_E)
                    scheduledDelay = WAIT_YEN_E
                }
                'n' -> {
                    val bracket = SakuraScriptCommandParser.readBracket(script, cursor.charIndex)
                    if (bracket != null) cursor = cursor.copy(charIndex = bracket.nextIndex)
                    if (scope < 2) {
                        next = next.copy(presentation = append(next.presentation, cursor, '\n'))
                    }
                    scheduledDelay = WAIT_UNIT
                }
                'c' -> if (scope < 2) {
                    next = next.copy(presentation = next.presentation.withText(cursor.speaker, ""))
                }
                '_' -> {
                    if (cursor.charIndex >= script.length) continue
                    val underscore = script[cursor.charIndex]
                    cursor = cursor.copy(charIndex = cursor.charIndex + 1)
                    when (underscore) {
                        's' -> cursor = cursor.copy(synchronizedSession = !cursor.synchronizedSession)
                        'q' -> cursor = cursor.copy(
                            wholeLine = !cursor.wholeLine,
                            quickSession = !cursor.quickSession,
                        )
                        'w' -> {
                            val bracket = SakuraScriptCommandParser.readBracket(script, cursor.charIndex)
                            if (bracket != null) {
                                cursor = cursor.copy(charIndex = bracket.nextIndex)
                                bracket.value.toLongOrNull()?.let {
                                    cursor = cursor.copy(waitMillis = it)
                                    scheduledDelay = it
                                }
                            }
                        }
                        'b' -> {
                            val parsed = parseId(script, cursor.charIndex)
                            if (parsed != null) {
                                cursor = cursor.copy(charIndex = parsed.second)
                                if (scope < 2) next = next.copy(
                                    presentation = next.presentation.withBalloon(cursor.speaker, parsed.first != "-1"),
                                )
                                scheduledDelay = WAIT_UNIT
                            }
                        }
                        'a' -> {
                            val bracket = SakuraScriptCommandParser.readBracket(script, cursor.charIndex)
                            if (bracket != null) {
                                // Only consume the opening command. Its label is ordinary visible
                                // playback text; the later payload-less \_a consumes the close.
                                cursor = cursor.copy(charIndex = bracket.nextIndex)
                            }
                        }
                        else -> {
                            val bracket = SakuraScriptCommandParser.readBracket(script, cursor.charIndex)
                            if (bracket != null) cursor = cursor.copy(charIndex = bracket.nextIndex)
                            else if (script.getOrNull(cursor.charIndex) == '[') {
                                cursor = cursor.copy(charIndex = resumeAfterMalformed(script, cursor.charIndex))
                            }
                        }
                    }
                }
                '!' -> {
                    val bracket = SakuraScriptCommandParser.readBracket(script, cursor.charIndex)
                    if (bracket != null) {
                        cursor = cursor.copy(charIndex = bracket.nextIndex)
                        val args = SakuraScriptCommandParser.splitArguments(bracket.value)
                        if (args.size == 2 && args[1] == "passivemode" && args[0] in setOf("enter", "leave")) {
                            next = next.copy(passive = args[0] == "enter")
                        } else if (
                            scope < 2 && args.firstOrNull() == "open" &&
                            args.getOrNull(1) in setOf("inputbox", "passwordinput")
                        ) {
                            scheduledDelay = WAIT_UNIT
                        }
                    } else if (script.getOrNull(cursor.charIndex) == '[') {
                        cursor = cursor.copy(charIndex = resumeAfterMalformed(script, cursor.charIndex))
                    }
                }
                'q' -> {
                    val bracket = SakuraScriptCommandParser.readBracket(script, cursor.charIndex)
                    if (bracket != null) {
                        cursor = cursor.copy(charIndex = bracket.nextIndex)
                        val args = SakuraScriptCommandParser.splitArguments(bracket.value)
                        if (scope < 2 && args.size >= 2) {
                            args.first().forEach { labelCharacter ->
                                next = next.copy(presentation = append(next.presentation, cursor, labelCharacter))
                            }
                            cursor = cursor.copy(wholeLine = true)
                        }
                    } else if (script.getOrNull(cursor.charIndex) == '[') {
                        cursor = cursor.copy(charIndex = resumeAfterMalformed(script, cursor.charIndex))
                    }
                }
                'w' -> {
                    val digit = script.getOrNull(cursor.charIndex)?.digitToIntOrNull()
                    if (digit != null) {
                        cursor = cursor.copy(charIndex = cursor.charIndex + 1, waitMillis = digit * WAIT_UNIT)
                        scheduledDelay = cursor.waitMillis
                    }
                }
                else -> {
                    val bracket = SakuraScriptCommandParser.readBracket(script, cursor.charIndex)
                    if (bracket != null) cursor = cursor.copy(charIndex = bracket.nextIndex)
                    else if (script.getOrNull(cursor.charIndex) == '[') {
                        cursor = cursor.copy(charIndex = resumeAfterMalformed(script, cursor.charIndex))
                    } else if (command in setOf('s', 'b') && script.getOrNull(cursor.charIndex)?.isDigit() == true) {
                        cursor = cursor.copy(charIndex = cursor.charIndex + 1)
                    }
                }
            }
        }

        val nextTalkingFrameIndex = (cursor.renderedFrameIndex + 1) % 10
        cursor = cursor.copy(renderedFrameIndex = nextTalkingFrameIndex)
        next = next.copy(
            current = cursor,
            talkingFrameIndex = nextTalkingFrameIndex,
            presentation = next.presentation.copy(talkingAnimationEnabled = talkingFrame),
        )
        next = projectDialogue(next, startIndex, cursor.charIndex)
        val effects = mutableListOf<PlayerEffect>()
        explicitCue?.let(effects::add)
        if (talkingFrame) {
            GhostSpeaker.entries.filter { speaker ->
                next.presentation.speaker(speaker).balloonVisible && explicitCue?.speaker != speaker
            }.forEach { speaker ->
                effects += PlayerEffect.PresentationCue(speaker, RuntimeCueKind.TALKING, null)
            }
        }
        request?.let(effects::add)
        if (request == null && next.dialogue.input == null) {
            val delay = scheduledDelay ?: WAIT_UNIT
            val scheduled = schedule(next, delay, effects)
            return scheduled
        }
        return transition(next, effects)
    }

    private fun nativeResponse(state: PlayerState, command: PlayerCommand.NativeResponse): PlayerTransition {
        val pending = state.authoredRequest ?: return transition(state)
        if (
            command.token.generation != state.generation ||
            command.token.origin != pending ||
            command.token.parentOperationId != state.current?.payload?.parent?.operationId()
        ) return transition(state)
        return when (val response = command.response) {
            PlayerResponse.StaleGeneration -> transition(state)
            PlayerResponse.FatalFailure -> poisonPlayback(
                state,
                state.current?.payload?.parent,
            )
            PlayerResponse.ReplayableFailure -> failPlayback(
                state,
                state.current?.payload?.parent,
                RuntimeNoticeCode.REQUEST_FAILED,
            )
            is PlayerResponse.Returned -> {
                val value = response.response.takeIf { it.getStatusCode() == 200 }
                    ?.getKey("Value")
                    ?.takeIf(String::isNotEmpty)
                val resumed = state.copy(
                    queue = if (value == null) state.queue else state.queue + PlayerPayload(
                        value,
                        state.current?.payload?.parent,
                    ),
                    authoredRequest = null,
                )
                schedule(resumed, 0L)
            }
        }
    }

    private fun activateChoice(state: PlayerState, key: DialogueActionKey): PlayerTransition {
        val selected = state.dialogue.choices.firstOrNull { it.key == key } ?: return transition(state)
        val cleared = state.copy(
            dialogue = state.dialogue.copy(
                state = state.dialogue.state.copy(
                    revision = state.dialogue.state.revision + 1,
                    pendingChoices = emptyList(),
                ),
                choices = emptyList(),
            ),
        )
        return when (val action = selected.action) {
            is DialogueAction.Normal -> transition(
                cleared,
                listOf(
                    PlayerEffect.RequestShiori(
                        RuntimeRequestOrigin.Dialogue(key),
                        ShioriRequestIntent.event(
                            "OnChoiceSelectEx",
                            listOf(action.label, action.id) + action.extraReferences,
                        ),
                        ShioriRequestIntent.event("OnChoiceSelect", listOf(action.id)),
                    ),
                ),
            )
            is DialogueAction.DirectEvent -> transition(
                cleared,
                listOf(
                    PlayerEffect.RequestShiori(
                        RuntimeRequestOrigin.Dialogue(key),
                        ShioriRequestIntent.event(action.eventId, action.references),
                        null,
                    ),
                ),
            )
            is DialogueAction.Script -> {
                val queued = cleared.copy(queue = cleared.queue + PlayerPayload(action.sakuraScript, null))
                if (queued.current == null) schedule(queued, 0L) else transition(queued)
            }
        }
    }

    private fun activateAnchor(state: PlayerState, key: DialogueActionKey): PlayerTransition {
        val selected = state.dialogue.anchors.firstOrNull { it.key == key } ?: return transition(state)
        val (intent, fallback) = when (val action = selected.action) {
            is AnchorAction.Normal -> ShioriRequestIntent.event(
                "OnAnchorSelectEx",
                listOf(action.label, action.id) + action.extraReferences,
            ) to ShioriRequestIntent.event("OnAnchorSelect", listOf(action.id))
            is AnchorAction.DirectEvent -> ShioriRequestIntent.event(action.eventId, action.references) to null
        }
        return transition(
            state,
            listOf(PlayerEffect.RequestShiori(RuntimeRequestOrigin.Dialogue(key), intent, fallback)),
        )
    }

    private fun submitInput(state: PlayerState, key: DialogueActionKey, value: String): PlayerTransition {
        val input = state.dialogue.input?.takeIf { it.key == key } ?: return transition(state)
        val references = when (val dispatch = input.pending.spec.dispatch) {
            is InputDispatch.Normal -> listOf(dispatch.id, value, input.pending.spec.supplement) +
                input.pending.spec.extraReferences
            is InputDispatch.DirectEvent -> listOf(value, input.pending.spec.supplement) +
                input.pending.spec.extraReferences
        }
        val event = when (val dispatch = input.pending.spec.dispatch) {
            is InputDispatch.Normal -> "OnUserInput"
            is InputDispatch.DirectEvent -> dispatch.eventId
        }
        return claimedInputTransition(
            state,
            input,
            PlayerEffect.RequestShiori(RuntimeRequestOrigin.Dialogue(key), ShioriRequestIntent.event(event, references), null),
        )
    }

    private fun expireInput(state: PlayerState, command: PlayerCommand.InputExpired): PlayerTransition {
        val input = state.dialogue.input?.takeIf { it.key == command.key } ?: return transition(state)
        if (
            input.pending.deadlineElapsedMillis == Long.MAX_VALUE ||
            command.elapsedMillis < input.pending.deadlineElapsedMillis
        ) return transition(state)
        return cancelInput(state, command.key, "timeout", true)
    }

    private fun cancelInput(
        state: PlayerState,
        key: DialogueActionKey,
        reason: String,
        withFallback: Boolean,
    ): PlayerTransition {
        val input = state.dialogue.input?.takeIf { it.key == key } ?: return transition(state)
        val id = when (val dispatch = input.pending.spec.dispatch) {
            is InputDispatch.Normal -> dispatch.id
            is InputDispatch.DirectEvent -> dispatch.eventId
        }
        val references = listOf(id, reason, input.pending.spec.supplement) + input.pending.spec.extraReferences
        return claimedInputTransition(
            state,
            input,
            PlayerEffect.RequestShiori(
                RuntimeRequestOrigin.Dialogue(key),
                ShioriRequestIntent.event("OnUserInputCancel", references),
                ShioriRequestIntent.event("OnUserInput", references).takeIf { withFallback },
            ),
        )
    }

    private fun claimedInputTransition(
        state: PlayerState,
        input: RuntimeInputAction,
        request: PlayerEffect.RequestShiori,
    ): PlayerTransition {
        val cleared = state.copy(
            dialogue = state.dialogue.copy(
                state = state.dialogue.state.copy(
                    revision = state.dialogue.state.revision + 1,
                    pendingInput = null,
                ),
                input = null,
            ),
        )
        val effects = mutableListOf<PlayerEffect>(request)
        return if (cleared.current != null) schedule(cleared, 0L, effects) else transition(cleared, effects)
    }

    private fun clear(state: PlayerState, owner: PlayerParent?): PlayerTransition {
        if (owner == null && state.current?.payload?.parent != null) return transition(state)
        val clearsCurrent = state.current?.payload?.parent == owner && state.current != null
        val clearsDormantOrdinaryState = owner == null && state.current == null && (
            state.dialogue.choices.isNotEmpty() ||
                state.dialogue.anchors.isNotEmpty() ||
                state.dialogue.input != null ||
                state.passive ||
                state.authoredRequest != null
            )
        val remainingQueue = if (owner == null) {
            state.queue.dropWhile { it.parent == null }
        } else {
            state.queue.filterNot { it.parent == owner }
        }
        if (!clearsCurrent && !clearsDormantOrdinaryState) {
            return if (remainingQueue.size == state.queue.size) transition(state)
            else transition(state.copy(queue = remainingQueue))
        }
        val cleared = state.copy(
            queue = remainingQueue,
            current = null,
            dialogue = emptyDialogue(state.dialogue),
            passive = false,
            authoredRequest = null,
            playbackToken = state.playbackToken + 1,
            presentation = resetTransient(state.presentation),
        )
        return if (remainingQueue.isEmpty()) transition(cleared) else schedule(cleared, 0L)
    }

    private fun completeCurrent(state: PlayerState, elapsedMillis: Long): PlayerTransition {
        val parent = state.current?.payload?.parent
        val hasQueuedTalk = state.queue.isNotEmpty()
        val talkingFrameIndex = if (hasQueuedTalk) {
            state.current?.renderedFrameIndex ?: state.talkingFrameIndex
        } else {
            ((state.current?.renderedFrameIndex ?: state.talkingFrameIndex) + 1) % 10
        }
        val next = state.copy(
            current = null,
            authoredRequest = null,
            talkingFrameIndex = talkingFrameIndex,
            presentation = resetTransient(state.presentation),
        )
        val stillOwned = next.queue.any { it.parent == parent }
        val effects = if (parent != null && !stillOwned) listOf(PlayerEffect.ParentCompleted(parent)) else emptyList()
        return if (next.queue.isNotEmpty()) {
            val adopted = adopt(next, elapsedMillis)
            schedule(adopted, WAIT_UNIT, effects)
        } else transition(next, effects)
    }

    private fun failPlayback(
        state: PlayerState,
        parent: PlayerParent?,
        reason: RuntimeNoticeCode = RuntimeNoticeCode.PLAYER_FAILED,
    ): PlayerTransition {
        val remainingQueue = if (parent == null) {
            state.queue.dropWhile { it.parent == null }
        } else {
            state.queue.filterNot { it.parent == parent }
        }
        val failed = state.copy(
            queue = remainingQueue,
            current = null,
            authoredRequest = null,
            dialogue = emptyDialogue(state.dialogue),
            passive = false,
            playbackToken = state.playbackToken + 1,
            presentation = resetTransient(state.presentation),
        )
        val effects = listOf(PlayerEffect.Failure(parent, reason))
        return if (remainingQueue.isEmpty()) transition(failed, effects) else schedule(failed, 0L, effects)
    }

    private fun poisonPlayback(state: PlayerState, parent: PlayerParent?): PlayerTransition = transition(
        state.copy(
            queue = emptyList(),
            current = null,
            authoredRequest = null,
            dialogue = emptyDialogue(state.dialogue),
            passive = false,
            playbackToken = state.playbackToken + 1,
            presentation = resetTransient(state.presentation),
        ),
        listOf(PlayerEffect.Failure(parent, RuntimeNoticeCode.RUNTIME_POISONED)),
    )

    private fun projectDialogue(
        state: PlayerState,
        fromIndex: Int,
        throughIndex: Int,
    ): PlayerState {
        val cursor = state.current ?: return state
        val markers = actionMarkers(cursor.payload.script)
        val baseActionId = state.nextActionId - markers.size
        val revealed = SakuraScriptTokenizer.tokenizeRevealed(
            cursor.payload.script.take(throughIndex.coerceIn(0, cursor.payload.script.length)),
        )
        // The tokenizer preserves source action values inside each speaker's content.
        // Stable identity is carried by DialogueActionKey, so regrouping those values here
        // would only risk swapping alternating-speaker actions.
        val mapped = revealed
        val visible = visibleSegments(mapped)
        val visibleChoices = visible.filterIsInstance<DialogueSegment.Choice>().map(DialogueSegment.Choice::action)
        val visibleAnchors = visible.filterIsInstance<DialogueSegment.Anchor>().map(DialogueSegment.Anchor::action)
        val crossed = markers.withIndex().filter { (_, marker) ->
            marker.sourceEnd > fromIndex && marker.sourceEnd <= throughIndex
        }
        val incarnation = state.dialogue.state.incarnation
        val choices = (state.dialogue.choices + crossed.mapNotNull { (index, marker) ->
            (marker.value as? DialogueAction)?.let {
                RuntimeChoiceAction(DialogueActionKey(state.generation, incarnation, baseActionId + index), it)
            }
        }).filter { candidate -> visibleChoices.any { it == candidate.action } }
        val anchors = (state.dialogue.anchors + crossed.mapNotNull { (index, marker) ->
            (marker.value as? AnchorAction)?.let {
                RuntimeAnchorAction(DialogueActionKey(state.generation, incarnation, baseActionId + index), it)
            }
        }).filter { candidate -> visibleAnchors.any { it == candidate.action } }
        val existingInput = state.dialogue.input
        val input = existingInput ?: crossed.firstNotNullOfOrNull { (index, marker) ->
            val pending = marker.value as? PendingInputSeed ?: return@firstNotNullOfOrNull null
            val deadline = inputDeadline(cursor.adoptedElapsedMillis, pending.timeoutMillis)
            RuntimeInputAction(
                DialogueActionKey(state.generation, incarnation, baseActionId + index),
                PendingInputState(
                    generation = baseActionId + index,
                    spec = pending.spec,
                    deadlineElapsedMillis = deadline,
                    owner = pending.speaker,
                ),
            )
        }
        return state.copy(
            dialogue = state.dialogue.copy(
                state = state.dialogue.state.copy(
                    revision = state.dialogue.state.revision + 1,
                    contents = mapped,
                    pendingChoices = choices.map(RuntimeChoiceAction::action),
                    pendingInput = input?.pending,
                ),
                choices = choices.distinctBy(RuntimeChoiceAction::key),
                anchors = anchors.distinctBy(RuntimeAnchorAction::key),
                input = input,
            ),
        )
    }

    private fun actionMarkers(script: String): List<ActionMarker> {
        val choices = SakuraScriptTokenizer.tokenizeWithInteractions(script).interactions
            .map { ActionMarker(it.sourceEnd, ActionKind.CHOICE, it.action) }
        val others = mutableListOf<ActionMarker>()
        var index = 0
        var speaker = GhostSpeaker.SAKURA
        var scope = 0
        while (index < script.length) {
            if (script[index++] != '\\' || index >= script.length) continue
            when (script[index++]) {
                'h', '0' -> { scope = 0; speaker = GhostSpeaker.SAKURA }
                'u', '1' -> { scope = 1; speaker = GhostSpeaker.KERO }
                'p' -> SakuraScriptCommandParser.parseScope(script, index)?.let {
                    scope = it.first
                    index = it.second
                    if (scope == 0) speaker = GhostSpeaker.SAKURA else if (scope == 1) speaker = GhostSpeaker.KERO
                }
                '!' -> {
                    val start = index - 2
                    val bracket = SakuraScriptCommandParser.readBracket(script, index)
                    if (bracket != null) {
                        index = bracket.nextIndex
                        if (scope < 2) {
                            val prefix = if (speaker == GhostSpeaker.SAKURA) "\\h" else "\\u"
                            val input = SakuraScriptTokenizer.tokenize(prefix + script.substring(start, index))
                                .asSequence().flatMap { it.segments.asSequence() }
                                .filterIsInstance<DialogueSegment.InputBox>().lastOrNull()
                            input?.let {
                                others += ActionMarker(
                                    index,
                                    ActionKind.INPUT,
                                    PendingInputSeed(it.spec, it.spec.timeoutMillis, speaker),
                                )
                            }
                        }
                    }
                }
                '_' -> if (index < script.length && script[index++] == 'a') {
                    val start = index - 3
                    val bracket = SakuraScriptCommandParser.readBracket(script, index)
                    if (bracket != null) {
                        val closing = findAnchorClosing(script, bracket.nextIndex)
                        if (closing >= 0) {
                            index = closing + 3
                            if (scope < 2) {
                                val prefix = if (speaker == GhostSpeaker.SAKURA) "\\h" else "\\u"
                                val anchor = SakuraScriptTokenizer.tokenize(prefix + script.substring(start, index))
                                    .asSequence().flatMap { it.segments.asSequence() }
                                    .filterIsInstance<DialogueSegment.Anchor>().lastOrNull()
                                anchor?.let { others += ActionMarker(index, ActionKind.ANCHOR, it.action) }
                            }
                        } else index = bracket.nextIndex
                    }
                } else {
                    val bracket = SakuraScriptCommandParser.readBracket(script, index)
                    if (bracket != null) index = bracket.nextIndex
                }
                else -> {
                    val bracket = SakuraScriptCommandParser.readBracket(script, index)
                    if (bracket != null) index = bracket.nextIndex
                }
            }
        }
        return (choices + others).sortedBy(ActionMarker::sourceEnd)
    }

    private fun visibleSegments(contents: List<DialogueContent>): List<DialogueSegment> = GhostSpeaker.entries.flatMap { speaker ->
        contents.asSequence().filter { it.speaker == speaker }.flatMap { it.segments.asSequence() }
            .fold(mutableListOf()) { visible, segment ->
                when (segment) {
                    DialogueSegment.Clear -> visible.clear()
                    DialogueSegment.SpeakerChangeClear -> visible.removeAll {
                        it !is DialogueSegment.Choice && it !is DialogueSegment.InputBox
                    }
                    else -> visible += segment
                }
                visible
            }
    }

    private fun append(
        presentation: RuntimePresentation,
        cursor: PlayerCursor,
        character: Char,
    ): RuntimePresentation {
        val speakers = if (cursor.synchronizedSession) GhostSpeaker.entries else listOf(cursor.speaker)
        return speakers.fold(presentation) { frame, speaker ->
            val current = frame.speaker(speaker)
            frame.withSpeaker(
                speaker,
                current.copy(
                    text = current.text + character,
                    balloonVisible = true,
                ),
            )
        }
    }

    private fun changeSurface(
        presentation: RuntimePresentation,
        speaker: GhostSpeaker,
        surfaceId: String,
    ): RuntimePresentation {
        val current = presentation.speaker(speaker)
        return presentation.withSpeaker(
            speaker,
            current.copy(
                surfaceId = surfaceId,
                surfaceEpoch = current.surfaceEpoch + if (surfaceId == current.surfaceId) 0 else 1,
            ),
        )
    }

    private fun RuntimePresentation.speaker(speaker: GhostSpeaker): RuntimeSpeakerPresentation = when (speaker) {
        GhostSpeaker.SAKURA -> sakura
        GhostSpeaker.KERO -> kero
    }

    private fun RuntimePresentation.withSpeaker(
        speaker: GhostSpeaker,
        value: RuntimeSpeakerPresentation,
    ): RuntimePresentation = when (speaker) {
        GhostSpeaker.SAKURA -> copy(sakura = value)
        GhostSpeaker.KERO -> copy(kero = value)
    }

    private fun RuntimePresentation.withText(speaker: GhostSpeaker, text: String): RuntimePresentation {
        val current = speaker(speaker)
        return withSpeaker(speaker, current.copy(text = text, balloonVisible = text.isNotEmpty()))
    }

    private fun RuntimePresentation.withBalloon(speaker: GhostSpeaker, visible: Boolean): RuntimePresentation =
        withSpeaker(
            speaker,
            speaker(speaker).let { current ->
                current.copy(balloonVisible = visible || current.text.isNotEmpty())
            },
        )

    private fun resetTransient(presentation: RuntimePresentation): RuntimePresentation = presentation.copy(
        sakura = presentation.sakura.copy(text = "", balloonVisible = false),
        kero = presentation.kero.copy(text = "", balloonVisible = false),
        talkingAnimationEnabled = false,
    )

    private fun emptyDialogue(dialogue: RuntimeDialogueSnapshot): RuntimeDialogueSnapshot = RuntimeDialogueSnapshot(
        state = DialogueRuntimeState(
            revision = dialogue.state.revision + 1,
            incarnation = dialogue.state.incarnation + 1,
            talkId = dialogue.state.talkId,
        ),
        choices = emptyList(),
        anchors = emptyList(),
        input = null,
    )

    private fun inputDeadline(now: Long, timeout: Long?): Long {
        if (timeout == null || timeout <= 0L || now > Long.MAX_VALUE - timeout) return Long.MAX_VALUE
        return now + timeout
    }

    private fun scopeAt(script: String, throughExclusive: Int): Int {
        var scope = 0
        var index = 0
        while (index < throughExclusive.coerceAtMost(script.length)) {
            if (script[index++] != '\\' || index >= throughExclusive) continue
            when (script[index++]) {
                'h', '0' -> scope = 0
                'u', '1' -> scope = 1
                'p' -> SakuraScriptCommandParser.parseScope(script, index)?.let {
                    scope = it.first
                    index = it.second
                }
                '_' -> {
                    if (index < throughExclusive) index++
                    val bracket = SakuraScriptCommandParser.readBracket(script, index)
                    if (bracket != null) index = bracket.nextIndex
                }
                else -> {
                    val bracket = SakuraScriptCommandParser.readBracket(script, index)
                    if (bracket != null) index = bracket.nextIndex
                }
            }
        }
        return scope
    }

    private fun parseId(script: String, index: Int): Pair<String, Int>? =
        parseBracketId(script, index) ?: script.getOrNull(index)?.takeIf { it.isDigit() || it == '-' }
            ?.let { character ->
                val end = if (character == '-' && script.getOrNull(index + 1)?.isDigit() == true) index + 2 else index + 1
                script.substring(index, end) to end
            }

    private fun parseBracketId(script: String, index: Int): Pair<String, Int>? =
        SakuraScriptCommandParser.readBracket(script, index)?.let { it.value to it.nextIndex }

    private fun resumeAfterMalformed(script: String, start: Int): Int =
        script.indexOf('\\', start).takeIf { it >= 0 } ?: script.length

    private fun findAnchorClosing(script: String, start: Int): Int {
        var index = start
        while (index < script.length - 2) {
            if (script[index] == '\\' && script[index + 1] == '_' && script[index + 2] == 'a') return index
            index += if (script[index] == '\\' && script.getOrNull(index + 1) == '\\') 2 else 1
        }
        return -1
    }

    private fun schedule(
        state: PlayerState,
        delayMillis: Long,
        priorEffects: List<PlayerEffect> = emptyList(),
    ): PlayerTransition {
        val next = state.copy(playbackToken = state.playbackToken + 1)
        return transition(next, priorEffects + PlayerEffect.SchedulePlayback(next.playbackToken, delayMillis))
    }

    private fun transition(
        state: PlayerState,
        effects: List<PlayerEffect> = emptyList(),
    ): PlayerTransition = PlayerTransition(state, effects)

    private fun PlayerParent.operationId(): Long = when (this) {
        is PlayerParent.Switch -> operationId
        is PlayerParent.Exit -> operationId
    }

    private enum class ActionKind { CHOICE, ANCHOR, INPUT }
    private data class ActionMarker(val sourceEnd: Int, val kind: ActionKind, val value: Any)
    private data class PendingInputSeed(
        val spec: com.cattailsw.nanidroid.runtime.dialogue.InputBoxSpec,
        val timeoutMillis: Long?,
        val speaker: GhostSpeaker,
    )
}

private fun PlayerTransition.frozen(): PlayerTransition = PlayerTransition(
    state = freeze(state),
    effects = immutableList(effects),
)

private fun freeze(state: PlayerState): PlayerState = state.copy(
    queue = immutableList(state.queue.map { it.copy() }),
    current = state.current?.copy(payload = state.current.payload.copy()),
    dialogue = state.dialogue.copy(
        state = state.dialogue.state.copy(
            contents = immutableList(state.dialogue.state.contents.map { content ->
                content.copy(segments = immutableList(content.segments.map(::freezeSegment)))
            }),
            pendingChoices = immutableList(state.dialogue.state.pendingChoices.map(::freezeChoice)),
            pendingInput = state.dialogue.state.pendingInput?.let(::freezePending),
        ),
        choices = immutableList(state.dialogue.choices.map {
            it.copy(key = it.key.copy(), action = freezeChoice(it.action))
        }),
        anchors = immutableList(state.dialogue.anchors.map {
            it.copy(key = it.key.copy(), action = freezeAnchor(it.action))
        }),
        input = state.dialogue.input?.let {
            it.copy(key = it.key.copy(), pending = freezePending(it.pending))
        },
    ),
)

private fun freezeSegment(segment: DialogueSegment): DialogueSegment = when (segment) {
    is DialogueSegment.Choice -> segment.copy(action = freezeChoice(segment.action))
    is DialogueSegment.Anchor -> segment.copy(action = freezeAnchor(segment.action))
    is DialogueSegment.InputBox -> segment.copy(spec = freezeInputSpec(segment.spec))
    else -> segment
}

private fun freezeChoice(action: DialogueAction): DialogueAction = when (action) {
    is DialogueAction.Normal -> action.copy(extraReferences = immutableList(action.extraReferences))
    is DialogueAction.DirectEvent -> action.copy(references = immutableList(action.references))
    is DialogueAction.Script -> action.copy()
}

private fun freezeAnchor(action: AnchorAction): AnchorAction = when (action) {
    is AnchorAction.Normal -> action.copy(extraReferences = immutableList(action.extraReferences))
    is AnchorAction.DirectEvent -> action.copy(references = immutableList(action.references))
}

private fun freezePending(pending: PendingInputState): PendingInputState =
    pending.copy(spec = freezeInputSpec(pending.spec))

private fun freezeInputSpec(spec: InputBoxSpec): InputBoxSpec = spec.copy(
    dispatch = when (val dispatch = spec.dispatch) {
        is InputDispatch.Normal -> dispatch.copy()
        is InputDispatch.DirectEvent -> dispatch.copy()
    },
    behaviorOptions = Collections.unmodifiableSet(LinkedHashSet(spec.behaviorOptions)),
    presentation = spec.presentation.copy(),
    extraReferences = immutableList(spec.extraReferences),
    unknownOptions = immutableList(spec.unknownOptions),
)

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
