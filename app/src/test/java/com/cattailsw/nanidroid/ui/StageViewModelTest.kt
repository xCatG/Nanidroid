package com.cattailsw.nanidroid.ui

import com.cattailsw.nanidroid.data.BootStateStore
import com.cattailsw.nanidroid.engine.ShioriEngine
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.engine.ShioriReply
import com.cattailsw.nanidroid.ghost.BundledGhost
import com.cattailsw.nanidroid.runtime.GhostRuntime
import com.cattailsw.nanidroid.runtime.StageState
import com.cattailsw.nanidroid.install.GhostImporter
import com.cattailsw.nanidroid.install.ImportAttemptStore
import com.cattailsw.nanidroid.install.ImportCoordinator
import com.cattailsw.nanidroid.install.ImportState
import androidx.lifecycle.SavedStateHandle
import java.io.File
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.CancellationException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test

class StageViewModelTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun restoredConsumedPickerResetsWithoutWaitingForCallback() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = simpleRuntime(scope)
            val coordinator = ImportCoordinator(GhostImporter(folder.newFolder()), runtime, scope)
            val saved = SavedStateHandle(mapOf("import_attempt_id" to "old-id", "import_phase" to "picking"))
            val store = ImportAttemptStore({ it == "old-id" }, {})
            val model = StageViewModel(runtime, coordinator, saved, store)
            model.restoreAttempt()
            assertEquals(ImportState.Idle, coordinator.state.value)
            assertTrue(model.importInterrupted.value)
            val newId = model.beginImport()
            assertTrue(newId != null && newId != "old-id")
            assertEquals(ImportState.Picking(newId!!), coordinator.state.value)
        } finally { scope.cancel() }
    }

    @Test fun rotationReattachesToLivePickingEvenWhenSavedIdWasConsumed() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = simpleRuntime(scope)
            val coordinator = ImportCoordinator(GhostImporter(folder.newFolder()), runtime, scope)
            val id = coordinator.beginPicking()!!
            val saved = SavedStateHandle(mapOf("import_attempt_id" to id, "import_phase" to "picking"))
            val model = StageViewModel(runtime, coordinator, saved, ImportAttemptStore({ true }, {}))
            model.restoreAttempt()
            assertEquals(ImportState.Picking(id), coordinator.state.value)
            assertFalse(model.importInterrupted.value)
        } finally { scope.cancel() }
    }

    @Test fun returnedResultIsMarkedBeforeProviderOpensAndDuplicateIsIgnored() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = simpleRuntime(scope)
            val coordinator = ImportCoordinator(GhostImporter(folder.newFolder()), runtime, scope)
            var consumed: String? = null
            var opens = 0
            val model = StageViewModel(runtime, coordinator, SavedStateHandle(),
                ImportAttemptStore({ id -> consumed == id }, { id -> consumed = id }))
            model.restoreAttempt()
            val id = model.beginImport()!!
            val source = {
                assertEquals(id, consumed)
                opens++
                byteArrayOf(0, 1, 2).inputStream()
            }
            model.acceptPickerResult(source)
            model.acceptPickerResult(source)
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>().first() }
            }
            assertEquals(1, opens)
        } finally { scope.cancel() }
    }

    @Test fun failedConsumedRecordPreventsProviderOpen() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = simpleRuntime(scope)
            val coordinator = ImportCoordinator(GhostImporter(folder.newFolder()), runtime, scope)
            val model = StageViewModel(runtime, coordinator, SavedStateHandle(),
                ImportAttemptStore({ false }, { throw IllegalStateException("commit failed") }))
            model.restoreAttempt()
            model.beginImport()
            model.acceptPickerResult(openSource = { error("provider must not open") })
            assertEquals(ImportState.Idle, coordinator.state.value)
            assertTrue(model.importInterrupted.value)
        } finally { scope.cancel() }
    }

    @Test fun staleRunningRestoresIdleAndAllowsNewPicker() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = simpleRuntime(scope)
            val coordinator = ImportCoordinator(GhostImporter(folder.newFolder()), runtime, scope)
            val saved = SavedStateHandle(mapOf("import_attempt_id" to "old-id", "import_phase" to "running"))
            val model = StageViewModel(runtime, coordinator, saved, ImportAttemptStore({ true }, {}))
            model.restoreAttempt()
            assertTrue(model.importInterrupted.value)
            assertEquals(ImportState.Idle, coordinator.state.value)
            assertTrue(model.beginImport() != null)
        } finally { scope.cancel() }
    }

    @Test fun restoredCompletedAttemptClearsWithoutInterruptedNotice() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = simpleRuntime(scope)
            val coordinator = ImportCoordinator(GhostImporter(folder.newFolder()), runtime, scope)
            val saved = SavedStateHandle(mapOf("import_attempt_id" to "done-id", "import_phase" to "completed"))
            val model = StageViewModel(runtime, coordinator, saved, ImportAttemptStore({ true }, {}))
            model.restoreAttempt()
            assertFalse(model.importInterrupted.value)
            assertEquals(ImportState.Idle, coordinator.state.value)
            assertEquals(null, saved.get<String>("import_attempt_id"))
            assertTrue(model.beginImport() != null)
        } finally { scope.cancel() }
    }

    @Test fun returnedWorkWaitsForReceivingActivityToStart() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = simpleRuntime(scope)
            val coordinator = ImportCoordinator(GhostImporter(folder.newFolder()), runtime, scope)
            var consumed: String? = null
            var opens = 0
            val gate = CompletableDeferred<Unit>()
            val model = StageViewModel(runtime, coordinator, SavedStateHandle(),
                ImportAttemptStore({ id -> consumed == id }, { id -> consumed = id }))
            model.restoreAttempt()
            model.beginImport()
            val receiving = launch {
                model.acceptPickerResult({ opens++; byteArrayOf(0).inputStream() }, awaitStarted = { gate.await() })
            }
            yield()
            assertTrue(coordinator.state.value is ImportState.Picking)
            assertEquals(0, opens)
            gate.complete(Unit)
            receiving.join()
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>().first() }
            }
            assertEquals(1, opens)
        } finally { scope.cancel() }
    }

    @Test fun rotationAfterConsumedMarkKeepsResultInRetainedViewModel() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = simpleRuntime(scope)
            val coordinator = ImportCoordinator(GhostImporter(folder.newFolder()), runtime, scope)
            var consumed: String? = null
            var opens = 0
            val marked = CompletableDeferred<Unit>()
            val model = StageViewModel(runtime, coordinator, SavedStateHandle(),
                ImportAttemptStore({ id -> consumed == id }, { id -> consumed = id; marked.complete(Unit) }))
            model.restoreAttempt()
            val id = model.beginImport()!!
            val oldHost = Any()
            val newHost = Any()
            model.setImportActivityStarted(oldHost, false)
            val oldActivity = launch {
                model.submitPickerResult { opens++; byteArrayOf(0).inputStream() }
            }
            marked.await()
            oldActivity.cancel()
            assertEquals(ImportState.Picking(id), coordinator.state.value)
            assertEquals(0, opens)
            model.setImportActivityStarted(newHost, true)
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>().first() }
            }
            assertEquals(1, opens)
        } finally { scope.cancel() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun stoppedHostDoesNotAdmitResultAfterRestoreLookupResumes() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = simpleRuntime(scope)
            val coordinator = ImportCoordinator(GhostImporter(folder.newFolder()), runtime, scope)
            val lookup = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val marked = CompletableDeferred<Unit>()
            val saved = SavedStateHandle(mapOf("import_attempt_id" to "saved", "import_phase" to "picking"))
            val model = StageViewModel(runtime, coordinator, saved,
                ImportAttemptStore({ entered.complete(Unit); lookup.await(); false }, { marked.complete(Unit) }))
            val host = Any()
            model.onImportActivityStart(host)
            entered.await()
            model.submitPickerResult { error("provider opened while stopped") }
            model.setImportActivityStarted(host, false)
            model.cancelRunningOnStop()
            lookup.complete(Unit)
            marked.await()
            runCurrent()
            assertEquals(ImportState.Picking("saved"), coordinator.state.value)
        } finally { scope.cancel() }
    }

    @Test fun cancelledRestoreKeepsSavedPickingForLaterAttempt() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = simpleRuntime(scope)
            val coordinator = ImportCoordinator(GhostImporter(folder.newFolder()), runtime, scope)
            val entered = CompletableDeferred<Unit>()
            val lookup = CompletableDeferred<Unit>()
            val saved = SavedStateHandle(mapOf("import_attempt_id" to "saved", "import_phase" to "picking"))
            val model = StageViewModel(runtime, coordinator, saved,
                ImportAttemptStore({ entered.complete(Unit); lookup.await(); false }, {}))
            val first = launch { model.restoreAttempt() }
            entered.await()
            first.cancel()
            first.join()
            assertEquals("saved", saved.get<String>("import_attempt_id"))
            lookup.complete(Unit)
            model.restoreAttempt()
            assertEquals(ImportState.Picking("saved"), coordinator.state.value)
        } finally { scope.cancel() }
    }

    @Test fun failedPickerLaunchAbandonsMatchingAttempt() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = simpleRuntime(scope)
            val coordinator = ImportCoordinator(GhostImporter(folder.newFolder()), runtime, scope)
            val model = StageViewModel(runtime, coordinator, SavedStateHandle(),
                ImportAttemptStore({ false }, {}))
            model.restoreAttempt()
            model.launchImport { throw IllegalStateException("picker missing") }
            assertEquals(ImportState.Idle, coordinator.state.value)
            assertTrue(model.beginImport() != null)
        } finally { scope.cancel() }
    }

    @Test fun cancelledPendingAdmissionAbandonsPicking() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = simpleRuntime(scope)
            val coordinator = ImportCoordinator(GhostImporter(folder.newFolder()), runtime, scope)
            val gate = CompletableDeferred<Unit>()
            val model = StageViewModel(runtime, coordinator, SavedStateHandle(),
                ImportAttemptStore({ false }, {}))
            model.restoreAttempt()
            val id = model.beginImport()!!
            val pending = launch {
                model.acceptPickerResult({ error("provider opened") }, awaitStarted = { gate.await() })
            }
            yield()
            assertEquals(ImportState.Picking(id), coordinator.state.value)
            pending.cancel()
            pending.join()
            assertEquals(ImportState.Idle, coordinator.state.value)
        } finally { scope.cancel() }
    }

    private fun simpleRuntime(scope: CoroutineScope) = GhostRuntime(
        loadGhost = { BundledGhost("nanidroid", "Nanidroid", "", "", emptyMap(), "") },
        engineFactory = { object : ShioriEngine {
            override suspend fun request(event: ShioriEvent) = ShioriReply(204)
        } },
        bootState = object : BootStateStore {
            override suspend fun recordActivation(directoryId: String) = false
            override suspend fun consumeOnboarding() = false
        }, scope = scope,
    )
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun recreationObservesExistingPartialFrame() = runTest {
        var boots = 0
        val runtime = GhostRuntime(
            loadGhost = { BundledGhost("nanidroid", "Nanidroid", "Sakura", "Kero", mapOf(0 to File("surface.png")), "") },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply {
                    if (event.id == "OnFirstBoot") boots++
                    return ShioriReply(200, "\\0\\s0Hi\\e")
                }
            } },
            bootState = object : BootStateStore {
                override suspend fun recordActivation(directoryId: String) = true
                override suspend fun consumeOnboarding() = false
            },
            scope = backgroundScope,
            elapsedRealtime = { 0L },
        )
        val first = StageViewModel(runtime)
        first.start("en")
        first.setResumed(true)
        runCurrent()
        advanceTimeBy(50); runCurrent()
        val partial = (first.state.value as StageState.Ready).frame
        val recreated = StageViewModel(runtime)
        recreated.start("en")
        assertEquals(partial, (recreated.state.value as StageState.Ready).frame)
        assertEquals(1, boots)
    }
}
