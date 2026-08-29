package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.install.ArchiveInstallResult
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface BundledInstallState {
    data object Idle : BundledInstallState
    data class Running(val operationId: Long) : BundledInstallState
    data class RecoveryRequired(val operationId: Long, val message: String) : BundledInstallState
    data class Completed(val operationId: Long, val targetId: String) : BundledInstallState
}

internal data class BundledInstallPublication(
    val operationId: Long,
    val targetId: String,
)

internal sealed interface BundledInstallEligibility {
    data class Eligible(val storageRoot: File) : BundledInstallEligibility
    data object Ineligible : BundledInstallEligibility
    data class RecoveryRequired(val message: String) : BundledInstallEligibility
}

internal fun bundledInstallEligibility(
    storageRoot: () -> File?,
    storageEntries: (File) -> Array<out File>? = { it.listFiles() },
): BundledInstallEligibility = try {
    val root = storageRoot() ?: return BundledInstallEligibility.RecoveryRequired(
        "Nanidroid cannot prepare its ghost storage.",
    )
    if (shouldInstallBundledGhost(0, storageEntries(root).orEmpty())) {
        BundledInstallEligibility.Eligible(root)
    } else {
        BundledInstallEligibility.Ineligible
    }
} catch (exception: Exception) {
    BundledInstallEligibility.RecoveryRequired(
        exception.message?.takeIf(String::isNotBlank)
            ?: "Nanidroid cannot inspect its ghost storage.",
    )
}

/** Process-owned state for the bundled archive adapter operation only. */
internal class BundledInstallWorkflow {
    private val mutableState = MutableStateFlow<BundledInstallState>(BundledInstallState.Idle)
    private var nextOperationId = 0L
    private var claimedExecutionId: Long? = null

    val state: StateFlow<BundledInstallState> = mutableState.asStateFlow()

    @Synchronized
    fun startIfIdle(): Long? = if (mutableState.value == BundledInstallState.Idle) {
        startNewOperation()
    } else {
        null
    }

    @Synchronized
    fun retry(expectedFailureOperationId: Long): Long? {
        val recovery = mutableState.value as? BundledInstallState.RecoveryRequired ?: return null
        if (recovery.operationId != expectedFailureOperationId) return null
        return startNewOperation()
    }

    @Synchronized
    fun recordPreflightFailure(message: String): Long? {
        if (mutableState.value != BundledInstallState.Idle) return null
        val operationId = ++nextOperationId
        claimedExecutionId = operationId
        mutableState.value = BundledInstallState.RecoveryRequired(operationId, message)
        return operationId
    }

    fun execute(
        operationId: Long,
        install: () -> ArchiveInstallResult,
    ): BundledInstallPublication? {
        synchronized(this) {
            if (mutableState.value != BundledInstallState.Running(operationId)) return null
            if (claimedExecutionId != null) return null
            claimedExecutionId = operationId
        }
        val result = try {
            install()
        } catch (exception: Exception) {
            return settleFailure(
                operationId,
                exception.message?.takeIf(String::isNotBlank)
                    ?: "Nanidroid could not install its built-in ghost.",
            )
        }
        return when (result) {
            is ArchiveInstallResult.Installed -> settleSuccess(
                operationId,
                result.targetId ?: "nanidroid",
            )
            is ArchiveInstallResult.Failed -> settleFailure(operationId, result.message)
            ArchiveInstallResult.Cancelled -> settleFailure(
                operationId,
                "The built-in ghost installation was interrupted.",
            )
        }
    }

    @Synchronized
    private fun startNewOperation(): Long {
        val operationId = ++nextOperationId
        claimedExecutionId = null
        mutableState.value = BundledInstallState.Running(operationId)
        return operationId
    }

    @Synchronized
    private fun settleSuccess(operationId: Long, targetId: String): BundledInstallPublication? {
        if (mutableState.value != BundledInstallState.Running(operationId) || claimedExecutionId != operationId) {
            return null
        }
        mutableState.value = BundledInstallState.Completed(operationId, targetId)
        return BundledInstallPublication(operationId, targetId)
    }

    @Synchronized
    private fun settleFailure(operationId: Long, message: String): BundledInstallPublication? {
        if (mutableState.value != BundledInstallState.Running(operationId) || claimedExecutionId != operationId) {
            return null
        }
        mutableState.value = BundledInstallState.RecoveryRequired(operationId, message)
        return null
    }
}
