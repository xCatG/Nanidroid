package com.cattailsw.nanidroid.install

internal data class NarImportAttemptToken(
    val processNonce: String,
    val sequence: Long,
)

internal data class NarDocumentSelection(
    val uri: String,
    val scheme: String?,
)

internal sealed interface NarImportPrimaryOutcome {
    data object Interrupted : NarImportPrimaryOutcome
    data class Installed(val installedPath: String, val targetId: String) : NarImportPrimaryOutcome
    data class Failed(val message: String, val failure: ArchiveInstallFailure) : NarImportPrimaryOutcome
}

internal sealed interface ForegroundNarImportState {
    data object Recovering : ForegroundNarImportState
    data object Idle : ForegroundNarImportState
    data class AwaitingSelection(val token: NarImportAttemptToken) : ForegroundNarImportState
    data class Copying(val token: NarImportAttemptToken) : ForegroundNarImportState
    data class Installing(val token: NarImportAttemptToken, val phase: String, val completed: Long) : ForegroundNarImportState
    data class Installed(val token: NarImportAttemptToken, val installedPath: String, val targetId: String) : ForegroundNarImportState
    data class Failed(val token: NarImportAttemptToken, val message: String, val failure: ArchiveInstallFailure) : ForegroundNarImportState
    data class Interrupted(val token: NarImportAttemptToken) : ForegroundNarImportState
    data class RecoveryRequired(val token: NarImportAttemptToken, val primary: NarImportPrimaryOutcome, val message: String) : ForegroundNarImportState
    data class Cleaning(val token: NarImportAttemptToken, val primary: NarImportPrimaryOutcome) : ForegroundNarImportState
}

internal sealed interface NarImportRecoveryResult {
    data object Clean : NarImportRecoveryResult
    data object Cleaned : NarImportRecoveryResult
    data class Failed(val message: String) : NarImportRecoveryResult
}
