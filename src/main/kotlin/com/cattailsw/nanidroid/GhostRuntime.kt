package com.cattailsw.nanidroid

import android.content.Context
import com.cattailsw.nanidroid.runtime.dialogue.GhostEventCapabilityDiscovery
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

internal data class GhostHandle(
    val prepared: PreparedGhost,
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
    private val admission: AttachmentAdmission?,
    private val testConstructed: Boolean,
) : Closeable {
    private val applicationContext = context?.applicationContext ?: context
    private val sessionCoordinator = GhostSessionCoordinator()
    val runner = SScriptRunner(context, sessionCoordinator)

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
        admission = null,
        testConstructed = false,
    )

    fun beginGhostConstruction(
        ghostId: String,
        ghostRoot: File,
    ): GhostConstructionReservation = sessionCoordinator.beginConstruction(ghostId, ghostRoot)

    fun reuseActiveGhost(
        ghostId: String,
        ghostRoot: File,
    ): ReservedGhost? = sessionCoordinator.reuseActive(ghostId, ghostRoot)

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
        val operation = synchronized(stateLock) {
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
                    immediate = if (session!!.prepared.canonicalRoot == root) {
                        RuntimeResult.Success(session!!.handle)
                    } else {
                        RuntimeResult.Failure(RuntimeFailure.Busy)
                    }
                    null
                }
                inFlight != null -> {
                    if (inFlight!!.canonicalRoot == root) {
                        inFlight
                    } else {
                        immediate = RuntimeResult.Failure(RuntimeFailure.Busy)
                        null
                    }
                }
                switchIntent != null -> {
                    immediate = RuntimeResult.Failure(RuntimeFailure.Busy)
                    null
                }
                else -> createInitialOperationLocked(ghostId, root)
            }
        }
        immediate?.let { return it }
        return requireNotNull(operation).completion.await()
    }

    internal fun request(
        expectedGeneration: Long,
        intent: ShioriRequestIntent,
    ): RuntimeResult<TaggedShioriResponse> = submitNativeResult {
        val active = synchronized(stateLock) {
            when {
                poison != null -> return@submitNativeResult fatalResult(requireNotNull(poison))
                session?.handle?.generation != expectedGeneration -> {
                    return@submitNativeResult RuntimeResult.Failure(RuntimeFailure.StaleGeneration)
                }
                else -> requireNotNull(session)
            }
        }
        try {
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

    internal fun unload(expectedGeneration: Long): RuntimeResult<Unit> = submitNativeResult {
        val active = synchronized(stateLock) {
            when {
                poison != null -> return@submitNativeResult fatalResult(requireNotNull(poison))
                session?.handle?.generation != expectedGeneration -> {
                    return@submitNativeResult RuntimeResult.Failure(RuntimeFailure.StaleGeneration)
                }
                else -> requireNotNull(session)
            }
        }
        when (val result = active.adapter.unloadShiori()) {
            ShioriUnloadResult.Unloaded -> {
                synchronized(stateLock) {
                    if (session === active) session = null
                }
                RuntimeResult.Success(Unit)
            }
            is ShioriUnloadResult.Failed -> {
                poison(result.cause)
                fatalResult(result.cause)
            }
        }
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
                admission == null -> {
                    immediate = fatalResult(
                        IllegalStateException("Production attachment admission is not installed yet"),
                    )
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
                switchIntent != null || inFlight != null || active.prepared.canonicalRoot == root -> {
                    RuntimeResult.Failure(RuntimeFailure.Busy)
                }
                else -> {
                    val operationId = ++nextOperationId
                    switchIntent = SwitchIntent(
                        operationId = operationId,
                        outgoingGeneration = expectedGeneration,
                        outgoingGhostName = active.prepared.name ?: active.prepared.id,
                        targetGhostId = targetGhostId,
                        targetRoot = root,
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
        val intent = synchronized(stateLock) {
            val candidate = switchIntent
            when {
                poison != null -> {
                    immediate = fatalResult(requireNotNull(poison))
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
                    candidate.completion = CompletableDeferred()
                    candidate
                }
            }
        }
        immediate?.let { return it }
        val accepted = requireNotNull(intent)
        preparationScope.launch { processSwitchCompletion(accepted) }
        return requireNotNull(accepted.completion).await()
    }

    internal fun failSwitchBeforeUnload(
        expectedGeneration: Long,
        switchOperationId: Long,
        failure: Throwable,
    ): RuntimeResult<Unit> = synchronized(stateLock) {
        val candidate = switchIntent
        when {
            poison != null -> fatalResult(requireNotNull(poison))
            candidate == null ||
                candidate.operationId != switchOperationId ||
                candidate.outgoingGeneration != expectedGeneration ||
                session?.handle?.generation != expectedGeneration ||
                candidate.completionClaimed -> {
                RuntimeResult.Failure(RuntimeFailure.StaleGeneration)
            }
            else -> {
                switchIntent = null
                RuntimeResult.Failure(RuntimeFailure.Replayable(failure))
            }
        }
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
        if (synchronized(stateLock) { poison == null && session != null }) {
            runCatching {
                submitNative<Unit> {
                    val active = synchronized(stateLock) { session }
                    if (active != null && synchronized(stateLock) { poison == null }) {
                        when (val result = active.adapter.unloadShiori()) {
                            ShioriUnloadResult.Unloaded -> synchronized(stateLock) {
                                if (session === active) session = null
                            }
                            is ShioriUnloadResult.Failed -> poison(result.cause)
                        }
                    }
                    Unit
                }
            }
        }
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
                        if (session === active && switchIntent === intent) session = null
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
            intent.completion?.complete(RuntimeResult.Failure(unloadResult.failure))
            return
        }
        val operation = synchronized(stateLock) {
            if (closed || poison != null || switchIntent !== intent || session != null) {
                null
            } else {
                InFlight(
                    operationId = intent.operationId,
                    ghostId = intent.targetGhostId,
                    canonicalRoot = intent.targetRoot,
                    completion = requireNotNull(intent.completion),
                    attachmentReason = AttachmentReason.Switched(intent.outgoingGhostName),
                    switchOperationId = intent.operationId,
                ).also { inFlight = it }
            }
        }
        if (operation == null) {
            intent.completion?.complete(RuntimeResult.Failure(RuntimeFailure.StaleGeneration))
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
                persistence.readActivationCount(operation.handle.prepared.id)
            }.getOrDefault(0L)
            operation.priorActivationCount = priorCount
            runCatching {
                persistence.commitActivationCount(operation.handle.prepared.id, priorCount + 1L)
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
            requireNotNull(admission).admit(operation.operationId, operation.handle, outcome)
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
                listOf(operation.handle.prepared.shellName),
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
                handle = GhostHandle(prepared, capabilities, generation)
                session = Session(
                    prepared = prepared,
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
        var completionClaimed: Boolean = false,
        var completion: CompletableDeferred<RuntimeResult<GhostHandle>>? = null,
    ) {
        fun toIdentity() = PendingGhostIdentity(operationId, targetGhostId, targetRoot)
    }

    private data class Session(
        val prepared: PreparedGhost,
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
            admission: AttachmentAdmission = AttachmentAdmission {
                    _, _, _ -> RuntimeResult.Success(Unit)
            },
        ): GhostRuntime = GhostRuntime(
            context = context,
            preparer = preparer,
            injectedAdapterFactory = adapterFactory,
            persistence = persistence,
            admission = admission,
            testConstructed = true,
        )
    }
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
