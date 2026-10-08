package com.cattailsw.nanidroid.install

import com.cattailsw.nanidroid.data.BootStateStore
import com.cattailsw.nanidroid.engine.ShioriEngine
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.engine.ShioriReply
import com.cattailsw.nanidroid.ghost.BundledGhost
import com.cattailsw.nanidroid.ghost.InstalledGhostRepository
import com.cattailsw.nanidroid.runtime.GhostRuntime
import com.cattailsw.nanidroid.runtime.ImportPromptResult
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportCoordinatorTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun abandoningOnlyMatchingUnstartedPickerPreservesOtherAttempts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val root = folder.newFolder()
            val coordinator = ImportCoordinator(GhostImporter(root), runtime(root, scope, EventLog()), scope)
            val first = coordinator.beginPicking()!!
            coordinator.abandonPicking("wrong")
            assertEquals(ImportState.Picking(first), coordinator.state.value)
            coordinator.abandonPicking(first)
            assertEquals(ImportState.Idle, coordinator.state.value)
            val second = coordinator.beginPicking()!!
            assertNotEquals(first, second)
            coordinator.abandonPicking(first)
            assertEquals(ImportState.Picking(second), coordinator.state.value)
            coordinator.acceptResult(second) { ByteArrayInputStream(archive("visitor")) }
            coordinator.abandonPicking(second)
            assertTrue(coordinator.state.value is ImportState.Running ||
                coordinator.state.value is ImportState.Completed)
        } finally { scope.cancel() }
    }

    @Test fun restoredPickerAdmitsFirstResultWithoutRetargetingEvents() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val root = folder.newFolder()
            val events = EventLog()
            val runtime = runtime(root, scope, events)
            runtime.start("ja")
            val coordinator = ImportCoordinator(GhostImporter(root), runtime, scope)
            val savedId = "saved-picker-id"
            assertTrue(coordinator.restorePicking(savedId))
            assertFalse(coordinator.restorePicking("other-id"))
            coordinator.acceptResult(savedId) { ByteArrayInputStream(archive("visitor")) }
            val done = withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>().first() }
            assertEquals(ImportOutcome.Installed("visitor"), done.outcome)
            assertEquals(listOf("OnBoot"), events.map { it.id })
        } finally { scope.cancel() }
    }

    @Test fun cancelledPickerStartsNoImportAndEmitsNoEvent() = runBlocking {
        val events = EventLog()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val root = folder.newFolder()
            val runtime = runtime(root, scope, events)
            runtime.start("ja")
            val coordinator = ImportCoordinator(GhostImporter(root), runtime, scope)
            val id = coordinator.beginPicking()!!
            coordinator.acceptResult(id, null)
            assertEquals(ImportState.Idle, coordinator.state.value)
            assertEquals(listOf("OnBoot"), events.map { it.id })
            assertFalse(root.resolve("ghost/visitor").exists())
        } finally { scope.cancel() }
    }

    @Test fun successPublishesBeforePromptWithoutActivatingAndRejectsDuplicateCallback() = runBlocking {
        val events = EventLog()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val root = folder.newFolder()
            val runtime = runtime(root, scope, events)
            runtime.start("ja")
            val coordinator = ImportCoordinator(GhostImporter(root), runtime, scope)
            val bytes = archive("visitor")
            var opens = 0
            val id = coordinator.beginPicking()!!
            coordinator.acceptResult(id) { opens++; ByteArrayInputStream(bytes) }
            coordinator.acceptResult(id) { error("duplicate opened") }
            val done = withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>()
                .first { it.promptResult == ImportPromptResult.Shown } }
            events.await { it.size >= 3 }
            assertEquals(ImportOutcome.Installed("visitor"), done.outcome)
            assertEquals(1, opens)
            assertTrue(root.resolve("ghost/visitor/ghost/master/descript.txt").isFile)
            assertEquals(ImportPromptResult.Shown, done.promptResult)
            val stage = runtime.state.value as com.cattailsw.nanidroid.runtime.StageState.Ready
            assertEquals("visitor", stage.switchPrompt?.directoryId)
            assertEquals("Nanidroid", stage.ghostName)
            awaitReady(runtime) { ready -> ready.installedGhosts.any { it.directoryId == "visitor" } }
            assertEquals(listOf("OnBoot", "OnInstallBegin", "OnInstallComplete"), events.map { it.id })
        } finally { scope.cancel() }
    }

    @Test fun blockedBeginCannotDelayPublicationAndCloseDropsQueuedComplete() = runBlocking {
        val events = EventLog()
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val root = folder.newFolder()
            val runtime = runtime(root, scope, events, gate)
            runtime.start("ja")
            val coordinator = ImportCoordinator(GhostImporter(root), runtime, scope)
            val bytes = archive("visitor")
            val id = coordinator.beginPicking()!!
            val establishedJobs = scope.coroutineContext[Job]!!.children.toSet()
            coordinator.acceptResult(id) { ByteArrayInputStream(bytes) }
            val done = withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>()
                .first { it.outcome is ImportOutcome.Installed } }
            assertEquals(ImportOutcome.Installed("visitor"), done.outcome)
            assertTrue(root.resolve("ghost/visitor/ghost/master/descript.txt").isFile)
            events.await { observed -> observed.any { it.id == "OnInstallBegin" } }
            assertFalse(events.any { it.id == "OnInstallComplete" })
            runtime.close()
            withTimeout(10_000) { runtime.state.first { it == com.cattailsw.nanidroid.runtime.StageState.Finished } }
            gate.complete(Unit)
            settleAcceptedWork(scope, establishedJobs)
            assertFalse(events.any { it.id == "OnInstallComplete" })
        } finally { gate.complete(Unit); scope.cancel() }
    }

    @Test fun refusalAndFailureEventsFollowValidationBoundary() = runBlocking {
        val cases = listOf("reserved", "existing", "late", "invalid", "extraction")
        for (case in cases) {
            val events = EventLog()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val root = folder.newFolder()
                val runtime = runtime(root, scope, events)
                runtime.start("ja")
                val importer = when (case) {
                    "late" -> GhostImporter(root, beforeMove = { root.resolve("ghost/visitor").mkdirs() })
                    else -> GhostImporter(root)
                }
                if (case == "existing") root.resolve("ghost/visitor").mkdirs()
                val coordinator = ImportCoordinator(importer, runtime, scope)
                val bytes = when (case) {
                    "reserved" -> archive("nanidroid")
                    "invalid" -> byteArrayOf(0, 1, 2)
                    "extraction" -> archive("visitor", includeShell = false)
                    else -> archive("visitor")
                }
                val id = coordinator.beginPicking()!!
                coordinator.acceptResult(id) { ByteArrayInputStream(bytes) }
                val done = withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>().first() }
                events.await { observed -> observed.any {
                    it.id == "OnInstallRefuse" || it.id == "OnInstallFailure"
                } }
                val delivered = events.filter { it.id.startsWith("OnInstall") }.map { it.id }
                when (case) {
                    "reserved", "existing" -> {
                        assertTrue(done.outcome is ImportOutcome.Refused)
                        assertEquals(listOf("OnInstallRefuse"), delivered)
                    }
                    "late" -> {
                        assertTrue(done.outcome is ImportOutcome.Refused)
                        assertEquals(listOf("OnInstallBegin", "OnInstallRefuse"), delivered)
                    }
                    "extraction" -> {
                        assertTrue(done.outcome is ImportOutcome.Failed)
                        assertEquals(listOf("OnInstallBegin", "OnInstallFailure"), delivered)
                    }
                    else -> {
                        assertTrue(done.outcome is ImportOutcome.Failed)
                        assertEquals(listOf("OnInstallFailure"), delivered)
                    }
                }
            } finally { scope.cancel() }
        }
    }

    @Test fun pendingPromptRetriesOnActiveTransitionAndSurvivesAcknowledgmentUntilAsked() = runBlocking {
        val events = EventLog()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val root = folder.newFolder()
            val runtime = runtime(root, scope, events)
            val coordinator = ImportCoordinator(GhostImporter(root), runtime, scope)
            val bytes = archive("visitor")
            val id = coordinator.beginPicking()!!
            coordinator.acceptResult(id) { ByteArrayInputStream(bytes) }
            val deferred = withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>()
                .first { it.promptResult == ImportPromptResult.Deferred } }
            assertTrue(deferred.pendingPrompt)
            runtime.start("ja")
            val shown = withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>()
                .first { it.promptResult == ImportPromptResult.Shown } }
            assertEquals(id, shown.attemptId)
            assertEquals("visitor", shown.pendingDirectoryId)
            assertEquals("visitor", (runtime.state.value as com.cattailsw.nanidroid.runtime.StageState.Ready).switchPrompt?.directoryId)
            coordinator.acknowledge("wrong")
            assertTrue(coordinator.state.value is ImportState.Completed)
            coordinator.acknowledge(id)
            assertEquals(ImportState.Idle, coordinator.state.value)
        } finally { scope.cancel() }
    }

    @Test fun pickerStartedUnderAEmitsNoInstallEventToBAfterSwitch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val root = folder.newFolder()
            val other = root.resolve("ghost/other")
            other.resolve("ghost/master/ja").mkdirs()
            other.resolve("shell/master").mkdirs()
            other.resolve("ghost/master/descript.txt").writeText("name,Other\nshiori,Nanidroid\n")
            other.resolve("ghost/master/ja/content.txt").writeText("other")
            val delivered = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
            val runtime = GhostRuntime(
                loadGhost = { BundledGhost("nanidroid", "Nanidroid", "", "", emptyMap(), "") },
                engineFactory = { ghost -> object : ShioriEngine {
                    override suspend fun request(event: ShioriEvent): ShioriReply {
                        synchronized(delivered) { delivered += ghost.directoryId to event.id }
                        return ShioriReply(204)
                    }
                } },
                bootState = object : BootStateStore {
                    override suspend fun recordActivation(directoryId: String) = false
                    override suspend fun consumeOnboarding() = false
                },
                scope = scope,
                installedGhosts = InstalledGhostRepository(root.resolve("ghost")),
                elapsedRealtime = { 0L },
            )
            runtime.start("ja")
            val coordinator = ImportCoordinator(GhostImporter(root), runtime, scope)
            val attempt = coordinator.beginPicking()!!
            runtime.selectGhost("other")
            awaitReady(runtime) { it.switchPrompt?.directoryId == "other" }
            runtime.confirmSwitch()
            awaitReady(runtime) { it.ghostName == "Other" }
            val bytes = archive("visitor")
            val establishedJobs = scope.coroutineContext[Job]!!.children.toSet()
            coordinator.acceptResult(attempt) { ByteArrayInputStream(bytes) }
            withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>()
                .first { it.outcome is ImportOutcome.Installed } }
            settleAcceptedWork(scope, establishedJobs)
            assertTrue(delivered.none { it.first == "other" && it.second.startsWith("OnInstall") })
            assertTrue(root.resolve("ghost/visitor/ghost/master/descript.txt").isFile)
        } finally { scope.cancel() }
    }

    @Test fun cancellationAfterMoveStillReportsInstalled() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val root = folder.newFolder()
            val runtime = runtime(root, scope, EventLog())
            runtime.start("ja")
            lateinit var coordinator: ImportCoordinator
            val importer = GhostImporter(root, afterMove = { coordinator.cancelBeforePublication() })
            coordinator = ImportCoordinator(importer, runtime, scope)
            val bytes = archive("visitor")
            val id = coordinator.beginPicking()!!
            coordinator.acceptResult(id) { ByteArrayInputStream(bytes) }
            val done = withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>().first() }
            assertEquals(ImportOutcome.Installed("visitor"), done.outcome)
            assertTrue(root.resolve("ghost/visitor/ghost/master/descript.txt").isFile)
        } finally { scope.cancel() }
    }

    @Test fun cancellationAfterInstallBeginEmitsFailureWithoutReferences() = runBlocking {
        val events = EventLog()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val root = folder.newFolder()
            val runtime = runtime(root, scope, events)
            runtime.start("ja")
            lateinit var coordinator: ImportCoordinator
            val importer = GhostImporter(root, beforeMove = { coordinator.cancelBeforePublication() })
            coordinator = ImportCoordinator(importer, runtime, scope)
            val id = coordinator.beginPicking()!!
            coordinator.acceptResult(id) { ByteArrayInputStream(archive("visitor")) }
            val done = withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>().first() }
            assertEquals(ImportOutcome.Cancelled, done.outcome)
            events.await { observed -> observed.any { it.id == "OnInstallFailure" } }
            assertEquals(listOf("OnBoot", "OnInstallBegin", "OnInstallFailure"), events.map { it.id })
            assertTrue(events.single { it.id == "OnInstallFailure" }.references.isEmpty())
            assertFalse(root.resolve("ghost/visitor").exists())
        } finally { scope.cancel() }
    }

    @Test fun cancellationBeforeInstallBeginEmitsNoInstallEvent() = runBlocking {
        val events = EventLog()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val root = folder.newFolder()
            val runtime = runtime(root, scope, events)
            runtime.start("ja")
            lateinit var coordinator: ImportCoordinator
            val importer = GhostImporter(root, writeSourceChunk = { output, bytes, count ->
                coordinator.cancelBeforePublication()
                output.write(bytes, 0, count)
            })
            coordinator = ImportCoordinator(importer, runtime, scope)
            val id = coordinator.beginPicking()!!
            coordinator.acceptResult(id) { ByteArrayInputStream(archive("visitor")) }
            val done = withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>().first() }
            assertEquals(ImportOutcome.Cancelled, done.outcome)
            assertEquals(listOf("OnBoot"), events.map { it.id })
            assertFalse(root.resolve("ghost/visitor").exists())
        } finally { scope.cancel() }
    }

    @Test fun finishedSessionDropsAutomaticPromptButKeepsInstallation() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val root = folder.newFolder()
            val runtime = runtime(root, scope, EventLog())
            runtime.start("ja")
            val importer = GhostImporter(root, beforeMove = {
                entered.countDown()
                release.await()
            })
            val coordinator = ImportCoordinator(importer, runtime, scope)
            val bytes = archive("visitor")
            val id = coordinator.beginPicking()!!
            coordinator.acceptResult(id) { ByteArrayInputStream(bytes) }
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, java.util.concurrent.TimeUnit.SECONDS) })
            runtime.close()
            withTimeout(10_000) { runtime.state.first { it == com.cattailsw.nanidroid.runtime.StageState.Finished } }
            release.countDown()
            // The finished session's result is not reopened by a later launch in this process.
            withTimeout(10_000) { coordinatorJob(coordinator)?.join() }
            assertTrue(root.resolve("ghost/visitor/ghost/master/descript.txt").isFile)
            withTimeout(10_000) { coordinator.state.first { it == ImportState.Idle } }
        } finally { release.countDown(); scope.cancel() }
    }

    @Test fun deferredPromptRetriesWhenSwitchingSessionBecomesActive() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val changing = CompletableDeferred<Unit>()
        try {
            val root = folder.newFolder()
            val other = root.resolve("ghost/other")
            other.resolve("ghost/master/ja").mkdirs()
            other.resolve("shell/master").mkdirs()
            other.resolve("ghost/master/descript.txt").writeText("name,Other\nshiori,Nanidroid\n")
            other.resolve("ghost/master/ja/content.txt").writeText("other")
            val events = EventLog()
            val runtime = runtime(root, scope, events, changingGate = changing)
            runtime.start("ja")
            runtime.selectGhost("other")
            awaitReady(runtime) { it.switchPrompt?.directoryId == "other" }
            runtime.confirmSwitch()
            events.await { observed -> observed.any { it.id == "OnGhostChanging" } }
            val coordinator = ImportCoordinator(GhostImporter(root), runtime, scope)
            val bytes = archive("visitor")
            val id = coordinator.beginPicking()!!
            coordinator.acceptResult(id) { ByteArrayInputStream(bytes) }
            withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>()
                .first { it.promptResult == ImportPromptResult.Deferred } }
            changing.complete(Unit)
            val shown = withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>()
                .first { it.promptResult == ImportPromptResult.Shown } }
            assertEquals("visitor", shown.pendingDirectoryId)
            assertEquals("visitor", (runtime.state.value as com.cattailsw.nanidroid.runtime.StageState.Ready)
                .switchPrompt?.directoryId)
        } finally { changing.complete(Unit); scope.cancel() }
    }

    @Test fun deferredPromptRetriesWhenGhostChangingThrows() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val changing = CompletableDeferred<Unit>()
        try {
            val root = folder.newFolder()
            val other = root.resolve("ghost/other")
            other.resolve("ghost/master/ja").mkdirs()
            other.resolve("shell/master").mkdirs()
            other.resolve("ghost/master/descript.txt").writeText("name,Other\nshiori,Nanidroid\n")
            other.resolve("ghost/master/ja/content.txt").writeText("other")
            val events = EventLog()
            val runtime = runtime(root, scope, events, changingGate = changing, failChanging = true)
            runtime.start("ja")
            runtime.selectGhost("other")
            awaitReady(runtime) { it.switchPrompt?.directoryId == "other" }
            runtime.confirmSwitch()
            events.await { observed -> observed.any { it.id == "OnGhostChanging" } }
            val coordinator = ImportCoordinator(GhostImporter(root), runtime, scope)
            val id = coordinator.beginPicking()!!
            coordinator.acceptResult(id) { ByteArrayInputStream(archive("visitor")) }
            withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>()
                .first { it.promptResult == ImportPromptResult.Deferred } }
            changing.complete(Unit)
            val shown = withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>()
                .first { it.promptResult == ImportPromptResult.Shown } }
            assertEquals(id, shown.attemptId)
            val stage = runtime.state.value as com.cattailsw.nanidroid.runtime.StageState.Ready
            assertEquals("visitor", stage.switchPrompt?.directoryId)
            assertEquals("Nanidroid", stage.ghostName)
        } finally { changing.complete(Unit); scope.cancel() }
    }

    @Test fun resolvingShownPromptCompletesAttempt() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val root = folder.newFolder()
            val runtime = runtime(root, scope, EventLog())
            runtime.start("ja")
            val coordinator = ImportCoordinator(GhostImporter(root), runtime, scope)
            val id = coordinator.beginPicking()!!
            coordinator.acceptResult(id) { ByteArrayInputStream(archive("visitor")) }
            withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>()
                .first { it.promptResult == ImportPromptResult.Shown } }
            runtime.dismissSwitch()
            withTimeout(10_000) { coordinator.state.first { it == ImportState.Idle } }
        } finally { scope.cancel() }
    }

    @Test fun finishedRuntimeClearsCompletedResult() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val root = folder.newFolder()
            val runtime = runtime(root, scope, EventLog())
            runtime.start("ja")
            val coordinator = ImportCoordinator(GhostImporter(root), runtime, scope)
            val id = coordinator.beginPicking()!!
            coordinator.acceptResult(id) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
            withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>()
                .first { it.outcome is ImportOutcome.Failed } }
            runtime.close()
            withTimeout(10_000) { coordinator.state.first { it == ImportState.Idle } }
        } finally { scope.cancel() }
    }

    @Test fun acknowledgingResultKeepsDeferredPrompt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val changing = CompletableDeferred<Unit>()
        try {
            val root = folder.newFolder()
            val other = root.resolve("ghost/other")
            other.resolve("ghost/master/ja").mkdirs()
            other.resolve("shell/master").mkdirs()
            other.resolve("ghost/master/descript.txt").writeText("name,Other\nshiori,Nanidroid\n")
            other.resolve("ghost/master/ja/content.txt").writeText("other")
            val events = EventLog()
            val runtime = runtime(root, scope, events, changingGate = changing)
            runtime.start("ja")
            runtime.selectGhost("other")
            awaitReady(runtime) { it.switchPrompt?.directoryId == "other" }
            runtime.confirmSwitch()
            events.await { observed -> observed.any { it.id == "OnGhostChanging" } }
            val coordinator = ImportCoordinator(GhostImporter(root), runtime, scope)
            val id = coordinator.beginPicking()!!
            coordinator.acceptResult(id) { ByteArrayInputStream(archive("visitor")) }
            withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>()
                .first { it.promptResult == ImportPromptResult.Deferred } }
            coordinator.acknowledge(id)
            assertEquals(ImportState.Idle, coordinator.state.value)
            changing.complete(Unit)
            awaitReady(runtime) { it.switchPrompt?.directoryId == "visitor" }
        } finally { changing.complete(Unit); scope.cancel() }
    }

    @Test fun shownPromptSurvivesBootFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val boot = CompletableDeferred<Unit>()
        try {
            val root = folder.newFolder()
            val events = EventLog()
            val runtime = runtime(root, scope, events, bootGate = boot, failBoot = true)
            val starting = scope.launch { runtime.start("ja") }
            awaitReady(runtime) { true }
            val coordinator = ImportCoordinator(GhostImporter(root), runtime, scope)
            val id = coordinator.beginPicking()!!
            coordinator.acceptResult(id) { ByteArrayInputStream(archive("visitor")) }
            withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>()
                .first { it.promptResult == ImportPromptResult.Shown } }
            boot.complete(Unit)
            starting.join()
            val stage = runtime.state.value as com.cattailsw.nanidroid.runtime.StageState.Ready
            assertEquals("visitor", stage.switchPrompt?.directoryId)
            assertEquals("Boot failed", stage.activationError)
            assertEquals(ImportPromptResult.Shown,
                (coordinator.state.value as ImportState.Completed).promptResult)
        } finally { boot.complete(Unit); scope.cancel() }
    }

    private class EventLog : AbstractList<ShioriEvent>() {
        private val observed = MutableStateFlow<List<ShioriEvent>>(emptyList())
        override val size get() = observed.value.size
        override fun get(index: Int) = observed.value[index]
        override fun iterator() = observed.value.iterator()
        fun record(event: ShioriEvent) { observed.update { it + event } }
        suspend fun await(predicate: (List<ShioriEvent>) -> Boolean) =
            withTimeout(10_000) { observed.first(predicate) }
    }

    private suspend fun awaitReady(runtime: GhostRuntime,
        predicate: (com.cattailsw.nanidroid.runtime.StageState.Ready) -> Boolean) {
        withTimeout(10_000) {
            runtime.state.filterIsInstance<com.cattailsw.nanidroid.runtime.StageState.Ready>().first(predicate)
        }
    }

    private fun coordinatorJob(coordinator: ImportCoordinator): Job? =
        ImportCoordinator::class.java.getDeclaredField("running").let {
            it.isAccessible = true
            it.get(coordinator) as? Job
        }

    private suspend fun settleAcceptedWork(scope: CoroutineScope, established: Set<Job>) {
        // Completed publication precedes launch completion. Snapshot includes the queued
        // install-event chain; joining it waits for the released request and dropped successor.
        withTimeout(10_000) {
            scope.coroutineContext[Job]!!.children.filter { it !in established }.toList().forEach { it.join() }
        }
    }

    private fun runtime(root: File, scope: CoroutineScope, events: EventLog,
                        gate: CompletableDeferred<Unit>? = null,
                        changingGate: CompletableDeferred<Unit>? = null,
                        failChanging: Boolean = false,
                        bootGate: CompletableDeferred<Unit>? = null,
                        failBoot: Boolean = false) =
        GhostRuntime(
            loadGhost = { BundledGhost("nanidroid", "Nanidroid", "", "", emptyMap(), "") },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply {
                    events.record(event)
                    if (event.id == "OnInstallBegin") gate?.await()
                    if (event.id == "OnGhostChanging") changingGate?.await()
                    if (event.id == "OnGhostChanging" && failChanging) error("Changing failed")
                    if (event.id == "OnBoot") bootGate?.await()
                    if (event.id == "OnBoot" && failBoot) error("Boot failed")
                    return ShioriReply(204)
                }
            } },
            bootState = object : BootStateStore {
                override suspend fun recordActivation(directoryId: String) = false
                override suspend fun consumeOnboarding() = false
            },
            scope = scope,
            installedGhosts = InstalledGhostRepository(root.resolve("ghost")),
            elapsedRealtime = { 0L },
        )

    private fun archive(id: String, includeShell: Boolean = true): ByteArray {
        val file = folder.newFile()
        ZipArchiveOutputStream(file).use { zip ->
            val entries = mutableMapOf(
                "install.txt" to "type,ghost\ndirectory,$id\n".toByteArray(),
                "ghost/master/descript.txt" to "name,Visitor\nshiori,Nanidroid\n".toByteArray(),
            )
            if (includeShell) entries["shell/master/placeholder.txt"] = byteArrayOf(1)
            entries.forEach { (name, bytes) ->
                zip.putArchiveEntry(ZipArchiveEntry(name))
                zip.write(bytes)
                zip.closeArchiveEntry()
            }
        }
        return file.readBytes()
    }
}
