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
import kotlinx.coroutines.delay
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
            val coordinator = ImportCoordinator(GhostImporter(root), runtime(root, scope, mutableListOf()), scope)
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
            val events = mutableListOf<ShioriEvent>()
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
        val events = mutableListOf<ShioriEvent>()
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
        val events = mutableListOf<ShioriEvent>()
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
            withTimeout(10_000) { while (events.size < 3) delay(10) }
            assertEquals(ImportOutcome.Installed("visitor"), done.outcome)
            assertEquals(1, opens)
            assertTrue(root.resolve("ghost/visitor/ghost/master/descript.txt").isFile)
            assertEquals(ImportPromptResult.Shown, done.promptResult)
            val stage = runtime.state.value as com.cattailsw.nanidroid.runtime.StageState.Ready
            assertEquals("visitor", stage.switchPrompt?.directoryId)
            assertEquals("Nanidroid", stage.ghostName)
            withTimeout(10_000) { while ((runtime.state.value as com.cattailsw.nanidroid.runtime.StageState.Ready)
                    .installedGhosts.none { it.directoryId == "visitor" }) delay(10) }
            assertEquals(listOf("OnBoot", "OnInstallBegin", "OnInstallComplete"), events.map { it.id })
        } finally { scope.cancel() }
    }

    @Test fun blockedBeginCannotDelayPublicationAndCloseDropsQueuedComplete() = runBlocking {
        val events = mutableListOf<ShioriEvent>()
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val root = folder.newFolder()
            val runtime = runtime(root, scope, events, gate)
            runtime.start("ja")
            val coordinator = ImportCoordinator(GhostImporter(root), runtime, scope)
            val bytes = archive("visitor")
            val id = coordinator.beginPicking()!!
            coordinator.acceptResult(id) { ByteArrayInputStream(bytes) }
            val done = withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>()
                .first { it.outcome is ImportOutcome.Installed } }
            assertEquals(ImportOutcome.Installed("visitor"), done.outcome)
            assertTrue(root.resolve("ghost/visitor/ghost/master/descript.txt").isFile)
            withTimeout(10_000) { while (events.none { it.id == "OnInstallBegin" }) delay(10) }
            assertFalse(events.any { it.id == "OnInstallComplete" })
            runtime.close()
            withTimeout(10_000) { while (runtime.state.value != com.cattailsw.nanidroid.runtime.StageState.Finished) delay(10) }
            gate.complete(Unit)
            delay(100)
            assertFalse(events.any { it.id == "OnInstallComplete" })
        } finally { gate.complete(Unit); scope.cancel() }
    }

    @Test fun refusalAndFailureEventsFollowValidationBoundary() = runBlocking {
        val cases = listOf("reserved", "existing", "late", "invalid", "extraction")
        for (case in cases) {
            val events = mutableListOf<ShioriEvent>()
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
                withTimeout(10_000) { while (events.none {
                    it.id == "OnInstallRefuse" || it.id == "OnInstallFailure"
                }) delay(10) }
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
        val events = mutableListOf<ShioriEvent>()
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
            val delivered = mutableListOf<Pair<String, String>>()
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
            withTimeout(10_000) { while ((runtime.state.value as com.cattailsw.nanidroid.runtime.StageState.Ready)
                    .switchPrompt?.directoryId != "other") delay(10) }
            runtime.confirmSwitch()
            withTimeout(10_000) { while ((runtime.state.value as com.cattailsw.nanidroid.runtime.StageState.Ready)
                    .ghostName != "Other") delay(10) }
            val bytes = archive("visitor")
            coordinator.acceptResult(attempt) { ByteArrayInputStream(bytes) }
            withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>()
                .first { it.outcome is ImportOutcome.Installed } }
            delay(100)
            assertTrue(delivered.none { it.first == "other" && it.second.startsWith("OnInstall") })
            assertTrue(root.resolve("ghost/visitor/ghost/master/descript.txt").isFile)
        } finally { scope.cancel() }
    }

    @Test fun cancellationAfterMoveStillReportsInstalled() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val root = folder.newFolder()
            val runtime = runtime(root, scope, mutableListOf())
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
        val events = mutableListOf<ShioriEvent>()
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
            withTimeout(10_000) { while (events.none { it.id == "OnInstallFailure" }) delay(10) }
            assertEquals(listOf("OnBoot", "OnInstallBegin", "OnInstallFailure"), events.map { it.id })
            assertTrue(events.single { it.id == "OnInstallFailure" }.references.isEmpty())
            assertFalse(root.resolve("ghost/visitor").exists())
        } finally { scope.cancel() }
    }

    @Test fun cancellationBeforeInstallBeginEmitsNoInstallEvent() = runBlocking {
        val events = mutableListOf<ShioriEvent>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val root = folder.newFolder()
            val runtime = runtime(root, scope, events)
            runtime.start("ja")
            val importer = GhostImporter(root, writeSourceChunk = { output, bytes, count ->
                entered.countDown()
                release.await()
                output.write(bytes, 0, count)
            })
            val coordinator = ImportCoordinator(importer, runtime, scope)
            val id = coordinator.beginPicking()!!
            coordinator.acceptResult(id) { ByteArrayInputStream(archive("visitor")) }
            withTimeout(10_000) { while (entered.count > 0) delay(10) }
            // UI cancellation follows admission on the caller; the IO hook only holds source copying.
            coordinator.cancelBeforePublication()
            release.countDown()
            val done = withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>().first() }
            assertEquals(ImportOutcome.Cancelled, done.outcome)
            assertEquals(listOf("OnBoot"), events.map { it.id })
            assertFalse(root.resolve("ghost/visitor").exists())
        } finally { release.countDown(); scope.cancel() }
    }

    @Test fun finishedSessionDropsAutomaticPromptButKeepsInstallation() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val root = folder.newFolder()
            val runtime = runtime(root, scope, mutableListOf())
            runtime.start("ja")
            val importer = GhostImporter(root, beforeMove = {
                entered.countDown()
                release.await()
            })
            val coordinator = ImportCoordinator(importer, runtime, scope)
            val bytes = archive("visitor")
            val id = coordinator.beginPicking()!!
            coordinator.acceptResult(id) { ByteArrayInputStream(bytes) }
            withTimeout(10_000) { while (entered.count > 0) delay(10) }
            runtime.close()
            withTimeout(10_000) { while (runtime.state.value != com.cattailsw.nanidroid.runtime.StageState.Finished) delay(10) }
            release.countDown()
            // The finished session's result is not reopened by a later launch in this process.
            withTimeout(10_000) { while (!root.resolve("ghost/visitor/ghost/master/descript.txt").isFile) delay(10) }
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
            val events = mutableListOf<ShioriEvent>()
            val runtime = runtime(root, scope, events, changingGate = changing)
            runtime.start("ja")
            runtime.selectGhost("other")
            withTimeout(10_000) { while ((runtime.state.value as com.cattailsw.nanidroid.runtime.StageState.Ready)
                    .switchPrompt?.directoryId != "other") delay(10) }
            runtime.confirmSwitch()
            withTimeout(10_000) { while (events.none { it.id == "OnGhostChanging" }) delay(10) }
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
            val events = mutableListOf<ShioriEvent>()
            val runtime = runtime(root, scope, events, changingGate = changing, failChanging = true)
            runtime.start("ja")
            runtime.selectGhost("other")
            withTimeout(10_000) { while ((runtime.state.value as com.cattailsw.nanidroid.runtime.StageState.Ready)
                    .switchPrompt?.directoryId != "other") delay(10) }
            runtime.confirmSwitch()
            withTimeout(10_000) { while (events.none { it.id == "OnGhostChanging" }) delay(10) }
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
            val runtime = runtime(root, scope, mutableListOf())
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
            val runtime = runtime(root, scope, mutableListOf())
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
            val events = mutableListOf<ShioriEvent>()
            val runtime = runtime(root, scope, events, changingGate = changing)
            runtime.start("ja")
            runtime.selectGhost("other")
            withTimeout(10_000) { while ((runtime.state.value as com.cattailsw.nanidroid.runtime.StageState.Ready)
                    .switchPrompt?.directoryId != "other") delay(10) }
            runtime.confirmSwitch()
            withTimeout(10_000) { while (events.none { it.id == "OnGhostChanging" }) delay(10) }
            val coordinator = ImportCoordinator(GhostImporter(root), runtime, scope)
            val id = coordinator.beginPicking()!!
            coordinator.acceptResult(id) { ByteArrayInputStream(archive("visitor")) }
            withTimeout(10_000) { coordinator.state.filterIsInstance<ImportState.Completed>()
                .first { it.promptResult == ImportPromptResult.Deferred } }
            coordinator.acknowledge(id)
            assertEquals(ImportState.Idle, coordinator.state.value)
            changing.complete(Unit)
            withTimeout(10_000) {
                while ((runtime.state.value as? com.cattailsw.nanidroid.runtime.StageState.Ready)
                        ?.switchPrompt?.directoryId != "visitor") delay(10)
            }
        } finally { changing.complete(Unit); scope.cancel() }
    }

    @Test fun shownPromptSurvivesBootFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val boot = CompletableDeferred<Unit>()
        try {
            val root = folder.newFolder()
            val events = mutableListOf<ShioriEvent>()
            val runtime = runtime(root, scope, events, bootGate = boot, failBoot = true)
            val starting = scope.launch { runtime.start("ja") }
            withTimeout(10_000) { while (runtime.state.value !is com.cattailsw.nanidroid.runtime.StageState.Ready) delay(10) }
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

    private fun runtime(root: File, scope: CoroutineScope, events: MutableList<ShioriEvent>,
                        gate: CompletableDeferred<Unit>? = null,
                        changingGate: CompletableDeferred<Unit>? = null,
                        failChanging: Boolean = false,
                        bootGate: CompletableDeferred<Unit>? = null,
                        failBoot: Boolean = false) =
        GhostRuntime(
            loadGhost = { BundledGhost("nanidroid", "Nanidroid", "", "", emptyMap(), "") },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply {
                    synchronized(events) { events += event }
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
