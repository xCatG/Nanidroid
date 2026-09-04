package com.cattailsw.nanidroid

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import com.cattailsw.nanidroid.runtime.MonotonicClock
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.AnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueRuntimeState
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.dialogue.GhostRuntimeMode
import com.cattailsw.nanidroid.runtime.dialogue.InputDispatch
import com.cattailsw.nanidroid.runtime.dialogue.PendingInputState
import com.cattailsw.nanidroid.runtime.dialogue.SakuraScriptCommandParser
import com.cattailsw.nanidroid.runtime.dialogue.SakuraScriptInteraction
import com.cattailsw.nanidroid.runtime.dialogue.SakuraScriptTokenizer
import com.cattailsw.nanidroid.runtime.dialogue.tokenizeWithInteractions
import com.cattailsw.nanidroid.runtime.dialogue.ShioriMethod
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionProtocol
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

internal interface SScriptPlaybackScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit)
    fun cancelPending()
}

internal fun interface SScriptResponseScheduler {
    fun schedule(action: () -> Unit)
}

internal data class SScriptPlaybackHooks(
    val afterRunPrepared: () -> Unit = {},
    val afterRunClaimed: () -> Unit = {},
    val afterExitCaptured: () -> Unit = {},
    val beforeTimerResponseAdmission: () -> Unit = {},
    val afterStopClaimed: () -> Unit = {},
    val afterSurfaceChangeCaptured: () -> Unit = {},
    val afterInputEffectCaptured: () -> Unit = {},
    val afterSelectionEffectCaptured: () -> Unit = {},
    val afterPresentationEffectCaptured: () -> Unit = {},
    val afterSurfaceInteractionCaptured: () -> Unit = {},
    val beforeRequestResponseAdmission: () -> Unit = {},
    val afterRequestResponseFence: () -> Unit = {},
)

internal data class SScriptRunnerConfiguration(
    val monotonicClock: MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() },
    val playbackSchedulerFactory: () -> SScriptPlaybackScheduler = {
        HandlerSScriptPlaybackScheduler()
    },
    val responseSchedulerFactory: () -> SScriptResponseScheduler = {
        HandlerSScriptResponseScheduler()
    },
    val playbackHooks: SScriptPlaybackHooks = SScriptPlaybackHooks(),
)

private data class RequestAdmissionStamp(
    val generation: Long,
    val epoch: Long,
)

private data class TimerEventKey(
    val event: String,
    val generation: Long,
    val clockEpoch: Long,
)

private data class DeferredMinuteBoundary(
    val elapsedMinute: Long,
    val uptimeHours: Long,
    val generation: Long,
    val clockEpoch: Long,
)

private data class TimerRequestCapture(
    val handle: GhostHandle,
    val key: TimerEventKey,
    val wasIdle: Boolean,
    val modeGeneration: Long,
)

internal data class SurfaceInteractionDispatchResult(
    val candidateEvent: String?,
    val accepted: Boolean,
)

private class HandlerSScriptPlaybackScheduler : SScriptPlaybackScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(delayMillis: Long, action: () -> Unit) {
        if (delayMillis <= 0L) handler.post(action) else handler.postDelayed(action, delayMillis)
    }

    override fun cancelPending() {
        handler.removeCallbacksAndMessages(null)
    }
}

private class HandlerSScriptResponseScheduler : SScriptResponseScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(action: () -> Unit) {
        handler.post(action)
    }
}

/** Executes Sakura Script while keeping the legacy Java-facing runner contract. */
open class SScriptRunner internal constructor(
    ctx: Context?,
    private val runtimePort: GhostRuntime,
    configuration: SScriptRunnerConfiguration = SScriptRunnerConfiguration(),
) : Runnable {
    internal class HostToken

    interface StatusCallback {
        fun stop()
        fun canExit(expectedGeneration: Long?)
        fun switchPlaybackComplete()
    }
    interface UICallback { fun showUserInputBox(id: String); fun showUserSelection(textlabel: Array<String>, ids: Array<String>) }

    companion object {
        private const val TAG = "SScriptRunner"
        @JvmField val WAIT_UNIT: Long = 50
        @JvmField val WAIT_YEN_E: Long = 1000
        private const val RUN = 42; private const val STOP = 43; private const val INC_CLOCK = 44; private const val CLOCK_STEP = 1000L
    }

    private class PlaybackState(
        var talkAnimeControl: Int = 0,
    ) {
        var running = false
        var paused = false
        var msg: String? = null
        var sync = false
        var wholeline = false
        var sakuraTalk = true
        var scope = 0
        val sakuraMsg = StringBuilder()
        val keroMsg = StringBuilder()
        var waitTime = WAIT_UNIT
        var charIndex = 0
        var bSakuraId = "0"
        var bKeroId = "-1"
        var sakuraAnimationId: String? = null
        var keroAnimationId: String? = null
        var dialogueScript: AuthoredDialogueScript? = null
        var legacyChoiceCallbackPublished = false
        // A SHIORI terminal may clear this, but must never clear the independent user-input pause.
        var authoredRequestPending = false
    }

    private val msgQueue = ConcurrentLinkedQueue<String>()
    private val monotonicClock = configuration.monotonicClock
    private val playbackSchedulerFactory = configuration.playbackSchedulerFactory
    private val responseScheduler = lazy(configuration.responseSchedulerFactory)
    private val responseExecutor = Executor { command ->
        responseScheduler.value.schedule { command.run() }
    }
    private val playbackHooks = configuration.playbackHooks
    private var presentationRenderer: GhostPresentationRenderer? = null
    private var currentPresentationFrame: GhostPresentationFrame? = null
    private var hostOwner: HostToken? = null
    private var clockOwner: HostToken? = null
    private var clockEpoch = 0L
    private val pendingTimerEvents = mutableSetOf<TimerEventKey>()
    private var deferredMinuteBoundary: DeferredMinuteBoundary? = null
    private var activeHandle: GhostHandle? = null
    private var admittedAttachmentOperationId: Long? = null
    private var retiredGenerationAwaitingAttachment: Long? = null
    private var ucb: UICallback? = null; private var cb: StatusCallback? = null
    private var noWaitMode = false
    private var playback = PlaybackState()
    private var sakuraSurfaceId = "0"; private var keroSurfaceId = "10"
    private var lastElapsedSecond: Long? = null
    private var lastElapsedMinute: Long? = null
    private var restore = false; private var pendingSwitch: RunnerSwitchOperation? = null; private val bootDispatchState = BootDispatchState()
    private var nextExitOperationId = 0L
    private var exitOperation: ExitOperation? = null
    private var dialogueState = DialogueRuntimeState()
    private var dialogueIncarnation = 0L
    private var nextDialogueTalkId = 0L
    @Volatile private var dialogueStateObserver: ((DialogueRuntimeState) -> Unit)? = null
    private var nextInputGeneration = 0L
    private val retiredInputGenerations = mutableSetOf<Long>()
    private var dialogueDialogOwner = UUID.randomUUID().toString()
    private var nextChoiceGeneration = 0L
    private val retiredDialogueChoices = java.util.Collections.newSetFromMap(IdentityHashMap<DialogueAction, Boolean>())
    private var pendingChoiceGeneration: Long? = null
    private var passive = false
    private var runtimeModeGeneration: Long = 0L
    private var requestAdmissionEpoch: Long = 0L
    private val playbackScheduler = lazy(playbackSchedulerFactory)
    @Volatile private var dialogueClaimHookForTesting: (() -> Unit)? = null

    private data class RunnerSwitchOperation(
        val operationId: Long,
        val outgoingGeneration: Long,
    )

    private enum class ExitPhase {
        AwaitingResponse,
        AwaitingPlayback,
        AwaitingReplacement,
        Unloading,
        ReadyForHost,
    }

    private data class ExitOperation(
        val operationId: Long,
        var generation: Long?,
        var phase: ExitPhase = ExitPhase.AwaitingResponse,
    )

    internal fun exitStateForTesting(): Pair<Long?, String>? = synchronized(this) {
        exitOperation?.let { it.generation to it.phase.name }
    }
    internal fun exitOperationIdForTesting(): Long? = synchronized(this) {
        exitOperation?.operationId
    }
    internal fun deferredMinuteBoundaryForTesting(): Pair<Long, Long>? = synchronized(this) {
        deferredMinuteBoundary?.let { it.elapsedMinute to it.uptimeHours }
    }

    private data class HostBindingEffects(
        val frame: GhostPresentationFrame?,
        val dialogueState: DialogueRuntimeState,
        val exitOperationId: Long?,
    )

    private data class ExitDeliveryLease(
        val operationId: Long,
        val generation: Long?,
        val hostToken: HostToken?,
        val callback: StatusCallback,
    )

    internal fun setDialogueClaimHookForTesting(hook: (() -> Unit)?) { dialogueClaimHookForTesting = hook }
    internal fun bindHost(
        token: HostToken,
        renderer: GhostPresentationRenderer,
        dialogueObserver: (DialogueRuntimeState) -> Unit,
        uiCallback: UICallback,
        statusCallback: StatusCallback,
    ) {
        val runtimeIdentity = runtimePort.identity()
        val effects = synchronized(this) {
            hostOwner = token
            presentationRenderer = renderer
            dialogueStateObserver = dialogueObserver
            ucb = uiCallback
            cb = statusCallback
            val pendingExit = exitOperation
            val exitOperationId = if (
                pendingExit?.phase == ExitPhase.ReadyForHost &&
                exitGenerationHasNoReplacement(pendingExit, runtimeIdentity)
            ) {
                pendingExit.operationId
            } else {
                if (pendingExit?.phase == ExitPhase.ReadyForHost) {
                    if (runtimeOwnsReplacement(pendingExit, runtimeIdentity)) {
                        pendingExit.phase = ExitPhase.AwaitingReplacement
                    } else {
                        exitOperation = null
                    }
                }
                null
            }
            HostBindingEffects(currentPresentationFrame, dialogueState, exitOperationId)
        }
        try {
            effects.frame?.let(renderer::render)
            dialogueObserver(effects.dialogueState)
        } finally {
            effects.exitOperationId?.let { operationId ->
                schedulePendingExitDelivery(
                    operationId,
                    expectedToken = token,
                    expectedCallback = statusCallback,
                )
            }
        }
    }

    internal fun unbindHost(token: HostToken): Boolean = synchronized(this) {
        if (hostOwner !== token) return@synchronized false
        hostOwner = null
        presentationRenderer = null
        dialogueStateObserver = null
        ucb = null
        cb = null
        true
    }

    internal fun isHostOwner(token: HostToken): Boolean = synchronized(this) {
        hostOwner === token
    }

    internal fun hasPendingExit(): Boolean = synchronized(this) { exitOperation != null }

    fun setDialogueStateObserver(observer: ((DialogueRuntimeState) -> Unit)?) = synchronized(this) {
        dialogueStateObserver = observer
        observer?.invoke(dialogueState)
    }
    fun setPresentationRenderer(renderer: GhostPresentationRenderer?) = synchronized(this) {
        presentationRenderer = renderer
        if (renderer != null) currentPresentationFrame?.let(renderer::render)
    }
    fun dispatchSurfaceInteraction(effect: SurfaceInteractionEffect): Boolean =
        dispatchSurfaceInteractionWithDiagnostics(effect).accepted

    internal fun dispatchSurfaceInteractionWithDiagnostics(
        effect: SurfaceInteractionEffect,
    ): SurfaceInteractionDispatchResult {
        val handle = synchronized(this) { activeHandle }
            ?: return SurfaceInteractionDispatchResult(null, false)
        val candidateEvent = SurfaceInteractionProtocol.eventFor(effect, handle.pointerCapabilities)
        if (candidateEvent == null) {
            return SurfaceInteractionDispatchResult(candidateEvent, false)
        }
        playbackHooks.afterSurfaceInteractionCaptured()
        val passiveSequence = runtimeModeSnapshot().passive
        if (
            !passiveSequence &&
            effect.speaker.legacyReference == "1" &&
            !clearMsgQueueIfPinned(handle)
        ) {
            return SurfaceInteractionDispatchResult(candidateEvent, false)
        }
        if (!isPinnedHandle(handle)) return SurfaceInteractionDispatchResult(candidateEvent, false)
        val accepted = requestPinned(
            handle,
            ShioriRequestIntent.raw(
                ShioriMethod.GET,
                candidateEvent,
                SurfaceInteractionProtocol.references(effect),
            ),
        ) { result, admissionStamp ->
            val tagged = (result as? RuntimeResult.Success)?.value ?: return@requestPinned false
            if (!passiveSequence && !runtimeModeSnapshot().passive && isPinnedHandle(handle)) {
                parseShioriResponseAndInsert(tagged, admissionStamp)
            }
            true
        }
        return SurfaceInteractionDispatchResult(candidateEvent, accepted)
    }

    internal fun admitAttachment(
        operationId: Long,
        handle: GhostHandle,
        outcome: BootOutcome,
    ): RuntimeResult<Unit> {
        var clearedDialogueState: DialogueRuntimeState? = null
        var replacementExit = false
        val admitted = synchronized(this) {
            if (admittedAttachmentOperationId == operationId) {
                return@synchronized RuntimeResult.Success(Unit)
            }
            val current = activeHandle
            if (
                current != null &&
                (handle.generation < current.generation || current.generation == handle.generation)
            ) {
                return@synchronized RuntimeResult.Failure(RuntimeFailure.StaleGeneration)
            }
            val response = when (outcome) {
                is BootOutcome.Response -> {
                    if (outcome.tagged.generation != handle.generation) {
                        return@synchronized RuntimeResult.Failure(RuntimeFailure.StaleGeneration)
                    }
                    outcome.tagged.response
                }
                is BootOutcome.BootAttemptFailed -> null
            }
            activeHandle = handle
            val pendingExit = exitOperation
            val attachesRetiredReplacement = retiredGenerationAwaitingAttachment != null
            if (
                pendingExit != null &&
                (
                    pendingExit.phase == ExitPhase.AwaitingReplacement ||
                        (
                            pendingExit.phase == ExitPhase.AwaitingResponse &&
                                pendingExit.generation == null &&
                                attachesRetiredReplacement
                        )
                )
            ) {
                pendingExit.generation = handle.generation
                pendingExit.phase = ExitPhase.AwaitingReplacement
                replacementExit = true
            } else if (pendingExit?.generation != handle.generation) {
                exitOperation = null
            }
            admittedAttachmentOperationId = operationId
            runtimeModeGeneration++
            passive = false
            if (current != null || retiredGenerationAwaitingAttachment != null) {
                clearedDialogueState = clearDialogueStateLocked()
            }
            retiredGenerationAwaitingAttachment = null
            response
                ?.takeIf { !replacementExit }
                ?.takeIf { it.getStatusCode() == 200 }
                ?.getKey("Value")
                ?.takeIf(String::isNotEmpty)
                ?.let {
                    msgQueue.add(it)
                    runtimeModeGeneration++
                }
            bootDispatchState.markBootDispatched()
            RuntimeResult.Success(Unit)
        }
        clearedDialogueState?.let(::publishDialogueState)
        return admitted
    }

    internal fun resumeExitAfterAttachment(expectedGeneration: Long) {
        responseScheduler.value.schedule { resumeExitAfterAttachmentOnMain(expectedGeneration) }
    }

    private fun resumeExitAfterAttachmentOnMain(expectedGeneration: Long) {
        val pending = synchronized(this) {
            val operation = exitOperation
            val handle = activeHandle
            if (
                operation?.phase != ExitPhase.AwaitingReplacement ||
                operation.generation != expectedGeneration ||
                handle?.generation != expectedGeneration
            ) {
                return@synchronized null
            }
            operation.phase = ExitPhase.AwaitingResponse
            operation to handle
        }
        pending?.let { (operation, handle) -> requestExit(operation, handle) }
    }

    internal fun retireGeneration(expectedGeneration: Long, preservedExitOperationId: Long? = null) {
        retireGeneration(expectedGeneration, preservedExitOperationId, forSwitch = false)
    }

    internal fun retireGenerationForSwitch(expectedGeneration: Long) {
        retireGeneration(expectedGeneration, preservedExitOperationId = null, forSwitch = true)
    }

    private fun retireGeneration(
        expectedGeneration: Long,
        preservedExitOperationId: Long?,
        forSwitch: Boolean,
    ) {
        synchronized(this) {
            if (activeHandle?.generation != expectedGeneration) {
                if (forSwitch) retargetExitForSwitchLocked(expectedGeneration)
                return
            }
            activeHandle = null
            val pendingExit = exitOperation
            if (pendingExit?.generation == expectedGeneration) {
                if (forSwitch && retargetExitForSwitchLocked(expectedGeneration)) {
                    Unit
                } else if (pendingExit.operationId != preservedExitOperationId) {
                    exitOperation = null
                }
            }
            admittedAttachmentOperationId = null
            retiredGenerationAwaitingAttachment = expectedGeneration
            pendingSwitch = null
            pendingTimerEvents.removeAll { it.generation == expectedGeneration }
            deferredMinuteBoundary = null
            passive = false
            runtimeModeGeneration++
            msgQueue.clear()
            if (playbackScheduler.isInitialized()) playbackScheduler.value.cancelPending()
            playback = PlaybackState()
            bootDispatchState.resetForNoGhost()
        }
    }

    private fun retargetExitForSwitchLocked(expectedGeneration: Long): Boolean {
        val pendingExit = exitOperation ?: return false
        if (pendingExit.generation != expectedGeneration) return false
        if (
            pendingExit.phase != ExitPhase.AwaitingResponse &&
            pendingExit.phase != ExitPhase.AwaitingPlayback &&
            pendingExit.phase != ExitPhase.Unloading &&
            pendingExit.phase != ExitPhase.ReadyForHost &&
            pendingExit.phase != ExitPhase.AwaitingReplacement
        ) return false
        pendingExit.phase = ExitPhase.AwaitingReplacement
        return true
    }
    @Synchronized fun addMsgToQueue(msgs: Array<String>) {
        if (msgs.isNotEmpty()) runtimeModeGeneration++
        msgs.forEach { msgQueue.add(it) }
    }
    fun setNoWaitMode(wait: Boolean) { noWaitMode=wait }; fun setCallback(c: StatusCallback?) { cb=c }; fun setUICallback(c: UICallback?) { ucb=c }
    private val clockHandler: Handler by lazy { object: Handler(Looper.getMainLooper()) { override fun handleMessage(m: Message) { if(m.what==INC_CLOCK){perClockEvent();sendEmptyMessageDelayed(INC_CLOCK,1000)} } } }
    fun resumeEvt() {
        val resumed = synchronized(this) {
            val state = playback
            val wasPaused = state.paused
            if (wasPaused) state.paused = false
            state.takeIf { wasPaused && state.running }
        }
        if (resumed == null) run() else schedulePlayback(RUN, state = resumed)
    }
    private fun dispatchPlayback(command: Int, state: PlaybackState) {
        if (command == RUN) {
            loopControl(state)
        } else if (command == STOP && isPlaybackCurrent(state)) {
            playbackHooks.afterStopClaimed()
            stop(state, continueQueuedTalk = true)
        }
    }
    private fun isPlaybackCurrent(state: PlaybackState): Boolean = synchronized(this) {
        playback === state && state.running
    }
    private fun schedulePlayback(command: Int, delayMillis: Long = 0L, state: PlaybackState) {
        synchronized(this) {
            if (playback !== state || !state.running) return
            playbackScheduler.value.schedule(delayMillis) { dispatchPlayback(command, state) }
        }
    }
    private fun loopControl(state: PlaybackState) {
        val claimed = synchronized(this) {
            playback === state && state.running && !state.paused && !state.authoredRequestPending
        }
        if (!claimed) return
        playbackHooks.afterRunClaimed()
        val current = synchronized(this) {
            if (playback !== state || !state.running || state.paused || state.authoredRequestPending) return
            state.msg?.takeIf { state.charIndex < it.length }
        }
        if (current != null) {
            parseMsg(state)
            updateUI(state)
            publishDialogueProjection(state)
            val suspended = synchronized(this) {
                playback !== state || state.paused || state.authoredRequestPending
            }
            if (!suspended) {
                if (noWaitMode) loopControl(state) else schedulePlayback(RUN, state.waitTime, state)
            }
            return
        }
        val next = synchronized(this) {
            if (playback !== state || !state.running || state.paused || state.authoredRequestPending) return
            reset(state)
            state.msg = getFromQueue(state)
            state.msg
        }
        if (next == null) {
            if (noWaitMode) stop(state) else schedulePlayback(STOP, state.waitTime, state)
        } else if (noWaitMode) {
            loopControl(state)
        } else {
            schedulePlayback(RUN, state.waitTime, state)
        }
    }
    internal fun startClock(token: HostToken): Boolean = startClockOwnedBy(token)

    fun startClock() {
        startClockOwnedBy(null)
    }

    private fun startClockOwnedBy(token: HostToken?): Boolean {
        LegacyPlatform.debug(TAG, "startClock called")
        val start = synchronized(this) {
            if (token != null && hostOwner !== token) return false
            if (exitOperation != null) return false
            clockOwner = token
            bootDispatchState.startClock().also { if (it.started) clockEpoch++ }
        }
        if (!start.started) return true
        LegacyPlatform.scheduleDelayed(CLOCK_STEP) {
            clockHandler.sendEmptyMessageDelayed(INC_CLOCK, CLOCK_STEP)
        }
        if (restore) {
            doShioriEvent("OnWindowStateRestore", null)
        }
        restore = false
        return true
    }

    internal fun stopClock(token: HostToken): Boolean = stopClockOwnedBy(token)

    fun stopClock() {
        stopClockOwnedBy(null)
    }

    private fun stopClockOwnedBy(token: HostToken?): Boolean {
        synchronized(this) {
            if (
                token != null &&
                (hostOwner !== token || (clockOwner != null && clockOwner !== token))
            ) {
                return false
            }
            clockOwner = null
            clockEpoch++
            deferredMinuteBoundary = null
            runtimeModeGeneration++
            bootDispatchState.stopClock()
        }
        LegacyPlatform.cancelDelayed { clockHandler.removeMessages(INC_CLOCK) }
        return true
    }
    override fun run() {
        val prepared = synchronized(this) {
            val state = playback
            if (state.running) return
            if (pendingSwitch != null && msgQueue.isEmpty()) return
            state.running = true
            runtimeModeGeneration++
            reset(state)
            state.msg = getFromQueue(state)
            state to (state.msg == null)
        }
        playbackHooks.afterRunPrepared()
        val (state, shouldStop) = prepared
        if (shouldStop) stop(state) else if (noWaitMode) loopControl(state) else schedulePlayback(RUN, state = state)
    }
    private fun getFromQueue(state: PlaybackState) = rewriteMsg(msgQueue.poll()).also { script ->
        state.dialogueScript = script?.let(::recordDialogueScript)
    }
    private fun rewriteMsg(input: String?): String? {
        val ghost = activeHandle?.ghost ?: return input
        if (input == null) return null
        return input
            .replace("%username", ghost.username)
            .replace("%selfname2?", ghost.sakuraName ?: "null")
            .replace("%keroname", ghost.keroName ?: "null")
    }
    fun clearMsgQueue() {
        val state = synchronized(this) {
            msgQueue.clear()
            playback.msg = null
            runtimeModeGeneration++
            requestAdmissionEpoch++
            playback
        }
        stop(state)
    }
    private fun clearMsgQueueIfPinned(handle: GhostHandle): Boolean {
        val state = synchronized(this) {
            if (activeHandle?.generation != handle.generation) return false
            requestAdmissionEpoch++
            msgQueue.clear()
            playback.msg = null
            runtimeModeGeneration++
            playback
        }
        stop(state)
        return true
    }
    fun stop() {
        val state = synchronized(this) {
            requestAdmissionEpoch++
            playback
        }
        stop(state)
    }
    private fun stop(state: PlaybackState, continueQueuedTalk: Boolean = false) {
        val switch = synchronized(this) {
            if (playback !== state) return
            pendingSwitch
        }
        finishStop(state, switch, continueQueuedTalk)
    }
    private fun finishStop(
        state: PlaybackState,
        switch: RunnerSwitchOperation?,
        continueQueuedTalk: Boolean,
    ) {
        var restarted = false
        val effects = synchronized(this) {
            if (playback !== state) return
            if (continueQueuedTalk && switch == null && msgQueue.isNotEmpty()) {
                reset(state)
                state.msg = getFromQueue(state)
                restarted = true
                null
            } else {
                if (playbackScheduler.isInitialized()) playbackScheduler.value.cancelPending()
                state.running = false
                state.paused = false
                state.authoredRequestPending = false
                if (switch != null) {
                    passive = false
                    runtimeModeGeneration++
                }
                state.bSakuraId = "-1"
                state.bKeroId = "-1"
                val frame = takePresentationFrame(state)
                currentPresentationFrame = frame
                val renderer = presentationRenderer
                val callback = cb
                val pendingExit = exitOperation
                val exitOperationId = if (pendingExit?.phase == ExitPhase.AwaitingPlayback) {
                    if (pendingExit.generation != activeHandle?.generation) {
                        exitOperation = null
                        null
                    } else {
                        pendingExit.operationId
                    }
                } else {
                    null
                }
                val handoff = pendingSwitch == switch && switch != null
                if (handoff) pendingSwitch = null
                playback = PlaybackState(state.talkAnimeControl)
                StopEffects(renderer, frame, callback, exitOperationId, switch.takeIf { handoff })
            }
        }
        if (restarted) {
            if (noWaitMode) loopControl(state) else schedulePlayback(RUN, state.waitTime, state)
            return
        }
        effects ?: return
        try {
            effects.renderer?.render(effects.frame)
            effects.callback?.stop()
        } finally {
            effects.exitOperationId?.let(::completePlaybackExit)
        }
        effects.switch?.let { operation ->
            runtimePort.completeSwitchPlaybackFromRunner(
                operation.outgoingGeneration,
                operation.operationId,
            )
            runCatching { effects.callback?.switchPlaybackComplete() }
        }
    }
    private data class StopEffects(
        val renderer: GhostPresentationRenderer?,
        val frame: GhostPresentationFrame,
        val callback: StatusCallback?,
        val exitOperationId: Long?,
        val switch: RunnerSwitchOperation?,
    )
    private fun reset(state: PlaybackState){
        state.sync = false
        state.wholeline = false
        state.scope = 0
        state.sakuraTalk = true
        state.sakuraMsg.setLength(0)
        state.keroMsg.setLength(0)
        state.msg = ""
        state.charIndex = 0
        state.bSakuraId = "-1"
        state.bKeroId = "-1"
        state.sakuraAnimationId = null
        state.keroAnimationId = null
        state.legacyChoiceCallbackPublished = false
    }
    private fun appendChar(state: PlaybackState, c: Char) {
        if (state.scope >= 2) return
        if (state.sync) {
            state.sakuraMsg.append(c)
            state.keroMsg.append(c)
        } else if (state.sakuraTalk) {
            state.sakuraMsg.append(c)
        } else {
            state.keroMsg.append(c)
        }
        if (state.keroMsg.isNotEmpty()) state.bKeroId = "0"
    }

    private fun clearMsg(state: PlaybackState) {
        if (state.sakuraTalk) state.sakuraMsg.setLength(0) else state.keroMsg.setLength(0)
    }

    private fun parseMsg(state: PlaybackState) {
        state.waitTime = WAIT_UNIT
        while (true) try {
            val text = state.msg!!
            val c1 = text[state.charIndex++]
            if (c1 != '\\') {
                appendChar(state, c1)
                if (state.wholeline) continue else break
            }
            when (val c2 = text[state.charIndex++]) {
                '0', 'h' -> {
                    val wasKero = !state.sakuraTalk
                    state.sakuraTalk = true
                    state.scope = 0
                    if (wasKero) {
                        state.sakuraMsg.setLength(0)
                    }
                }
                '1', 'u' -> {
                    state.scope = 1
                    state.sakuraTalk = false
                    state.keroMsg.setLength(0)
                }
                'p' -> parseScope(state)
                's' -> if (handleSurface(state, state.scope < 2)) break
                'i' -> if (handleAnimation(state, state.scope < 2)) break
                'e' -> {
                    state.charIndex = text.length
                    state.waitTime = WAIT_YEN_E
                    break
                }
                'n' -> {
                    if (state.scope < 2) appendChar(state, '\n')
                    val matcher = PatternHolders.sqbracket_half_number.matcher(text.substring(state.charIndex))
                    if (matcher.find()) state.charIndex += matcher.group().length
                    break
                }
                'c' -> if (state.scope < 2) clearMsg(state)
                '_' -> if (handleUnderscore(state)) break
                '!' -> if (handleExclaim(state, state.scope < 2)) break
                'w' -> {
                    val wait = text[state.charIndex++]
                    if (wait.isDigit()) {
                        state.waitTime = (wait - '0') * WAIT_UNIT
                        break
                    }
                }
                'b' -> if (handleBalloon(state, state.scope < 2)) break
                'q' -> if (state.scope < 2) handleSelection(state) else if (handleSelection(state, false)) Unit
                '-', '4', '5', '6', 'v' -> Log.d(TAG, "ignore unsupported $c2 tag")
                else -> Unit
            }
        } catch (_: Exception) {
            break
        }
    }

    private fun parseScope(state: PlaybackState) {
        val text = state.msg ?: return
        if (state.charIndex >= text.length) return
        val previouslySakura = state.sakuraTalk
        val directScope = text[state.charIndex].digitToIntOrNull()
        if (directScope != null) {
            if (directScope == 0 && !previouslySakura) state.sakuraMsg.setLength(0)
            if (directScope == 1 && previouslySakura) state.keroMsg.setLength(0)
            state.scope = directScope
            when (directScope) {
                0 -> state.sakuraTalk = true
                1 -> state.sakuraTalk = false
            }
            state.charIndex++
            return
        }
        if (text[state.charIndex] != '[') return
        val bracket = parseBracketScope(text, state.charIndex)
        if (bracket == null) {
            state.charIndex = text.indexOf('\\', state.charIndex).takeIf { it >= 0 } ?: text.length
            return
        }
        state.charIndex = bracket.nextIndex
        val resolvedScope = bracket.value.toIntOrNull() ?: return
        if (resolvedScope == 0 && !state.sakuraTalk) state.sakuraMsg.setLength(0)
        if (resolvedScope == 1 && state.sakuraTalk) state.keroMsg.setLength(0)
        state.scope = resolvedScope
        when (state.scope) {
            0 -> state.sakuraTalk = true
            1 -> state.sakuraTalk = false
        }
    }

    private data class ScopeBracket(val value: String, val nextIndex: Int)
    private fun parseBracketScope(text: String, start: Int): ScopeBracket? {
        if (text.getOrNull(start) != '[') return null
        val body = StringBuilder()
        var index = start + 1
        var depth = 1
        var quoted = false
        while (index < text.length) {
            val character = text[index++]
            if (character == '\\' && index < text.length) {
                val escaped = text[index++]
                body.append('\\').append(escaped)
                continue
            }
            if (character == '"') {
                if (quoted && text.getOrNull(index) == '"') {
                    body.append("\"\"")
                    index++
                } else {
                    quoted = !quoted
                    body.append(character)
                }
                continue
            }
            if (!quoted && character == '[') {
                depth++
            }
            if (!quoted && character == ']') {
                depth--
                if (depth == 0) return ScopeBracket(body.toString(), index)
            }
            body.append(character)
        }
        return null
    }

    private fun handleUnderscore(state: PlaybackState): Boolean {
        val text = state.msg!!
        when (val c = text[state.charIndex++]) {
            's' -> state.sync = !state.sync
            'q' -> state.wholeline = !state.wholeline
            'l', 'a', 'v' -> {
                val matcher = PatternHolders.sqbracket_half_number.matcher(text.substring(state.charIndex))
                if (matcher.find()) state.charIndex += matcher.group().length
            }
            'b' -> return handleBalloon(state, state.scope < 2)
            'w' -> {
                val matcher = PatternHolders.sqbracket_half_number.matcher(text.substring(state.charIndex))
                if (matcher.find()) {
                    state.charIndex += matcher.group().length
                    try {
                        state.waitTime = requireNotNull(matcher.group(1)).toLong()
                        return true
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return false
    }

    private fun handleExclaim(state: PlaybackState, publishSelection: Boolean): Boolean {
        val remaining = state.msg!!.substring(state.charIndex)
        val passiveCommand = SakuraScriptCommandParser.readBracket(remaining, 0)?.let { bracket ->
            SakuraScriptCommandParser.splitArguments(bracket.value)
                .takeIf { args ->
                    args.size == 2 &&
                        args[0] in setOf("enter", "leave") &&
                        args[1] == "passivemode"
                }
                ?.let { args -> args[0] to bracket.nextIndex }
        }
        if (passiveCommand != null) {
            state.charIndex += passiveCommand.second
            synchronized(this) {
                if (playback === state && state.running) {
                    val updatedPassive = passiveCommand.first == "enter"
                    if (passive != updatedPassive) {
                        passive = updatedPassive
                        runtimeModeGeneration++
                    }
                }
            }
            return false
        }
        val input = consumeOpenInputCommand(remaining) ?: return false
        state.charIndex += input.consumedCharacters
        if (!publishSelection) return false
        openUserInputBox(state, input.id)
        return true
    }

    private data class OpenInputCommand(val consumedCharacters: Int, val id: String)

    /** Consumes exactly one bracket command; a later input command must remain for the next step. */
    private fun consumeOpenInputCommand(remaining: String): OpenInputCommand? {
        val prefix = listOf("[open,inputbox,", "[open,passwordinput,")
            .firstOrNull(remaining::startsWith)
            ?: return null
        var quote: Char? = null
        var escaped = false
        var depth = 0
        var end = -1
        remaining.forEachIndexed { index, character ->
            if (end >= 0) return@forEachIndexed
            if (escaped) {
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (quote != null) {
                if (character == quote) quote = null
            } else if (character == '\'' || character == '"') {
                quote = character
            } else if (character == '[') {
                depth++
            } else if (character == ']' && --depth == 0) {
                end = index
            }
        }
        if (end < 0) return null
        val payload = remaining.substring(prefix.length, end)
        quote = null
        escaped = false
        var separator = payload.length
        payload.forEachIndexed { index, character ->
            if (separator != payload.length) return@forEachIndexed
            if (escaped) {
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (quote != null) {
                if (character == quote) quote = null
            } else if (character == '\'' || character == '"') {
                quote = character
            } else if (character == ',') {
                separator = index
            }
        }
        val id = payload.substring(0, separator).trim().let { raw ->
            when {
                raw.length >= 2 && raw.first() == '"' && raw.last() == '"' -> raw.substring(1, raw.lastIndex)
                raw.length >= 2 && raw.first() == '\'' && raw.last() == '\'' -> raw.substring(1, raw.lastIndex)
                else -> raw
            }
        }
        return OpenInputCommand(end + 1, id)
    }

    private fun openUserInputBox(state: PlaybackState, id: String?) {
        if (id == null) return
        val callback = synchronized(this) {
            if (playback !== state || !state.running) return
            ucb
        }
        playbackHooks.afterInputEffectCaptured()
        publishPlaybackEffect(state) {
            if (ucb !== callback) return@publishPlaybackEffect
            callback?.also { state.paused = true }?.showUserInputBox(id)
        }
    }

    private fun handleSurface(state: PlaybackState, apply: Boolean): Boolean {
        val matcher = PatternHolders.surface_ptrn.matcher(state.msg!!.substring(state.charIndex))
        if (!matcher.find()) return false
        if (apply) changeSurface(state, matcher.group(2) ?: matcher.group(1))
        state.charIndex += matcher.group().length
        return true
    }

    private fun handleBalloon(state: PlaybackState, apply: Boolean): Boolean {
        val matcher = PatternHolders.balloon_ptrn.matcher(state.msg!!.substring(state.charIndex))
        if (!matcher.find()) return false
        if (apply) changeBalloon(state, matcher.group(2) ?: matcher.group(1))
        state.charIndex += matcher.group().length
        return true
    }

    private fun handleAnimation(state: PlaybackState, apply: Boolean): Boolean {
        val matcher = PatternHolders.ani_ptrn.matcher(state.msg!!.substring(state.charIndex))
        if (!matcher.find()) return false
        if (apply) queueAnimation(state, requireNotNull(matcher.group(1)))
        state.charIndex += matcher.group().length
        return true
    }

    private fun handleSelection(state: PlaybackState, publishSelection: Boolean = true): Boolean {
        val text = state.msg ?: return false
        val matcher = PatternHolders.q_choice_ptrn.matcher(text)
        val commandStart = state.charIndex - 2
        if (!matcher.find(commandStart) || matcher.start() != commandStart) return false
        state.charIndex = matcher.end()
        if (publishSelection) appendChoiceLabel(state, requireNotNull(matcher.group(1)))
        val callback = synchronized(this) {
            if (playback !== state || !state.running) return false
            ucb
        }
        if (callback == null || state.legacyChoiceCallbackPublished || !publishSelection) return false
        val remainingChoices = SakuraScriptTokenizer.remainingVisibleChoices(
            script = text,
            commandStart = commandStart,
            initialScope = state.scope,
        )
        state.legacyChoiceCallbackPublished = true
        state.wholeline = true
        playbackHooks.afterSelectionEffectCaptured()
        publishPlaybackEffect(state) {
            if (ucb !== callback) return@publishPlaybackEffect
            callback.showUserSelection(
                remainingChoices.map { it.label }.toTypedArray(),
                remainingChoices.map { it.id }.toTypedArray(),
            )
        }
        return false
    }

    private fun appendChoiceLabel(state: PlaybackState, label: String) {
        if (state.sync || state.sakuraTalk) state.sakuraMsg.append(label)
        if (state.sync || !state.sakuraTalk) state.keroMsg.append(label)
    }

    private fun changeSurface(state: PlaybackState, id: String) {
        playbackHooks.afterSurfaceChangeCaptured()
        doPlaybackShioriEvent(state, "OnSurfaceChange") {
            if (state.sakuraTalk) sakuraSurfaceId = id else keroSurfaceId = id
            arrayOf("Reference0: $sakuraSurfaceId", "Reference1: $keroSurfaceId")
        }
    }

    private fun doPlaybackShioriEvent(
        state: PlaybackState,
        event: String,
        prepareReferences: () -> Array<String>,
    ): Boolean {
        val awaitResponse = runsOnMainLooper()
        val captured = synchronized(this) {
            if (playback !== state || !state.running) return false
            val handle = activeHandle ?: run {
                // Host-only and pre-attachment playback still owns its visual surface state,
                // but there is no SHIORI session to pin or notify.
                prepareReferences()
                return false
            }
            val references = prepareReferences()
            if (awaitResponse) {
                if (state.authoredRequestPending) return false
                state.authoredRequestPending = true
            }
            handle to references
        }
        val (handle, references) = captured
        return try {
            requestPinned(
                handle,
                ShioriRequestIntent.event(event, references.toList()),
                onUnscheduled = {
                    if (awaitResponse) finishPlaybackShioriRequest(state, resume = false)
                },
            ) { result, admissionStamp ->
                try {
                    val tagged = (result as? RuntimeResult.Success)?.value
                        ?: return@requestPinned false
                    parsePlaybackShioriResponseAndInsert(
                        state,
                        handle,
                        tagged.response,
                        admissionStamp,
                    )
                    true
                } finally {
                    if (awaitResponse) finishPlaybackShioriRequest(state, resume = true)
                }
            }
        } catch (failure: Throwable) {
            if (awaitResponse) finishPlaybackShioriRequest(state, resume = false)
            throw failure
        }
    }

    private fun finishPlaybackShioriRequest(state: PlaybackState, resume: Boolean) {
        val shouldResume = synchronized(this) {
            if (!state.authoredRequestPending) return
            state.authoredRequestPending = false
            resume && playback === state && state.running && !state.paused
        }
        if (!shouldResume) return
        if (noWaitMode) loopControl(state) else schedulePlayback(RUN, state = state)
    }

    private fun parsePlaybackShioriResponseAndInsert(
        state: PlaybackState,
        handle: GhostHandle,
        response: ShioriResponse?,
        admissionStamp: RequestAdmissionStamp,
    ) {
        if (response == null || response.getStatusCode() != 200) return
        val value = response.getKey("Value") ?: return
        synchronized(this) {
            if (
                activeHandle?.generation != handle.generation ||
                !admissionIsCurrent(admissionStamp) ||
                playback !== state ||
                !state.running
            ) return
            msgQueue.add(value)
        }
    }

    private fun changeBalloon(state: PlaybackState, id: String) {
        if (state.sakuraTalk) state.bSakuraId = id else state.bKeroId = id
    }

    private fun queueAnimation(state: PlaybackState, id: String) {
        if (state.sakuraTalk) state.sakuraAnimationId = id else state.keroAnimationId = id
    }

    private fun updateUI(state: PlaybackState) {
        val frame = synchronized(this) {
            if (playback !== state || !state.running) return
            takePresentationFrame(state)
        }
        playbackHooks.afterPresentationEffectCaptured()
        publishPlaybackEffect(state) {
            currentPresentationFrame = frame
            presentationRenderer?.render(frame)
        }
    }

    /**
     * Totally orders an external playback effect with session invalidation.
     * Production UI callbacks only re-enter synchronized runner snapshots (the JVM monitor is
     * reentrant), and the Compose renderer never acquires the runtime state lock. Keep SHIORI
     * and runtime calls out of this block so the runtime -> runner lock order is preserved.
     */
    private fun publishPlaybackEffect(state: PlaybackState, effect: () -> Unit) {
        synchronized(this) {
            if (playback !== state || !state.running) return
            effect()
        }
    }

    private fun takePresentationFrame(state: PlaybackState): GhostPresentationFrame {
        val sakuraAnimated = state.sakuraAnimationId != null
        val keroAnimated = state.keroAnimationId != null
        val frame = GhostPresentationFrame(
            GhostPresentationFrame.Speaker(
                state.sakuraMsg.toString(),
                sakuraSurfaceId,
                state.sakuraAnimationId,
                state.bSakuraId,
            ),
            GhostPresentationFrame.Speaker(
                state.keroMsg.toString(),
                keroSurfaceId,
                state.keroAnimationId,
                state.bKeroId,
            ),
            state.talkAnimeControl == 0,
        )
        if (sakuraAnimated) state.sakuraAnimationId = null
        if (keroAnimated) state.keroAnimationId = null
        state.talkAnimeControl++
        if (state.talkAnimeControl == 10) state.talkAnimeControl = 0
        return frame
    }
    private fun doPerSecondEvent(hr: Long, generation: Long, epoch: Long): Boolean =
        dispatchTimerEvent("OnSecondChange", hr, generation, epoch)
    private fun doPerMinuteEvent(hr: Long, generation: Long, epoch: Long): Boolean =
        dispatchTimerEvent("OnMinuteChange", hr, generation, epoch)
    private fun dispatchTimerEvent(
        event: String,
        uptimeHours: Long,
        expectedGeneration: Long,
        expectedClockEpoch: Long,
    ): Boolean {
        val captured = synchronized(this) {
            val handle = activeHandle ?: return false
            if (handle.generation != expectedGeneration || clockEpoch != expectedClockEpoch) return false
            val key = TimerEventKey(event, handle.generation, clockEpoch)
            if (hasPendingTimerEvent(event, handle.generation)) return false
            pendingTimerEvents.add(key)
            TimerRequestCapture(handle, key, runtimeModeSnapshot().canTalk, runtimeModeGeneration)
        }
        val method = if (captured.wasIdle) ShioriMethod.GET else ShioriMethod.NOTIFY
        return requestPinned(
            captured.handle,
            ShioriRequestIntent.raw(
                method,
                event,
                listOf(uptimeHours.toString(), "0", "0", if (captured.wasIdle) "1" else "0"),
            ),
            onUnscheduled = { settleTimerEvent(captured.key) },
        ) { result, admissionStamp ->
            try {
                val tagged = (result as? RuntimeResult.Success)?.value ?: return@requestPinned false
                if (!captured.wasIdle) return@requestPinned true
                val value = tagged.response
                    .takeIf { it.getStatusCode() == 200 }
                    ?.getKey("Value")
                    ?: return@requestPinned true
                playbackHooks.beforeTimerResponseAdmission()
                val shouldRun = synchronized(this) {
                    if (
                        !admissionIsCurrent(admissionStamp) ||
                        !timerResponseIsEligible(captured.handle, captured.modeGeneration)
                    ) {
                        false
                    } else {
                        msgQueue.add(value)
                        runtimeModeGeneration++
                        !playback.running
                    }
                }
                if (shouldRun) run()
                true
            } finally {
                settleTimerEvent(captured.key)
            }
        }
    }

    private fun hasPendingTimerEvent(event: String, generation: Long): Boolean =
        pendingTimerEvents.any { it.event == event && it.generation == generation }

    private fun settleTimerEvent(key: TimerEventKey) {
        val minuteBoundary = synchronized(this) {
            pendingTimerEvents.remove(key)
            deferredMinuteBoundary?.takeIf {
                key.event == "OnSecondChange" &&
                    it.generation == key.generation &&
                    it.clockEpoch == key.clockEpoch
            }
        }
        minuteBoundary?.let(::dispatchDeferredMinuteBoundary)
    }

    private fun dispatchDeferredMinuteBoundary(boundary: DeferredMinuteBoundary) {
        if (synchronized(this) { deferredMinuteBoundary != boundary }) return
        if (!doPerMinuteEvent(boundary.uptimeHours, boundary.generation, boundary.clockEpoch)) return
        synchronized(this) {
            if (deferredMinuteBoundary == boundary) {
                lastElapsedMinute = boundary.elapsedMinute
                deferredMinuteBoundary = null
            }
        }
    }

    private fun perClockEvent() {
        val secondsAll = monotonicClock.nowMillis() / 1_000L
        val minutesAll = secondsAll / 60L
        val hour = minutesAll / 60L
        val (generation, epoch) = synchronized(this) {
            (activeHandle?.generation ?: return) to clockEpoch
        }
        val minuteBoundary = when {
            lastElapsedMinute == null -> {
                lastElapsedMinute = minutesAll
                null
            }
            lastElapsedMinute != minutesAll -> synchronized(this) {
                if (
                    activeHandle?.generation == generation &&
                    clockEpoch == epoch &&
                    !hasPendingTimerEvent("OnMinuteChange", generation)
                ) {
                    DeferredMinuteBoundary(minutesAll, hour, generation, epoch).also { boundary ->
                        deferredMinuteBoundary = boundary
                    }
                } else {
                    null
                }
            }
            else -> null
        }
        var secondIsSettling = synchronized(this) {
            hasPendingTimerEvent("OnSecondChange", generation)
        }
        if (lastElapsedSecond != secondsAll && doPerSecondEvent(hour, generation, epoch)) {
            lastElapsedSecond = secondsAll
            secondIsSettling = true
        }
        if (minuteBoundary != null && !secondIsSettling) dispatchDeferredMinuteBoundary(minuteBoundary)
    }
    internal fun dispatchClockTickForTesting() = perClockEvent()
    private fun parseShioriResponseAndInsert(
        tagged: TaggedShioriResponse,
        admissionStamp: RequestAdmissionStamp? = null,
    ): Boolean {
        if (tagged.response.getStatusCode() != 200) return false
        val value = tagged.response.getKey("Value") ?: return false
        val shouldRun = synchronized(this) {
            if (
                activeHandle?.generation != tagged.generation ||
                (admissionStamp != null && !admissionIsCurrent(admissionStamp))
            ) return@synchronized null
            msgQueue.add(value)
            !playback.running
        } ?: return false
        if (shouldRun) run()
        return true
    }
    private fun doMouseWheel(x:Int,y:Int,w:Int,s:Boolean,c:Int)=doShioriEvent("OnMouseWheel",arrayOf("$x","$y","$w",if(s)"0" else "1",if(c>-1)"$c" else "",null,"touch"))
    private fun doMouseMove(x:Int,y:Int,w:Int,s:Boolean,c:Int)=doShioriEvent("OnMouseMove",arrayOf("$x","$y","$w",if(s)"0" else "1",if(c>-1)"$c" else "",null,"touch"))
    fun doMinimize(){doShioriEvent("OnWindowStateMinimize",null)};fun doRestore(){restore=true}
    fun doExit() {
        val captured = synchronized(this) {
            if (exitOperation != null) return
            val operation = ExitOperation(++nextExitOperationId, activeHandle?.generation)
            val abandonedSwitch = pendingSwitch.also { pendingSwitch = null }
            exitOperation = operation
            msgQueue.clear()
            playback.msg = null
            runtimeModeGeneration++
            requestAdmissionEpoch++
            ExitCapture(operation, activeHandle, playback, abandonedSwitch)
        }
        val (operation, handle, state, abandonedSwitch) = captured
        playbackHooks.afterExitCaptured()
        abandonedSwitch?.let { switch ->
            runtimePort.failSwitchBeforeUnload(
                switch.outgoingGeneration,
                switch.operationId,
                IllegalStateException("Ghost switch was superseded by authored exit"),
            )
        }
        stop(state)
        if (handle == null) {
            if (!awaitReplacementIfRuntimeOwnsSwitch(operation)) beginExitUnload(operation)
            return
        }
        requestExit(operation, handle)
    }

    private fun requestExit(operation: ExitOperation, handle: GhostHandle) {
        requestPinned(
            handle,
            ShioriRequestIntent.event("OnClose"),
            onUnscheduled = {
                if (!awaitReplacementIfRuntimeOwnsSwitch(operation)) beginExitUnload(operation)
            },
            fenceAdmissionEpoch = false,
            admission = { result, _ -> admitExitResponse(operation, result) },
        )
    }

    private fun admitExitResponse(
        operation: ExitOperation,
        result: RuntimeResult<TaggedShioriResponse>,
    ): Boolean {
        if (result is RuntimeResult.Failure && result.failure == RuntimeFailure.StaleGeneration) {
            if (awaitReplacementIfRuntimeOwnsSwitch(operation)) return true
            return cancelExit(operation)
        }
        val playable = (result as? RuntimeResult.Success)?.value?.takeIf { tagged ->
            tagged.response.getStatusCode() == 200 &&
                !tagged.response.getKey("Value").isNullOrEmpty()
        }
        if (playable != null) {
            val shouldPlay = synchronized(this) {
                if (exitOperation?.operationId != operation.operationId) return@synchronized false
                if (operation.generation != activeHandle?.generation) {
                    if (operation.phase != ExitPhase.AwaitingReplacement) exitOperation = null
                    return@synchronized false
                }
                operation.phase = ExitPhase.AwaitingPlayback
                true
            }
            if (shouldPlay && parseShioriResponseAndInsert(playable)) return true
            if (synchronized(this) {
                    exitOperation?.operationId == operation.operationId &&
                        operation.phase == ExitPhase.AwaitingReplacement
                }
            ) return true
        }
        return beginExitUnload(operation)
    }

    private fun awaitReplacementIfRuntimeOwnsSwitch(operation: ExitOperation): Boolean {
        val identity = runtimePort.identity()
        val runtimeIsReplacing = identity.phase == GhostRuntimePhase.Replacing ||
            identity.pending != null ||
            identity.activeHandle?.generation?.let { it != operation.generation } == true
        return synchronized(this) {
            if (exitOperation?.operationId != operation.operationId) {
                false
            } else if (
                operation.phase == ExitPhase.AwaitingResponse &&
                operation.generation != null &&
                operation.generation == activeHandle?.generation
            ) {
                true
            } else if (operation.phase == ExitPhase.AwaitingReplacement || runtimeIsReplacing) {
                operation.phase = ExitPhase.AwaitingReplacement
                true
            } else {
                false
            }
        }
    }

    private fun beginExitUnload(operation: ExitOperation): Boolean {
        var awaitingReplacement = false
        val accepted = synchronized(this) {
            if (exitOperation?.operationId != operation.operationId) return@synchronized false
            if (operation.generation != activeHandle?.generation) {
                if (operation.phase == ExitPhase.AwaitingReplacement) {
                    awaitingReplacement = true
                } else {
                    exitOperation = null
                    return@synchronized false
                }
            }
            if (awaitingReplacement) {
                true
            } else if (operation.generation == null) {
                operation.phase = ExitPhase.ReadyForHost
                true
            } else {
                operation.phase = ExitPhase.Unloading
                true
            }
        }
        if (!accepted) return false
        if (awaitingReplacement) return true
        val generation = operation.generation
        if (generation == null) {
            schedulePendingExitDelivery(operation.operationId)
        } else if (runsOnMainLooper()) {
            runtimePort.unloadForExit(generation, operation.operationId)
        } else {
            runtimePort.unloadForExitImmediately(generation, operation.operationId)
        }
        return true
    }

    private fun completePlaybackExit(operationId: Long) {
        val generation = synchronized(this) {
            val operation = exitOperation
            if (
                operation?.operationId != operationId ||
                operation.phase != ExitPhase.AwaitingPlayback
            ) {
                return@synchronized null
            }
            if (operation.generation != activeHandle?.generation) {
                if (operation.phase != ExitPhase.AwaitingReplacement) exitOperation = null
                return@synchronized null
            }
            operation.phase = ExitPhase.Unloading
            operation.generation
        }
        generation ?: return
        if (runsOnMainLooper()) {
            runtimePort.unloadForExit(generation, operationId)
        } else {
            runtimePort.unloadForExitImmediately(generation, operationId)
        }
    }

    internal fun completeExitUnload(
        expectedGeneration: Long,
        operationId: Long,
        result: RuntimeResult<Unit>,
    ) {
        if (result is RuntimeResult.Success) {
            retireGeneration(expectedGeneration, preservedExitOperationId = operationId)
        }
        responseScheduler.value.schedule { admitExitUnload(expectedGeneration, operationId) }
    }

    internal fun completeExitUnloadImmediately(
        expectedGeneration: Long,
        operationId: Long,
        result: RuntimeResult<Unit>,
    ) {
        if (result is RuntimeResult.Success) {
            retireGeneration(expectedGeneration, preservedExitOperationId = operationId)
        }
        admitExitUnload(expectedGeneration, operationId)
    }

    private fun admitExitUnload(expectedGeneration: Long, operationId: Long) {
        val runtimeIdentity = runtimePort.identity()
        val ready = synchronized(this) {
            val operation = exitOperation
            if (
                operation?.operationId != operationId ||
                operation.generation != expectedGeneration ||
                operation.phase != ExitPhase.Unloading
            ) {
                return@synchronized false
            }
            if (!exitGenerationHasNoReplacement(operation, runtimeIdentity)) {
                operation.phase = ExitPhase.AwaitingReplacement
                return@synchronized false
            }
            operation.phase = ExitPhase.ReadyForHost
            true
        }
        if (ready) schedulePendingExitDelivery(operationId)
    }

    private fun schedulePendingExitDelivery(
        operationId: Long,
        expectedToken: HostToken? = null,
        expectedCallback: StatusCallback? = null,
    ) {
        val target = synchronized(this) {
            val token = expectedToken ?: hostOwner ?: return@synchronized null
            val callback = expectedCallback ?: cb ?: return@synchronized null
            if (hostOwner !== token || cb !== callback) return@synchronized null
            token to callback
        } ?: return
        responseScheduler.value.schedule {
            deliverPendingExit(operationId, target.first, target.second)
        }
    }

    private fun deliverPendingExit(
        operationId: Long,
        expectedToken: HostToken,
        expectedCallback: StatusCallback,
    ): Boolean {
        val runtimeIdentity = runtimePort.identity()
        val lease = synchronized(this) {
            val operation = exitOperation
            if (
                operation?.operationId != operationId ||
                operation.phase != ExitPhase.ReadyForHost
            ) {
                return@synchronized null
            }
            if (!exitGenerationHasNoReplacement(operation, runtimeIdentity)) {
                operation.phase = ExitPhase.AwaitingReplacement
                return@synchronized null
            }
            val callback = cb ?: return@synchronized null
            val token = hostOwner
            if (token !== expectedToken || callback !== expectedCallback) return@synchronized null
            exitOperation = null
            ExitDeliveryLease(operation.operationId, operation.generation, token, callback)
        } ?: return false
        lease.callback.canExit(lease.generation)
        return true
    }

    private fun cancelExit(operation: ExitOperation): Boolean = synchronized(this) {
        if (exitOperation?.operationId != operation.operationId) return@synchronized false
        exitOperation = null
        true
    }

    private fun exitOperationFor(operationId: Long): ExitOperation? = synchronized(this) {
        exitOperation?.takeIf { it.operationId == operationId }
    }

    private fun runtimeOwnsReplacement(
        operation: ExitOperation,
        identity: GhostRuntimeIdentity,
    ): Boolean = identity.pending != null ||
        identity.phase == GhostRuntimePhase.Replacing ||
        identity.activeHandle?.generation?.let { it != operation.generation } == true

    private fun exitGenerationHasNoReplacement(
        operation: ExitOperation,
        identity: GhostRuntimeIdentity,
    ): Boolean = !runtimeOwnsReplacement(operation, identity) &&
        (activeHandle?.generation?.let { it == operation.generation } ?: true)

    private data class ExitCapture(
        val operation: ExitOperation,
        val handle: GhostHandle?,
        val state: PlaybackState,
        val abandonedSwitch: RunnerSwitchOperation?,
    )
    fun doGhostChanging(
        switchOperationId: Long,
        nextName: String,
        type: String,
        nextPath: String,
    ): Boolean {
        val captured = synchronized(this) {
            val handle = activeHandle ?: return false
            RunnerSwitchOperation(switchOperationId, handle.generation)
                .also { pendingSwitch = it } to handle
        }
        val (operation, handle) = captured
        return requestPinned(
            handle,
            ShioriRequestIntent.event("OnGhostChanging", listOf(nextName, type, null, nextPath)),
        ) { result, _ -> admitGhostChangingResponse(operation, result) }
    }

    private fun admitGhostChangingResponse(
        operation: RunnerSwitchOperation,
        result: RuntimeResult<TaggedShioriResponse>,
    ): Boolean {
        val tagged = when (result) {
            is RuntimeResult.Success -> result.value.takeIf {
                synchronized(this) { pendingSwitch == operation }
            }
            is RuntimeResult.Failure -> {
                val owned = synchronized(this) {
                    (pendingSwitch == operation).also { if (it) pendingSwitch = null }
                }
                if (!owned) return false
                val cause = when (val failure = result.failure) {
                    is RuntimeFailure.Replayable -> failure.cause
                    is RuntimeFailure.Fatal -> failure.cause
                    RuntimeFailure.Busy -> IllegalStateException(
                        "Runtime became busy before outgoing switch unload",
                    )
                    RuntimeFailure.StaleGeneration -> IllegalStateException(
                        "Outgoing switch generation became stale before unload",
                    )
                }
                runtimePort.failSwitchBeforeUnload(
                    operation.outgoingGeneration,
                    operation.operationId,
                    cause,
                )
                null
            }
        } ?: return false
        val value = tagged.response
            .takeIf { it.getStatusCode() == 200 }
            ?.getKey("Value")
            ?.takeIf(String::isNotEmpty)
        if (value == null) {
            val callback = synchronized(this) {
                if (pendingSwitch == operation) {
                    pendingSwitch = null
                    cb
                } else {
                    null
                }
            }
            runtimePort.completeSwitchPlaybackFromRunner(
                operation.outgoingGeneration,
                operation.operationId,
            )
            runCatching { callback?.switchPlaybackComplete() }
            return true
        }
        val shouldRun = synchronized(this) {
            if (
                activeHandle?.generation != tagged.generation ||
                pendingSwitch != operation
            ) {
                false
            } else {
                msgQueue.add(value)
                runtimeModeGeneration++
                !playback.running
            }
        }
        if (shouldRun) run()
        return true
    }
    fun doInstallBegin(id:String){doShioriEvent("OnInstallBegin",arrayOf("ghost",id,id))};fun doInstallComplete(id:String){doShioriEvent("OnInstallComplete",arrayOf("ghost",id,id))}
    fun doShioriEvent(evt: String, ref: Array<out String?>?): Boolean {
        return requestCurrent(
            ShioriRequestIntent.event(evt, ref?.toList().orEmpty()),
        ) { result, admissionStamp ->
            val tagged = (result as? RuntimeResult.Success)?.value ?: return@requestCurrent false
            parseShioriResponseAndInsert(tagged, admissionStamp)
            true
        }
    }

    private fun requestCurrent(
        intent: ShioriRequestIntent,
        admission: (RuntimeResult<TaggedShioriResponse>, RequestAdmissionStamp) -> Boolean,
    ): Boolean {
        val handle = synchronized(this) { activeHandle } ?: return false
        return requestPinned(handle, intent, admission = admission)
    }

    private fun requestPinned(
        handle: GhostHandle,
        intent: ShioriRequestIntent,
        onUnscheduled: () -> Unit = {},
        fenceAdmissionEpoch: Boolean = true,
        admission: (RuntimeResult<TaggedShioriResponse>, RequestAdmissionStamp) -> Boolean,
    ): Boolean = requestPinnedCommand(
        handle = handle,
        onUnscheduled = onUnscheduled,
        request = { runtimePort.request(handle.generation, intent) },
        requestAsync = { runtimePort.requestAsync(handle.generation, intent) },
        fenceAdmissionEpoch = fenceAdmissionEpoch,
        admission = admission,
    )

    private fun requestPinnedWithFallback(
        handle: GhostHandle,
        primary: ShioriRequestIntent,
        fallback: ShioriRequestIntent,
        admission: (RuntimeResult<TaggedShioriResponse>, RequestAdmissionStamp) -> Boolean,
    ): Boolean = requestPinnedCommand(
        handle = handle,
        request = {
            runtimePort.requestWithFallback(handle.generation, primary, fallback)
        },
        requestAsync = {
            runtimePort.requestWithFallbackAsync(handle.generation, primary, fallback)
        },
        admission = admission,
    )

    private fun requestPinnedCommand(
        handle: GhostHandle,
        onUnscheduled: () -> Unit = {},
        request: () -> RuntimeResult<TaggedShioriResponse>,
        requestAsync: () -> RuntimeRequestSubmission,
        fenceAdmissionEpoch: Boolean = true,
        admission: (RuntimeResult<TaggedShioriResponse>, RequestAdmissionStamp) -> Boolean,
    ): Boolean {
        val capturedAdmissionEpoch = synchronized(this) {
            requestAdmissionEpoch.takeIf { activeHandle?.generation == handle.generation }
        }
        if (capturedAdmissionEpoch == null) {
            onUnscheduled()
            return false
        }

        val admissionStamp = RequestAdmissionStamp(handle.generation, capturedAdmissionEpoch)
        fun admit(result: RuntimeResult<TaggedShioriResponse>): Boolean {
            val fenced = when (result) {
                is RuntimeResult.Success -> {
                    playbackHooks.beforeRequestResponseAdmission()
                    if (
                        result.value.generation == handle.generation &&
                        synchronized(this) {
                            activeHandle?.generation == handle.generation &&
                                (!fenceAdmissionEpoch || requestAdmissionEpoch == capturedAdmissionEpoch)
                        }
                    ) {
                        result
                    } else {
                        RuntimeResult.Failure(RuntimeFailure.StaleGeneration)
                    }
                }
                is RuntimeResult.Failure -> result
            }
            playbackHooks.afterRequestResponseFence()
            return admission(fenced, admissionStamp)
        }

        // Legacy/background callers keep the result-returning contract. On Android's main
        // looper the Boolean is an acceptance receipt; the generation-fenced response is
        // admitted later by the main-side scheduler.
        if (!runsOnMainLooper()) {
            return admit(request())
        }
        return when (val submission = requestAsync()) {
            is RuntimeRequestSubmission.Accepted -> observeRequest(submission, ::admit)
            is RuntimeRequestSubmission.Rejected -> {
                responseScheduler.value.schedule { admit(submission.failure) }
                false
            }
        }
    }

    private fun observeRequest(
        submission: RuntimeRequestSubmission.Accepted,
        admission: (RuntimeResult<TaggedShioriResponse>) -> Boolean,
    ): Boolean = try {
        submission.result.whenCompleteAsync(
            { result, failure ->
                val outcome = if (failure == null) {
                    requireNotNull(result)
                } else {
                    RuntimeResult.Failure(RuntimeFailure.Fatal(failure.cause ?: failure))
                }
                admission(outcome)
            },
            responseExecutor,
        )
        true
    } catch (failure: RejectedExecutionException) {
        responseScheduler.value.schedule {
            admission(RuntimeResult.Failure(RuntimeFailure.Fatal(failure)))
        }
        false
    }

    private fun runsOnMainLooper(): Boolean = runCatching {
        Looper.myLooper() === Looper.getMainLooper()
    }.getOrDefault(false)
    /** A UI host observes this immutable value; it never owns pending actions. */
    internal fun dialogueStateSnapshot(): DialogueRuntimeState = synchronized(this) { dialogueState }
    internal fun dialogueDialogRuntimeSnapshot(): DialogueDialogRuntimeSnapshot = synchronized(this) {
        DialogueDialogRuntimeSnapshot(dialogueDialogOwner, pendingChoiceGeneration, dialogueState)
    }
    internal fun runtimeModeSnapshot(): GhostRuntimeMode = synchronized(this) {
        val state = playback
        GhostRuntimeMode(
            playingTalk = state.running || msgQueue.isNotEmpty() || !state.msg.isNullOrEmpty(),
            pendingUserAction = dialogueState.pendingChoices.isNotEmpty() || dialogueState.pendingInput != null,
            passive = passive,
        )
    }

    internal fun activateChoice(action: DialogueAction) {
        when (action) {
            is DialogueAction.Normal -> {
                dispatchDialogueTransaction(
                    claim = { action.takeIf { takePendingChoice(it) } },
                    primary = {
                        DialogueEvent("OnChoiceSelectEx", listOf(it.label, it.id) + it.extraReferences)
                    },
                    fallback = { DialogueEvent("OnChoiceSelect", listOf(it.id)) },
                )
            }
            is DialogueAction.DirectEvent -> dispatchDialogueTransaction(
                claim = { action.takeIf { takePendingChoice(it) } },
                primary = { DialogueEvent(it.eventId, it.references) },
            )
            is DialogueAction.Script -> enqueueLocalDialogueScript(
                claim = { takePendingChoice(action) },
                script = action.sakuraScript,
            )
        }
        publishDialogueState()
    }

    internal fun activateAnchor(action: AnchorAction) {
        when (action) {
            is AnchorAction.Normal -> {
                dispatchDialogueTransaction(
                    claim = { action.takeIf { isCurrentAnchor(it) } },
                    primary = {
                        DialogueEvent("OnAnchorSelectEx", listOf(it.label, it.id) + it.extraReferences)
                    },
                    fallback = { DialogueEvent("OnAnchorSelect", listOf(it.id)) },
                )
            }
            is AnchorAction.DirectEvent -> dispatchDialogueTransaction(
                claim = { action.takeIf { isCurrentAnchor(it) } },
                primary = { DialogueEvent(it.eventId, it.references) },
            )
        }
    }

    internal fun submitInput(generation: Long, value: String) {
        dispatchDialogueTransaction(
            claim = { takePendingInput(generation) },
            primary = { pending ->
                when (val dispatch = pending.spec.dispatch) {
                    is InputDispatch.Normal -> DialogueEvent(
                        "OnUserInput",
                        listOf(dispatch.id, value, pending.spec.supplement) + pending.spec.extraReferences,
                    )
                    is InputDispatch.DirectEvent -> DialogueEvent(
                        dispatch.eventId,
                        listOf(value, pending.spec.supplement) + pending.spec.extraReferences,
                    )
                }
            },
        )
        publishDialogueState()
    }

    internal fun dismissInput(generation: Long) {
        cancelInput({ takePendingInput(generation) }, "close", fallback = false)
        publishDialogueState()
    }

    internal fun processExpiredInput() {
        cancelInput(::takeExpiredPendingInput, "timeout", fallback = true)
        publishDialogueState()
    }

    private fun cancelInput(claim: () -> PendingInputState?, reason: String, fallback: Boolean) {
        dispatchDialogueTransaction(
            claim = claim,
            primary = { pending ->
                DialogueEvent("OnUserInputCancel", cancelReferences(pending, reason))
            },
            fallback = if (fallback) {
                { pending -> DialogueEvent("OnUserInput", cancelReferences(pending, "timeout")) }
            } else null,
        )
    }

    private fun cancelReferences(pending: PendingInputState, reason: String): List<String> {
        val id = when (val dispatch = pending.spec.dispatch) {
            is InputDispatch.Normal -> dispatch.id
            is InputDispatch.DirectEvent -> dispatch.eventId
        }
        return listOf(id, reason, pending.spec.supplement) + pending.spec.extraReferences
    }

    private fun takePendingInput(generation: Long): PendingInputState? = synchronized(this) {
        val pending = dialogueState.pendingInput ?: return@synchronized null
        if (pending.generation != generation) return@synchronized null
        runtimeModeGeneration++
        retiredInputGenerations += generation
        dialogueState = dialogueState.copy(revision = dialogueState.revision + 1, pendingInput = null)
        pending
    }

    private fun takePendingChoice(action: DialogueAction): Boolean = synchronized(this) {
        if (dialogueState.pendingChoices.none { it === action }) return@synchronized false
        runtimeModeGeneration++
        retiredDialogueChoices.addAll(dialogueState.pendingChoices)
        pendingChoiceGeneration = null
        dialogueState = dialogueState.copy(
            revision = dialogueState.revision + 1,
            pendingChoices = emptyList(),
        )
        true
    }

    /** Keeps primary, fallback, and response enqueue on the same live SHIORI generation. */
    private fun <T> dispatchDialogueTransaction(
        claim: () -> T?,
        primary: (T) -> DialogueEvent,
        fallback: ((T) -> DialogueEvent)? = null,
    ): Boolean {
        val handle = synchronized(this) { activeHandle } ?: return false
        val claimed = claim() ?: return false
        dialogueClaimHookForTesting?.invoke()
        if (!isPinnedHandle(handle)) return false

        fun enqueueIfPlayable(
            tagged: TaggedShioriResponse?,
            admissionStamp: RequestAdmissionStamp,
        ): Boolean {
            val value = tagged?.response
                ?.takeIf { it.getStatusCode() == 200 }
                ?.getKey("Value")
                ?.takeIf(String::isNotEmpty)
                ?: return false
            val shouldRun = synchronized(this) {
                if (!admissionIsCurrent(admissionStamp)) return@synchronized false
                msgQueue.add(value)
                runtimeModeGeneration++
                !playback.running
            }
            if (shouldRun) run()
            return true
        }

        val primaryEvent = primary(claimed)
        val primaryIntent = ShioriRequestIntent.event(primaryEvent.event, primaryEvent.references)
        val fallbackEvent = fallback?.invoke(claimed)
        if (fallbackEvent != null) {
            return requestPinnedWithFallback(
                handle,
                primaryIntent,
                ShioriRequestIntent.event(fallbackEvent.event, fallbackEvent.references),
            ) { finalResult, admissionStamp ->
                enqueueIfPlayable(
                    (finalResult as? RuntimeResult.Success)?.value,
                    admissionStamp,
                )
            }
        }
        return requestPinned(handle, primaryIntent) { primaryResult, admissionStamp ->
            enqueueIfPlayable(
                (primaryResult as? RuntimeResult.Success)?.value,
                admissionStamp,
            )
        }
    }

    private fun isPinnedHandle(handle: GhostHandle): Boolean =
        synchronized(this) { activeHandle?.generation == handle.generation }

    /** Called with the runner lock held at the state mutation that admits a response. */
    private fun admissionIsCurrent(stamp: RequestAdmissionStamp): Boolean =
        activeHandle?.generation == stamp.generation && requestAdmissionEpoch == stamp.epoch

    /** Called with the runner lock held after the runtime response has returned. */
    private fun timerResponseIsEligible(handle: GhostHandle, capturedGeneration: Long): Boolean =
        runtimeModeSnapshot().canTalk &&
            runtimeModeGeneration == capturedGeneration &&
            activeHandle?.generation == handle.generation

    private fun enqueueLocalDialogueScript(claim: () -> Boolean, script: String) {
        val handle = synchronized(this) { activeHandle } ?: return
        if (!claim()) return
        val shouldRun = synchronized(this) {
            if (activeHandle?.generation != handle.generation) return@synchronized false
            msgQueue.add(script)
            runtimeModeGeneration++
            !playback.running
        }
        if (shouldRun) run()
    }

    private fun isCurrentAnchor(action: AnchorAction): Boolean = synchronized(this) {
        visibleDialogueSegments(dialogueState.contents).asSequence()
            .mapNotNull { (it as? DialogueSegment.Anchor)?.action }
            .any { it === action }
    }

    private fun takeExpiredPendingInput(): PendingInputState? = synchronized(this) {
        val pending = dialogueState.pendingInput ?: return@synchronized null
        if (monotonicClock.nowMillis() < pending.deadlineElapsedMillis) return@synchronized null
        takePendingInput(pending.generation)
    }

    private data class DialogueEvent(val event: String, val references: List<String>)

    /** Full authored tokens are retained only to preserve exact action object identity. */
    private data class AuthoredDialogueScript(
        val script: String,
        val contents: List<com.cattailsw.nanidroid.runtime.dialogue.DialogueContent>,
        val interactions: List<SakuraScriptInteraction>,
        val pendingInputs: List<PendingInputState>,
        val carriedInput: PendingInputState?,
        val talkId: Long,
    )

    private fun recordDialogueScript(script: String): AuthoredDialogueScript? {
        val tokenization = SakuraScriptTokenizer.tokenizeWithInteractions(script) {
            LegacyPlatform.debug(TAG, it)
        }
        val contents = tokenization.contents
        val inputs = contents.asSequence()
            .flatMap { content ->
                content.segments.asSequence().mapNotNull { segment ->
                    (segment as? DialogueSegment.InputBox)?.spec?.let { content.speaker to it }
                }
            }
            .toList()
        val passiveOnly = contents.asSequence()
            .flatMap { it.segments.asSequence() }
            .let { segments ->
                segments.any { it is DialogueSegment.PassiveMode } &&
                    segments.all {
                        it is DialogueSegment.PassiveMode || it is DialogueSegment.SpeakerChangeClear
                    }
            }
        var authored: AuthoredDialogueScript? = null
        val published = synchronized(this) {
            if (passiveOnly) {
                dialogueState = dialogueState.copy(revision = dialogueState.revision + 1)
            } else {
                retiredDialogueChoices.clear()
                retiredInputGenerations.clear()
                val pendingInputs = inputs.map { (speaker, spec) ->
                    PendingInputState(++nextInputGeneration, spec, inputDeadline(spec), speaker)
                }
                val carriedInput = dialogueState.pendingInput.takeIf { pendingInputs.isEmpty() }
                pendingChoiceGeneration = null
                dialogueState = DialogueRuntimeState(
                    revision = dialogueState.revision + 1,
                    talkId = ++nextDialogueTalkId,
                    incarnation = dialogueIncarnation,
                    pendingInput = carriedInput,
                )
                authored = AuthoredDialogueScript(
                    script,
                    contents,
                    tokenization.interactions,
                    pendingInputs,
                    carriedInput,
                    dialogueState.talkId,
                )
            }
            dialogueState
        }
        publishDialogueState(published)
        return authored
    }

    private fun publishDialogueProjection(state: PlaybackState) {
        val (authored, revealed) = synchronized(this) {
            if (playback !== state || !state.running) return
            state.dialogueScript to state.charIndex
        }
        authored ?: return
        val contents = projectDialogue(authored, revealed)
        val visibleSegments = visibleDialogueSegments(contents)
        val visibleChoiceActions = java.util.Collections.newSetFromMap(
            IdentityHashMap<DialogueAction, Boolean>(),
        ).apply {
            visibleSegments.forEach { segment ->
                (segment as? DialogueSegment.Choice)?.action?.let(::add)
            }
        }
        val revealedPendingChoices = authored.interactions.asSequence()
            .filter { interaction ->
                interaction.sourceEnd <= revealed &&
                    interaction.scope in 0..1 &&
                    interaction.action in visibleChoiceActions
            }
            .map { it.action }
            .toList()
        val reachedInputs = visibleSegments.asSequence()
            .mapNotNull { (it as? DialogueSegment.InputBox)?.spec }
            .toList()
        val published = synchronized(this) {
            if (playback !== state || dialogueState.talkId != authored.talkId) return
            val pendingChoices = revealedPendingChoices.filterNot(retiredDialogueChoices::contains)
            val pendingInput = authored.pendingInputs.firstOrNull { pending ->
                pending.generation !in retiredInputGenerations &&
                    reachedInputs.any { it === pending.spec }
            } ?: authored.carriedInput?.takeIf { it.generation !in retiredInputGenerations }
            if (
                pendingChoices != dialogueState.pendingChoices ||
                pendingInput != dialogueState.pendingInput
            ) {
                runtimeModeGeneration++
            }
            pendingChoiceGeneration = pendingChoices
                .takeIf { it.isNotEmpty() && dialogueState.pendingChoices.isEmpty() }
                ?.let { ++nextChoiceGeneration } ?: pendingChoiceGeneration
            dialogueState = dialogueState.copy(
                revision = dialogueState.revision + 1,
                contents = contents,
                pendingChoices = pendingChoices,
                pendingInput = pendingInput,
            )
            dialogueState
        }
        publishDialogueState(published)
    }

    private fun visibleDialogueSegments(
        contents: List<com.cattailsw.nanidroid.runtime.dialogue.DialogueContent>,
    ): List<DialogueSegment> = GhostSpeaker.entries.flatMap { speaker ->
        contents.asSequence()
            .filter { it.speaker == speaker }
            .flatMap { it.segments.asSequence() }
            .fold(mutableListOf<DialogueSegment>()) { visible, segment ->
                if (segment is DialogueSegment.Clear || segment is DialogueSegment.SpeakerChangeClear) {
                    if (segment is DialogueSegment.Clear) {
                        visible.clear()
                    } else {
                        visible.removeAll { it.shouldPreserveAcrossSpeakerChange().not() }
                    }
                } else {
                    visible += segment
                }
                visible
            }
    }

    private fun DialogueSegment.shouldPreserveAcrossSpeakerChange(): Boolean = when (this) {
        is DialogueSegment.Choice,
        is DialogueSegment.InputBox -> true
        else -> false
    }

    private fun projectDialogue(
        authored: AuthoredDialogueScript,
        revealedCharacters: Int,
    ): List<com.cattailsw.nanidroid.runtime.dialogue.DialogueContent> {
        val revealed = SakuraScriptTokenizer.tokenizeRevealed(
            authored.script.take(revealedCharacters.coerceIn(0, authored.script.length)),
        )
        val authoredBySpeaker = authored.contents.associateBy { it.speaker }
        return revealed.map { content ->
            val authoredSegments = authoredBySpeaker[content.speaker]?.segments.orEmpty()
            var choiceIndex = 0
            var anchorIndex = 0
            var inputIndex = 0
            content.copy(
                segments = content.segments.map { segment ->
                    when (segment) {
                        is DialogueSegment.Choice -> {
                            val action = authoredSegments.filterIsInstance<DialogueSegment.Choice>()
                                .getOrNull(choiceIndex++)?.action ?: segment.action
                            DialogueSegment.Choice(action)
                        }
                        is DialogueSegment.Anchor -> {
                            val action = authoredSegments.filterIsInstance<DialogueSegment.Anchor>()
                                .getOrNull(anchorIndex++)?.action ?: segment.action
                            DialogueSegment.Anchor(action)
                        }
                        is DialogueSegment.InputBox -> {
                            val spec = authoredSegments.filterIsInstance<DialogueSegment.InputBox>()
                                .getOrNull(inputIndex++)?.spec ?: segment.spec
                            DialogueSegment.InputBox(spec)
                        }
                        else -> segment
                    }
                },
            )
        }
    }

    /** Delivers only the current immutable snapshot, in runner mutation order. */
    private fun publishDialogueState(state: DialogueRuntimeState = dialogueStateSnapshot()) {
        synchronized(this) {
            if (dialogueState !== state) return
            dialogueStateObserver?.invoke(state)
        }
    }

    private fun inputDeadline(spec: com.cattailsw.nanidroid.runtime.dialogue.InputBoxSpec): Long {
        val timeout = spec.timeoutMillis ?: return Long.MAX_VALUE
        if (timeout <= 0L) return Long.MAX_VALUE
        val now = monotonicClock.nowMillis()
        return if (now > Long.MAX_VALUE - timeout) Long.MAX_VALUE else now + timeout
    }

    private fun clearDialogueStateLocked(): DialogueRuntimeState {
        runtimeModeGeneration++
        if (playbackScheduler.isInitialized()) playbackScheduler.value.cancelPending()
        playback = PlaybackState()
        dialogueDialogOwner = UUID.randomUUID().toString()
        pendingChoiceGeneration = null
        retiredDialogueChoices.clear()
        retiredInputGenerations.clear()
        dialogueState = DialogueRuntimeState(
            revision = dialogueState.revision + 1,
            incarnation = ++dialogueIncarnation,
        )
        msgQueue.clear()
        passive = false
        return dialogueState
    }
}
