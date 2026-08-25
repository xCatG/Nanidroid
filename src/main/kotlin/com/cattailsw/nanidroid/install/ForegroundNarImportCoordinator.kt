package com.cattailsw.nanidroid.install

import android.content.Context
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal interface ForegroundNarImportBackend {
    fun recoverOwnedStaging(): NarImportRecoveryResult

    fun importDocument(
        selection: NarDocumentSelection,
        isCancelled: () -> Boolean,
        onInstallingProgress: (phase: String, completed: Long) -> Unit,
    ): ArchiveInstallResult
}

internal class ForegroundNarImportLifecycleTestHooks(
    val afterArmLock: () -> Unit = {},
    val beforeRetirementLock: () -> Unit = {},
    val afterRetired: () -> Unit = {},
)

/** Coordinates one foreground document import without retaining Activity-owned work. */
internal class ForegroundNarImportCoordinator(
    private val backend: ForegroundNarImportBackend,
    dispatcher: CoroutineDispatcher,
    private val processNonce: String,
    private val lifecycleTestHooks: ForegroundNarImportLifecycleTestHooks =
        ForegroundNarImportLifecycleTestHooks(),
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val attempts = AtomicLong(0)
    private val mutableState = MutableStateFlow<ForegroundNarImportState>(ForegroundNarImportState.Recovering)
    private val lifecycleLock = Any()
    private var retired = false

    val state: StateFlow<ForegroundNarImportState> = mutableState.asStateFlow()

    init {
        scope.launch { completeStartupRecovery() }
    }

    fun armPicker(ownerTaskId: Int = UNKNOWN_NAR_PICKER_OWNER_TASK_ID): NarImportAttemptToken? {
        val token = nextToken(ownerTaskId)
        return synchronized(lifecycleLock) {
            if (retired) return@synchronized null
            lifecycleTestHooks.afterArmLock()
            if (mutableState.compareAndSet(
                    ForegroundNarImportState.Idle,
                    ForegroundNarImportState.AwaitingSelection(token),
                )
            ) {
                token
            } else {
                null
            }
        }
    }

    fun abandonPicker(expectedToken: NarImportAttemptToken): Boolean = mutableState.compareAndSet(
        ForegroundNarImportState.AwaitingSelection(expectedToken),
        ForegroundNarImportState.Idle,
    )

    fun consumePickerResult(
        expectedToken: NarImportAttemptToken,
        selection: NarDocumentSelection?,
        importAllowed: Boolean,
    ): Boolean {
        val awaiting = ForegroundNarImportState.AwaitingSelection(expectedToken)
        if (selection == null || !importAllowed) {
            return mutableState.compareAndSet(awaiting, ForegroundNarImportState.Idle)
        }
        if (!mutableState.compareAndSet(awaiting, ForegroundNarImportState.Copying(expectedToken))) {
            return false
        }
        scope.launch { importAndReconcile(expectedToken, selection) }
        return true
    }

    fun failPickerLaunch(expectedToken: NarImportAttemptToken, message: String): Boolean = mutableState.compareAndSet(
        ForegroundNarImportState.AwaitingSelection(expectedToken),
        ForegroundNarImportState.Failed(expectedToken, message, ArchiveInstallFailure.SourceUnavailable),
    )

    fun acknowledge(expectedToken: NarImportAttemptToken): Boolean {
        while (true) {
            val current = mutableState.value
            val acknowledged = when (current) {
                is ForegroundNarImportState.Installed -> current.token == expectedToken
                is ForegroundNarImportState.Failed -> current.token == expectedToken
                is ForegroundNarImportState.Interrupted -> current.token == expectedToken
                else -> false
            }
            if (!acknowledged) return false
            if (mutableState.compareAndSet(current, ForegroundNarImportState.Idle)) return true
        }
    }

    fun retryCleanup(expectedToken: NarImportAttemptToken): Boolean {
        val current = mutableState.value as? ForegroundNarImportState.RecoveryRequired ?: return false
        if (current.token != expectedToken) return false
        val cleaning = ForegroundNarImportState.Cleaning(current.token, current.primary)
        if (!mutableState.compareAndSet(current, cleaning)) return false
        scope.launch { retryReconciliation(cleaning) }
        return true
    }

    private fun completeStartupRecovery() {
        val recovery = recoverOwnedStaging()
        val next = when (recovery) {
            NarImportRecoveryResult.Clean -> ForegroundNarImportState.Idle
            NarImportRecoveryResult.Cleaned -> ForegroundNarImportState.Interrupted(nextToken())
            is NarImportRecoveryResult.Failed -> recoveryRequired(nextToken(), NarImportPrimaryOutcome.Interrupted)
        }
        mutableState.compareAndSet(ForegroundNarImportState.Recovering, next)
    }

    private fun importAndReconcile(token: NarImportAttemptToken, selection: NarDocumentSelection) {
        val primary = importPrimary(token, selection)
        val next = when (recoverOwnedStaging()) {
            NarImportRecoveryResult.Clean,
            NarImportRecoveryResult.Cleaned,
            -> terminalState(token, primary)
            is NarImportRecoveryResult.Failed -> recoveryRequired(token, primary)
        }
        replaceInFlightState(token, next)
    }

    private fun importPrimary(
        token: NarImportAttemptToken,
        selection: NarDocumentSelection,
    ): NarImportPrimaryOutcome = try {
        when (val result = backend.importDocument(
            selection = selection,
            isCancelled = { !isCurrentInFlight(token) },
            onInstallingProgress = { phase, completed -> publishInstalling(token, phase, completed) },
        )) {
            is ArchiveInstallResult.Installed -> NarImportPrimaryOutcome.Installed(
                installedPath = result.installedPath,
                targetId = result.targetId.orEmpty(),
            )
            is ArchiveInstallResult.Failed -> NarImportPrimaryOutcome.Failed(result.message, result.failure)
            ArchiveInstallResult.Cancelled -> NarImportPrimaryOutcome.Interrupted
        }
    } catch (exception: Exception) {
        if (containsCancellation(exception)) {
            NarImportPrimaryOutcome.Interrupted
        } else {
            NarImportPrimaryOutcome.Failed(
                "Nanidroid could not complete the selected document import.",
                ArchiveInstallFailure.StagingFailed,
            )
        }
    }

    private fun retryReconciliation(cleaning: ForegroundNarImportState.Cleaning) {
        val next = when (recoverOwnedStaging()) {
            NarImportRecoveryResult.Clean,
            NarImportRecoveryResult.Cleaned,
            -> terminalState(cleaning.token, cleaning.primary)
            is NarImportRecoveryResult.Failed -> recoveryRequired(cleaning.token, cleaning.primary)
        }
        mutableState.compareAndSet(cleaning, next)
    }

    private fun recoverOwnedStaging(): NarImportRecoveryResult = try {
        backend.recoverOwnedStaging()
    } catch (_: Exception) {
        NarImportRecoveryResult.Failed(RECOVERY_FAILURE_MESSAGE)
    }

    private fun publishInstalling(token: NarImportAttemptToken, phase: String, completed: Long) {
        while (true) {
            val current = mutableState.value
            val installing = when (current) {
                is ForegroundNarImportState.Copying -> if (current.token == token) {
                    ForegroundNarImportState.Installing(token, phase, completed)
                } else {
                    return
                }
                is ForegroundNarImportState.Installing -> if (current.token == token) {
                    ForegroundNarImportState.Installing(token, phase, completed)
                } else {
                    return
                }
                else -> return
            }
            if (mutableState.compareAndSet(current, installing)) return
        }
    }

    private fun replaceInFlightState(token: NarImportAttemptToken, next: ForegroundNarImportState) {
        while (true) {
            val current = mutableState.value
            val belongsToAttempt = when (current) {
                is ForegroundNarImportState.Copying -> current.token == token
                is ForegroundNarImportState.Installing -> current.token == token
                else -> false
            }
            if (!belongsToAttempt) return
            if (mutableState.compareAndSet(current, next)) return
        }
    }

    private fun isCurrentInFlight(token: NarImportAttemptToken): Boolean = when (val current = mutableState.value) {
        is ForegroundNarImportState.Copying -> current.token == token
        is ForegroundNarImportState.Installing -> current.token == token
        else -> false
    }

    private fun terminalState(
        token: NarImportAttemptToken,
        primary: NarImportPrimaryOutcome,
    ): ForegroundNarImportState = when (primary) {
        NarImportPrimaryOutcome.Interrupted -> ForegroundNarImportState.Interrupted(token)
        is NarImportPrimaryOutcome.Installed -> ForegroundNarImportState.Installed(token, primary.installedPath, primary.targetId)
        is NarImportPrimaryOutcome.Failed -> ForegroundNarImportState.Failed(token, primary.message, primary.failure)
    }

    private fun recoveryRequired(
        token: NarImportAttemptToken,
        primary: NarImportPrimaryOutcome,
    ) = ForegroundNarImportState.RecoveryRequired(token, primary, RECOVERY_FAILURE_MESSAGE)

    private fun nextToken(ownerTaskId: Int = UNKNOWN_NAR_PICKER_OWNER_TASK_ID) =
        NarImportAttemptToken(processNonce, attempts.incrementAndGet(), ownerTaskId)

    private fun retireIfIdle(): Boolean {
        lifecycleTestHooks.beforeRetirementLock()
        return synchronized(lifecycleLock) {
            if (mutableState.value != ForegroundNarImportState.Idle) {
                false
            } else {
                retired = true
                lifecycleTestHooks.afterRetired()
                true
            }
        }
    }

    private fun containsCancellation(exception: Exception): Boolean {
        var cause: Throwable? = exception
        while (cause != null) {
            if (cause is CancellationException) return true
            cause = cause.cause
        }
        return false
    }

    companion object {
        private const val RECOVERY_FAILURE_MESSAGE = "Nanidroid could not reconcile its private import staging."

        @Volatile
        private var instance: ForegroundNarImportCoordinator? = null

        fun get(context: Context): ForegroundNarImportCoordinator {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: ForegroundNarImportCoordinator(
                    backend = AndroidForegroundNarImportBackend.create(context),
                    dispatcher = Dispatchers.IO,
                    processNonce = UUID.randomUUID().toString(),
                ).also { instance = it }
            }
        }

        internal fun replaceForTesting(replacement: ForegroundNarImportCoordinator) {
            synchronized(this) {
                val current = instance
                if (current === replacement) return
                check(current == null || current.retireIfIdle())
                instance = replacement
            }
        }

        internal fun resetForTesting() {
            synchronized(this) {
                check(instance?.retireIfIdle() != false)
                instance = null
            }
        }
    }
}
