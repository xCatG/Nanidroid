package com.cattailsw.nanidroid.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cattailsw.nanidroid.install.ImportAttemptStore
import com.cattailsw.nanidroid.install.ImportCoordinator
import com.cattailsw.nanidroid.install.ImportState
import com.cattailsw.nanidroid.runtime.GhostRuntime
import com.cattailsw.nanidroid.runtime.InteractionToken
import com.cattailsw.nanidroid.runtime.DialogueToken
import com.cattailsw.nanidroid.runtime.RenderedMoveSource
import com.cattailsw.nanidroid.runtime.StageState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class StageViewModel(
    private val runtime: GhostRuntime,
    private val coordinator: ImportCoordinator? = null,
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val attempts: ImportAttemptStore? = null,
) : ViewModel() {
    val state: StateFlow<StageState> = runtime.state
    val importState: StateFlow<ImportState> = coordinator?.state ?: MutableStateFlow(ImportState.Idle)
    private val interrupted = MutableStateFlow(false)
    val importInterrupted: StateFlow<Boolean> = interrupted
    private val restoreLock = Mutex()
    private var restored = false
    private var accepting = false
    private val activityStarted = MutableStateFlow(false)
    private var activeImportHost: Any? = null

    fun onImportActivityStart(host: Any) {
        setImportActivityStarted(host, true)
        viewModelScope.launch { restoreAttempt() }
    }

    fun setImportActivityStarted(host: Any, started: Boolean) {
        if (started) {
            activeImportHost = host
            activityStarted.value = true
        } else if (activeImportHost === host) {
            activeImportHost = null
            activityStarted.value = false
        }
    }

    /** The retained ViewModel owns a returned URI until it is admitted or interrupted. */
    fun submitPickerResult(openSource: (() -> InputStream)?) {
        viewModelScope.launch {
            acceptPickerResult(openSource, awaitStarted = { activityStarted.first { it } })
        }
    }

    suspend fun restoreAttempt() = restoreLock.withLock {
        if (restored) return@withLock
        val id = saved.get<String>("import_attempt_id")
        val phase = saved.get<String>("import_phase")
        val live = coordinator?.state?.value
        if (id != null && live != ImportState.Idle && when (live) {
            is ImportState.Picking -> live.attemptId == id
            is ImportState.Running -> live.attemptId == id
            is ImportState.Completed -> live.attemptId == id
            else -> false
        }) {
            // Rotation retains the application-owned attempt, including completed prompts.
        } else if (id != null && phase == "picking" &&
            try { attempts?.wasConsumed(id) } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { true } == false &&
            coordinator?.restorePicking(id) == true) {
            // This is the first copy after a picker outlived the process.
        } else if (id != null) {
            saved.remove<String>("import_attempt_id")
            saved.remove<String>("import_phase")
            // A completed result was already presented; only unfinished attempts are reported.
            if (phase != "completed") interrupted.value = true
        }
        restored = true
        coordinator?.let { owner ->
            viewModelScope.launch {
                owner.state.collect { current ->
                    when (current) {
                        is ImportState.Picking -> saveAttempt(current.attemptId, "picking")
                        is ImportState.Running -> saveAttempt(current.attemptId, "running")
                        is ImportState.Completed -> saveAttempt(current.attemptId, "completed")
                        ImportState.Idle -> {
                            saved.remove<String>("import_attempt_id")
                            saved.remove<String>("import_phase")
                        }
                    }
                }
            }
        }
    }

    fun beginImport(): String? {
        if (!restored) return null
        val id = coordinator?.beginPicking() ?: return null
        interrupted.value = false
        saveAttempt(id, "picking")
        return id
    }

    fun launchImport(launchPicker: () -> Unit) {
        val id = beginImport() ?: return
        try {
            launchPicker()
        } catch (_: Exception) {
            coordinator?.abandonPicking(id)
            interrupted.value = true
        }
    }

    suspend fun acceptPickerResult(
        openSource: (() -> InputStream)?,
        awaitStarted: suspend () -> Unit = {},
    ) {
        restoreAttempt()
        val id = saved.get<String>("import_attempt_id") ?: return
        if ((coordinator?.state?.value as? ImportState.Picking)?.attemptId != id || accepting) return
        accepting = true
        try {
            if (openSource != null) {
                try {
                    if (attempts?.wasConsumed(id) != false) {
                        coordinator.acceptResult(id, null)
                        interrupted.value = true
                        return
                    }
                    attempts.markConsumed(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    coordinator.acceptResult(id, null)
                    interrupted.value = true
                    return
                }
                saveAttempt(id, "running")
            }
            awaitStarted()
            coordinator.acceptResult(id, openSource)
        } finally {
            withContext(NonCancellable) { coordinator.abandonPicking(id) }
            accepting = false
        }
    }

    fun cancelImport() = coordinator?.cancelBeforePublication()
    fun acknowledgeImport(id: String) = coordinator?.acknowledge(id)
    fun dismissInterrupted() { interrupted.value = false }
    fun cancelRunningOnStop() { if (importState.value is ImportState.Running) cancelImport() }

    private fun saveAttempt(id: String, phase: String) {
        saved["import_attempt_id"] = id
        saved["import_phase"] = phase
    }

    suspend fun start(language: String) = runtime.start(language)
    fun setResumed(resumed: Boolean) = runtime.setResumed(resumed)
    fun click(speaker: Int, x: Int, y: Int, collisionSurfaceId: Int, origin: DialogueToken?) =
        runtime.click(speaker, x, y, collisionSurfaceId, origin)
    fun doubleClick(speaker: Int, x: Int, y: Int, collisionSurfaceId: Int,
        origin: DialogueToken?) = runtime.doubleClick(speaker, x, y, collisionSurfaceId, origin)
    fun move(speaker: Int, x: Int, y: Int, collisionSurfaceId: Int, source: RenderedMoveSource) =
        runtime.move(speaker, x, y, collisionSurfaceId, source)
    fun cancelMove(speaker: Int) = runtime.cancelMove(speaker)
    fun choose(token: InteractionToken) = runtime.choose(token)
    fun submitInput(token: InteractionToken, text: String) = runtime.submitInput(token, text)
    fun cancelInput(token: InteractionToken) = runtime.cancelInput(token)
    fun isCurrentDialogue(token: DialogueToken) = runtime.isCurrentDialogue(token)
    fun isFarewellInput(token: InteractionToken) = runtime.isFarewellInput(token)
    fun selectGhost(directoryId: String) = runtime.selectGhost(directoryId)
    fun confirmSwitch() {
        runtime.confirmSwitch()
    }
    fun dismissSwitch() = runtime.dismissSwitch()
    fun close() {
        cancelImport()
        runtime.close()
    }
}
