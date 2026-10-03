package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.data.BootStateStore
import com.cattailsw.nanidroid.data.LastGhostStore
import com.cattailsw.nanidroid.engine.EngineKind
import com.cattailsw.nanidroid.engine.EngineSelector
import com.cattailsw.nanidroid.engine.NativeAvailability
import com.cattailsw.nanidroid.engine.NativeKind
import com.cattailsw.nanidroid.engine.NativeLease
import com.cattailsw.nanidroid.engine.NativeLoadResult
import com.cattailsw.nanidroid.engine.NativeShioriHost
import com.cattailsw.nanidroid.engine.NativeShutdownResult
import com.cattailsw.nanidroid.engine.ShioriEngine
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.engine.ShioriMethod
import com.cattailsw.nanidroid.engine.ShioriReply
import com.cattailsw.nanidroid.engine.UnsupportedShiori
import com.cattailsw.nanidroid.ghost.BundledGhost
import com.cattailsw.nanidroid.ghost.GhostDescriptor
import com.cattailsw.nanidroid.ghost.InstalledGhostRepository
import com.cattailsw.nanidroid.ghost.NativeProfileDirectory
import com.cattailsw.nanidroid.ghost.ShellCatalog
import com.cattailsw.nanidroid.ghost.loadShellCatalog
import com.cattailsw.nanidroid.ghost.SurfaceCollisionHitTest
import kotlin.random.Random
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.logging.Logger

interface InstallEventSink {
    fun emit(id: String, references: List<String> = emptyList())
}

sealed interface ImportPromptResult {
    data object Shown : ImportPromptResult
    data object Deferred : ImportPromptResult
    data class Unavailable(val message: String) : ImportPromptResult
}

private fun PlaybackFrame.hasInteraction(): Boolean =
    input != null || sakura.choices.isNotEmpty() || kero.choices.isNotEmpty()

/** Identity of the character image that supplied a pointer's authored coordinates. */
data class RenderedMoveSource(
    val dialogueToken: DialogueToken?,
    val logicalSurfaceId: Int,
    val visual: SurfaceVisual?,
    val geometry: RenderedMoveSnapshot? = null,
)

/** Composed image bounds and the coordinates this character was drawn with at pointer down. */
data class RenderedMoveSnapshot(
    val images: Map<Int, SurfaceImageBounds>,
    val coordinates: MoveCoordinateSpace,
)

/**
 * One application-owned dialogue session. JNI ownership stays in [NativeShioriHost],
 * while every asynchronous result is checked against the current session identity.
 */
class GhostRuntime(
    private val loadGhost: suspend () -> BundledGhost,
    private val engineFactory: (BundledGhost) -> ShioriEngine,
    private val bootState: BootStateStore,
    private val scope: CoroutineScope,
    private val installedGhosts: InstalledGhostRepository? = null,
    private val lastGhostStore: LastGhostStore? = null,
    private val nativeHost: NativePort = HostNativePort(NativeShioriHost.process),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val loadCatalog: suspend (File, Map<Int, File>, CoroutineDispatcher) -> ShellCatalog = ::loadShellCatalog,
    private val elapsedRealtime: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private class Session(
        val id: Long,
        val ghost: BundledGhost,
        val engine: ShioriEngine,
        val lease: NativeLease? = null,
    ) {
        val queue = ArrayDeque<QueuedDialogue>()
        var dialogueVersion = 0L
        var replacementGeneration = 0L
        var player: ScriptPlayer? = null
        var playerStartedAt = 0L
        var frame: PlaybackFrame = EMPTY_FRAME
        val pendingClockEvents = mutableSetOf<String>()
        var clockElapsedMs = 0L
        var shell: ShellCatalog? = null
        var animator: SurfaceAnimator? = null
        var visuals: Map<Int, SurfaceVisual> = emptyMap()
        var transitionRepliesPending = 0
        val surfaceEdges = ArrayDeque<SurfaceEdge>()
        var surfaceDraining = false
        val moves = arrayOf(MoveState(), MoveState())

        fun clearPendingMoves() { moves.forEach { it.pending = null } }

        fun pendingInteraction(): Boolean = frame.hasInteraction()

        fun coalesceClockReplies() {
            val latest = queue.filter { it.clockEventId != null }
                .associateBy { it.clockEventId }
            val seen = mutableSetOf<String>()
            val retained = queue.mapNotNull { item ->
                val id = item.clockEventId
                if (id == null) item else if (seen.add(id)) latest[id] else null
            }
            queue.clear()
            queue.addAll(retained)
        }
    }

    private data class QueuedDialogue(val script: String, val clockEventId: String? = null)
    private data class MovePoint(
        val x: Int, val y: Int, val collisionSurfaceId: Int?, val source: RenderedMoveSource,
    )

    // The other character only matters through this character's rendered rectangle,
    // and the stage cancels the gesture itself when that rectangle changes.
    private fun sameMoveCoordinates(point: MovePoint, frame: SpeakerFrame, speaker: Int): Boolean {
        val source = point.source
        if (frame.surfaceId != source.logicalSurfaceId) return false
        if (frame.visual == source.visual) return true
        val snapshot = source.geometry ?: return false
        return MoveGeometryResolver.resolve(frame.surfaceId, frame.visual, snapshot.images,
            MoveGeometryResolver.fallbackSurfaceId(speaker))?.coordinates == snapshot.coordinates
    }
    private class MoveState {
        var pending: MovePoint? = null
        var inFlight = false
        var lastAdmittedAt = Long.MIN_VALUE
    }
    private data class SurfaceEdge(
        val change: SurfaceChange, val dialogueVersion: Long, val replacementGeneration: Long,
    )

    private sealed interface SessionState {
        data object Idle : SessionState
        class Loading(val id: Long, val done: CompletableDeferred<Unit>) : SessionState
        class Active(val session: Session, val prompt: CandidatePrompt? = null, val error: String? = null) : SessionState
        class Switching(val from: Session, val to: GhostDescriptor, val phase: Phase) : SessionState
        class Closing(val session: Session, var cleanupStarted: Boolean = false) : SessionState
        class ClosingSwitch(val from: Session) : SessionState
        data object Finished : SessionState
    }
    private class CandidatePrompt(val descriptor: GhostDescriptor, val view: SwitchPrompt)
    private enum class Phase { Dialogue, Unloading, Loading }

    private val mutableState = MutableStateFlow<StageState>(StageState.Loading)
    val state: StateFlow<StageState> = mutableState
    private var sessionState: SessionState = SessionState.Idle
    private var nextId = 0L
    @Volatile private var resumed = false
    private var ticker: Job? = null
    private var bundled: BundledGhost? = null
    private var bundledEngine: ShioriEngine? = null
    private var availableChoices: List<GhostDescriptor> = emptyList()
    private var availableChoiceKinds: Map<String, EngineKind> = emptyMap()
    private var filteredChoices: List<GhostDescriptor> = emptyList()
    private var filteredChoicesKey: Triple<Long, Long, Pair<NativeAvailability, Boolean>>? = null
    private var choicesVersion = 0L
    private var selectionVersion = 0L
    private var nativeUnavailable = false
    private val moveSubmissionLock = Any()
    private val moveSubmissions = arrayOfNulls<MovePoint>(2)
    private var currentLanguage = "ja"
    @Volatile private var importRunning = false
    private val importEpoch = AtomicLong()
    private val logger = Logger.getLogger(GhostRuntime::class.java.name)

    fun setImportRunning(value: Boolean) {
        if (value) importEpoch.incrementAndGet()
        importRunning = value
    }

    /** Events retain their original owner and serialize with one another without blocking import. */
    fun beginImportEvents(): InstallEventSink? {
        val owner = (sessionState as? SessionState.Active)?.session ?: return null
        var previous: Job? = null
        return object : InstallEventSink {
            override fun emit(id: String, references: List<String>) {
                val predecessor = previous
                previous = scope.launch {
                    predecessor?.join()
                    if (!sameActive(owner)) return@launch
                    try {
                        val reply = owner.engine.request(ShioriEvent(id, references))
                        if (reply.status != 200 && reply.status != 204) {
                            logger.warning("$id returned ${reply.status}")
                        }
                        if (sameActive(owner)) {
                            enqueue(owner, reply)
                            publish(owner)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        logger.warning("$id delivery failed: ${error.message}")
                        val active = sessionState as? SessionState.Active
                        if (active?.session === owner) {
                            recoverQuarantinedRequest(active, owner, error)
                        }
                    }
                }
            }
        }
    }

    suspend fun offerImportedGhost(directoryId: String): ImportPromptResult =
        withContext(scope.coroutineContext) {
            val active = sessionState as? SessionState.Active ?: return@withContext ImportPromptResult.Deferred
            offerSelection(active, directoryId)
        }

    suspend fun refreshInstalledChoices() = withContext(scope.coroutineContext) {
        val version = ++choicesVersion
        val (choices, kinds) = withContext(ioDispatcher) {
            val installed = installedGhosts?.list().orEmpty()
            installed to installed.associate { it.directoryId to EngineSelector.select(it) }
        }
        if (version == choicesVersion && sessionState != SessionState.Finished) {
            availableChoices = choices
            availableChoiceKinds = kinds
            filteredChoicesKey = null
            (sessionState as? SessionState.Active)?.session?.let(::publish)
        }
    }

    /** Read-only identity probe for device recreation tests. */
    internal fun activeNativeLeaseForTest(): NativeLease? =
        (sessionState as? SessionState.Active)?.session?.lease

    suspend fun start(language: String) = withContext(scope.coroutineContext) {
        when (val current = sessionState) {
            is SessionState.Loading -> { current.done.await(); return@withContext }
            is SessionState.Active, is SessionState.Switching, is SessionState.Closing,
            is SessionState.ClosingSwitch -> return@withContext
            SessionState.Idle, SessionState.Finished -> Unit
        }
        val loading = SessionState.Loading(++nextId, CompletableDeferred())
        sessionState = loading
        currentLanguage = language
        mutableState.value = StageState.Loading
        try {
            val base = getBundled()
            if (sessionState !== loading) return@withContext
            val choicesRequest = ++choicesVersion
            val (choices, kinds, candidate) = withContext(ioDispatcher) {
                val installed = installedGhosts?.list().orEmpty()
                val selectedKinds = installed.associate { it.directoryId to EngineSelector.select(it) }
                val saved = lastGhostStore?.read()
                val restored = saved?.takeIf { it != base.directoryId }?.let {
                    runCatching { installedGhosts?.validate(it) }.getOrNull()
                }
                Triple(installed, selectedKinds, restored)
            }
            if (sessionState !== loading) return@withContext
            if (choicesRequest == choicesVersion) {
                availableChoices = choices
                availableChoiceKinds = kinds
                filteredChoicesKey = null
            }
            activate(loading, candidate, base, language, previousName = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (sessionState === loading) {
                sessionState = SessionState.Idle
                mutableState.value = StageState.Error(error.message ?: "Unable to load ghost")
            }
        } finally {
            loading.done.complete(Unit)
        }
    }

    fun setResumed(value: Boolean) {
        scope.launch {
            if (resumed == value) return@launch
            resumed = value
            if (value) ensureTicker() else {
                clearMoveSubmissions()
                (sessionState as? SessionState.Active)?.session?.clearPendingMoves()
                ticker?.cancel()
                ticker = null
            }
        }
    }

    fun selectGhost(directoryId: String) {
        scope.launch {
            if (importRunning) return@launch
            val active = sessionState as? SessionState.Active ?: return@launch
            offerSelection(active, directoryId, manual = true)
        }
    }

    private suspend fun offerSelection(
        active: SessionState.Active, directoryId: String, manual: Boolean = false,
    ): ImportPromptResult {
        val request = ++selectionVersion
        val expectedImportEpoch = importEpoch.get()
        val candidate = try {
            withContext(ioDispatcher) {
                installedGhosts?.validate(directoryId)?.let {
                    Triple(it, readme(it.readmePath), EngineSelector.select(it))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val current = currentSelectionOwner(active, request, manual, expectedImportEpoch)
                ?: return ImportPromptResult.Unavailable(
                "Selection was superseded")
            showError(current, error.message ?: "Invalid ghost")
            return ImportPromptResult.Unavailable("Installed ghost is unavailable")
        }
        val current = currentSelectionOwner(active, request, manual, expectedImportEpoch)
            ?: return ImportPromptResult.Unavailable(
            "Selection was superseded")
        if (candidate == null) {
            showError(current, "Invalid ghost")
            return ImportPromptResult.Unavailable("Installed ghost is unavailable")
        }
        val (descriptor, readme, selected) = candidate
        if (selected in NATIVE_KINDS &&
            !nativeSelectionAllowed(current.session)) {
            showError(current, "Native engines are unavailable until restart")
            return ImportPromptResult.Unavailable("Installed ghost is unavailable until restart")
        }
        if (descriptor.directoryId == current.session.ghost.directoryId)
            return ImportPromptResult.Unavailable("Ghost is already active")
        availableChoiceKinds = availableChoiceKinds + (descriptor.directoryId to selected)
        sessionState = SessionState.Active(current.session, prompt = CandidatePrompt(
            descriptor, SwitchPrompt(descriptor.directoryId, descriptor.displayName, readme)
        ), error = current.error)
        publish(current.session)
        return ImportPromptResult.Shown
    }

    private fun currentSelectionOwner(
        active: SessionState.Active, request: Long, manual: Boolean, expectedImportEpoch: Long,
    ): SessionState.Active? =
        (sessionState as? SessionState.Active)?.takeIf {
            it.session === active.session && request == selectionVersion &&
                (!manual || (!importRunning && importEpoch.get() == expectedImportEpoch))
        }

    fun dismissSwitch() {
        scope.launch {
            ++selectionVersion
            val active = sessionState as? SessionState.Active ?: return@launch
            if (active.prompt != null) {
                sessionState = SessionState.Active(active.session, error = active.error)
                publish(active.session)
            }
        }
    }

    fun confirmSwitch() {
        scope.launch {
            if (importRunning) return@launch
            val active = sessionState as? SessionState.Active ?: return@launch
            val target = active.prompt?.descriptor ?: return@launch
            ++selectionVersion
            val from = active.session
            clearDialogue(from)
            val switching = SessionState.Switching(from, target, Phase.Dialogue)
            sessionState = switching
            ensureTicker()
            publish(from)
            from.transitionRepliesPending++
            val reply = try {
                from.engine.request(ShioriEvent("OnGhostChanging", listOf(
                    target.sakuraName, "manual", "", target.masterPath.parentFile?.parentFile?.absolutePath.orEmpty()
                )))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (sessionState === switching) {
                    from.transitionRepliesPending--
                    if (recoverQuarantinedRequest(switching, from, error)) return@launch
                    showError(active, error.message ?: "Switch dialogue failed")
                }
                return@launch
            }
            if (sessionState !== switching) return@launch
            enqueue(from, reply)
            from.transitionRepliesPending--
            publish(from)
            maybeFinishTransition(from)
        }
    }

    fun click(speaker: Int, x: Int, y: Int) = pointerEvent("OnMouseClick", speaker, x, y)
    fun click(speaker: Int, x: Int, y: Int, collisionSurfaceId: Int, origin: DialogueToken?) =
        pointerEvent("OnMouseClick", speaker, x, y, collisionSurfaceId, origin, true)

    fun doubleClick(speaker: Int, x: Int, y: Int) = pointerEvent("OnMouseDoubleClick", speaker, x, y)
    fun doubleClick(speaker: Int, x: Int, y: Int, collisionSurfaceId: Int, origin: DialogueToken?) =
        pointerEvent("OnMouseDoubleClick", speaker, x, y, collisionSurfaceId, origin, true)

    fun move(speaker: Int, x: Int, y: Int) = submitMove(speaker, x, y, null, null)
    fun move(speaker: Int, x: Int, y: Int, collisionSurfaceId: Int, source: RenderedMoveSource) =
        submitMove(speaker, x, y, collisionSurfaceId, source)

    private fun submitMove(speaker: Int, x: Int, y: Int, collisionSurfaceId: Int?,
        source: RenderedMoveSource?) {
        if (speaker !in 0..1 || !resumed) return
        val ready = mutableState.value as? StageState.Ready ?: return
        val frame = if (speaker == 0) ready.frame.sakura else ready.frame.kero
        val rendered = source ?: RenderedMoveSource(ready.dialogueToken, frame.surfaceId, frame.visual)
        if (rendered.dialogueToken == null) return
        val point = MovePoint(x, y, collisionSurfaceId, rendered)
        synchronized(moveSubmissionLock) { moveSubmissions[speaker] = point }
    }

    fun cancelMove(speaker: Int) {
        if (speaker !in 0..1) return
        synchronized(moveSubmissionLock) { moveSubmissions[speaker] = null }
        scope.launch {
            (sessionState as? SessionState.Active)?.session?.moves?.get(speaker)?.pending = null
        }
    }

    private fun clearMoveSubmissions() {
        synchronized(moveSubmissionLock) { moveSubmissions.fill(null) }
    }

    private fun drainMoveSubmissions(session: Session) {
        val batch = synchronized(moveSubmissionLock) {
            moveSubmissions.copyOf().also { moveSubmissions.fill(null) }
        }
        val current = DialogueToken(session.id, session.dialogueVersion)
        for (speaker in 0..1) {
            val point = batch[speaker]?.takeIf { it.source.dialogueToken == current } ?: continue
            session.moves[speaker].pending = point
        }
    }

    fun isCurrentDialogue(token: DialogueToken): Boolean =
        dialogueOwner(token.sessionId, token.dialogueId) != null

    fun isFarewellInput(token: InteractionToken): Boolean {
        val session = dialogueOwner(token.sessionId, token.dialogueId) ?: return false
        return inFarewellDialogue(session) && session.frame.input?.key == token.itemKey
    }

    fun choose(token: InteractionToken) {
        scope.launch {
            val session = dialogueOwner(token.sessionId, token.dialogueId) ?: return@launch
            val choice = (session.frame.sakura.choices + session.frame.kero.choices)
                .firstOrNull { it.key == token.itemKey } ?: return@launch
            clearDialogue(session)
            val generation = session.replacementGeneration
            if (inFarewellDialogue(session)) session.transitionRepliesPending++
            publish(session)
            requestAction(session, generation, choice.event)
        }
    }

    fun submitInput(token: InteractionToken, text: String) {
        scope.launch {
            val session = dialogueOwner(token.sessionId, token.dialogueId) ?: return@launch
            if (!ScriptInput.isAllowed(text)) return@launch
            val input = session.frame.input?.takeIf { it.key == token.itemKey } ?: return@launch
            if (session.player?.resolveInput(input.key) != true) return@launch
            if (inFarewellDialogue(session)) session.transitionRepliesPending++
            resumeInput(session)
            requestAction(session, session.replacementGeneration,
                ShioriEvent("OnUserInput", listOf(input.boxId, text)))
        }
    }

    fun cancelInput(token: InteractionToken) {
        scope.launch {
            val session = dialogueOwner(token.sessionId, token.dialogueId) ?: return@launch
            val input = session.frame.input?.takeIf { it.key == token.itemKey } ?: return@launch
            if (session.player?.resolveInput(input.key) == true) resumeInput(session)
        }
    }

    private fun resumeInput(session: Session) {
        session.playerStartedAt = elapsedRealtime()
        session.player?.advanceBy(0)?.let { acceptPlayback(session, it) }
        if (session.frame.ended) {
            session.player = null
            playNext(session)
        }
        publish(session)
        scope.launch { maybeFinishTransition(session) }
    }

    /**
     * Sends [event] for [session]. A failure is reported only while [generation] is still
     * current, unless [reportStale]; either way it returns null.
     */
    private suspend fun request(session: Session, event: ShioriEvent, generation: Long,
        failure: String, reportStale: Boolean = false): ShioriReply? = try {
        session.engine.request(event)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if (reportStale || session.replacementGeneration == generation)
            reportRequestFailure(session, error, failure)
        null
    }

    /** Replies belong to the replacement generation they were requested in. */
    private fun acceptsReply(session: Session, generation: Long): Boolean =
        sameActive(session) && session.replacementGeneration == generation

    private suspend fun requestAction(session: Session, generation: Long, event: ShioriEvent) {
        val reply = request(session, event, generation, "Interaction request failed") ?: run {
            if (inFarewellDialogue(session) && session.replacementGeneration == generation) {
                session.transitionRepliesPending--
                maybeFinishTransition(session)
            }
            return
        }
        if (interactionOwner() !== session || session.replacementGeneration != generation) return
        enqueue(session, reply)
        if (inFarewellDialogue(session)) session.transitionRepliesPending--
        publish(session)
        maybeFinishTransition(session)
    }

    private fun interactionOwner(): Session? = when (val current = sessionState) {
        is SessionState.Active -> current.session
        is SessionState.Closing -> current.session.takeUnless { current.cleanupStarted }
        is SessionState.Switching -> current.from.takeIf { current.phase == Phase.Dialogue }
        else -> null
    }

    private fun dialogueOwner(sessionId: Long, dialogueId: Long): Session? =
        interactionOwner()?.takeIf { it.id == sessionId && it.dialogueVersion == dialogueId }

    private fun inFarewellDialogue(session: Session): Boolean = when (val current = sessionState) {
        is SessionState.Closing -> current.session === session && !current.cleanupStarted
        is SessionState.Switching -> current.from === session && current.phase == Phase.Dialogue
        else -> false
    }

    private suspend fun maybeFinishTransition(session: Session) {
        if (session.player != null || session.queue.isNotEmpty() || session.pendingInteraction() ||
            session.transitionRepliesPending != 0) return
        when (val current = sessionState) {
            is SessionState.Closing -> if (current.session === session) finishClose(current)
            is SessionState.Switching -> if (current.from === session && current.phase == Phase.Dialogue)
                finishSwitch(current)
            else -> Unit
        }
    }

    private fun pointerEvent(id: String, speaker: Int, x: Int, y: Int,
        collisionSurfaceId: Int? = null, origin: DialogueToken? = null,
        requireOrigin: Boolean = false) {
        scope.launch {
            if (!resumed) return@launch
            val active = sessionState as? SessionState.Active ?: return@launch
            val session = active.session
            if (requireOrigin && (origin == null || origin.sessionId != session.id ||
                origin.dialogueId != session.dialogueVersion)) return@launch
            val collision = collisionAt(session, speaker, x, y, collisionSurfaceId)
            clearDialogue(session)
            val generation = session.replacementGeneration
            publish(session)
            val reply = request(session, ShioriEvent(id, touchReferences(speaker, x, y, collision)),
                generation, "Touch request failed") ?: return@launch
            if (!acceptsReply(session, generation)) return@launch
            enqueue(session, reply)
            publish(session)
        }
    }

    private fun touchReferences(speaker: Int, x: Int, y: Int, collision: String) =
        listOf(x.toString(), y.toString(), "0", speaker.toString(), collision, "0", "touch")

    private fun collisionAt(session: Session, speaker: Int, x: Int, y: Int,
        displayedCollisionSurfaceId: Int? = null): String {
        val frame = when (speaker) {
            0 -> session.frame.sakura
            1 -> session.frame.kero
            else -> return ""
        }
        val surfaceId = displayedCollisionSurfaceId ?: frame.visual?.baseSurfaceId ?: frame.surfaceId
        return SurfaceCollisionHitTest.find(
            session.shell?.definitions?.definition(surfaceId)?.collisions.orEmpty(), x, y)
    }

    private fun drainMoves(session: Session, now: Long) {
        if (!resumed || !sameActive(session)) return
        for (speaker in 0..1) {
            val state = session.moves[speaker]
            val point = state.pending ?: continue
            val frame = if (speaker == 0) session.frame.sakura else session.frame.kero
            if (!sameMoveCoordinates(point, frame, speaker)) {
                state.pending = null
                continue
            }
            if (state.inFlight || state.lastAdmittedAt != Long.MIN_VALUE &&
                now - state.lastAdmittedAt < 50) continue
            state.pending = null
            state.inFlight = true
            state.lastAdmittedAt = now
            val generation = session.replacementGeneration
            val collision = collisionAt(session, speaker, point.x, point.y,
                point.collisionSurfaceId)
            scope.launch {
                try {
                    val reply = request(session, ShioriEvent("OnMouseMove",
                        touchReferences(speaker, point.x, point.y, collision)),
                        generation, "Move request failed") ?: return@launch
                    if (resumed && acceptsReply(session, generation)) {
                        enqueue(session, reply)
                        publish(session)
                    }
                } finally {
                    state.inFlight = false
                }
            }
        }
    }

    fun close() {
        scope.launch {
            ++selectionVersion
            when (val current = sessionState) {
                is SessionState.Loading -> {
                    finish()
                    current.done.complete(Unit)
                }
                is SessionState.Active -> closeSession(current.session)
                is SessionState.Switching -> when (current.phase) {
                    Phase.Dialogue -> closeSession(current.from)
                    Phase.Unloading -> sessionState = SessionState.ClosingSwitch(current.from)
                    Phase.Loading -> finish()
                }
                SessionState.Idle -> finish()
                is SessionState.Closing -> {
                    if (!current.cleanupStarted) {
                        clearDialogue(current.session)
                        finishClose(current)
                    }
                }
                is SessionState.ClosingSwitch, SessionState.Finished -> Unit
            }
        }
    }

    private suspend fun closeSession(session: Session) {
        clearDialogue(session)
        val closing = SessionState.Closing(session)
        sessionState = closing
        ensureTicker()
        publish(session)
        session.transitionRepliesPending++
        val reply = try { session.engine.request(ShioriEvent("OnClose")) }
            catch (error: Exception) {
                if (sessionState === closing) {
                    session.transitionRepliesPending--
                    finishClose(closing, error.message)
                }
                return
            }
        if (sessionState !== closing) return
        enqueue(session, reply)
        session.transitionRepliesPending--
        publish(session)
        maybeFinishTransition(session)
    }

    private suspend fun activate(
        expected: SessionState,
        target: GhostDescriptor?,
        base: BundledGhost,
        language: String,
        previousName: String?,
    ) {
        val selected = if (target == null) EngineKind.BUILTIN
            else availableChoiceKinds[target.directoryId]
                ?: withContext(ioDispatcher) { EngineSelector.select(target) }
        val result = if (target == null) {
            bundledSession(base)
        } else if (selected == EngineKind.BUILTIN) {
            try {
                val content = withContext(ioDispatcher) {
                    val locale = language.takeIf { it.matches(Regex("[A-Za-z]{2,8}")) } ?: "ja"
                    val requested = File(target.masterPath, "$locale/content.txt")
                    val file = requested.takeIf { it.isFile } ?: File(target.masterPath, "ja/content.txt")
                    file.readText(Charsets.UTF_8)
                }
                val ghost = descriptorGhost(target).copy(content = content)
                Session(++nextId, ghost, engineFactory(ghost))
            } catch (error: Exception) {
                "Built-in ghost load failed: ${error.message}"
            }
        } else if (selected == EngineKind.UNSUPPORTED) {
            Session(++nextId, descriptorGhost(target), UnsupportedShiori())
        } else {
            if (nativeUnavailable || nativeHost.availability.value != NativeAvailability.Available) {
                NativeLoadResult.Unresolved("Native engine is unavailable")
            } else {
                val kind = when (selected) {
                    EngineKind.SATORI -> NativeKind.SATORI
                    EngineKind.KAWARI -> NativeKind.KAWARI
                    EngineKind.YAYA -> NativeKind.YAYA
                    EngineKind.BUILTIN, EngineKind.UNSUPPORTED -> error("Unexpected engine")
                }
                try {
                    withContext(ioDispatcher) { NativeProfileDirectory.prepare(target.masterPath) }
                    if (sessionState !== expected) return
                    try { nativeHost.load(kind, target.masterPath, target.engineDeclaration.orEmpty()) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        nativeUnavailable = true
                        NativeLoadResult.Unresolved("Native load threw: ${error.message}")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    "Native profile preparation failed: ${error.message}"
                }
            }.let { load ->
                when (load) {
                    is String -> load
                    is NativeLoadResult.Loaded -> {
                        if (sessionState !== expected) {
                            cleanupCandidate(load.lease)
                            return
                        }
                        val initializationError = try {
                            val reply = nativeHost.request(load.lease,
                                ShioriEvent("OnInitialize", method = ShioriMethod.NOTIFY))
                            if (nativeHost.availability.value != NativeAvailability.Occupied) {
                                throw IllegalStateException("Native ownership is unresolved after OnInitialize")
                            }
                            if (reply.status != 200 && reply.status != 204) {
                                logger.warning("OnInitialize returned ${reply.status}")
                            }
                            null
                        } catch (cancelled: CancellationException) {
                            cleanupCandidate(load.lease)
                            throw cancelled
                        } catch (error: Exception) {
                            cleanupCandidate(load.lease)
                            "OnInitialize failed: ${error.message}"
                        }
                        if (initializationError != null) initializationError
                        else Session(
                            ++nextId, descriptorGhost(target),
                            object : ShioriEngine {
                                override suspend fun request(event: ShioriEvent): ShioriReply =
                                    nativeHost.request(load.lease, event)
                            }, load.lease
                        )
                    }
                    is NativeLoadResult.Failed -> {
                        if (load.status == -1) nativeUnavailable = true
                        "Native load failed: ${load.status}"
                    }
                    is NativeLoadResult.Unresolved -> {
                        nativeUnavailable = true
                        load.error
                    }
                    else -> error("Unexpected native load result")
                }
            }
        }
        if (sessionState !== expected) {
            if (result is Session) result.lease?.let { lease ->
                cleanupCandidate(lease)
            }
            return
        }
        var session = result as? Session ?: bundledSession(base)
        var error = result as? String
        var transferred = false
        var failure: String? = null
        try {
            val shell = try {
                loadShell(session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (catalogFailure: Exception) {
                if (session.ghost === base) throw catalogFailure
                // Like other candidate failures, fall back to the bundled ghost. The native
                // lease is released before any state lets another activation begin.
                val failed = session
                session = bundledSession(base)
                error = "Shell load failed: ${catalogFailure.message}"
                failed.lease?.let { cleanupCandidate(it) }
                if (sessionState !== expected) return
                loadShell(session)
            }
            session.shell = shell
            session.animator = session.shell?.let { catalog ->
                SurfaceAnimator(catalog.definitions) { bound -> Random.nextLong(bound) }
                    .also { it.follow(session.frame) }
            }
            settleVisuals(session)
            if (sessionState !== expected) return
            sessionState = SessionState.Active(session, prompt = (expected as? SessionState.Active)?.prompt,
                error = error)
            transferred = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (activationFailure: Exception) {
            failure = activationFailure.message ?: "Ghost activation failed"
        } finally {
            if (!transferred) {
                session.lease?.let { cleanupCandidate(it) }
                if (sessionState === expected) {
                    sessionState = SessionState.Idle
                    mutableState.value = StageState.Error(failure ?: "Ghost activation interrupted")
                }
            }
        }
        if (!transferred) return
        publish(session)
        try {
            val first = bootState.recordActivation(session.ghost.directoryId)
            if (!sameActive(session)) return
            if (target == null && first && bootState.consumeOnboarding()) {
                session.queue.addLast(QueuedDialogue(ONBOARDING))
            }
            lastGhostStore?.write(session.ghost.directoryId)
            val id = when {
                first -> "OnFirstBoot"
                previousName != null && error == null -> "OnGhostChanged"
                else -> "OnBoot"
            }
            val reference = when (id) {
                "OnFirstBoot" -> "0"
                "OnGhostChanged" -> previousName.orEmpty()
                else -> "master"
            }
            val reply = session.engine.request(ShioriEvent(id, listOf(reference)))
            if (!sameActive(session)) return
            enqueue(session, reply)
            publish(session)
            ensureTicker()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            reportRequestFailure(session, failure, "Activation failed")
        }
    }

    /** Shows a request failure on its still-active session unless quarantine recovery replaces it. */
    private suspend fun reportRequestFailure(
        session: Session, error: Exception, fallback: String, show: Boolean = true,
    ) {
        val active = sessionState as? SessionState.Active ?: return
        if (active.session !== session || recoverQuarantinedRequest(active, session, error) || !show) return
        showError(active, error.message ?: fallback)
    }

    private fun showError(active: SessionState.Active, message: String) {
        sessionState = SessionState.Active(active.session, prompt = active.prompt, error = message)
        publish(active.session)
    }

    private suspend fun recoverQuarantinedRequest(
        expected: SessionState,
        failed: Session,
        error: Exception,
    ): Boolean {
        if (failed.lease == null || nativeHost.availability.value != NativeAvailability.Quarantined) {
            return false
        }
        nativeUnavailable = true
        if (sessionState !== expected) return true
        clearDialogue(failed)
        val message = error.message ?: "Native request failed; ownership is unresolved"
        val fallbackExpected = if (expected is SessionState.Active)
            SessionState.Active(failed, error = expected.error) else expected
        try {
            val base = getBundled()
            if (sessionState !== expected) return true
            // The host refuses JNI operations while quarantined. Keep its lease there
            // until process restart and move dialogue to the independent built-in engine.
            sessionState = fallbackExpected
            activate(fallbackExpected, null, base, currentLanguage, previousName = failed.ghost.name)
            val current = sessionState as? SessionState.Active
            if (current != null && current.session.ghost.directoryId == base.directoryId) {
                val visibleError = if (current.error == null) message
                    else "$message; bundled recovery failed: ${current.error}"
                sessionState = SessionState.Active(current.session, error = visibleError)
                publish(current.session)
            }
        } catch (fallbackError: Exception) {
            if (sessionState === expected || sessionState === fallbackExpected) {
                sessionState = SessionState.Idle
                mutableState.value = StageState.Error("$message; bundled recovery failed: ${fallbackError.message}")
            }
        }
        return true
    }

    private suspend fun finishSwitch(switching: SessionState.Switching) {
        if (sessionState !== switching) return
        val from = switching.from
        val unloading = SessionState.Switching(from, switching.to, Phase.Unloading)
        sessionState = unloading
        val outcome = from.lease?.let { shutdownSafely(it) }
        val interrupted = sessionState as? SessionState.ClosingSwitch
        if (interrupted?.from === from) {
            shutdownError(outcome, from.lease)?.let(logger::warning)
            clearDialogue(from)
            finish()
            return
        }
        if (sessionState !== unloading) return
        val failure = shutdownError(outcome, from.lease)
        clearDialogue(from)
        val loading = SessionState.Switching(from, switching.to, Phase.Loading)
        sessionState = loading
        val base = try { getBundled() } catch (error: Exception) {
            sessionState = SessionState.Finished
            mutableState.value = StageState.Error(error.message ?: "Bundled ghost unavailable")
            return
        }
        if (failure != null) {
            activate(loading, null, base, currentLanguage, previousName = from.ghost.name)
            val active = sessionState as? SessionState.Active
            if (active != null) {
                sessionState = SessionState.Active(active.session, error = failure)
                publish(active.session)
            }
        } else {
            activate(loading, switching.to, base, currentLanguage, previousName = from.ghost.name)
        }
    }

    private suspend fun finishClose(closing: SessionState.Closing, requestError: String? = null) {
        if (sessionState !== closing || closing.cleanupStarted) return
        closing.cleanupStarted = true
        val cleanup = closing.session.lease?.let { shutdownSafely(it) }
        if (sessionState !== closing) return
        ticker?.cancel()
        ticker = null
        listOfNotNull(requestError, shutdownError(cleanup, closing.session.lease))
            .forEach(logger::warning)
        clearDialogue(closing.session)
        finish()
    }

    private fun finish() {
        sessionState = SessionState.Finished
        mutableState.value = StageState.Finished
    }

    private fun ensureTicker() {
        if (!resumed || ticker?.isActive == true || tickedSession() == null) return
        ticker = scope.launch {
            var lastTickAt = elapsedRealtime()
            while (true) {
                delay(50)
                val now = elapsedRealtime()
                val current = sessionState
                val session = tickedSession() ?: break
                val sinceLastTick = (now - lastTickAt).coerceAtLeast(0L)
                val before = session.frame
                val player = session.player
                var talking = emptySet<Int>()
                if (player != null) {
                    val elapsed = (now - maxOf(lastTickAt, session.playerStartedAt)).coerceAtLeast(0L)
                    val playback = player.advanceBy(elapsed)
                    talking = playback.textAdvanceSpeakers
                    acceptPlayback(session, playback)
                    if (session.frame.ended) {
                        session.player = null
                        playNext(session)
                        if (session.player == null) scope.launch { maybeFinishTransition(session) }
                    }
                }
                val animator = session.animator
                if (animator != null) {
                    val animationElapsed = if (player != null && session.player !== player &&
                        session.player != null) 0L else sinceLastTick
                    animator.advanceBy(animationElapsed, talking)?.let { applyVisuals(session, it) }
                }
                val eligibilityChanged = filteredChoicesKey?.third !=
                    (nativeHost.availability.value to nativeUnavailable)
                if (current is SessionState.Active && sessionState === current) {
                    drainMoveSubmissions(session)
                    drainMoves(session, now)
                }
                if (sessionState === current && (session.frame != before || eligibilityChanged)) publish(session)
                lastTickAt = now
                if (current is SessionState.Active && sessionState === current) {
                    // Resumed Active time accumulates per session, so pauses and cancelled
                    // switches delay the next minute instead of restarting it.
                    val previousSecond = session.clockElapsedMs / 1_000
                    session.clockElapsedMs += sinceLastTick
                    val currentSecond = session.clockElapsedMs / 1_000
                    if (currentSecond > previousSecond) {
                        dispatchClock(session, "OnSecondChange")
                    }
                    if (currentSecond / 60 > previousSecond / 60) {
                        dispatchClock(session, "OnMinuteChange")
                    }
                }
            }
            ticker = null
        }
    }

    /** The session whose playback, animation and farewell dialogue the ticker advances. */
    private fun tickedSession(): Session? = when (val current = sessionState) {
        is SessionState.Active -> current.session
        is SessionState.Switching -> current.from
        is SessionState.Closing -> current.session
        else -> null
    }

    private fun dispatchClock(session: Session, id: String) {
        if (!session.pendingClockEvents.add(id)) return
        val generation = session.replacementGeneration
        scope.launch {
            try {
                if (!resumed || !sameActive(session)) return@launch
                val reply = request(session, ShioriEvent(id, listOf(
                    (elapsedRealtime() / 3_600_000).toString(), "0", "0", "1")),
                    generation, "Timer request failed", reportStale = true) ?: return@launch
                if (!resumed || !acceptsReply(session, generation)) return@launch
                enqueue(session, reply, id)
                publish(session)
            } finally {
                session.pendingClockEvents.remove(id)
            }
        }
    }

    private fun sameActive(session: Session): Boolean =
        (sessionState as? SessionState.Active)?.session?.id == session.id

    private fun clearDialogue(session: Session) {
        clearMoveSubmissions()
        session.clearPendingMoves()
        session.dialogueVersion++
        session.replacementGeneration++
        session.transitionRepliesPending = 0
        session.queue.clear()
        session.surfaceEdges.clear()
        session.player = null
        session.frame = EMPTY_FRAME
        session.animator?.follow(EMPTY_FRAME)
        settleVisuals(session)
    }

    private fun enqueue(session: Session, reply: ShioriReply, clockEventId: String? = null) {
        if (reply.status == 200 && !reply.value.isNullOrEmpty()) {
            val script = ScriptPlayer.substituteNames(reply.value, mapOf(
                "username" to "User",
                "selfname" to session.ghost.sakuraName,
                "selfname2" to session.ghost.sakuraName2,
                "keroname" to session.ghost.keroName,
            ))
            session.queue.addLast(QueuedDialogue(script, clockEventId))
            if (clockEventId != null && session.pendingInteraction()) session.coalesceClockReplies()
        }
        if (session.player == null) playNext(session)
    }

    private fun playNext(session: Session) {
        if (session.pendingInteraction()) return
        session.player = session.queue.removeFirstOrNull()?.let { queued ->
            session.dialogueVersion++
            ScriptPlayer(queued.script)
        }
        if (session.player != null) session.playerStartedAt = elapsedRealtime()
        session.player?.advanceBy(0)?.let { acceptPlayback(session, it) }
        settleVisuals(session)
    }

    private fun acceptPlayback(session: Session, playback: PlaybackFrame) {
        val prior = session.frame
        session.frame = playback.copy(
            sakura = playback.sakura.copy(visual = prior.sakura.visual),
            kero = playback.kero.copy(visual = prior.kero.visual),
            textAdvanceSpeakers = emptySet(), animationRequests = emptyList(),
            surfaceChanges = emptyList(),
        )
        if (!prior.hasInteraction() && session.pendingInteraction()) session.coalesceClockReplies()
        session.animator?.let { animator ->
            animator.follow(playback)
            playback.animationRequests.forEach { animator.startExplicit(it.speaker, it.animationId) }
        }
        if (playback.surfaceChanges.isNotEmpty()) dispatchSurfaceChanges(session, playback.surfaceChanges)
    }

    private fun dispatchSurfaceChanges(session: Session, changes: List<SurfaceChange>) {
        if (!sameActive(session)) return
        val version = session.dialogueVersion
        val generation = session.replacementGeneration
        changes.forEach { session.surfaceEdges.addLast(SurfaceEdge(it, version, generation)) }
        if (session.surfaceDraining) return
        session.surfaceDraining = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                while (true) {
                    val edge = session.surfaceEdges.removeFirstOrNull() ?: break
                    if (!sameActive(session)) {
                        session.surfaceEdges.clear()
                        break
                    }
                    if (session.replacementGeneration != edge.replacementGeneration) continue
                    val reply = try {
                        session.engine.request(ShioriEvent("OnSurfaceChange", listOf(
                            edge.change.sakuraId.toString(), edge.change.keroId.toString())))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        if (session.replacementGeneration == edge.replacementGeneration)
                            reportRequestFailure(session, error, "Surface request failed",
                                show = session.dialogueVersion == edge.dialogueVersion)
                        continue
                    }
                    if (acceptsReply(session, edge.replacementGeneration) &&
                        session.dialogueVersion == edge.dialogueVersion) {
                        enqueue(session, reply)
                        publish(session)
                    }
                }
            } finally {
                session.surfaceDraining = false
            }
        }
    }

    // Only acceptPlayback and clearDialogue change a frame's surface, and both call follow.
    private fun SurfaceAnimator.follow(frame: PlaybackFrame) {
        setSurface(0, if (frame.sakura.visible) frame.sakura.surfaceId else -1)
        setSurface(1, if (frame.kero.visible) frame.kero.surfaceId else -1)
    }

    private fun settleVisuals(session: Session) {
        session.animator?.let { applyVisuals(session, it.advanceBy(0, emptySet())) }
    }

    private fun applyVisuals(session: Session, visuals: Map<Int, SurfaceVisual>?) {
        if (visuals != null) session.visuals = visuals
        val current = session.visuals
        session.frame = session.frame.copy(
            sakura = session.frame.sakura.copy(visual = current[0]),
            kero = session.frame.kero.copy(visual = current[1]),
        )
    }

    private fun publish(session: Session) {
        val active = sessionState as? SessionState.Active
        val prompt = active?.prompt?.view
        val key = Triple(session.id, choicesVersion,
            nativeHost.availability.value to nativeUnavailable)
        if (filteredChoicesKey != key) {
            filteredChoices = availableChoices.filter {
                nativeSelectionAllowed(session) || availableChoiceKinds[it.directoryId] !in NATIVE_KINDS
            }
            filteredChoicesKey = key
        }
        val owns = interactionOwner() === session
        fun token(key: Long) = InteractionToken(session.id, session.dialogueVersion, key)
        // A shared empty list keeps balloons skippable while no choices are shown.
        fun present(frame: SpeakerFrame) = if (frame.choices.isEmpty()) emptyList()
            else frame.choices.map { PresentedChoice(token(it.key), it.label, it.textOffset) }
        mutableState.value = StageState.Ready(
            session.frame, session.ghost.name, session.ghost.sakuraName,
            session.ghost.keroName, session.ghost.surfaces, filteredChoices, prompt, active?.error,
            session.shell,
            choices = if (owns) mapOf(0 to present(session.frame.sakura), 1 to present(session.frame.kero))
                else emptyMap(),
            input = if (owns) session.frame.input?.let { PresentedInput(token(it.key), it.boxId) } else null,
            dialogueToken = DialogueToken(session.id, session.dialogueVersion),
        )
    }

    private fun readme(path: File?): String? = path?.let {
        runCatching {
            val limit = 64 * 1024
            val buffer = ByteArray(limit + 1)
            val count = it.inputStream().use { input ->
                var read = 0
                while (read < buffer.size) {
                    val next = input.read(buffer, read, buffer.size - read)
                    if (next < 0) break
                    read += next
                }
                read
            }
            val bytes = buffer.copyOf(count.coerceAtMost(limit))
            val content = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
            } else bytes.toString(Charset.forName("Shift_JIS"))
            if (count > limit) "$content\n\n[README truncated]" else content
        }.getOrNull()
    }

    private fun nativeSelectionAllowed(session: Session): Boolean = !nativeUnavailable && when (
        nativeHost.availability.value
    ) {
        NativeAvailability.Available -> true
        NativeAvailability.Occupied -> session.lease != null
        NativeAvailability.Quarantined -> false
    }

    private suspend fun cleanupCandidate(lease: NativeLease) {
        withContext(NonCancellable) {
            shutdownSafely(lease, notifyDestroy = false).let { result ->
                shutdownError(result, lease)?.let(logger::warning)
            }
        }
    }

    private suspend fun shutdownSafely(
        lease: NativeLease, notifyDestroy: Boolean = true,
    ): NativeShutdownResult = try {
        if (nativeHost.availability.value == NativeAvailability.Quarantined) {
            nativeUnavailable = true
            NativeShutdownResult.Unresolved("Native ownership is quarantined")
        } else nativeHost.shutdown(lease, notifyDestroy = notifyDestroy)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        nativeUnavailable = true
        NativeShutdownResult.Unresolved("Native shutdown threw: ${error.message}")
    }

    private fun shutdownError(result: NativeShutdownResult?, lease: NativeLease?): String? =
        if (lease == null) null else when (result) {
            NativeShutdownResult.Completed -> null
            is NativeShutdownResult.Failed -> result.message
            is NativeShutdownResult.Unresolved -> {
                nativeUnavailable = true
                result.message
            }
            null -> "Native shutdown did not complete"
        }

    private suspend fun getBundled(): BundledGhost {
        bundled?.let { return it }
        return loadGhost().also { bundled = it }
    }

    private fun bundledSession(base: BundledGhost) = Session(++nextId, base, getBundledEngine(base))

    private suspend fun loadShell(session: Session): ShellCatalog? =
        session.ghost.shellDirectory?.let { loadCatalog(it, session.ghost.surfaces, ioDispatcher) }

    private fun getBundledEngine(ghost: BundledGhost): ShioriEngine {
        bundledEngine?.let { return it }
        return engineFactory(ghost).also { bundledEngine = it }
    }

    private fun descriptorGhost(descriptor: GhostDescriptor) = BundledGhost(
        descriptor.directoryId, descriptor.displayName, descriptor.sakuraName,
        descriptor.keroName, descriptor.surfaces, "",
        File(descriptor.masterPath.parentFile.parentFile, "shell/master"),
        descriptor.sakuraName2
    )

    private companion object {
        val NATIVE_KINDS = setOf(EngineKind.SATORI, EngineKind.KAWARI, EngineKind.YAYA)
        const val ONBOARDING = "\\0\\s0Welcome to Nanidroid.\\nTap a character to talk. Tap empty stage for Ghosts and About.\\e"
        val EMPTY_FRAME = PlaybackFrame(
            SpeakerFrame(0, true, "", false), SpeakerFrame(10, true, "", false), true
        )
    }
}
