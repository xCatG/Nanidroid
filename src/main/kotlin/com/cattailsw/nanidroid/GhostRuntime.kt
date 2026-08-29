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

internal enum class RuntimeOwnershipMode { LEGACY_RUNNER, SNAPSHOT_CORE_TEST }

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
    context: Context?,
    private val preparer: GhostPreparer,
    private val injectedAdapterFactory: ((PreparedGhost) -> Shiori)?,
    private val persistence: GhostRuntimePersistence,
    private val injectedAdmission: AttachmentAdmission?,
    runnerConfiguration: SScriptRunnerConfiguration?,
    private val testConstructed: Boolean,
    private val ownershipMode: RuntimeOwnershipMode,
    injectedNativePort: RuntimeNativePort?,
    injectedRuntimeScheduler: RuntimeScheduler?,
    injectedCoordinationDispatcher: RuntimeCommandDispatcher?,
    injectedCatalogScanner: RuntimeCatalogScanner?,
    elapsedRealtimeMillis: () -> Long,
    canonicalizeRoot: (File) -> File,
) : Closeable {
    private val applicationContext = context?.applicationContext ?: context
    private val legacyRunner = if (ownershipMode == RuntimeOwnershipMode.LEGACY_RUNNER) {
        if (runnerConfiguration == null) {
            SScriptRunner(context, runtimePort = this)
        } else {
            SScriptRunner(context, runtimePort = this, configuration = runnerConfiguration)
        }
    } else {
        null
    }
    val runner: SScriptRunner get() = checkNotNull(legacyRunner) { "Legacy runner authority is inactive" }
    private val attachmentAdmission = if (ownershipMode == RuntimeOwnershipMode.LEGACY_RUNNER) {
        injectedAdmission ?: AttachmentAdmission(runner::admitAttachment)
    } else {
        injectedAdmission
    }
    private val snapshotCore = if (ownershipMode == RuntimeOwnershipMode.SNAPSHOT_CORE_TEST) {
        SnapshotRuntimeCore(
            preparer = preparer,
            persistence = persistence,
            nativePort = requireNotNull(injectedNativePort),
            scheduler = requireNotNull(injectedRuntimeScheduler),
            dispatcher = injectedCoordinationDispatcher ?: SerializedRuntimeCommandDispatcher(),
            catalogScanner = requireNotNull(injectedCatalogScanner),
            elapsedRealtimeMillis = elapsedRealtimeMillis,
            canonicalizeRoot = canonicalizeRoot,
        )
    } else {
        null
    }

    private val stateLock = Any()
    private val hooks = AtomicReference<GhostRuntimeTestHooks?>(null)
    private val preparationJob = SupervisorJob()
    private val preparationScope = CoroutineScope(
        preparationJob + Dispatchers.IO.limitedParallelism(PREPARATION_PARALLELISM),
    )
    internal val nativeThreadName = "GhostRuntime-Native-${runtimeIds.incrementAndGet()}"
    private val nativeThread = AtomicReference<Thread?>(null)
    private val nativeExecutor: ExecutorService = Executors.newSingleThreadExecutor { command ->
        Thread(command, nativeThreadName).apply {
            isDaemon = true
            nativeThread.set(this)
        }
    }

    private var nextOperationId = 0L
    private var nextGeneration = 0L
    // Publication is gap-free and requires no active session, so one monotonic
    // frontier proves every positive generation at or below it was retired.
    private var lastRetiredGeneration = 0L
    private var inFlight: InFlight? = null
    private var switchIntent: SwitchIntent? = null
    private var session: Session? = null
    private var poison: Throwable? = null
    private var closed = false

    constructor(context: Context?) : this(
        context = context,
        preparer = GhostPreparer(context?.applicationContext ?: context),
        injectedAdapterFactory = null,
        persistence = PreferenceGhostRuntimePersistence(context?.applicationContext ?: context),
        injectedAdmission = null,
        runnerConfiguration = null,
        testConstructed = false,
        ownershipMode = RuntimeOwnershipMode.LEGACY_RUNNER,
        injectedNativePort = null,
        injectedRuntimeScheduler = null,
        injectedCoordinationDispatcher = null,
        injectedCatalogScanner = null,
        elapsedRealtimeMillis = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
        canonicalizeRoot = File::getCanonicalFile,
    )

    internal val snapshots: StateFlow<RuntimeSnapshot>
        get() = checkNotNull(snapshotCore) { "Snapshot authority is inactive" }.snapshots

    internal fun submit(command: RuntimeCommand) {
        snapshotCore?.submit(command)
    }

    internal fun enqueueScriptForTesting(script: String, parent: PlayerParent? = null) {
        snapshotCore?.enqueueScript(script, parent)
    }

    internal fun hasLegacyRunnerAuthorityForTesting(): Boolean = legacyRunner != null

    internal fun hasSnapshotAuthorityForTesting(): Boolean = snapshotCore != null

    internal fun snapshotRevisionForTesting(): Long = snapshotCore?.snapshots?.value?.revision ?: 0L

    internal fun snapshotCommandTraceForTesting(): List<String> = snapshotCore?.commandTrace() ?: emptyList()

    internal fun pendingSnapshotRequestCountForTesting(): Int = snapshotCore?.pendingRequestCount() ?: 0

    internal fun claimedDialogueCountForTesting(): Int = snapshotCore?.claimedDialogueCount() ?: 0

    internal fun shouldInstallBundledGhostForTesting(storageEntries: Array<out File>): Boolean =
        snapshotCore?.shouldInstallBundledGhost(storageEntries) == true

    internal suspend fun startOrJoin(
        ghostId: String,
        canonicalRoot: File,
    ): RuntimeResult<GhostHandle> {
        val root = try {
            canonicalRoot.canonicalFile
        } catch (failure: Throwable) {
            return RuntimeResult.Failure(RuntimeFailure.Replayable(failure))
        }
        var immediate: RuntimeResult<GhostHandle>? = null
        val completion = synchronized(stateLock) {
            when {
                poison != null -> {
                    immediate = fatalResult(requireNotNull(poison))
                    null
                }
                closed -> {
                    immediate = fatalResult(IllegalStateException("GhostRuntime is closed"))
                    null
                }
                session != null -> {
                    immediate = if (session!!.ghost.canonicalRoot == root) {
                        RuntimeResult.Success(session!!.handle)
                    } else {
                        null
                    }
                    when {
                        immediate != null -> null
                        switchIntent?.targetRoot == root && switchIntent?.targetGhostId == ghostId -> {
                            switchIntent!!.completion
                        }
                        else -> {
                            immediate = RuntimeResult.Failure(RuntimeFailure.Busy)
                            null
                        }
                    }
                }
                inFlight != null -> {
                    if (inFlight!!.canonicalRoot == root) {
                        inFlight!!.completion
                    } else {
                        immediate = RuntimeResult.Failure(RuntimeFailure.Busy)
                        null
                    }
                }
                switchIntent != null -> {
                    if (
                        switchIntent!!.targetRoot == root &&
                        switchIntent!!.targetGhostId == ghostId
                    ) {
                        switchIntent!!.completion
                    } else {
                        immediate = RuntimeResult.Failure(RuntimeFailure.Busy)
                        null
                    }
                }
                else -> createInitialOperationLocked(ghostId, root).completion
            }
        }
        immediate?.let { return it }
        return requireNotNull(completion).await()
    }

    internal fun request(
        expectedGeneration: Long,
        intent: ShioriRequestIntent,
    ): RuntimeResult<TaggedShioriResponse> = submitNativeResult {
        requestOnNative(expectedGeneration, intent)
    }

    internal fun requestAsync(
        expectedGeneration: Long,
        intent: ShioriRequestIntent,
    ): RuntimeRequestSubmission = submitRequestAsync {
        requestOnNative(expectedGeneration, intent)
    }

    internal fun requestWithFallback(
        expectedGeneration: Long,
        primary: ShioriRequestIntent,
        fallback: ShioriRequestIntent,
    ): RuntimeResult<TaggedShioriResponse> = submitNativeResult {
        requestWithFallbackOnNative(expectedGeneration, primary, fallback)
    }

    internal fun requestWithFallbackAsync(
        expectedGeneration: Long,
        primary: ShioriRequestIntent,
        fallback: ShioriRequestIntent,
    ): RuntimeRequestSubmission = submitRequestAsync {
        requestWithFallbackOnNative(expectedGeneration, primary, fallback)
    }

    private fun submitRequestAsync(
        command: () -> RuntimeResult<TaggedShioriResponse>,
    ): RuntimeRequestSubmission {
        val completion = CompletableFuture<RuntimeResult<TaggedShioriResponse>>()
        return try {
            nativeExecutor.execute {
                val result = try {
                    command()
                } catch (failure: Throwable) {
                    fatalResult(failure)
                }
                completion.complete(result)
            }
            RuntimeRequestSubmission.Accepted(completion)
        } catch (failure: RejectedExecutionException) {
            RuntimeRequestSubmission.Rejected(
                RuntimeResult.Failure(RuntimeFailure.Fatal(failure)),
            )
        }
    }

    private fun requestWithFallbackOnNative(
        expectedGeneration: Long,
        primary: ShioriRequestIntent,
        fallback: ShioriRequestIntent,
    ): RuntimeResult<TaggedShioriResponse> {
        val primaryResult = requestOnNative(expectedGeneration, primary)
        return when (primaryResult) {
            is RuntimeResult.Success -> {
                if (primaryResult.value.response.isPlayable()) {
                    primaryResult
                } else {
                    requestOnNative(expectedGeneration, fallback)
                }
            }
            is RuntimeResult.Failure -> when (primaryResult.failure) {
                is RuntimeFailure.Replayable -> requestOnNative(expectedGeneration, fallback)
                else -> primaryResult
            }
        }
    }

    private fun ShioriResponse.isPlayable(): Boolean =
        getStatusCode() == 200 && !getKey("Value").isNullOrEmpty()

    private fun requestOnNative(
        expectedGeneration: Long,
        intent: ShioriRequestIntent,
    ): RuntimeResult<TaggedShioriResponse> {
        val active = synchronized(stateLock) {
            when {
                poison != null -> return fatalResult(requireNotNull(poison))
                session?.handle?.generation != expectedGeneration -> {
                    return RuntimeResult.Failure(RuntimeFailure.StaleGeneration)
                }
                else -> requireNotNull(session)
            }
        }
        return try {
            RuntimeResult.Success(
                TaggedShioriResponse(
                    expectedGeneration,
                    parseResponse(active.adapter.request(intent.protocolText)),
                ),
            )
        } catch (failure: ShioriRequestException) {
            if (failure.ownershipCertain) {
                RuntimeResult.Failure(RuntimeFailure.Replayable(failure))
            } else {
                poison(failure)
                fatalResult(failure)
            }
        } catch (failure: Throwable) {
            RuntimeResult.Failure(RuntimeFailure.Replayable(failure))
        }
    }

    internal fun unload(expectedGeneration: Long): RuntimeResult<Unit> {
        val result = submitNativeResult<Unit> {
            val active = synchronized(stateLock) {
                when {
                    poison != null -> return@submitNativeResult fatalResult(requireNotNull(poison))
                    session?.handle?.generation != expectedGeneration -> {
                        return@submitNativeResult if (isKnownRetiredGenerationLocked(expectedGeneration)) {
                            RuntimeResult.Success(Unit)
                        } else {
                            RuntimeResult.Failure(RuntimeFailure.StaleGeneration)
                        }
                    }
                    else -> requireNotNull(session)
                }
            }
            when (val unload = active.adapter.unloadShiori()) {
                ShioriUnloadResult.Unloaded -> {
                    synchronized(stateLock) {
                        retireSessionLocked(active)
                    }
                    RuntimeResult.Success(Unit)
                }
                is ShioriUnloadResult.Failed -> {
                    poison(unload.cause)
                    fatalResult(unload.cause)
                }
            }
        }
        if (result is RuntimeResult.Success) runner.retireGeneration(expectedGeneration)
        return result
    }

    internal suspend fun attachHost(
        expectedGeneration: Long,
    ): RuntimeResult<AttachmentReceipt> {
        var immediate: RuntimeResult<AttachmentReceipt>? = null
        var launch: AttachmentLaunch? = null
        val attempt = synchronized(stateLock) {
            val active = session
            when {
                poison != null -> {
                    immediate = fatalResult(requireNotNull(poison))
                    null
                }
                closed -> {
                    immediate = fatalResult(IllegalStateException("GhostRuntime is closed"))
                    null
                }
                active?.handle?.generation != expectedGeneration -> {
                    immediate = RuntimeResult.Failure(RuntimeFailure.StaleGeneration)
                    null
                }
                active.attachment is AttachmentState.Attached -> {
                    immediate = RuntimeResult.Success(AttachmentReceipt.AlreadyAttached)
                    null
                }
                active.attachment is AttachmentState.Unattached -> {
                    val unattached = active.attachment as AttachmentState.Unattached
                    val operation = AttachmentOperation(
                        operationId = ++nextOperationId,
                        handle = active.handle,
                        reason = unattached.reason,
                    )
                    val deferred = CompletableDeferred<RuntimeResult<AttachmentReceipt>>()
                    operation.attempt = deferred
                    active.attachment = AttachmentState.Attaching(operation)
                    launch = AttachmentLaunch(active, operation, deferred)
                    deferred
                }
                else -> {
                    val attaching = active.attachment as AttachmentState.Attaching
                    val existing = attaching.operation.attempt
                    if (existing != null && !existing.isCompleted) {
                        existing
                    } else {
                        val deferred = CompletableDeferred<RuntimeResult<AttachmentReceipt>>()
                        attaching.operation.attempt = deferred
                        launch = AttachmentLaunch(active, attaching.operation, deferred)
                        deferred
                    }
                }
            }
        }
        immediate?.let { return it }
        launch?.let { work -> preparationScope.launch { processAttachment(work) } }
        return requireNotNull(attempt).await()
    }

    internal fun beginSwitch(
        expectedGeneration: Long,
        targetGhostId: String,
        targetRoot: File,
    ): RuntimeResult<Long> {
        val root = try {
            targetRoot.canonicalFile
        } catch (failure: Throwable) {
            return RuntimeResult.Failure(RuntimeFailure.Replayable(failure))
        }
        return synchronized(stateLock) {
            val active = session
            when {
                poison != null -> fatalResult(requireNotNull(poison))
                closed -> fatalResult(IllegalStateException("GhostRuntime is closed"))
                active?.handle?.generation != expectedGeneration -> {
                    RuntimeResult.Failure(RuntimeFailure.StaleGeneration)
                }
                active.attachment !is AttachmentState.Attached -> {
                    RuntimeResult.Failure(RuntimeFailure.Busy)
                }
                switchIntent != null || inFlight != null || active.ghost.canonicalRoot == root -> {
                    RuntimeResult.Failure(RuntimeFailure.Busy)
                }
                else -> {
                    val operationId = ++nextOperationId
                    switchIntent = SwitchIntent(
                        operationId = operationId,
                        outgoingGeneration = expectedGeneration,
                        outgoingGhostName = active.ghost.name ?: active.ghost.id,
                        targetGhostId = targetGhostId,
                        targetRoot = root,
                        completion = CompletableDeferred(),
                    )
                    RuntimeResult.Success(operationId)
                }
            }
        }
    }

    internal suspend fun completeSwitchPlayback(
        expectedGeneration: Long,
        switchOperationId: Long,
    ): RuntimeResult<GhostHandle> {
        var immediate: RuntimeResult<GhostHandle>? = null
        var retired: SwitchIntent? = null
        val intent = synchronized(stateLock) {
            val candidate = switchIntent
            when {
                poison != null -> {
                    immediate = fatalResult(requireNotNull(poison))
                    if (
                        candidate != null &&
                        candidate.operationId == switchOperationId &&
                        candidate.outgoingGeneration == expectedGeneration &&
                        session?.handle?.generation == expectedGeneration &&
                        !candidate.completionClaimed
                    ) {
                        candidate.completionClaimed = true
                        switchIntent = null
                        retired = candidate
                    }
                    null
                }
                closed -> {
                    immediate = fatalResult(IllegalStateException("GhostRuntime is closed"))
                    null
                }
                candidate == null ||
                    candidate.operationId != switchOperationId ||
                    candidate.outgoingGeneration != expectedGeneration ||
                    session?.handle?.generation != expectedGeneration ||
                    candidate.completionClaimed -> {
                    immediate = RuntimeResult.Failure(RuntimeFailure.StaleGeneration)
                    null
                }
                else -> {
                    candidate.completionClaimed = true
                    candidate
                }
            }
        }
        retired?.completion?.complete(requireNotNull(immediate))
        immediate?.let { return it }
        val accepted = requireNotNull(intent)
        preparationScope.launch { processSwitchCompletion(accepted) }
        return accepted.completion.await()
    }

    internal fun completeSwitchPlaybackFromRunner(
        expectedGeneration: Long,
        switchOperationId: Long,
    ) {
        preparationScope.launch {
            completeSwitchPlayback(expectedGeneration, switchOperationId)
        }
    }

    internal fun failSwitchBeforeUnload(
        expectedGeneration: Long,
        switchOperationId: Long,
        failure: Throwable,
    ): RuntimeResult<Unit> {
        var retired: SwitchIntent? = null
        var terminalFailure: RuntimeFailure? = null
        val result = synchronized(stateLock) {
            val candidate = switchIntent
            when {
                candidate == null ||
                    candidate.operationId != switchOperationId ||
                    candidate.outgoingGeneration != expectedGeneration ||
                    session?.handle?.generation != expectedGeneration ||
                    candidate.completionClaimed -> {
                    poison?.let(::fatalResult)
                        ?: RuntimeResult.Failure(RuntimeFailure.StaleGeneration)
                }
                else -> {
                    switchIntent = null
                    retired = candidate
                    terminalFailure = poison
                        ?.let(RuntimeFailure::Fatal)
                        ?: RuntimeFailure.Replayable(failure)
                    RuntimeResult.Failure(requireNotNull(terminalFailure))
                }
            }
        }
        retired?.completion?.complete(RuntimeResult.Failure(requireNotNull(terminalFailure)))
        return result
    }

    internal fun identity(): GhostRuntimeIdentity = synchronized(stateLock) {
        val active = session?.handle
        val pending = if (poison == null) {
            inFlight?.toIdentity() ?: switchIntent?.toIdentity()
        } else {
            null
        }
        val phase = when {
            poison != null -> GhostRuntimePhase.Poisoned
            switchIntent != null && active != null -> GhostRuntimePhase.SwitchPlayback
            switchIntent != null -> GhostRuntimePhase.Replacing
            active != null -> when (session?.attachment) {
                is AttachmentState.Unattached -> GhostRuntimePhase.Unattached
                is AttachmentState.Attaching -> GhostRuntimePhase.Attaching
                AttachmentState.Attached -> GhostRuntimePhase.Attached
                null -> GhostRuntimePhase.Unattached
            }
            pending != null -> GhostRuntimePhase.Starting
            else -> GhostRuntimePhase.Idle
        }
        GhostRuntimeIdentity(active, pending, phase)
    }

    internal fun preferredGhostId(): String? = persistence.readLastRunGhostId()

    internal fun installTestHooksForTesting(hooks: GhostRuntimeTestHooks): AutoCloseable {
        check(this.hooks.compareAndSet(null, hooks)) { "GhostRuntime test hooks are already installed" }
        return AutoCloseable { this.hooks.compareAndSet(hooks, null) }
    }

    internal fun resetSessionForTesting(): RuntimeResult<Unit> {
        val (stale, staleSwitch, staleAttachment) = synchronized(stateLock) {
            poison?.let { return fatalResult(it) }
            if (inFlight?.nativeStarted == true) {
                return RuntimeResult.Failure(RuntimeFailure.Busy)
            }
            val operation = inFlight.also { inFlight = null }
            val switching = switchIntent.also { switchIntent = null }
            val attachment = (session?.attachment as? AttachmentState.Attaching)
                ?.operation
                ?.attempt
            Triple(operation, switching, attachment)
        }
        stale?.completion?.complete(RuntimeResult.Failure(RuntimeFailure.StaleGeneration))
        staleSwitch?.completion?.complete(RuntimeResult.Failure(RuntimeFailure.StaleGeneration))
        val activeGeneration = synchronized(stateLock) { session?.handle?.generation }
        val result = if (activeGeneration == null) {
            RuntimeResult.Success(Unit)
        } else {
            unload(activeGeneration)
        }
        staleAttachment?.complete(RuntimeResult.Failure(RuntimeFailure.StaleGeneration))
        runCatching { runner.clearMsgQueue() }
        return result
    }

    internal fun probeAdapterLifecycleForTesting(
        prepared: PreparedGhost,
        invalidPrepared: PreparedGhost,
    ): RuntimeResult<NativeLifecycleProbeTrace> {
        if (!testConstructed) {
            return fatalResult(
                IllegalStateException("Adapter lifecycle probes require a test-constructed runtime"),
            )
        }
        if (prepared.engine != invalidPrepared.engine) {
            return fatalResult(IllegalArgumentException("Lifecycle probe engines must match"))
        }
        synchronized(stateLock) {
            when {
                poison != null -> return fatalResult(requireNotNull(poison))
                closed -> return fatalResult(IllegalStateException("GhostRuntime is closed"))
                session != null || inFlight != null || switchIntent != null -> {
                    return RuntimeResult.Failure(RuntimeFailure.Busy)
                }
            }
        }
        return try {
            submitNative {
                val steps = mutableListOf<String>()
                val commandThreads = mutableListOf<String>()
                fun record(step: String) {
                    steps += step
                    commandThreads += Thread.currentThread().name
                }
                fun failure(message: String): RuntimeResult<NativeLifecycleProbeTrace> =
                    fatalResult(IllegalStateException(message))

                val invalidAdapter = createAdapter(invalidPrepared)
                when (val invalidLoad = invalidAdapter.load()) {
                    ShioriLoadResult.Loaded -> {
                        invalidAdapter.unloadShiori()
                        return@submitNative failure("Invalid probe ghost unexpectedly loaded")
                    }
                    is ShioriLoadResult.Failed -> when (invalidLoad.state) {
                        LoadFailureState.ProvenEmpty -> record("invalid-load:proven-empty")
                        LoadFailureState.OwnerAlreadyPresent -> {
                            return@submitNative failure("Invalid probe found a foreign native owner")
                        }
                        LoadFailureState.CleanupRequired -> {
                            invalidAdapter.unloadShiori()
                            return@submitNative failure("Invalid probe required native cleanup")
                        }
                    }
                }

                val adapter = createAdapter(prepared)
                var cleanupPending = false
                try {
                    when (val load = adapter.load()) {
                        ShioriLoadResult.Loaded -> cleanupPending = true
                        is ShioriLoadResult.Failed -> {
                            if (load.state == LoadFailureState.CleanupRequired) {
                                adapter.unloadShiori()
                            }
                            return@submitNative failure("Valid probe ghost did not load")
                        }
                    }
                    record("load:success")
                    val duplicate = adapter.load()
                    if (
                        duplicate !is ShioriLoadResult.Failed ||
                        duplicate.state != LoadFailureState.OwnerAlreadyPresent
                    ) {
                        return@submitNative failure("Duplicate load did not reject the existing owner")
                    }
                    record("duplicate-load:owner-already-present")
                    adapter.request(ShioriRequestIntent.event("OnBoot").protocolText)
                    record("request:success")
                    cleanupPending = false
                    if (adapter.unloadShiori() != ShioriUnloadResult.Unloaded) {
                        return@submitNative failure("Valid probe unload failed")
                    }
                    record("unload:success")

                    val secondAdapter = createAdapter(prepared)
                    if (secondAdapter.unloadShiori() != ShioriUnloadResult.Unloaded) {
                        return@submitNative failure("Second adapter unload was not idempotent")
                    }
                    record("second-unload:success")

                    val postUnloadProbes = when (adapter) {
                        is SatoriShiori -> listOf(adapter.probeNativeRequestAfterUnloadForTesting())
                        is YayaShiori -> adapter.probeNativeCharsetAndRequestAfterUnloadForTesting()
                        is Kawari -> listOf(adapter.probeNativeRequestAfterUnloadForTesting())
                        else -> return@submitNative failure(
                            "Lifecycle JNI probe is unsupported for ${prepared.engine}",
                        )
                    }
                    val requestProbe = postUnloadProbes.singleOrNull { it.operation == "request" }
                    if (requestProbe?.rejected != true) {
                        return@submitNative failure("JNI request after unload was not rejected")
                    }
                    record("request-after-unload:rejected")
                    if (prepared.engine == GhostEngine.Yaya) {
                        val charsetProbe = postUnloadProbes.singleOrNull { it.operation == "charset" }
                        if (charsetProbe?.rejected != true) {
                            return@submitNative failure("YAYA charset lookup after unload was not rejected")
                        }
                        record("charset-after-unload:rejected")
                    }

                    when (val reload = adapter.load()) {
                        ShioriLoadResult.Loaded -> cleanupPending = true
                        is ShioriLoadResult.Failed -> {
                            if (reload.state == LoadFailureState.CleanupRequired) {
                                adapter.unloadShiori()
                            }
                            return@submitNative failure("Valid probe reload failed")
                        }
                    }
                    record("reload:success")
                    cleanupPending = false
                    if (adapter.unloadShiori() != ShioriUnloadResult.Unloaded) {
                        return@submitNative failure("Reloaded probe unload failed")
                    }
                    record("reload-unload:success")
                    RuntimeResult.Success(
                        NativeLifecycleProbeTrace(
                            engine = prepared.engine,
                            commandThreadNames = Collections.unmodifiableList(commandThreads.toList()),
                            steps = Collections.unmodifiableList(steps.toList()),
                        ),
                    )
                } finally {
                    if (cleanupPending) adapter.unloadShiori()
                }
            }
        } catch (failure: Throwable) {
            fatalResult(failure)
        }
    }

    override fun close() {
        if (ownershipMode == RuntimeOwnershipMode.SNAPSHOT_CORE_TEST) {
            snapshotCore?.close()
            return
        }
        var staleAttachment: CompletableDeferred<RuntimeResult<AttachmentReceipt>>? = null
        val (stale, staleSwitch) = synchronized(stateLock) {
            if (closed) return
            closed = true
            staleAttachment = (session?.attachment as? AttachmentState.Attaching)
                ?.operation
                ?.attempt
            val operation = inFlight.also { inFlight = null }
            val switching = switchIntent.also { switchIntent = null }
            operation to switching
        }
        stale?.completion?.complete(
            fatalResult(IllegalStateException("GhostRuntime closed during startup")),
        )
        staleSwitch?.completion?.complete(
            fatalResult(IllegalStateException("GhostRuntime closed during switch")),
        )
        staleAttachment?.complete(
            fatalResult(IllegalStateException("GhostRuntime closed during attachment")),
        )
        val retiredGeneration = synchronized(stateLock) { session?.handle?.generation }
        if (synchronized(stateLock) { poison == null && session != null }) {
            runCatching {
                submitNative<Unit> {
                    val active = synchronized(stateLock) { session }
                    if (active != null && synchronized(stateLock) { poison == null }) {
                        when (val result = active.adapter.unloadShiori()) {
                            ShioriUnloadResult.Unloaded -> synchronized(stateLock) {
                                retireSessionLocked(active)
                            }
                            is ShioriUnloadResult.Failed -> poison(result.cause)
                        }
                    }
                    Unit
                }
            }
        }
        retiredGeneration?.let(runner::retireGeneration)
        preparationScope.cancel()
        nativeExecutor.shutdown()
        if (!nativeExecutor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IllegalStateException("GhostRuntime native executor did not terminate: $nativeThreadName")
        }
    }

    private fun createInitialOperationLocked(ghostId: String, root: File): InFlight {
        val operation = InFlight(
            operationId = ++nextOperationId,
            ghostId = ghostId,
            canonicalRoot = root,
            completion = CompletableDeferred(),
            attachmentReason = AttachmentReason.Initial,
        )
        inFlight = operation
        preparationScope.launch { prepare(operation) }
        return operation
    }

    private fun processSwitchCompletion(intent: SwitchIntent) {
        val unloadResult = submitNativeResult<Unit> {
            val active = synchronized(stateLock) {
                poison?.let { return@submitNativeResult fatalResult(it) }
                if (
                    switchIntent !== intent ||
                    session?.handle?.generation != intent.outgoingGeneration
                ) {
                    return@submitNativeResult RuntimeResult.Failure(RuntimeFailure.StaleGeneration)
                }
                requireNotNull(session)
            }
            when (val result = active.adapter.unloadShiori()) {
                ShioriUnloadResult.Unloaded -> {
                    synchronized(stateLock) {
                        retireSessionLocked(active)
                    }
                    runCatching { hooks.get()?.onOutgoingUnloaded?.invoke(intent.operationId) }
                    RuntimeResult.Success(Unit)
                }
                is ShioriUnloadResult.Failed -> {
                    poison(result.cause)
                    fatalResult(result.cause)
                }
            }
        }
        if (unloadResult is RuntimeResult.Failure) {
            intent.completion.complete(RuntimeResult.Failure(unloadResult.failure))
            return
        }
        runner.retireGeneration(intent.outgoingGeneration)
        val operation = synchronized(stateLock) {
            if (closed || poison != null || switchIntent !== intent || session != null) {
                null
            } else {
                InFlight(
                    operationId = intent.operationId,
                    ghostId = intent.targetGhostId,
                    canonicalRoot = intent.targetRoot,
                    completion = intent.completion,
                    attachmentReason = AttachmentReason.Switched(intent.outgoingGhostName),
                    switchOperationId = intent.operationId,
                ).also { inFlight = it }
            }
        }
        if (operation == null) {
            intent.completion.complete(RuntimeResult.Failure(RuntimeFailure.StaleGeneration))
            return
        }
        preparationScope.launch { prepare(operation) }
    }

    private fun processAttachment(work: AttachmentLaunch) {
        val operation = work.operation
        if (!isCurrentAttachment(work.session, operation)) {
            work.completion.complete(RuntimeResult.Failure(RuntimeFailure.StaleGeneration))
            return
        }
        if (!operation.activationCommitted) {
            val priorCount = runCatching {
                persistence.readActivationCount(operation.handle.ghost.id)
            }.getOrDefault(0L)
            operation.priorActivationCount = priorCount
            runCatching {
                persistence.commitActivationCount(operation.handle.ghost.id, priorCount + 1L)
            }
            operation.activationCommitted = true
            runCatching { hooks.get()?.onActivationCommitted?.invoke(operation.operationId) }
        }
        if (!isCurrentAttachment(work.session, operation)) {
            work.completion.complete(RuntimeResult.Failure(RuntimeFailure.StaleGeneration))
            return
        }
        if (!operation.bootAttempted) {
            val (eventId, intent) = bootIntent(operation)
            runCatching { hooks.get()?.onBootAttempted?.invoke(operation.operationId, eventId) }
            val outcome = when (val result = request(operation.handle.generation, intent)) {
                is RuntimeResult.Success -> BootOutcome.Response(result.value)
                is RuntimeResult.Failure -> when (val failure = result.failure) {
                    is RuntimeFailure.Replayable -> BootOutcome.BootAttemptFailed(failure.cause)
                    is RuntimeFailure.Fatal -> {
                        operation.bootAttempted = true
                        work.completion.complete(result)
                        return
                    }
                    RuntimeFailure.Busy -> BootOutcome.BootAttemptFailed(
                        IllegalStateException("Runtime became busy during attachment boot"),
                    )
                    RuntimeFailure.StaleGeneration -> BootOutcome.BootAttemptFailed(
                        IllegalStateException("Attachment generation became stale during boot"),
                    )
                }
            }
            operation.outcome = outcome
            operation.bootAttempted = true
        }
        if (!isCurrentAttachment(work.session, operation)) {
            work.completion.complete(RuntimeResult.Failure(RuntimeFailure.StaleGeneration))
            return
        }
        val outcome = requireNotNull(operation.outcome)
        val admitted = try {
            requireNotNull(attachmentAdmission).admit(operation.operationId, operation.handle, outcome)
        } catch (failure: Throwable) {
            RuntimeResult.Failure(RuntimeFailure.Replayable(failure))
        }
        when (admitted) {
            is RuntimeResult.Failure -> work.completion.complete(admitted)
            is RuntimeResult.Success -> {
                val committed = synchronized(stateLock) {
                    if (isCurrentAttachmentLocked(work.session, operation)) {
                        operation.runnerAdmissionCommitted = true
                        work.session.attachment = AttachmentState.Attached
                        true
                    } else {
                        false
                    }
                }
                work.completion.complete(
                    if (committed) {
                        RuntimeResult.Success(AttachmentReceipt.NewlyAttached(operation.operationId))
                    } else {
                        RuntimeResult.Failure(RuntimeFailure.StaleGeneration)
                    },
                )
            }
        }
    }

    private fun bootIntent(operation: AttachmentOperation): Pair<String, ShioriRequestIntent> {
        val priorCount = operation.priorActivationCount ?: 0L
        return when {
            priorCount == 0L -> "OnFirstBoot" to ShioriRequestIntent.event(
                "OnFirstBoot",
                listOf("0"),
            )
            operation.reason is AttachmentReason.Switched -> "OnGhostChanged" to ShioriRequestIntent.event(
                "OnGhostChanged",
                listOf(operation.reason.outgoingGhostName, null),
            )
            else -> "OnBoot" to ShioriRequestIntent.event(
                "OnBoot",
                listOf(operation.handle.ghost.shellName),
            )
        }
    }

    private fun isCurrentAttachment(session: Session, operation: AttachmentOperation): Boolean =
        synchronized(stateLock) { isCurrentAttachmentLocked(session, operation) }

    private fun isCurrentAttachmentLocked(session: Session, operation: AttachmentOperation): Boolean =
        !closed &&
            poison == null &&
            this.session === session &&
            (session.attachment as? AttachmentState.Attaching)?.operation === operation

    private fun prepare(operation: InFlight) {
        val prepared = try {
            hooks.get()?.onPreparationStarted?.invoke(
                operation.operationId,
                operation.ghostId,
                operation.canonicalRoot,
            )
            preparer.prepare(operation.operationId, operation.ghostId, operation.canonicalRoot)
        } catch (failure: Throwable) {
            completePreparationFailure(operation, failure)
            return
        }
        val accepted = synchronized(stateLock) {
            if (
                !closed &&
                poison == null &&
                inFlight === operation &&
                operation.operationId == prepared.operationId &&
                operation.ghostId == prepared.id &&
                operation.canonicalRoot == prepared.canonicalRoot
            ) {
                operation.nativeStarted = true
                true
            } else {
                false
            }
        }
        if (!accepted) {
            operation.completion.complete(RuntimeResult.Failure(RuntimeFailure.StaleGeneration))
            return
        }
        try {
            nativeExecutor.execute { loadPrepared(operation, prepared) }
        } catch (failure: RejectedExecutionException) {
            completePreparationFailure(operation, failure)
        }
    }

    private fun loadPrepared(operation: InFlight, prepared: PreparedGhost) {
        val accepted = synchronized(stateLock) {
            !closed && poison == null && session == null && inFlight === operation
        }
        if (!accepted) {
            operation.completion.complete(RuntimeResult.Failure(RuntimeFailure.StaleGeneration))
            return
        }
        val adapter = try {
            hooks.get()?.onNativeLoadStarted?.invoke(operation.operationId, prepared.engine)
            injectedAdapterFactory?.invoke(prepared) ?: createAdapter(prepared)
        } catch (failure: Throwable) {
            finishOperation(operation, RuntimeResult.Failure(RuntimeFailure.Replayable(failure)))
            return
        }
        when (val load = try {
            adapter.load()
        } catch (failure: Throwable) {
            ShioriLoadResult.Failed(failure, LoadFailureState.CleanupRequired)
        }) {
            ShioriLoadResult.Loaded -> publishLoaded(operation, prepared, adapter)
            is ShioriLoadResult.Failed -> settleLoadFailure(operation, adapter, load)
        }
    }

    private fun publishLoaded(operation: InFlight, prepared: PreparedGhost, adapter: Shiori) {
        val capabilities = try {
            GhostEventCapabilityDiscovery.discover { method, eventId, references ->
                parseResponse(
                    adapter.request(
                        ShioriRequestIntent.raw(method, eventId, references).protocolText,
                    ),
                )
            }
        } catch (failure: ShioriRequestException) {
            cleanupAfterUncertainBootstrap(operation, adapter, failure)
            return
        }
        var persistenceFailure: Throwable? = null
        var handle: GhostHandle? = null
        val published = synchronized(stateLock) {
            if (!closed && poison == null && inFlight === operation && session == null) {
                val generation = nextGeneration + 1L
                try {
                    persistence.commitLastRunGhostId(prepared.id)
                } catch (failure: Throwable) {
                    persistenceFailure = failure
                    return@synchronized false
                }
                nextGeneration = generation
                val ghost = Ghost(prepared)
                handle = GhostHandle(ghost, capabilities, generation)
                session = Session(
                    ghost = ghost,
                    adapter = adapter,
                    handle = requireNotNull(handle),
                    attachment = AttachmentState.Unattached(operation.attachmentReason),
                )
                inFlight = null
                if (operation.switchOperationId != null) switchIntent = null
                true
            } else {
                false
            }
        }
        persistenceFailure?.let { failure ->
            cleanupAfterUncertainBootstrap(operation, adapter, failure)
            return
        }
        if (!published) {
            when (val cleanup = adapter.unloadShiori()) {
                ShioriUnloadResult.Unloaded -> Unit
                is ShioriUnloadResult.Failed -> poison(cleanup.cause)
            }
            operation.completion.complete(RuntimeResult.Failure(RuntimeFailure.StaleGeneration))
            return
        }
        val acceptedHandle = requireNotNull(handle)
        runCatching {
            hooks.get()?.onGenerationPublished?.invoke(acceptedHandle.generation, prepared.id)
        }
        operation.completion.complete(RuntimeResult.Success(acceptedHandle))
    }

    private fun settleLoadFailure(
        operation: InFlight,
        adapter: Shiori,
        failure: ShioriLoadResult.Failed,
    ) {
        when (failure.state) {
            LoadFailureState.ProvenEmpty -> finishOperation(
                operation,
                RuntimeResult.Failure(RuntimeFailure.Replayable(failure.cause)),
            )
            LoadFailureState.OwnerAlreadyPresent -> {
                poison(failure.cause)
                finishOperation(operation, fatalResult(failure.cause), retainPoison = true)
            }
            LoadFailureState.CleanupRequired -> when (val cleanup = adapter.unloadShiori()) {
                ShioriUnloadResult.Unloaded -> finishOperation(
                    operation,
                    RuntimeResult.Failure(RuntimeFailure.Replayable(failure.cause)),
                )
                is ShioriUnloadResult.Failed -> {
                    cleanup.cause.addSuppressed(failure.cause)
                    poison(cleanup.cause)
                    finishOperation(operation, fatalResult(cleanup.cause), retainPoison = true)
                }
            }
        }
    }

    private fun cleanupAfterUncertainBootstrap(
        operation: InFlight,
        adapter: Shiori,
        failure: Throwable,
    ) {
        when (val cleanup = adapter.unloadShiori()) {
            ShioriUnloadResult.Unloaded -> finishOperation(
                operation,
                RuntimeResult.Failure(RuntimeFailure.Replayable(failure)),
            )
            is ShioriUnloadResult.Failed -> {
                cleanup.cause.addSuppressed(failure)
                poison(cleanup.cause)
                finishOperation(operation, fatalResult(cleanup.cause), retainPoison = true)
            }
        }
    }

    private fun completePreparationFailure(operation: InFlight, failure: Throwable) {
        finishOperation(
            operation,
            RuntimeResult.Failure(RuntimeFailure.Replayable(failure)),
        )
    }

    private fun finishOperation(
        operation: InFlight,
        result: RuntimeResult<GhostHandle>,
        retainPoison: Boolean = false,
    ) {
        synchronized(stateLock) {
            if (inFlight === operation && (!retainPoison || poison != null)) {
                inFlight = null
                if (operation.switchOperationId != null) switchIntent = null
            }
        }
        operation.completion.complete(result)
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

    private fun parseResponse(responseText: String): ShioriResponse =
        BufferedReader(StringReader(responseText)).use(::ShioriResponse)

    private fun isKnownRetiredGenerationLocked(generation: Long): Boolean =
        generation > 0L && generation <= lastRetiredGeneration

    private fun retireSessionLocked(active: Session) {
        if (session !== active) return
        check(active.handle.generation == lastRetiredGeneration + 1L) {
            "GhostRuntime generations must retire monotonically: " +
                "last=$lastRetiredGeneration, active=${active.handle.generation}"
        }
        session = null
        lastRetiredGeneration = active.handle.generation
    }

    private fun poison(failure: Throwable) {
        synchronized(stateLock) {
            if (poison == null) poison = failure
        }
    }

    private fun <T> fatalResult(failure: Throwable): RuntimeResult<T> =
        RuntimeResult.Failure(RuntimeFailure.Fatal(failure))

    private fun <T> submitNative(command: () -> T): T {
        check(Thread.currentThread() !== nativeThread.get()) {
            "Nested GhostRuntime native command submission is forbidden"
        }
        return nativeExecutor.submit(Callable(command)).get()
    }

    private fun <T> submitNativeResult(
        command: () -> RuntimeResult<T>,
    ): RuntimeResult<T> = try {
        submitNative(command)
    } catch (failure: Throwable) {
        fatalResult(failure.cause ?: failure)
    }

    private data class InFlight(
        val operationId: Long,
        val ghostId: String,
        val canonicalRoot: File,
        val completion: CompletableDeferred<RuntimeResult<GhostHandle>>,
        val attachmentReason: AttachmentReason,
        val switchOperationId: Long? = null,
        var nativeStarted: Boolean = false,
    ) {
        fun toIdentity() = PendingGhostIdentity(operationId, ghostId, canonicalRoot)
    }

    private class SwitchIntent(
        val operationId: Long,
        val outgoingGeneration: Long,
        val outgoingGhostName: String,
        val targetGhostId: String,
        val targetRoot: File,
        val completion: CompletableDeferred<RuntimeResult<GhostHandle>>,
        var completionClaimed: Boolean = false,
    ) {
        fun toIdentity() = PendingGhostIdentity(operationId, targetGhostId, targetRoot)
    }

    private data class Session(
        val ghost: Ghost,
        val adapter: Shiori,
        val handle: GhostHandle,
        var attachment: AttachmentState,
    )

    private sealed interface AttachmentState {
        data class Unattached(val reason: AttachmentReason) : AttachmentState
        data class Attaching(val operation: AttachmentOperation) : AttachmentState
        data object Attached : AttachmentState
    }

    private class AttachmentOperation(
        val operationId: Long,
        val handle: GhostHandle,
        val reason: AttachmentReason,
        var activationCommitted: Boolean = false,
        var priorActivationCount: Long? = null,
        var bootAttempted: Boolean = false,
        var outcome: BootOutcome? = null,
        var runnerAdmissionCommitted: Boolean = false,
        var attempt: CompletableDeferred<RuntimeResult<AttachmentReceipt>>? = null,
    )

    private data class AttachmentLaunch(
        val session: Session,
        val operation: AttachmentOperation,
        val completion: CompletableDeferred<RuntimeResult<AttachmentReceipt>>,
    )

    internal companion object {
        private const val PREPARATION_PARALLELISM = 4
        private const val CLOSE_TIMEOUT_SECONDS = 5L
        private val runtimeIds = AtomicLong()

        fun testRuntime(
            context: Context?,
            preparer: GhostPreparer,
            adapterFactory: ((PreparedGhost) -> Shiori)? = null,
            persistence: GhostRuntimePersistence,
            admission: AttachmentAdmission? = null,
            runnerConfiguration: SScriptRunnerConfiguration? = null,
            ownershipMode: RuntimeOwnershipMode = RuntimeOwnershipMode.LEGACY_RUNNER,
            nativePort: RuntimeNativePort? = null,
            runtimeScheduler: RuntimeScheduler? = null,
            coordinationDispatcher: RuntimeCommandDispatcher? = null,
            catalogScanner: RuntimeCatalogScanner? = null,
            elapsedRealtimeMillis: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
            canonicalizeRoot: (File) -> File = File::getCanonicalFile,
        ): GhostRuntime = GhostRuntime(
            context = context,
            preparer = preparer,
            injectedAdapterFactory = adapterFactory,
            persistence = persistence,
            injectedAdmission = admission,
            runnerConfiguration = runnerConfiguration,
            testConstructed = true,
            ownershipMode = ownershipMode,
            injectedNativePort = nativePort,
            injectedRuntimeScheduler = runtimeScheduler,
            injectedCoordinationDispatcher = coordinationDispatcher,
            injectedCatalogScanner = catalogScanner,
            elapsedRealtimeMillis = elapsedRealtimeMillis,
            canonicalizeRoot = canonicalizeRoot,
        )
    }
}

private class SnapshotRuntimeCore(
    private val preparer: GhostPreparer,
    @Suppress("unused") private val persistence: GhostRuntimePersistence,
    private val nativePort: RuntimeNativePort,
    private val scheduler: RuntimeScheduler,
    private val dispatcher: RuntimeCommandDispatcher,
    private val catalogScanner: RuntimeCatalogScanner,
    private val elapsedRealtimeMillis: () -> Long,
    private val canonicalizeRoot: (File) -> File,
) : Closeable {
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
    private val nativeOwnership = AtomicReference<SnapshotNativeOwnership?>(null)
    private val resourceLock = Any()
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

    fun enqueueScript(script: String, parent: PlayerParent?) {
        if (closed) return
        dispatcher.dispatch {
            record("EnqueueScript")
            val current = playerState ?: return@dispatch
            consumePlayerTransition(SakuraScriptPlayer.reduce(current, PlayerCommand.Enqueue(script, parent)))
            publishSnapshot()
        }
    }

    fun commandTrace(): List<String> = synchronized(traceLock) { trace.toList() }

    fun pendingRequestCount(): Int = requestRegistry.pending.size

    fun claimedDialogueCount(): Int = requestRegistry.claimedDialogue.size

    fun shouldInstallBundledGhost(storageEntries: Array<out File>): Boolean {
        val ready = catalogOwner.state as? RuntimeCatalogState.Ready ?: return false
        return shouldInstallBundledGhost(ready.entries.size, storageEntries)
    }

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
                val candidateOwnership = SnapshotNativeOwnership(operation.operationId, candidateGeneration)
                enqueueNative(
                    onFailure = {
                        RuntimeCommand.NativeLoadCompleted(
                            operation.operationId,
                            candidateGeneration,
                            RuntimeNativeLifecycleOutcome.Failed(
                                com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.NATIVE_LOAD_FAILED,
                                ownershipCertain = false,
                            ),
                        )
                    },
                ) { complete ->
                    check(registerNativeOwnership(candidateOwnership)) {
                        "Native load ownership could not be registered"
                    }
                    nativePort.load(
                        operation.operationId,
                        candidateGeneration,
                        outcome.value,
                    ) { result ->
                        complete(
                            RuntimeCommand.NativeLoadCompleted(operation.operationId, candidateGeneration, result),
                        ) {
                            if (result is RuntimeNativeLifecycleOutcome.Failed && result.ownershipCertain) {
                                clearNativeOwnership(candidateOwnership)
                            }
                        }
                    }
                }
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
        if (phase == GhostRuntimePhase.Poisoned) return
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
            nativeOwnership.get() != null &&
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
        enqueueNative(
            onFailure = {
                RuntimeCommand.NativeUnloadCompleted(
                    parent.operationId,
                    parent.generation,
                    RuntimeNativeLifecycleOutcome.Failed(
                        com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.NATIVE_UNLOAD_FAILED,
                        ownershipCertain = false,
                    ),
                )
            },
        ) { complete ->
            nativePort.unload(parent.operationId, parent.generation) { outcome ->
                complete(
                    RuntimeCommand.NativeUnloadCompleted(parent.operationId, parent.generation, outcome),
                ) {
                    if (outcome == RuntimeNativeLifecycleOutcome.Success) {
                        clearNativeGeneration(parent.generation)
                    }
                }
            }
        }
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
        enqueueNative(
            onFailure = { failure ->
                RuntimeCommand.NativeResponse(token, RuntimeResult.Failure(RuntimeFailure.Fatal(failure)))
            },
        ) { complete ->
            nativePort.request(token, intent, fallback) { result ->
                complete(RuntimeCommand.NativeResponse(token, result)) { }
            }
        }
    }

    private fun enqueueNative(
        onFailure: (Throwable) -> RuntimeCommand,
        action: (complete: (RuntimeCommand, () -> Unit) -> Unit) -> Unit,
    ) {
        val sequence = nextNativeSubmission++
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        val complete = { command: RuntimeCommand, beforeSubmit: () -> Unit ->
            if (settled.compareAndSet(false, true)) {
                beforeSubmit()
                submitNativeCompletion(sequence, command)
            }
        }
        synchronized(resourceLock) {
            if (closed) return
            try {
                nativeExecutor.execute {
                    if (closed) return@execute
                    try {
                        action(complete)
                    } catch (failure: Throwable) {
                        complete(onFailure(failure)) { }
                    }
                }
            } catch (_: RejectedExecutionException) {
                // Close won the admission race.
            }
        }
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

    private fun registerNativeOwnership(ownership: SnapshotNativeOwnership): Boolean =
        synchronized(resourceLock) {
            !closed && nativeOwnership.compareAndSet(null, ownership)
        }

    private fun clearNativeOwnership(ownership: SnapshotNativeOwnership) {
        nativeOwnership.updateAndGet { owned -> owned?.takeUnless { it == ownership } }
    }

    private fun clearNativeGeneration(generation: Long) {
        nativeOwnership.updateAndGet { owned -> owned?.takeUnless { it.generation == generation } }
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
        mutableSnapshots.value = candidate.copy(revision = current.revision + 1L)
    }

    private fun record(value: String) {
        synchronized(traceLock) { trace += value }
    }

    override fun close() {
        synchronized(resourceLock) {
            if (closed) return
            val cleanup = nativeOwnership.getAndSet(null)
            closed = true
            dispatcher.close()
            ioScope.cancel()
            scheduler.close()
            cleanup?.let { owned ->
                try {
                    nativeExecutor.execute {
                        runCatching {
                            nativePort.unload(owned.operationId, owned.generation) { }
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    // The executor cannot reject before shutdown while resourceLock is held.
                }
            }
            nativeExecutor.shutdown()
        }
        if (!nativeExecutor.awaitTermination(5L, TimeUnit.SECONDS)) {
            nativeExecutor.shutdownNow()
        }
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
