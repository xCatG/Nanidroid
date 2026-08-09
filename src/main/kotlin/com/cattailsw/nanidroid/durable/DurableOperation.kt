package com.cattailsw.nanidroid.durable

import java.nio.charset.StandardCharsets
import java.util.UUID

@JvmInline value class OperationId(val value: String)

@JvmInline value class AttemptId(val value: Long)

data class OperationHandle(val operationId: OperationId, val attemptId: AttemptId)

sealed interface ExternalJobBinding {
    data class DownloadManager(val id: Long) : ExternalJobBinding
    data class WorkManager(val uuid: String) : ExternalJobBinding
}

enum class OperationKind { REMOTE_NAR, LOCAL_NAR, NAR_INSTALL, GHOST_UPDATE }

enum class OperationStatus { RUNNING, CANCEL_REQUESTED, COMPLETED, FAILED, CANCELLED }

data class OperationProgress(val phase: String, val completed: Long)

data class DurableOperationRecord(
    val id: OperationId,
    val attemptId: AttemptId,
    val kind: OperationKind,
    val externalJob: ExternalJobBinding?,
    val progress: OperationProgress,
    val status: OperationStatus,
    val showStallPrompt: Boolean,
    val diagnostics: String? = null,
    val externalJobHistory: Set<ExternalJobBinding> = emptySet(),
)

internal data class DurableAttentionSnapshot(
    val records: List<DurableOperationRecord>,
    val notificationRecords: List<DurableOperationRecord>,
    val nextCheckDelayMillis: Long?,
)

internal enum class DurableAttentionAction {
    KEEP_WAITING,
    STOP,
    RETRY_STOP,
}

fun interface OperationCancellation {
    fun cancel(handle: OperationHandle, kind: OperationKind, binding: ExternalJobBinding)
}

internal fun canonicalUuidOrNull(value: String): UUID? = runCatching { UUID.fromString(value) }
    .getOrNull()
    ?.takeIf { it.toString() == value }

internal fun durableWorkManagerId(
    handle: OperationHandle,
    kind: OperationKind,
): UUID {
    require(kind in WORK_MANAGER_OPERATION_KINDS) { "$kind does not use durable WorkManager jobs" }
    val operationId = handle.operationId.value
    val identity = buildString {
        append("nanidroid-durable-work-v1\n")
        append(kind.name)
        append('\n')
        append(handle.attemptId.value)
        append('\n')
        append(operationId.length)
        append(':')
        append(operationId)
    }
    return UUID.nameUUIDFromBytes(identity.toByteArray(StandardCharsets.UTF_8))
}

private val WORK_MANAGER_OPERATION_KINDS = setOf(
    OperationKind.LOCAL_NAR,
    OperationKind.NAR_INSTALL,
    OperationKind.GHOST_UPDATE,
)
