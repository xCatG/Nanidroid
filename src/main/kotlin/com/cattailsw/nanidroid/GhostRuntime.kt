package com.cattailsw.nanidroid

import android.content.Context
import com.cattailsw.nanidroid.runtime.PlayerCommand
import com.cattailsw.nanidroid.runtime.PlayerEffect
import com.cattailsw.nanidroid.runtime.PlayerParent
import com.cattailsw.nanidroid.runtime.PlayerResponse
import com.cattailsw.nanidroid.runtime.PlayerState
import com.cattailsw.nanidroid.runtime.RuntimeCatalog
import com.cattailsw.nanidroid.runtime.RuntimeCatalogEffect
import com.cattailsw.nanidroid.runtime.RuntimeCatalogOwner
import com.cattailsw.nanidroid.runtime.RuntimeCatalogScanOutcome
import com.cattailsw.nanidroid.runtime.RuntimeCatalogScanner
import com.cattailsw.nanidroid.runtime.RuntimeCatalogState
import com.cattailsw.nanidroid.runtime.RuntimeGhostMetadata
import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeCommandDispatcher
import com.cattailsw.nanidroid.runtime.RuntimeHostInput
import com.cattailsw.nanidroid.runtime.RuntimeHostReducer
import com.cattailsw.nanidroid.runtime.RuntimeHostState
import com.cattailsw.nanidroid.runtime.RuntimeNativeLifecycleOutcome
import com.cattailsw.nanidroid.runtime.RuntimeNativePort
import com.cattailsw.nanidroid.runtime.RuntimePendingGhostIdentity
import com.cattailsw.nanidroid.runtime.RuntimeRequestOrigin
import com.cattailsw.nanidroid.runtime.RuntimeRequestToken
import com.cattailsw.nanidroid.runtime.RuntimeScheduler
import com.cattailsw.nanidroid.runtime.ApplicationRuntimeScheduler
import com.cattailsw.nanidroid.runtime.RuntimeSnapshot
import com.cattailsw.nanidroid.runtime.SakuraScriptPlayer
import com.cattailsw.nanidroid.runtime.SerializedRuntimeCommandDispatcher
import com.cattailsw.nanidroid.runtime.dialogue.GhostEventCapabilityDiscovery
import com.cattailsw.nanidroid.runtime.dialogue.DialogueActionKey
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities
import com.cattailsw.nanidroid.runtime.dialogue.ShioriMethod
import com.cattailsw.nanidroid.shiori.Kawari
import com.cattailsw.nanidroid.shiori.LoadFailureState
import com.cattailsw.nanidroid.shiori.NanidroidShiori
import com.cattailsw.nanidroid.shiori.NotSupportedShiori
import com.cattailsw.nanidroid.shiori.SatoriShiori
import com.cattailsw.nanidroid.shiori.Shiori
import com.cattailsw.nanidroid.shiori.ShioriLoadResult
import com.cattailsw.nanidroid.shiori.ShioriRequestException
import com.cattailsw.nanidroid.shiori.ShioriUnloadResult
import com.cattailsw.nanidroid.shiori.YayaShiori
import com.cattailsw.nanidroid.util.PrefUtil
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.StringReader
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class GhostHandle(
    val ghost: Ghost,
    val pointerCapabilities: PointerEventCapabilities,
    val generation: Long,
)

internal data class TaggedShioriResponse(
    val generation: Long,
    val response: ShioriResponse,
)

@ConsistentCopyVisibility
internal data class ShioriRequestIntent private constructor(
    val protocolText: String,
) {
    companion object {
        fun event(eventId: String, references: List<String?> = emptyList()) =
            ShioriRequestIntent(formatEventRequest(eventId, references))

        fun raw(
            method: ShioriMethod,
            eventId: String,
            references: List<String?> = emptyList(),
        ) = ShioriRequestIntent(formatRawRequest(method, eventId, references))

        private fun formatEventRequest(eventId: String, references: List<String?>): String =
            buildString {
                append("GET SHIORI/3.0\r\nSender: Nanidroid\r\nID: ")
                append(eventId)
                append("\r\nSecurityLevel: local\r\n")
                appendReferences(references)
                append("\r\n")
            }

        private fun formatRawRequest(
            method: ShioriMethod,
            eventId: String,
            references: List<String?>,
        ): String = buildString {
            append(method.name)
            append(" SHIORI/3.0\r\nSender: Nanidroid\r\nSecurityLevel: local\r\nID: ")
            append(eventId)
            append("\r\n")
            appendReferences(references)
            append("\r\n")
        }

        private fun StringBuilder.appendReferences(references: List<String?>) {
            references.forEachIndexed { index, value ->
                append("Reference").append(index).append(": ").append(value).append("\r\n")
            }
        }
    }
}

internal sealed interface RuntimeFailure {
    data object Busy : RuntimeFailure
    data object StaleGeneration : RuntimeFailure
    data class Replayable(val cause: Throwable) : RuntimeFailure
    data class Fatal(val cause: Throwable) : RuntimeFailure
}

internal fun shouldInstallBundledGhost(
    usableGhostCount: Int,
    storageEntries: Array<out File>,
): Boolean = usableGhostCount == 0 && storageEntries.all { entry ->
    entry.isDirectory && entry.name == ".nanidroid-install-staging"
}

internal sealed interface RuntimeResult<out T> {
    data class Success<T>(val value: T) : RuntimeResult<T>
    data class Failure(val failure: RuntimeFailure) : RuntimeResult<Nothing>
}

internal sealed interface RuntimeRequestSubmission {
    data class Accepted(
        val result: CompletableFuture<RuntimeResult<TaggedShioriResponse>>,
    ) : RuntimeRequestSubmission

    data class Rejected(
        val failure: RuntimeResult.Failure,
    ) : RuntimeRequestSubmission
}

internal sealed interface BootOutcome {
    data class Response(val tagged: TaggedShioriResponse) : BootOutcome
    data class BootAttemptFailed(val cause: Throwable) : BootOutcome
}

internal sealed interface AttachmentReceipt {
    data class NewlyAttached(val operationId: Long) : AttachmentReceipt
    data object AlreadyAttached : AttachmentReceipt
}

internal fun interface AttachmentAdmission {
    fun admit(
        operationId: Long,
        handle: GhostHandle,
        outcome: BootOutcome,
    ): RuntimeResult<Unit>
}

internal interface GhostRuntimePersistence {
    fun readLastRunGhostId(): String?
    fun commitLastRunGhostId(ghostId: String)
    fun readActivationCount(ghostId: String): Long
    fun commitActivationCount(ghostId: String, count: Long)
}

internal sealed interface AttachmentReason {
    data object Initial : AttachmentReason
    data class Switched(val outgoingGhostName: String) : AttachmentReason
}

internal data class GhostRuntimeTestHooks(
    val onPreparationStarted: (Long, String, File) -> Unit = { _, _, _ -> },
    val onNativeLoadStarted: (Long, GhostEngine) -> Unit = { _, _ -> },
    val onGenerationPublished: (Long, String) -> Unit = { _, _ -> },
    val onActivationCommitted: (Long) -> Unit = {},
    val onBootAttempted: (Long, String) -> Unit = { _, _ -> },
    val onOutgoingUnloaded: (Long) -> Unit = {},
)

internal data class NativeLifecycleProbeTrace(
    val engine: GhostEngine,
    val commandThreadNames: List<String>,
    val steps: List<String>,
)

internal data class PendingGhostIdentity(
    val operationId: Long,
    val ghostId: String,
    val canonicalRoot: File,
)

internal enum class GhostRuntimePhase {
    Idle,
    Starting,
    Unattached,
    Attaching,
    Attached,
    SwitchPlayback,
    Replacing,
    Poisoned,
}

internal data class GhostRuntimeIdentity(
    val activeHandle: GhostHandle?,
    val pending: PendingGhostIdentity?,
    val phase: GhostRuntimePhase,
)

internal class GhostRuntime private constructor(
    private val preparer: GhostPreparer,
    private val persistence: GhostRuntimePersistence,
    private val nativePort: RuntimeNativePort,
    private val scheduler: RuntimeScheduler,
    private val dispatcher: RuntimeCommandDispatcher,
    private val catalogScanner: RuntimeCatalogScanner,
    private val elapsedRealtimeMillis: () -> Long,
    private val canonicalizeRoot: (File) -> File,
) : Closeable {
    constructor(context: Context) : this(
        preparer = GhostPreparer(context.applicationContext),
        persistence = PreferenceGhostRuntimePersistence(context.applicationContext),
        nativePort = NativeSessionRuntimePort(context.applicationContext),
        scheduler = ApplicationRuntimeScheduler(),
        dispatcher = SerializedRuntimeCommandDispatcher(),
        catalogScanner = RuntimeCatalogScanner { InstalledGhostCatalog.scan(context.applicationContext) },
        elapsedRealtimeMillis = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
        canonicalizeRoot = File::getCanonicalFile,
    )

    private val mutableSnapshots = MutableStateFlow(RuntimeSnapshot.initial())
    val snapshots: StateFlow<RuntimeSnapshot> = mutableSnapshots.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))
    private val nativeExecutor = Executors.newSingleThreadExecutor { action ->
        Thread(action, "GhostRuntime-SnapshotNative").apply { isDaemon = true }
    }
    private val traceLock = Any()
    private val trace = mutableListOf<String>()

    private var hostState = RuntimeHostState.empty()
    private var catalogOwner = RuntimeCatalogOwner(
        state = RuntimeSnapshot.initial().catalog,
        requestedEpoch = 0L,
        scanInFlight = true,
        dirty = false,
    )
    private var playerState: PlayerState? = null
    private var pending: SnapshotPendingOperation? = null
    private var activePrepared: PreparedGhost? = null
    private var generation: Long? = null
    private var nextGeneration = 0L
    private var nextOperationId = 0L
    private var phase = GhostRuntimePhase.Idle
    private var notice: com.cattailsw.nanidroid.runtime.RuntimeNotice? = null
    private var modeRevision = 0L
    private var clockState = RuntimeClockState(
        running = false,
        epoch = 0L,
        lastSecondBucket = null,
        lastMinuteBucket = null,
    )
    private var attachmentOperationId: Long? = null
    private var parentState: SnapshotParentState? = null
    private var deferredPlayback: PlayerEffect.SchedulePlayback? = null
    private var nextCueId = 1L
    private var requestRegistry = RuntimeRequestRegistry(
        nextRequestId = 1L,
        pending = emptySet(),
        claimedDialogue = emptyMap(),
    )
    private var joinedStartup: SnapshotStartupDecision? = null
    private var canonicalizing: SnapshotStartupDecision? = null
    private var nextNativeSubmission = 1L
    private var nextNativeCompletion = 1L
    private val nativeCompletions = sortedMapOf<Long, RuntimeCommand>()
    private var nativeLifecycle: SnapshotNativeLifecycle = SnapshotNativeLifecycle.Empty
    private val queuedNativeRequests = java.util.ArrayDeque<SnapshotNativeRequest>()
    private val closeReady = java.util.concurrent.CountDownLatch(1)
    private val resourceLock = Any()
    private val snapshotPublicationBarrier = Any()
    private var pendingSnapshotPublication: SnapshotPublication? = null
    @Volatile
    private var closed = false

    init {
        startCatalogScan(0L)
    }

    fun submit(command: RuntimeCommand) {
        if (closed) return
        dispatcher.dispatch { admit(command) }
    }

    private fun submitIo(completion: SnapshotIoCompletion) {
        if (closed) return
        dispatcher.dispatch {
            if (closed) return@dispatch
            admitIo(completion)
            publishSnapshot()
        }
    }

    private fun submitNativeCompletion(sequence: Long, command: RuntimeCommand) {
        if (closed) return
        dispatcher.dispatch {
            if (closed) return@dispatch
            nativeCompletions[sequence] = command
            while (true) {
                val next = nativeCompletions.remove(nextNativeCompletion) ?: break
                nextNativeCompletion += 1L
                admit(next)
            }
        }
    }

    internal fun enqueueScriptForTesting(script: String, parent: PlayerParent? = null) {
        if (closed) return
        dispatcher.dispatch {
            record("EnqueueScript")
            val current = playerState ?: return@dispatch
            consumePlayerTransition(SakuraScriptPlayer.reduce(current, PlayerCommand.Enqueue(script, parent)))
            publishSnapshot()
        }
    }

    internal fun snapshotCommandTraceForTesting(): List<String> = synchronized(traceLock) { trace.toList() }

    internal fun pendingSnapshotRequestCountForTesting(): Int = requestRegistry.pending.size

    internal fun claimedDialogueCountForTesting(): Int = requestRegistry.claimedDialogue.size

    internal fun blockSnapshotNativeLaneForTesting(action: () -> Unit) {
        synchronized(resourceLock) {
            if (closed) return
            nativeExecutor.execute(action)
        }
    }

    internal fun shouldInstallBundledGhostForTesting(storageEntries: Array<out File>): Boolean {
        val ready = catalogOwner.state as? RuntimeCatalogState.Ready ?: return false
        return shouldInstallBundledGhost(ready.entries.size, storageEntries)
    }

    internal fun preferredGhostId(): String? = persistence.readLastRunGhostId()

    private fun admit(command: RuntimeCommand) {
        if (closed) return
        record(command.javaClass.simpleName)
        when (command) {
            is RuntimeCommand.RegisterHost,
            is RuntimeCommand.SetResumed,
            is RuntimeCommand.SetTopResumed,
            is RuntimeCommand.UnregisterHost,
            is RuntimeCommand.ClaimExit,
            is RuntimeCommand.AcknowledgeExit,
            is RuntimeCommand.AcknowledgeCues,
            -> admitHost(command)
            is RuntimeCommand.CatalogChanged,
            is RuntimeCommand.CatalogScanned,
            is RuntimeCommand.RetryCatalog,
            -> admitCatalog(command)
            is RuntimeCommand.StartGhost -> startGhost(command)
            is RuntimeCommand.PreparationCompleted -> preparationCompleted(command)
            is RuntimeCommand.NativeLoadCompleted -> nativeLoadCompleted(command)
            is RuntimeCommand.NativeUnloadCompleted -> nativeUnloadCompleted(command)
            is RuntimeCommand.PlaybackDue -> playbackDue(command)
            is RuntimeCommand.NativeResponse -> nativeResponse(command)
            is RuntimeCommand.TimerDue -> timerDue(command)
            is RuntimeCommand.ActivateChoice -> dialogueCommand(
                PlayerCommand.ActivateChoice(command.key),
                RuntimeDialogueClaimKind.CHOICE,
            )
            is RuntimeCommand.ActivateAnchor -> dialogueCommand(
                PlayerCommand.ActivateAnchor(command.key),
                RuntimeDialogueClaimKind.ANCHOR,
            )
            is RuntimeCommand.SubmitInput -> dialogueCommand(
                PlayerCommand.SubmitInput(command.key, command.value),
                RuntimeDialogueClaimKind.INPUT_SUBMIT,
            )
            is RuntimeCommand.DismissInput -> dialogueCommand(
                PlayerCommand.DismissInput(command.key),
                RuntimeDialogueClaimKind.INPUT_DISMISS,
            )
            is RuntimeCommand.InputExpired -> dialogueCommand(
                PlayerCommand.InputExpired(command.key, command.elapsedMillis),
                RuntimeDialogueClaimKind.INPUT_TIMEOUT,
            )
            is RuntimeCommand.Back -> back(command)
            is RuntimeCommand.SwitchGhost -> switchGhost(command)
            is RuntimeCommand.Pointer -> pointer(command)
        }
        publishSnapshot()
    }

    private fun admitHost(command: RuntimeCommand) {
        val beforeTop = hostState.topResumed
        val beforeExit = hostState.exit
        val transition = RuntimeHostReducer.reduce(hostState, RuntimeHostInput.Command(command))
        hostState = transition.state
        if (transition.effects.any {
                it is com.cattailsw.nanidroid.runtime.RuntimeHostEffect.BackpressureChanged && !it.paused
            }
        ) {
            deferredPlayback?.let { schedulePlayback(it, playerState?.generation) }
            deferredPlayback = null
        }
        if (command is RuntimeCommand.AcknowledgeExit && beforeExit != null && hostState.exit == null) {
            parentState = null
            modeRevision += 1L
        }
        val afterTop = hostState.topResumed
        if (beforeTop != afterTop) {
            generation?.let { activeGeneration ->
                safeCancel(
                    com.cattailsw.nanidroid.runtime.RuntimeScheduleKey(
                        activeGeneration,
                        com.cattailsw.nanidroid.runtime.RuntimeScheduleKind.CLOCK,
                        clockState.epoch,
                    ),
                )
            }
            clockState = clockState.copy(
                running = afterTop != null && generation != null,
                epoch = clockState.epoch + 1L,
                lastSecondBucket = null,
                lastMinuteBucket = null,
            )
            scheduleClockIfRunning()
        }
    }

    private fun admitCatalog(command: RuntimeCommand) {
        val transition = RuntimeCatalog.reduce(catalogOwner, command)
        catalogOwner = transition.owner
        transition.effects.filterIsInstance<RuntimeCatalogEffect.StartScan>().forEach { effect ->
            startCatalogScan(effect.epoch)
        }
        if (catalogOwner.state is RuntimeCatalogState.Ready) {
            joinedStartup?.let {
                joinedStartup = null
                beginStartupIfCatalogReady(it)
            }
        }
    }

    private fun startGhost(command: RuntimeCommand.StartGhost) {
        if (phase != GhostRuntimePhase.Idle || pending != null || generation != null) return
        val decision = SnapshotStartupDecision(command.ghostId, command.canonicalRoot.path)
        if (catalogOwner.state !is RuntimeCatalogState.Ready) {
            if (joinedStartup == null || joinedStartup == decision) joinedStartup = decision
            return
        }
        beginStartupIfCatalogReady(decision)
    }

    private fun beginStartupIfCatalogReady(decision: SnapshotStartupDecision) {
        if (phase != GhostRuntimePhase.Idle || pending != null || generation != null || canonicalizing != null) return
        val ready = catalogOwner.state as? RuntimeCatalogState.Ready ?: run {
            joinedStartup = decision
            return
        }
        if (ready.entries.none { it.id.equals(decision.ghostId, ignoreCase = true) }) return
        val operationId = ++nextOperationId
        canonicalizing = decision.copy(operationId = operationId)
        phase = GhostRuntimePhase.Starting
        ioScope.launch {
            submitIo(
                SnapshotIoCompletion.Canonicalized(
                    operationId,
                    decision.ghostId,
                    runCatching { canonicalizeRoot(File(decision.rootPath)) }.getOrNull(),
                ),
            )
        }
    }

    private fun prepare(operation: SnapshotPendingOperation) {
        ioScope.launch {
            val outcome = runCatching {
                preparer.prepare(operation.operationId, operation.ghostId, operation.canonicalRoot)
            }.fold(
                onSuccess = { com.cattailsw.nanidroid.runtime.RuntimePreparationOutcome.Prepared(it) },
                onFailure = {
                    com.cattailsw.nanidroid.runtime.RuntimePreparationOutcome.Failed(
                        com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.PREPARATION_FAILED,
                    )
                },
            )
            submit(RuntimeCommand.PreparationCompleted(operation.operationId, outcome))
        }
    }

    private fun admitIo(completion: SnapshotIoCompletion) {
        when (completion) {
            is SnapshotIoCompletion.Canonicalized -> {
                val decision = canonicalizing?.takeIf { it.operationId == completion.operationId } ?: return
                canonicalizing = null
                val root = completion.canonicalRoot
                if (root == null) {
                    phase = GhostRuntimePhase.Idle
                    notice = com.cattailsw.nanidroid.runtime.RuntimeNotice(
                        completion.operationId,
                        com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.PREPARATION_FAILED,
                    )
                    return
                }
                val operation = SnapshotPendingOperation(
                    completion.operationId,
                    decision.ghostId,
                    root,
                    switchOutgoingName = null,
                )
                pending = operation
                prepare(operation)
            }
            is SnapshotIoCompletion.LoadPersistence -> {
                val operation = pending?.takeIf { it.operationId == completion.operationId } ?: return
                val prepared = operation.prepared ?: return
                if (!completion.committed) {
                    pending = null
                    transitionToPoisoned(operation.operationId)
                    return
                }
                finishNativeLoad(operation, prepared, completion.generation, completion.activationCount)
            }
            is SnapshotIoCompletion.AttachmentPersistence -> {
                if (attachmentOperationId != completion.operationId || phase != GhostRuntimePhase.Attaching) return
                finishAttachment(completion.operationId)
            }
        }
    }

    private fun preparationCompleted(command: RuntimeCommand.PreparationCompleted) {
        val operation = pending?.takeIf { it.operationId == command.operationId } ?: return
        when (val outcome = command.outcome) {
            is com.cattailsw.nanidroid.runtime.RuntimePreparationOutcome.Failed -> {
                pending = null
                phase = GhostRuntimePhase.Idle
                clearParent()
                notice = com.cattailsw.nanidroid.runtime.RuntimeNotice(operation.operationId, outcome.reason)
            }
            is com.cattailsw.nanidroid.runtime.RuntimePreparationOutcome.Prepared -> {
                if (
                    outcome.value.operationId != operation.operationId ||
                    outcome.value.id != operation.ghostId ||
                    outcome.value.canonicalRoot != operation.canonicalRoot
                ) {
                    pending = null
                    phase = GhostRuntimePhase.Idle
                    clearParent()
                    notice = com.cattailsw.nanidroid.runtime.RuntimeNotice(
                        operation.operationId,
                        com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.PREPARATION_FAILED,
                    )
                    return
                }
                operation.prepared = outcome.value
                val candidateGeneration = nextGeneration + 1L
                enqueueNativeLoad(operation, outcome.value, candidateGeneration)
            }
        }
    }

    private fun nativeLoadCompleted(command: RuntimeCommand.NativeLoadCompleted) {
        val operation = pending?.takeIf {
            it.operationId == command.operationId && command.generation == nextGeneration + 1L
        } ?: return
        val prepared = operation.prepared ?: return
        when (val outcome = command.outcome) {
            RuntimeNativeLifecycleOutcome.Success -> {
                ioScope.launch {
                    val committed = runCatching { persistence.commitLastRunGhostId(prepared.id) }.isSuccess
                    val activationCount = if (committed) {
                        runCatching { persistence.readActivationCount(prepared.id) }.getOrDefault(0L)
                    } else {
                        0L
                    }
                    submitIo(
                        SnapshotIoCompletion.LoadPersistence(
                            operation.operationId,
                            command.generation,
                            committed,
                            activationCount,
                        ),
                    )
                }
            }
            is RuntimeNativeLifecycleOutcome.Failed -> {
                pending = null
                if (outcome.ownershipCertain) {
                    phase = GhostRuntimePhase.Idle
                    clearParent()
                    notice = com.cattailsw.nanidroid.runtime.RuntimeNotice(command.operationId, outcome.reason)
                } else {
                    transitionToPoisoned(command.operationId, outcome.reason)
                }
            }
        }
    }

    private fun finishNativeLoad(
        operation: SnapshotPendingOperation,
        prepared: PreparedGhost,
        loadedGeneration: Long,
        activationCount: Long,
    ) {
        nextGeneration = loadedGeneration
        generation = loadedGeneration
        activePrepared = prepared
        playerState = PlayerState.initial(loadedGeneration)
        pending = null
        phase = GhostRuntimePhase.Attaching
        notice = null
        attachmentOperationId = operation.operationId
        parentState = (parentState as? SnapshotParentState.Switch)?.let { parent ->
            parent.copy(phase = SnapshotParentPhase.ATTACHING, phaseRevision = parent.phaseRevision + 1L)
        }
        clockState = clockState.copy(running = hostState.topResumed != null)
        scheduleClockIfRunning()
        val intent = if (operation.switchOutgoingName != null) {
            ShioriRequestIntent.event("OnGhostChanged", listOf(operation.switchOutgoingName, null))
        } else if (activationCount == 0L) {
            ShioriRequestIntent.event("OnFirstBoot", listOf("0"))
        } else {
            ShioriRequestIntent.event("OnBoot", listOf(prepared.shellName ?: "master"))
        }
        submitRequest(
            origin = RuntimeRequestOrigin.Attachment(operation.operationId),
            intent = intent,
            fallback = null,
            parentOperationId = operation.operationId,
            dialogueClaim = null,
        )
    }

    private fun playbackDue(command: RuntimeCommand.PlaybackDue) {
        if (phase == GhostRuntimePhase.Poisoned) return
        val current = playerState ?: return
        if (command.generation != current.generation || command.token != current.playbackToken) return
        consumePlayerTransition(
            SakuraScriptPlayer.reduce(
                current,
                PlayerCommand.Advance(command.token, elapsedRealtimeMillis()),
            ),
        )
    }

    private fun timerDue(command: RuntimeCommand.TimerDue) {
        if (phase == GhostRuntimePhase.Poisoned || parentState != null) return
        val activeGeneration = generation ?: return
        if (
            !clockState.running ||
            command.generation != activeGeneration ||
            command.clockEpoch != clockState.epoch
        ) return
        val duplicate = when (command.kind) {
            com.cattailsw.nanidroid.runtime.RuntimeTimerKind.SECOND -> clockState.lastSecondBucket == command.bucket
            com.cattailsw.nanidroid.runtime.RuntimeTimerKind.MINUTE -> clockState.lastMinuteBucket == command.bucket
        }
        if (duplicate) return
        clockState = when (command.kind) {
            com.cattailsw.nanidroid.runtime.RuntimeTimerKind.SECOND -> clockState.copy(lastSecondBucket = command.bucket)
            com.cattailsw.nanidroid.runtime.RuntimeTimerKind.MINUTE -> clockState.copy(lastMinuteBucket = command.bucket)
        }
        val origin = RuntimeRequestOrigin.Timer(
            clockEpoch = command.clockEpoch,
            kind = command.kind,
            bucket = command.bucket,
            mode = currentModeIdentity(),
        )
        submitRequest(
            origin = origin,
            intent = ShioriRequestIntent.event(
                if (command.kind == com.cattailsw.nanidroid.runtime.RuntimeTimerKind.SECOND) {
                    "OnSecondChange"
                } else {
                    "OnMinuteChange"
                },
            ),
            fallback = null,
            parentOperationId = null,
            dialogueClaim = null,
        )
        if (command.kind == com.cattailsw.nanidroid.runtime.RuntimeTimerKind.SECOND) {
            val minuteBucket = command.bucket / 60L
            if (clockState.lastMinuteBucket != minuteBucket) {
                submit(
                    RuntimeCommand.TimerDue(
                        activeGeneration,
                        command.clockEpoch,
                        com.cattailsw.nanidroid.runtime.RuntimeTimerKind.MINUTE,
                        minuteBucket,
                    ),
                )
            }
        }
        scheduleClockIfRunning()
    }

    private fun back(command: RuntimeCommand.Back) {
        val existing = parentState
        if (existing is SnapshotParentState.Exit) return
        if (existing != null || command.expected != currentModeIdentity()) return
        if (generation != command.generation) return
        if (
            command.generation == null &&
            hasReservedNativeOwnership() &&
            phase != GhostRuntimePhase.Poisoned
        ) return
        if (phase == GhostRuntimePhase.Poisoned) {
            val operationId = ++nextOperationId
            parentState = SnapshotParentState.Exit(
                operationId,
                command.generation,
                SnapshotParentPhase.REQUEST,
                1L,
            )
            modeRevision += 1L
            clearGenerationRequests(command.generation ?: return offerExit(operationId, null))
            offerExit(operationId, command.generation)
            return
        }
        if (command.generation != null && (phase != GhostRuntimePhase.Attached || hostState.topResumed != command.host)) {
            return
        }
        val operationId = ++nextOperationId
        if (command.generation == null) {
            joinedStartup = null
            canonicalizing = null
            pending = null
            phase = GhostRuntimePhase.Idle
        }
        parentState = SnapshotParentState.Exit(
            operationId = operationId,
            generation = command.generation,
            phase = SnapshotParentPhase.REQUEST,
            phaseRevision = 1L,
        )
        modeRevision += 1L
        if (command.generation == null) {
            offerExit(operationId, null)
            return
        }
        clearGenerationRequests(command.generation)
        playerState?.let {
            consumePlayerTransition(SakuraScriptPlayer.reduce(it, PlayerCommand.Clear(null)))
        }
        submitRequest(
            origin = RuntimeRequestOrigin.Parent(operationId, 1L),
            intent = ShioriRequestIntent.event("OnClose"),
            fallback = null,
            parentOperationId = operationId,
            dialogueClaim = null,
        )
    }

    private fun switchGhost(command: RuntimeCommand.SwitchGhost) {
        if (
            parentState != null ||
            phase != GhostRuntimePhase.Attached ||
            generation != command.generation ||
            hostState.topResumed != command.host ||
            command.expected != currentModeIdentity()
        ) return
        val ready = catalogOwner.state as? RuntimeCatalogState.Ready ?: return
        val target = ready.entries.firstOrNull { it.id.equals(command.targetGhostId, ignoreCase = true) } ?: return
        if (target.id.equals(activePrepared?.id, ignoreCase = true)) return
        val operationId = ++nextOperationId
        parentState = SnapshotParentState.Switch(
            operationId = operationId,
            generation = command.generation,
            targetGhostId = target.id,
            targetRoot = File(target.canonicalRootPath),
            outgoingName = activePrepared?.name ?: activePrepared?.id.orEmpty(),
            phase = SnapshotParentPhase.REQUEST,
            phaseRevision = 1L,
        )
        phase = GhostRuntimePhase.SwitchPlayback
        modeRevision += 1L
        clearGenerationRequests(command.generation)
        playerState?.let {
            consumePlayerTransition(SakuraScriptPlayer.reduce(it, PlayerCommand.Clear(null)))
        }
        submitRequest(
            origin = RuntimeRequestOrigin.Parent(operationId, 1L),
            intent = ShioriRequestIntent.event(
                "OnGhostChanging",
                listOf(target.name ?: target.id, "ghost", null, target.canonicalRootPath),
            ),
            fallback = null,
            parentOperationId = operationId,
            dialogueClaim = null,
        )
    }

    private fun pointer(command: RuntimeCommand.Pointer) {
        if (phase == GhostRuntimePhase.Poisoned) return
        val current = playerState ?: return
        if (
            parentState != null ||
            command.generation != current.generation ||
            hostState.topResumed != command.host ||
            !isCurrentSurface(command.surface)
        ) return
        val eventId = when (command.effect.kind) {
            com.cattailsw.nanidroid.runtime.dialogue.PointerEventKind.CLICK -> "OnMouseClick"
            com.cattailsw.nanidroid.runtime.dialogue.PointerEventKind.DOUBLE_CLICK -> "OnMouseDoubleClick"
            else -> return
        }
        if (command.effect.button != 0) return
        submitRequest(
            origin = RuntimeRequestOrigin.Pointer(command.surface),
            intent = ShioriRequestIntent.event(
                eventId,
                listOf(
                    command.effect.intrinsic.x.toString(),
                    command.effect.intrinsic.y.toString(),
                    "0",
                    command.effect.speaker.legacyReference,
                    command.effect.collisionIdentifier.orEmpty(),
                    command.effect.button.toString(),
                    command.effect.source.shioriReference,
                ),
            ),
            fallback = null,
            parentOperationId = null,
            dialogueClaim = null,
        )
    }

    private fun isCurrentSurface(surface: com.cattailsw.nanidroid.runtime.RuntimeSurfaceIdentity): Boolean {
        val current = playerState ?: return false
        if (surface.generation != current.generation) return false
        val presentation = when (surface.speaker) {
            com.cattailsw.nanidroid.runtime.GhostSpeaker.SAKURA -> current.presentation.sakura
            com.cattailsw.nanidroid.runtime.GhostSpeaker.KERO -> current.presentation.kero
        }
        return presentation.surfaceId == surface.surfaceId && presentation.surfaceEpoch == surface.surfaceEpoch
    }

    private fun dialogueCommand(command: PlayerCommand, claimKind: RuntimeDialogueClaimKind) {
        if (phase == GhostRuntimePhase.Poisoned) return
        val current = playerState ?: return
        consumePlayerTransition(SakuraScriptPlayer.reduce(current, command), claimKind)
    }

    private fun nativeResponse(command: RuntimeCommand.NativeResponse) {
        if (phase == GhostRuntimePhase.Poisoned) {
            record("NativeResponseRejected")
            return
        }
        if (command.token !in requestRegistry.pending) {
            if ((command.result as? RuntimeResult.Failure)?.failure is RuntimeFailure.Fatal) {
                val activeExit = parentState as? SnapshotParentState.Exit
                if (activeExit != null) poisonAndOfferExit(activeExit)
                else transitionToPoisoned(command.token.requestId)
                return
            }
            record("NativeResponseRejected")
            return
        }
        val claim = requestRegistry.claimedDialogue[command.token.requestId]
        requestRegistry = requestRegistry.copy(
            pending = requestRegistry.pending - command.token,
            claimedDialogue = requestRegistry.claimedDialogue - command.token.requestId,
        )
        val tagged = (command.result as? RuntimeResult.Success)?.value
        if (tagged != null && tagged.generation != command.token.generation) {
            record("NativeResponseRejected")
            return
        }
        val current = playerState
        val valid = current != null && command.token.generation == current.generation && when (val origin = command.token.origin) {
            is RuntimeRequestOrigin.Playback -> current.authoredRequest == origin
            is RuntimeRequestOrigin.Dialogue -> claim?.action == origin.action
            is RuntimeRequestOrigin.Attachment -> {
                attachmentOperationId == origin.operationId && phase == GhostRuntimePhase.Attaching
            }
            is RuntimeRequestOrigin.Timer -> {
                clockState.running &&
                    clockState.epoch == origin.clockEpoch &&
                    origin.mode == currentModeIdentity() &&
                    when (origin.kind) {
                        com.cattailsw.nanidroid.runtime.RuntimeTimerKind.SECOND -> {
                            clockState.lastSecondBucket == origin.bucket
                        }
                        com.cattailsw.nanidroid.runtime.RuntimeTimerKind.MINUTE -> {
                            clockState.lastMinuteBucket == origin.bucket
                        }
                    }
            }
            is RuntimeRequestOrigin.Parent -> {
                val parent = parentState
                parent != null &&
                    parent.operationId == origin.operationId &&
                    parent.phaseRevision == origin.phaseRevision &&
                    parent.phase == SnapshotParentPhase.REQUEST
            }
            is RuntimeRequestOrigin.Pointer -> isCurrentSurface(origin.surface)
        }
        if (!valid) {
            record("NativeResponseRejected")
            return
        }
        when (command.token.origin) {
            is RuntimeRequestOrigin.Playback -> consumePlayerTransition(
                SakuraScriptPlayer.reduce(
                    current,
                    PlayerCommand.NativeResponse(command.token, command.result.toPlayerResponse(current.generation)),
                ),
            )
            is RuntimeRequestOrigin.Dialogue -> when (val result = command.result) {
                is RuntimeResult.Success -> {
                    if (result.value.generation != current.generation) {
                        record("NativeResponseRejected")
                        return
                    }
                    val value = result.value.response.takeIf { it.getStatusCode() == 200 }?.getKey("Value")
                    if (!value.isNullOrEmpty()) {
                        consumePlayerTransition(
                            SakuraScriptPlayer.reduce(current, PlayerCommand.Enqueue(value, null)),
                        )
                    }
                }
                is RuntimeResult.Failure -> {
                    if (result.failure is RuntimeFailure.Fatal) {
                        transitionToPoisoned(command.token.requestId)
                    } else {
                        notice = com.cattailsw.nanidroid.runtime.RuntimeNotice(
                            command.token.requestId,
                            com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.REQUEST_FAILED,
                        )
                    }
                }
            }
            is RuntimeRequestOrigin.Attachment -> settleAttachmentResponse(command)
            is RuntimeRequestOrigin.Timer -> settlePlayableResponse(command.result, current, command.token.requestId)
            is RuntimeRequestOrigin.Parent -> settleParentResponse(command)
            is RuntimeRequestOrigin.Pointer -> settlePlayableResponse(command.result, current, command.token.requestId)
        }
    }

    private fun settleParentResponse(command: RuntimeCommand.NativeResponse) {
        val parent = parentState ?: return
        if ((command.result as? RuntimeResult.Failure)?.failure is RuntimeFailure.Fatal) {
            if (parent is SnapshotParentState.Exit) {
                poisonAndOfferExit(parent)
            } else {
                transitionToPoisoned(parent.operationId)
            }
            return
        }
        val response = (command.result as? RuntimeResult.Success)?.value
            ?.takeIf { it.generation == command.token.generation }
            ?.response
        val playable = response?.takeIf { it.getStatusCode() == 200 }?.getKey("Value")
        when (parent) {
            is SnapshotParentState.Exit -> {
                if (playable.isNullOrEmpty()) {
                    offerExit(parent.operationId, parent.generation)
                } else {
                    parentState = parent.copy(
                        phase = SnapshotParentPhase.PLAYBACK,
                        phaseRevision = parent.phaseRevision + 1L,
                    )
                    modeRevision += 1L
                    playerState?.let {
                        consumePlayerTransition(
                            SakuraScriptPlayer.reduce(
                                it,
                                PlayerCommand.Enqueue(playable, PlayerParent.Exit(parent.operationId)),
                            ),
                        )
                    }
                }
            }
            is SnapshotParentState.Switch -> {
                if (command.result is RuntimeResult.Failure) {
                    notice = com.cattailsw.nanidroid.runtime.RuntimeNotice(
                        parent.operationId,
                        com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.REQUEST_FAILED,
                    )
                    parentState = null
                    phase = GhostRuntimePhase.Attached
                    modeRevision += 1L
                    scheduleClockIfRunning()
                } else if (playable.isNullOrEmpty()) {
                    startSwitchUnload(parent)
                } else {
                    parentState = parent.copy(
                        phase = SnapshotParentPhase.PLAYBACK,
                        phaseRevision = parent.phaseRevision + 1L,
                    )
                    modeRevision += 1L
                    playerState?.let {
                        consumePlayerTransition(
                            SakuraScriptPlayer.reduce(
                                it,
                                PlayerCommand.Enqueue(playable, PlayerParent.Switch(parent.operationId)),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun offerExit(operationId: Long, exitGeneration: Long?) {
        hostState = RuntimeHostReducer.reduce(
            hostState,
            RuntimeHostInput.OfferExit(operationId, exitGeneration),
        ).state
        parentState = (parentState as? SnapshotParentState.Exit)?.copy(
            phase = SnapshotParentPhase.READY,
            phaseRevision = parentState!!.phaseRevision + 1L,
        )
        modeRevision += 1L
    }

    private fun poisonAndOfferExit(parent: SnapshotParentState.Exit) {
        transitionToPoisoned(parent.operationId)
        parentState = parent.copy(
            phase = SnapshotParentPhase.REQUEST,
            phaseRevision = parent.phaseRevision + 1L,
        )
        modeRevision += 1L
        offerExit(parent.operationId, parent.generation)
    }

    private fun startSwitchUnload(parent: SnapshotParentState.Switch) {
        val next = parent.copy(
            phase = SnapshotParentPhase.UNLOADING,
            phaseRevision = parent.phaseRevision + 1L,
        )
        parentState = next
        phase = GhostRuntimePhase.Replacing
        modeRevision += 1L
        enqueueNativeUnload(parent.operationId, parent.generation)
    }

    private fun nativeUnloadCompleted(command: RuntimeCommand.NativeUnloadCompleted) {
        val parent = parentState as? SnapshotParentState.Switch ?: return
        if (
            parent.operationId != command.operationId ||
            parent.generation != command.generation ||
            parent.phase != SnapshotParentPhase.UNLOADING
        ) return
        when (command.outcome) {
            RuntimeNativeLifecycleOutcome.Success -> {
                retireGenerationSchedules(parent.generation)
                generation = null
                activePrepared = null
                playerState = null
                attachmentOperationId = null
                deferredPlayback = null
                hostState = hostState.copy(cues = emptyList(), playerBackpressured = false)
                clearGenerationRequests(parent.generation)
                clockState = clockState.copy(running = false, epoch = clockState.epoch + 1L)
                val replacing = parent.copy(
                    phase = SnapshotParentPhase.REPLACING,
                    phaseRevision = parent.phaseRevision + 1L,
                )
                parentState = replacing
                pending = SnapshotPendingOperation(
                    operationId = parent.operationId,
                    ghostId = parent.targetGhostId,
                    canonicalRoot = parent.targetRoot,
                    switchOutgoingName = parent.outgoingName,
                )
                phase = GhostRuntimePhase.Replacing
                prepare(requireNotNull(pending))
            }
            is RuntimeNativeLifecycleOutcome.Failed -> {
                transitionToPoisoned(
                    parent.operationId,
                    com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.NATIVE_UNLOAD_FAILED,
                )
            }
        }
    }

    private fun settleAttachmentResponse(command: RuntimeCommand.NativeResponse) {
        val operationId = attachmentOperationId ?: return
        val current = playerState ?: return
        when (val result = command.result) {
            is RuntimeResult.Success -> {
                if (result.value.generation != current.generation) return
                val value = result.value.response.takeIf { it.getStatusCode() == 200 }?.getKey("Value")
                if (!value.isNullOrEmpty()) {
                    consumePlayerTransition(SakuraScriptPlayer.reduce(current, PlayerCommand.Enqueue(value, null)))
                }
                val prepared = activePrepared
                if (prepared == null) {
                    finishAttachment(operationId)
                } else {
                    ioScope.launch {
                        runCatching {
                            val count = persistence.readActivationCount(prepared.id)
                            persistence.commitActivationCount(prepared.id, count + 1L)
                        }
                        submitIo(SnapshotIoCompletion.AttachmentPersistence(operationId))
                    }
                }
            }
            is RuntimeResult.Failure -> when (result.failure) {
                is RuntimeFailure.Fatal -> {
                    transitionToPoisoned(operationId)
                }
                else -> {
                    notice = com.cattailsw.nanidroid.runtime.RuntimeNotice(
                        operationId,
                        com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.REQUEST_FAILED,
                    )
                    finishAttachment(operationId)
                }
            }
        }
    }

    private fun finishAttachment(operationId: Long) {
        if (attachmentOperationId != operationId) return
        phase = GhostRuntimePhase.Attached
        attachmentOperationId = null
        if (parentState is SnapshotParentState.Switch) {
            parentState = null
            modeRevision += 1L
        }
    }

    private fun settlePlayableResponse(
        result: RuntimeResult<TaggedShioriResponse>,
        current: PlayerState,
        operationId: Long,
    ) {
        when (result) {
            is RuntimeResult.Success -> {
                if (result.value.generation != current.generation) return
                val value = result.value.response.takeIf { it.getStatusCode() == 200 }?.getKey("Value")
                if (!value.isNullOrEmpty()) {
                    consumePlayerTransition(SakuraScriptPlayer.reduce(current, PlayerCommand.Enqueue(value, null)))
                }
            }
            is RuntimeResult.Failure -> if (result.failure is RuntimeFailure.Fatal) {
                transitionToPoisoned(operationId)
            } else {
                notice = com.cattailsw.nanidroid.runtime.RuntimeNotice(
                    operationId,
                    com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.REQUEST_FAILED,
                )
            }
        }
    }

    private fun consumePlayerTransition(
        transition: com.cattailsw.nanidroid.runtime.PlayerTransition,
        dialogueClaimKind: RuntimeDialogueClaimKind? = null,
    ) {
        if (transition.state == playerState && transition.effects.isEmpty()) return
        val previousMode = runtimeMode(playerState)
        val previousInput = playerState?.dialogue?.input
        val previousIncarnation = playerState?.dialogue?.state?.incarnation
        playerState = transition.state
        if (
            previousIncarnation != null &&
            previousIncarnation != transition.state.dialogue.state.incarnation
        ) {
            val retiredClaims = requestRegistry.claimedDialogue
                .filterValues { it.action.generation == transition.state.generation && it.action.incarnation != transition.state.dialogue.state.incarnation }
                .keys
            if (retiredClaims.isNotEmpty()) {
                requestRegistry = requestRegistry.copy(
                    pending = requestRegistry.pending.filterNot { it.requestId in retiredClaims }.toSet(),
                    claimedDialogue = requestRegistry.claimedDialogue - retiredClaims,
                )
            }
        }
        if (runtimeMode(transition.state) != previousMode) modeRevision += 1L
        transition.effects.forEach { effect ->
            when (effect) {
                is PlayerEffect.SchedulePlayback -> {
                    if (hostState.playerBackpressured) {
                        deferredPlayback = effect
                    } else {
                        schedulePlayback(effect, transition.state.generation)
                    }
                }
                is PlayerEffect.PresentationCue -> {
                    val cueId = nextCueId++
                    hostState = RuntimeHostReducer.reduce(
                        hostState,
                        RuntimeHostInput.Cue(
                            cueId,
                            com.cattailsw.nanidroid.runtime.RuntimeCuePayload(
                                transition.state.generation,
                                effect.speaker,
                                effect.kind,
                                effect.animationId,
                            ),
                        ),
                    ).state
                }
                is PlayerEffect.RequestShiori -> submitNativeRequest(effect, dialogueClaimKind)
                is PlayerEffect.ParentCompleted -> when (val parent = effect.parent) {
                    is PlayerParent.Exit -> {
                        val active = parentState as? SnapshotParentState.Exit
                        if (active?.operationId == parent.operationId && active.phase == SnapshotParentPhase.PLAYBACK) {
                            offerExit(active.operationId, active.generation)
                        }
                    }
                    is PlayerParent.Switch -> {
                        val active = parentState as? SnapshotParentState.Switch
                        if (active?.operationId == parent.operationId && active.phase == SnapshotParentPhase.PLAYBACK) {
                            startSwitchUnload(active)
                        }
                    }
                }
                is PlayerEffect.Failure -> {
                    val failedParent = effect.parent
                    notice = com.cattailsw.nanidroid.runtime.RuntimeNotice(
                        when (failedParent) {
                            is PlayerParent.Exit -> failedParent.operationId
                            is PlayerParent.Switch -> failedParent.operationId
                            null -> transition.state.generation
                        },
                        effect.reason,
                    )
                    if (effect.reason == com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.RUNTIME_POISONED) {
                        val activeExit = (failedParent as? PlayerParent.Exit)?.let { failed ->
                            (parentState as? SnapshotParentState.Exit)
                                ?.takeIf { it.operationId == failed.operationId }
                        }
                        if (activeExit != null) {
                            poisonAndOfferExit(activeExit)
                        } else {
                            transitionToPoisoned(
                                when (failedParent) {
                                    is PlayerParent.Exit -> failedParent.operationId
                                    is PlayerParent.Switch -> failedParent.operationId
                                    null -> transition.state.generation
                                },
                            )
                        }
                    } else {
                        when (failedParent) {
                            is PlayerParent.Exit -> offerExit(failedParent.operationId, generation)
                            is PlayerParent.Switch -> {
                                parentState = null
                                phase = GhostRuntimePhase.Attached
                                modeRevision += 1L
                                scheduleClockIfRunning()
                            }
                            null -> Unit
                        }
                    }
                }
            }
        }
        val currentInput = transition.state.dialogue.input
        if (previousInput?.key != currentInput?.key) {
            previousInput?.let {
                safeCancel(
                    com.cattailsw.nanidroid.runtime.RuntimeScheduleKey(
                        it.key.generation,
                        com.cattailsw.nanidroid.runtime.RuntimeScheduleKind.INPUT_TIMEOUT,
                        it.key.actionId,
                    ),
                )
            }
            currentInput?.takeIf { it.pending.deadlineElapsedMillis != Long.MAX_VALUE }?.let { input ->
                val key = com.cattailsw.nanidroid.runtime.RuntimeScheduleKey(
                    input.key.generation,
                    com.cattailsw.nanidroid.runtime.RuntimeScheduleKind.INPUT_TIMEOUT,
                    input.key.actionId,
                )
                val delay = (input.pending.deadlineElapsedMillis - elapsedRealtimeMillis()).coerceAtLeast(0L)
                safeSchedule(key, delay) {
                    submit(RuntimeCommand.InputExpired(input.key, elapsedRealtimeMillis()))
                }
            }
        }
    }

    private fun submitNativeRequest(
        effect: PlayerEffect.RequestShiori,
        dialogueClaimKind: RuntimeDialogueClaimKind?,
    ) {
        val current = playerState ?: return
        val parentOperationId = current.current?.payload?.parent?.let { parent ->
            when (parent) {
                is PlayerParent.Switch -> parent.operationId
                is PlayerParent.Exit -> parent.operationId
            }
        }
        val claim = (effect.origin as? RuntimeRequestOrigin.Dialogue)?.let { origin ->
            RuntimeDialogueRequestClaim(
                action = origin.action,
                kind = requireNotNull(dialogueClaimKind) { "Dialogue request requires an exact claim kind" },
            )
        }
        submitRequest(
            origin = effect.origin,
            intent = effect.intent,
            fallback = effect.fallback,
            parentOperationId = parentOperationId,
            dialogueClaim = claim,
        )
    }

    private fun schedulePlayback(effect: PlayerEffect.SchedulePlayback, activeGeneration: Long?) {
        val scheduledGeneration = activeGeneration ?: return
        val key = com.cattailsw.nanidroid.runtime.RuntimeScheduleKey(
            scheduledGeneration,
            com.cattailsw.nanidroid.runtime.RuntimeScheduleKind.PLAYBACK,
            effect.token,
        )
        safeSchedule(key, effect.delayMillis) {
            submit(RuntimeCommand.PlaybackDue(key.generation, key.token))
        }
    }

    private fun submitRequest(
        origin: RuntimeRequestOrigin,
        intent: ShioriRequestIntent,
        fallback: ShioriRequestIntent?,
        parentOperationId: Long?,
        dialogueClaim: RuntimeDialogueRequestClaim?,
    ) {
        val activeGeneration = generation ?: return
        val requestId = requestRegistry.nextRequestId
        val token = RuntimeRequestToken(activeGeneration, requestId, parentOperationId, origin)
        requestRegistry = requestRegistry.copy(
            nextRequestId = requestId + 1L,
            pending = requestRegistry.pending + token,
            claimedDialogue = if (dialogueClaim == null) {
                requestRegistry.claimedDialogue
            } else {
                requestRegistry.claimedDialogue + (requestId to dialogueClaim)
            },
        )
        enqueueNativeRequest(token, intent, fallback)
    }

    private fun safeSchedule(
        key: com.cattailsw.nanidroid.runtime.RuntimeScheduleKey,
        delayMillis: Long,
        action: () -> Unit,
    ) {
        synchronized(resourceLock) {
            if (closed) return
            runCatching { scheduler.schedule(key, delayMillis, action) }
        }
    }

    private fun hasReservedNativeOwnership(): Boolean =
        synchronized(resourceLock) { nativeLifecycle !is SnapshotNativeLifecycle.Empty }

    private fun enqueueNativeLoad(
        operation: SnapshotPendingOperation,
        prepared: PreparedGhost,
        candidateGeneration: Long,
    ) {
        val sequence = nextNativeSubmission++
        val loading = SnapshotNativeLifecycle.Loading(
            ownership = SnapshotNativeOwnership(operation.operationId, candidateGeneration),
            sequence = sequence,
            prepared = prepared,
        )
        var admissionFailure: Throwable? = null
        synchronized(resourceLock) {
            if (closed) return
            if (nativeLifecycle !is SnapshotNativeLifecycle.Empty) {
                admissionFailure = IllegalStateException("Native load admitted while an adapter is reserved")
                return@synchronized
            }
            nativeLifecycle = loading
            try {
                nativeExecutor.execute { invokeNativeLoad(loading) }
            } catch (failure: RejectedExecutionException) {
                if (nativeLifecycle === loading) nativeLifecycle = SnapshotNativeLifecycle.Empty
                admissionFailure = failure
            }
        }
        admissionFailure?.let {
            submitNativeCompletion(
                sequence,
                RuntimeCommand.NativeLoadCompleted(
                    operation.operationId,
                    candidateGeneration,
                    RuntimeNativeLifecycleOutcome.Failed(
                        com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.NATIVE_LOAD_FAILED,
                        ownershipCertain = true,
                    ),
                ),
            )
        }
    }

    private fun invokeNativeLoad(loading: SnapshotNativeLifecycle.Loading) {
        synchronized(resourceLock) {
            if (nativeLifecycle !== loading || loading.settled.get()) return
            loading.invoked = true
        }
        try {
            nativePort.load(
                loading.ownership.operationId,
                loading.ownership.generation,
                loading.prepared,
            ) { outcome -> settleNativeLoad(loading, outcome) }
        } catch (_: Throwable) {
            settleNativeLoad(
                loading,
                RuntimeNativeLifecycleOutcome.Failed(
                    com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.NATIVE_LOAD_FAILED,
                    ownershipCertain = false,
                ),
            )
        }
    }

    private fun settleNativeLoad(
        loading: SnapshotNativeLifecycle.Loading,
        outcome: RuntimeNativeLifecycleOutcome,
    ) {
        if (!loading.settled.compareAndSet(false, true)) return
        var publish = false
        synchronized(resourceLock) {
            if (nativeLifecycle !== loading) return
            val keepOwnership = outcome == RuntimeNativeLifecycleOutcome.Success ||
                (outcome is RuntimeNativeLifecycleOutcome.Failed && !outcome.ownershipCertain)
            if (keepOwnership) {
                val loaded = SnapshotNativeLifecycle.Loaded(loading.ownership)
                nativeLifecycle = loaded
                if (closed || loading.closeRequested) {
                    loaded.closeRequested = true
                    startCloseUnloadLocked(loaded)
                } else {
                    publish = true
                }
            } else {
                nativeLifecycle = SnapshotNativeLifecycle.Empty
                if (closed || loading.closeRequested) finishNativeCloseLocked()
                else publish = true
            }
        }
        if (publish) {
            submitNativeCompletion(
                loading.sequence,
                RuntimeCommand.NativeLoadCompleted(
                    loading.ownership.operationId,
                    loading.ownership.generation,
                    outcome,
                ),
            )
        }
    }

    private fun enqueueNativeRequest(
        token: RuntimeRequestToken,
        intent: ShioriRequestIntent,
        fallback: ShioriRequestIntent?,
    ) {
        val request = SnapshotNativeRequest(nextNativeSubmission++, token, intent, fallback)
        var rejected = false
        synchronized(resourceLock) {
            if (closed) return
            val loaded = nativeLifecycle as? SnapshotNativeLifecycle.Loaded
            if (loaded == null || loaded.closeRequested || loaded.fatalPending) {
                rejected = true
            } else if (loaded.request == null) {
                loaded.request = request
                startNativeRequestLocked(loaded, request)
            } else {
                queuedNativeRequests.addLast(request)
            }
        }
        if (rejected) {
            submitNativeCompletion(
                request.sequence,
                RuntimeCommand.NativeResponse(
                    token,
                    RuntimeResult.Failure(
                        RuntimeFailure.Fatal(IllegalStateException("Native request has no loaded adapter")),
                    ),
                ),
            )
        }
    }

    private fun startNativeRequestLocked(
        loaded: SnapshotNativeLifecycle.Loaded,
        request: SnapshotNativeRequest,
    ) {
        try {
            nativeExecutor.execute { invokeNativeRequest(loaded, request) }
        } catch (failure: RejectedExecutionException) {
            if (loaded.request === request) loaded.request = null
            if (closed || loaded.closeRequested) {
                startCloseUnloadLocked(loaded)
            } else {
                submitNativeCompletion(
                    request.sequence,
                    RuntimeCommand.NativeResponse(
                        request.token,
                        RuntimeResult.Failure(RuntimeFailure.Fatal(failure)),
                    ),
                )
            }
        }
    }

    private fun invokeNativeRequest(
        loaded: SnapshotNativeLifecycle.Loaded,
        request: SnapshotNativeRequest,
    ) {
        synchronized(resourceLock) {
            if (nativeLifecycle !== loaded || loaded.request !== request || request.settled.get()) return
            request.invoked = true
        }
        try {
            nativePort.request(request.token, request.intent, request.fallback) { result ->
                settleNativeRequest(loaded, request, result)
            }
        } catch (failure: Throwable) {
            settleNativeRequest(
                loaded,
                request,
                RuntimeResult.Failure(RuntimeFailure.Fatal(failure)),
            )
        }
    }

    private fun settleNativeRequest(
        loaded: SnapshotNativeLifecycle.Loaded,
        request: SnapshotNativeRequest,
        result: RuntimeResult<TaggedShioriResponse>,
    ) {
        if (!request.settled.compareAndSet(false, true)) return
        var publish = false
        var canceled = emptyList<SnapshotNativeRequest>()
        synchronized(resourceLock) {
            if (nativeLifecycle !== loaded || loaded.request !== request) return
            loaded.request = null
            if (closed || loaded.closeRequested) {
                queuedNativeRequests.clear()
                startCloseUnloadLocked(loaded)
            } else if ((result as? RuntimeResult.Failure)?.failure is RuntimeFailure.Fatal) {
                canceled = buildList {
                    while (queuedNativeRequests.isNotEmpty()) add(queuedNativeRequests.removeFirst())
                }
                loaded.fatalPending = true
                publish = true
            } else {
                publish = true
                val next = queuedNativeRequests.pollFirst()
                if (next != null) {
                    loaded.request = next
                    startNativeRequestLocked(loaded, next)
                }
            }
        }
        if (publish) {
            submitNativeCompletion(request.sequence, RuntimeCommand.NativeResponse(request.token, result))
            canceled.forEach { canceledRequest ->
                submitNativeCompletion(
                    canceledRequest.sequence,
                    RuntimeCommand.NativeResponse(
                        canceledRequest.token,
                        RuntimeResult.Failure(RuntimeFailure.StaleGeneration),
                    ),
                )
            }
        }
    }

    private fun enqueueNativeUnload(operationId: Long, retiredGeneration: Long) {
        val work = SnapshotNativeUnload(nextNativeSubmission++, operationId, retiredGeneration)
        var admissionFailure: Throwable? = null
        synchronized(resourceLock) {
            if (closed) return
            val loaded = nativeLifecycle as? SnapshotNativeLifecycle.Loaded
            if (
                loaded == null ||
                loaded.ownership.generation != retiredGeneration ||
                loaded.request != null
            ) {
                admissionFailure = IllegalStateException("Native unload has no idle loaded adapter")
                return@synchronized
            }
            val unloading = SnapshotNativeLifecycle.Unloading(loaded.ownership, work)
            nativeLifecycle = unloading
            try {
                nativeExecutor.execute { invokeNativeUnload(unloading) }
            } catch (failure: RejectedExecutionException) {
                nativeLifecycle = loaded
                admissionFailure = failure
            }
        }
        admissionFailure?.let { failure ->
            submitNativeCompletion(
                requireNotNull(work.sequence),
                RuntimeCommand.NativeUnloadCompleted(
                    operationId,
                    retiredGeneration,
                    RuntimeNativeLifecycleOutcome.Failed(
                        com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.NATIVE_UNLOAD_FAILED,
                        ownershipCertain = false,
                    ),
                ),
            )
        }
    }

    private fun startCloseUnloadLocked(loaded: SnapshotNativeLifecycle.Loaded) {
        if (nativeLifecycle !== loaded) return
        loaded.closeRequested = true
        val work = SnapshotNativeUnload(
            sequence = null,
            operationId = loaded.ownership.operationId,
            generation = loaded.ownership.generation,
        )
        val unloading = SnapshotNativeLifecycle.Unloading(
            ownership = loaded.ownership,
            work = work,
            closeRequested = true,
        )
        nativeLifecycle = unloading
        try {
            nativeExecutor.execute { invokeNativeUnload(unloading) }
        } catch (_: RejectedExecutionException) {
            nativeLifecycle = SnapshotNativeLifecycle.Empty
            finishNativeCloseLocked()
        }
    }

    private fun invokeNativeUnload(unloading: SnapshotNativeLifecycle.Unloading) {
        synchronized(resourceLock) {
            if (nativeLifecycle !== unloading || unloading.work.settled.get()) return
            unloading.work.invoked = true
            if (closed || unloading.closeRequested) closeReady.countDown()
        }
        try {
            nativePort.unload(
                unloading.work.operationId,
                unloading.work.generation,
            ) { outcome -> settleNativeUnload(unloading, outcome) }
        } catch (_: Throwable) {
            settleNativeUnload(
                unloading,
                RuntimeNativeLifecycleOutcome.Failed(
                    com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.NATIVE_UNLOAD_FAILED,
                    ownershipCertain = false,
                ),
            )
        }
    }

    private fun settleNativeUnload(
        unloading: SnapshotNativeLifecycle.Unloading,
        outcome: RuntimeNativeLifecycleOutcome,
    ) {
        if (!unloading.work.settled.compareAndSet(false, true)) return
        var publish = false
        synchronized(resourceLock) {
            if (nativeLifecycle !== unloading) return
            val releaseOwnership = outcome == RuntimeNativeLifecycleOutcome.Success ||
                (outcome is RuntimeNativeLifecycleOutcome.Failed && outcome.ownershipCertain) ||
                closed || unloading.closeRequested
            if (releaseOwnership) {
                nativeLifecycle = SnapshotNativeLifecycle.Empty
                if (closed || unloading.closeRequested) finishNativeCloseLocked()
                else publish = true
            } else {
                nativeLifecycle = SnapshotNativeLifecycle.Loaded(unloading.ownership)
                publish = true
            }
        }
        val sequence = unloading.work.sequence
        if (publish && sequence != null) {
            submitNativeCompletion(
                sequence,
                RuntimeCommand.NativeUnloadCompleted(
                    unloading.work.operationId,
                    unloading.work.generation,
                    outcome,
                ),
            )
        }
    }

    private fun finishNativeCloseLocked() {
        if (!closed || nativeLifecycle !is SnapshotNativeLifecycle.Empty) return
        closeReady.countDown()
        nativeExecutor.shutdown()
    }

    private fun safeCancel(key: com.cattailsw.nanidroid.runtime.RuntimeScheduleKey) {
        synchronized(resourceLock) {
            if (closed) return
            runCatching { scheduler.cancel(key) }
        }
    }

    private fun retireGenerationSchedules(retiredGeneration: Long) {
        safeCancel(
            com.cattailsw.nanidroid.runtime.RuntimeScheduleKey(
                retiredGeneration,
                com.cattailsw.nanidroid.runtime.RuntimeScheduleKind.CLOCK,
                clockState.epoch,
            ),
        )
        playerState?.playbackToken?.let { token ->
            safeCancel(
                com.cattailsw.nanidroid.runtime.RuntimeScheduleKey(
                    retiredGeneration,
                    com.cattailsw.nanidroid.runtime.RuntimeScheduleKind.PLAYBACK,
                    token,
                ),
            )
        }
        playerState?.dialogue?.input?.key?.let { key ->
            safeCancel(
                com.cattailsw.nanidroid.runtime.RuntimeScheduleKey(
                    retiredGeneration,
                    com.cattailsw.nanidroid.runtime.RuntimeScheduleKind.INPUT_TIMEOUT,
                    key.actionId,
                ),
            )
        }
    }

    private fun transitionToPoisoned(
        operationId: Long,
        code: com.cattailsw.nanidroid.runtime.RuntimeNoticeCode =
            com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.RUNTIME_POISONED,
    ) {
        generation?.let(::retireGenerationSchedules)
        generation?.let(::clearGenerationRequests)
        clockState = clockState.copy(
            running = false,
            epoch = clockState.epoch + 1L,
            lastSecondBucket = null,
            lastMinuteBucket = null,
        )
        deferredPlayback = null
        pending = null
        canonicalizing = null
        attachmentOperationId = null
        if (parentState != null) modeRevision += 1L
        parentState = null
        phase = GhostRuntimePhase.Poisoned
        notice = com.cattailsw.nanidroid.runtime.RuntimeNotice(operationId, code)
    }

    private fun scheduleClockIfRunning() {
        val activeGeneration = generation ?: return
        if (!clockState.running) return
        val key = com.cattailsw.nanidroid.runtime.RuntimeScheduleKey(
            activeGeneration,
            com.cattailsw.nanidroid.runtime.RuntimeScheduleKind.CLOCK,
            clockState.epoch,
        )
        val scheduledEpoch = clockState.epoch
        safeSchedule(key, 1_000L) {
            val elapsed = elapsedRealtimeMillis()
            submit(
                RuntimeCommand.TimerDue(
                    activeGeneration,
                    scheduledEpoch,
                    com.cattailsw.nanidroid.runtime.RuntimeTimerKind.SECOND,
                    elapsed / 1_000L,
                ),
            )
        }
    }

    private fun currentModeIdentity() = com.cattailsw.nanidroid.runtime.RuntimeModeIdentity(
        generation = generation,
        modeRevision = modeRevision,
        parentOperationId = parentState?.operationId,
        parentPhaseRevision = parentState?.phaseRevision,
    )

    private fun runtimeMode(player: PlayerState?) =
        com.cattailsw.nanidroid.runtime.dialogue.GhostRuntimeMode(
            playingTalk = player?.let { it.current != null || it.queue.isNotEmpty() || it.authoredRequest != null } == true,
            pendingUserAction = player?.let {
                it.dialogue.choices.isNotEmpty() || it.dialogue.anchors.isNotEmpty() || it.dialogue.input != null
            } == true,
            passive = player?.passive == true,
        )

    private fun clearGenerationRequests(retiredGeneration: Long) {
        val retained = requestRegistry.pending.filterNot { it.generation == retiredGeneration }.toSet()
        val retainedIds = retained.mapTo(mutableSetOf()) { it.requestId }
        requestRegistry = requestRegistry.copy(
            pending = retained,
            claimedDialogue = requestRegistry.claimedDialogue.filterKeys(retainedIds::contains),
        )
    }

    private fun clearParent() {
        if (parentState == null) return
        parentState = null
        modeRevision += 1L
    }

    private fun RuntimeResult<TaggedShioriResponse>.toPlayerResponse(expectedGeneration: Long): PlayerResponse =
        when (this) {
            is RuntimeResult.Success -> if (value.generation == expectedGeneration) {
                PlayerResponse.Returned(value.response)
            } else {
                PlayerResponse.StaleGeneration
            }
            is RuntimeResult.Failure -> when (failure) {
                RuntimeFailure.StaleGeneration -> PlayerResponse.StaleGeneration
                is RuntimeFailure.Fatal -> PlayerResponse.FatalFailure
                else -> PlayerResponse.ReplayableFailure
            }
        }

    private fun startCatalogScan(epoch: Long) {
        ioScope.launch { submit(catalogScanner.scanCommand(epoch)) }
    }

    private fun publishSnapshot() {
        val current = mutableSnapshots.value
        val player = playerState
        val candidate = RuntimeSnapshot.freeze(
            current.copy(
                generation = generation,
                phase = phase,
                activeGhostId = activePrepared?.id,
                activeSurfaces = activePrepared?.surfaces,
                pending = pending?.let {
                    RuntimePendingGhostIdentity(it.operationId, it.ghostId, it.canonicalRoot.path)
                } ?: (parentState as? SnapshotParentState.Switch)?.let {
                    RuntimePendingGhostIdentity(it.operationId, it.targetGhostId, it.targetRoot.path)
                },
                catalog = catalogOwner.state,
                presentation = player?.presentation ?: RuntimeSnapshot.initial().presentation,
                cues = hostState.cues,
                dialogue = player?.dialogue ?: RuntimeSnapshot.initial().dialogue,
                mode = runtimeMode(player),
                modeIdentity = currentModeIdentity(),
                clockRunning = clockState.running,
                foregroundHost = hostState.topResumed,
                exit = hostState.exit,
                notice = notice,
            ),
        )
        if (candidate == current) return
        val publication = SnapshotPublication(candidate.copy(revision = current.revision + 1L))
        synchronized(snapshotPublicationBarrier) {
            if (closed) return
            check(pendingSnapshotPublication == null) { "Snapshot publication is already reserved" }
            pendingSnapshotPublication = publication
        }
        if (!publication.phase.compareAndSet(SnapshotPublicationPhase.RESERVED, SnapshotPublicationPhase.COMMITTING)) {
            synchronized(snapshotPublicationBarrier) {
                if (pendingSnapshotPublication === publication) pendingSnapshotPublication = null
            }
            return
        }
        try {
            mutableSnapshots.value = publication.snapshot
        } finally {
            synchronized(snapshotPublicationBarrier) {
                if (pendingSnapshotPublication === publication) pendingSnapshotPublication = null
            }
        }
    }

    private fun record(value: String) {
        synchronized(traceLock) { trace += value }
    }

    override fun close() {
        synchronized(resourceLock) {
            if (closed) return
            var publicationToAwait: SnapshotPublication? = null
            synchronized(snapshotPublicationBarrier) {
                if (closed) return
                closed = true
                pendingSnapshotPublication?.let { publication ->
                    if (publication.phase.compareAndSet(
                            SnapshotPublicationPhase.RESERVED,
                            SnapshotPublicationPhase.CANCELED,
                        )
                    ) {
                        pendingSnapshotPublication = null
                    } else if (mutableSnapshots.value != publication.snapshot) {
                        publicationToAwait = publication
                    }
                }
            }
            publicationToAwait?.let { publication ->
                while (mutableSnapshots.value != publication.snapshot) Thread.yield()
            }
            dispatcher.close()
            ioScope.cancel()
            scheduler.close()
            queuedNativeRequests.clear()
            when (val lifecycle = nativeLifecycle) {
                SnapshotNativeLifecycle.Empty -> finishNativeCloseLocked()
                is SnapshotNativeLifecycle.Loading -> lifecycle.closeRequested = true
                is SnapshotNativeLifecycle.Loaded -> {
                    lifecycle.closeRequested = true
                    if (lifecycle.request == null) startCloseUnloadLocked(lifecycle)
                }
                is SnapshotNativeLifecycle.Unloading -> {
                    lifecycle.closeRequested = true
                    if (lifecycle.work.invoked) closeReady.countDown()
                }
            }
        }
        closeReady.await(5L, TimeUnit.SECONDS)
    }

    private data class SnapshotPendingOperation(
        val operationId: Long,
        val ghostId: String,
        val canonicalRoot: File,
        val switchOutgoingName: String?,
        var prepared: PreparedGhost? = null,
    )

    private data class SnapshotStartupDecision(
        val ghostId: String,
        val rootPath: String,
        val operationId: Long = 0L,
    )

    private data class SnapshotNativeOwnership(
        val operationId: Long,
        val generation: Long,
    )

    private class SnapshotPublication(
        val snapshot: RuntimeSnapshot,
        val phase: AtomicReference<SnapshotPublicationPhase> =
            AtomicReference(SnapshotPublicationPhase.RESERVED),
    )

    private enum class SnapshotPublicationPhase { RESERVED, COMMITTING, CANCELED }

    private sealed interface SnapshotNativeLifecycle {
        data object Empty : SnapshotNativeLifecycle

        class Loading(
            val ownership: SnapshotNativeOwnership,
            val sequence: Long,
            val prepared: PreparedGhost,
            val settled: java.util.concurrent.atomic.AtomicBoolean =
                java.util.concurrent.atomic.AtomicBoolean(false),
            var invoked: Boolean = false,
            var closeRequested: Boolean = false,
        ) : SnapshotNativeLifecycle

        class Loaded(
            val ownership: SnapshotNativeOwnership,
            var request: SnapshotNativeRequest? = null,
            var closeRequested: Boolean = false,
            var fatalPending: Boolean = false,
        ) : SnapshotNativeLifecycle

        class Unloading(
            val ownership: SnapshotNativeOwnership,
            val work: SnapshotNativeUnload,
            var closeRequested: Boolean = false,
        ) : SnapshotNativeLifecycle
    }

    private class SnapshotNativeRequest(
        val sequence: Long,
        val token: RuntimeRequestToken,
        val intent: ShioriRequestIntent,
        val fallback: ShioriRequestIntent?,
        val settled: java.util.concurrent.atomic.AtomicBoolean =
            java.util.concurrent.atomic.AtomicBoolean(false),
        var invoked: Boolean = false,
    )

    private class SnapshotNativeUnload(
        val sequence: Long?,
        val operationId: Long,
        val generation: Long,
        val settled: java.util.concurrent.atomic.AtomicBoolean =
            java.util.concurrent.atomic.AtomicBoolean(false),
        var invoked: Boolean = false,
    )

    private sealed interface SnapshotIoCompletion {
        data class Canonicalized(
            val operationId: Long,
            val ghostId: String,
            val canonicalRoot: File?,
        ) : SnapshotIoCompletion

        data class LoadPersistence(
            val operationId: Long,
            val generation: Long,
            val committed: Boolean,
            val activationCount: Long,
        ) : SnapshotIoCompletion

        data class AttachmentPersistence(val operationId: Long) : SnapshotIoCompletion
    }

    private enum class SnapshotParentPhase { REQUEST, PLAYBACK, UNLOADING, REPLACING, ATTACHING, READY }

    private sealed interface SnapshotParentState {
        val operationId: Long
        val phase: SnapshotParentPhase
        val phaseRevision: Long

        data class Exit(
            override val operationId: Long,
            val generation: Long?,
            override val phase: SnapshotParentPhase,
            override val phaseRevision: Long,
        ) : SnapshotParentState

        data class Switch(
            override val operationId: Long,
            val generation: Long,
            val targetGhostId: String,
            val targetRoot: File,
            val outgoingName: String,
            override val phase: SnapshotParentPhase,
            override val phaseRevision: Long,
        ) : SnapshotParentState
    }

    private data class RuntimeRequestRegistry(
        val nextRequestId: Long,
        val pending: Set<RuntimeRequestToken>,
        val claimedDialogue: Map<Long, RuntimeDialogueRequestClaim>,
    )

    private data class RuntimeClockState(
        val running: Boolean,
        val epoch: Long,
        val lastSecondBucket: Long?,
        val lastMinuteBucket: Long?,
    )

    private enum class RuntimeDialogueClaimKind {
        CHOICE,
        ANCHOR,
        INPUT_SUBMIT,
        INPUT_DISMISS,
        INPUT_TIMEOUT,
    }

    private data class RuntimeDialogueRequestClaim(
        val action: DialogueActionKey,
        val kind: RuntimeDialogueClaimKind,
    )

    internal companion object {
        fun testRuntime(
            @Suppress("UNUSED_PARAMETER") context: Context?,
            preparer: GhostPreparer,
            persistence: GhostRuntimePersistence,
            nativePort: RuntimeNativePort,
            runtimeScheduler: RuntimeScheduler,
            coordinationDispatcher: RuntimeCommandDispatcher = SerializedRuntimeCommandDispatcher(),
            catalogScanner: RuntimeCatalogScanner,
            elapsedRealtimeMillis: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
            canonicalizeRoot: (File) -> File = File::getCanonicalFile,
        ): GhostRuntime = GhostRuntime(
            preparer = preparer,
            persistence = persistence,
            nativePort = nativePort,
            scheduler = runtimeScheduler,
            dispatcher = coordinationDispatcher,
            catalogScanner = catalogScanner,
            elapsedRealtimeMillis = elapsedRealtimeMillis,
            canonicalizeRoot = canonicalizeRoot,
        )
    }
}

internal class NativeSessionRuntimePort(
    private val applicationContext: Context,
) : RuntimeNativePort {
    private var session: NativeSession? = null

    override fun load(
        operationId: Long,
        generation: Long,
        prepared: PreparedGhost,
        complete: (RuntimeNativeLifecycleOutcome) -> Unit,
    ) {
        if (session != null) {
            complete(
                RuntimeNativeLifecycleOutcome.Failed(
                    com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.NATIVE_OWNERSHIP_UNCERTAIN,
                    ownershipCertain = false,
                ),
            )
            return
        }
        val adapter = createAdapter(prepared)
        when (val result = runCatching { adapter.load() }.getOrElse {
            ShioriLoadResult.Failed(it, LoadFailureState.CleanupRequired)
        }) {
            ShioriLoadResult.Loaded -> {
                session = NativeSession(adapter, generation)
                complete(RuntimeNativeLifecycleOutcome.Success)
            }
            is ShioriLoadResult.Failed -> settleLoadFailure(adapter, result, complete)
        }
    }

    override fun request(
        token: RuntimeRequestToken,
        intent: ShioriRequestIntent,
        fallback: ShioriRequestIntent?,
        complete: (RuntimeResult<TaggedShioriResponse>) -> Unit,
    ) {
        val active = session
        if (active == null || active.generation != token.generation) {
            complete(RuntimeResult.Failure(RuntimeFailure.StaleGeneration))
            return
        }
        val primary = request(active, intent)
        val result = if (fallback != null && primary.needsFallback()) request(active, fallback) else primary
        complete(result)
    }

    override fun unload(
        operationId: Long,
        generation: Long,
        complete: (RuntimeNativeLifecycleOutcome) -> Unit,
    ) {
        val active = session
        if (active == null || active.generation != generation) {
            complete(
                RuntimeNativeLifecycleOutcome.Failed(
                    com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.NATIVE_OWNERSHIP_UNCERTAIN,
                    ownershipCertain = false,
                ),
            )
            return
        }
        when (val result = runCatching { active.adapter.unloadShiori() }.getOrElse {
            ShioriUnloadResult.Failed(it, ownershipCertain = false)
        }) {
            ShioriUnloadResult.Unloaded -> {
                session = null
                complete(RuntimeNativeLifecycleOutcome.Success)
            }
            is ShioriUnloadResult.Failed -> complete(
                RuntimeNativeLifecycleOutcome.Failed(
                    com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.NATIVE_UNLOAD_FAILED,
                    ownershipCertain = false,
                ),
            )
        }
    }

    private fun settleLoadFailure(
        adapter: Shiori,
        failure: ShioriLoadResult.Failed,
        complete: (RuntimeNativeLifecycleOutcome) -> Unit,
    ) {
        val ownershipCertain = when (failure.state) {
            LoadFailureState.ProvenEmpty -> true
            LoadFailureState.OwnerAlreadyPresent -> false
            LoadFailureState.CleanupRequired -> adapter.unloadShiori() == ShioriUnloadResult.Unloaded
        }
        complete(
            RuntimeNativeLifecycleOutcome.Failed(
                if (ownershipCertain) {
                    com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.NATIVE_LOAD_FAILED
                } else {
                    com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.NATIVE_OWNERSHIP_UNCERTAIN
                },
                ownershipCertain,
            ),
        )
    }

    private fun request(
        active: NativeSession,
        intent: ShioriRequestIntent,
    ): RuntimeResult<TaggedShioriResponse> = try {
        RuntimeResult.Success(
            TaggedShioriResponse(
                active.generation,
                BufferedReader(StringReader(active.adapter.request(intent.protocolText))).use(::ShioriResponse),
            ),
        )
    } catch (failure: ShioriRequestException) {
        RuntimeResult.Failure(
            if (failure.ownershipCertain) RuntimeFailure.Replayable(failure) else RuntimeFailure.Fatal(failure),
        )
    } catch (failure: Throwable) {
        RuntimeResult.Failure(RuntimeFailure.Replayable(failure))
    }

    private fun RuntimeResult<TaggedShioriResponse>.needsFallback(): Boolean = when (this) {
        is RuntimeResult.Success -> value.response.getStatusCode() != 200 ||
            value.response.getKey("Value").isNullOrEmpty()
        is RuntimeResult.Failure -> failure is RuntimeFailure.Replayable
    }

    private fun createAdapter(prepared: PreparedGhost): Shiori {
        val master = File(prepared.canonicalRoot, "ghost/master").path + File.separator
        return when (prepared.engine) {
            GhostEngine.Satori -> SatoriShiori(master, applicationContext)
            GhostEngine.Yaya -> YayaShiori(master, applicationContext)
            GhostEngine.Kawari -> Kawari(master)
            GhostEngine.Nanidroid -> NanidroidShiori(applicationContext, prepared.nanidroidContent)
            GhostEngine.Unsupported -> NotSupportedShiori(applicationContext)
        }
    }

    private data class NativeSession(val adapter: Shiori, val generation: Long)
}

private class PreferenceGhostRuntimePersistence(
    private val context: Context?,
) : GhostRuntimePersistence {
    override fun readLastRunGhostId(): String? = context?.let {
        if (PrefUtil.hasKey(it, LAST_RUN_GHOST)) PrefUtil.getKeyValue(it, LAST_RUN_GHOST) else null
    }

    override fun commitLastRunGhostId(ghostId: String) {
        context?.let { PrefUtil.setKey(it, LAST_RUN_GHOST, ghostId) }
    }

    override fun readActivationCount(ghostId: String): Long =
        context?.let { PrefUtil.getKeyValueLong(it, CREATE_COUNT_PREFIX + ghostId) } ?: 0L

    override fun commitActivationCount(ghostId: String, count: Long) {
        context?.let { PrefUtil.setKeyAsync(it, CREATE_COUNT_PREFIX + ghostId, count) }
    }

    private companion object {
        const val LAST_RUN_GHOST = "lastrunghost"
        const val CREATE_COUNT_PREFIX = "createcount_ghost"
    }
}
