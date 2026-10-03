package com.cattailsw.nanidroid.install

import com.cattailsw.nanidroid.runtime.ImportPromptResult

sealed interface ImportState {
    data object Idle : ImportState
    data class Picking(val attemptId: String) : ImportState
    data class Running(val attemptId: String) : ImportState
    data class Completed(
        val attemptId: String,
        val outcome: ImportOutcome,
        val pendingDirectoryId: String? = null,
        val pendingPrompt: Boolean = false,
        val promptResult: ImportPromptResult? = null,
    ) : ImportState
}
