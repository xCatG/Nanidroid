package com.cattailsw.nanidroid.install

import com.cattailsw.nanidroid.runtime.GhostRuntime
import com.cattailsw.nanidroid.runtime.ImportPromptResult
import com.cattailsw.nanidroid.runtime.InstallEventSink
import com.cattailsw.nanidroid.runtime.StageState
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Application-owned picker attempt; filesystem transactions remain in [GhostImporter]. */
class ImportCoordinator(
    private val importer: GhostImporter,
    private val runtime: GhostRuntime,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = mutableState
    private var running: Job? = null
    private var pickingSink: InstallEventSink? = null
    /** A deferred prompt whose result the user already closed; offered once the runtime is Active. */
    @Volatile private var acknowledgedPrompt: String? = null

    init {
        scope.launch {
            runtime.state.collect { stage ->
                val ready = stage as? StageState.Ready
                val completed = mutableState.value as? ImportState.Completed
                if (stage == StageState.Finished) {
                    // A finished session's result is not reopened by a later launch in this process.
                    acknowledgedPrompt = null
                    if (completed != null) mutableState.compareAndSet(completed, ImportState.Idle)
                    return@collect
                }
                acknowledgedPrompt?.let { id ->
                    if (ready != null && mutableState.value == ImportState.Idle &&
                        runtime.offerImportedGhost(id) != ImportPromptResult.Deferred) {
                        acknowledgedPrompt = null
                    }
                }
                if (completed != null && completed.pendingPrompt) {
                    if (completed.promptResult == ImportPromptResult.Shown &&
                        ready?.switchPrompt?.directoryId != completed.pendingDirectoryId) {
                        // Dismissing or confirming the prompt completes the attempt.
                        mutableState.compareAndSet(completed, ImportState.Idle)
                    } else if (ready != null && completed.promptResult == ImportPromptResult.Deferred) {
                        offerPrompt(completed)
                    }
                }
            }
        }
    }

    fun beginPicking(): String? {
        if (mutableState.value is ImportState.Picking || mutableState.value is ImportState.Running) return null
        acknowledgedPrompt = null
        val id = UUID.randomUUID().toString()
        pickingSink = runtime.beginImportEvents()
        mutableState.value = ImportState.Picking(id)
        return id
    }

    /** Admit only a never-started picker restored after process recreation. */
    fun restorePicking(attemptId: String): Boolean {
        if (attemptId.isBlank() || mutableState.value != ImportState.Idle) return false
        pickingSink = null
        mutableState.value = ImportState.Picking(attemptId)
        return true
    }

    fun abandonPicking(attemptId: String) {
        if ((mutableState.value as? ImportState.Picking)?.attemptId != attemptId) return
        pickingSink = null
        mutableState.value = ImportState.Idle
    }

    fun acceptResult(attemptId: String, openSource: (() -> InputStream)?) {
        if ((mutableState.value as? ImportState.Picking)?.attemptId != attemptId) return
        val sink = pickingSink
        pickingSink = null
        if (openSource == null) {
            mutableState.value = ImportState.Idle
            return
        }
        mutableState.value = ImportState.Running(attemptId)
        runtime.setImportRunning(true)
        running = scope.launch {
            var beginSent = false
            val outcome = try {
                importer.importArchive(openSource) { metadata ->
                    if (sink != null) {
                        sink.emit("OnInstallBegin", references(metadata.directoryId))
                        beginSent = true
                    }
                }
            } catch (_: CancellationException) {
                ImportOutcome.Cancelled
            }
            withContext(NonCancellable) {
                runtime.setImportRunning(false)
                if ((mutableState.value as? ImportState.Running)?.attemptId != attemptId) return@withContext
                when (outcome) {
                    is ImportOutcome.Installed -> sink?.emit("OnInstallComplete", references(outcome.directoryId))
                    is ImportOutcome.Refused -> sink?.emit("OnInstallRefuse")
                    is ImportOutcome.Failed -> sink?.emit("OnInstallFailure")
                    ImportOutcome.Cancelled -> if (beginSent) sink?.emit("OnInstallFailure")
                }
                val completed = ImportState.Completed(attemptId, outcome,
                    pendingDirectoryId = (outcome as? ImportOutcome.Installed)?.directoryId,
                    pendingPrompt = outcome is ImportOutcome.Installed)
                mutableState.value = completed
                // Checked after publishing Completed so a concurrent Finished cannot leave it behind.
                if (runtime.state.value == StageState.Finished) {
                    mutableState.compareAndSet(completed, ImportState.Idle)
                    return@withContext
                }
                if (outcome is ImportOutcome.Installed) {
                    runtime.refreshInstalledChoices()
                    offerPrompt(completed)
                }
            }
        }
    }

    fun cancelBeforePublication() {
        if (mutableState.value is ImportState.Running) running?.cancel()
    }

    fun acknowledge(attemptId: String) {
        val completed = mutableState.value as? ImportState.Completed ?: return
        if (completed.attemptId != attemptId) return
        if (completed.pendingPrompt && completed.promptResult == ImportPromptResult.Deferred) {
            acknowledgedPrompt = completed.pendingDirectoryId
        }
        mutableState.value = ImportState.Idle
    }

    private suspend fun offerPrompt(completed: ImportState.Completed) {
        val id = completed.pendingDirectoryId ?: return
        if (runtime.state.value == StageState.Finished) {
            mutableState.compareAndSet(completed, ImportState.Idle)
            return
        }
        val disposition = runtime.offerImportedGhost(id)
        val current = mutableState.value as? ImportState.Completed ?: return
        if (current.attemptId != completed.attemptId || !current.pendingPrompt) return
        mutableState.value = current.copy(
            pendingPrompt = disposition == ImportPromptResult.Deferred ||
                disposition == ImportPromptResult.Shown,
            promptResult = disposition,
        )
    }

    private fun references(id: String) = listOf("ghost", id, id)
}
