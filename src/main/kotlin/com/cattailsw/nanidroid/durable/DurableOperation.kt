package com.cattailsw.nanidroid.durable

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

fun interface OperationCancellation {
    fun cancel(handle: OperationHandle, binding: ExternalJobBinding)
}
