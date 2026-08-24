package com.cattailsw.nanidroid.install

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class ForegroundNarImportCoordinatorTest {
    @Test fun startupBeginsRecoveringAndAcknowlegesCleanRecoveryAsIdle() {
        val coordinator = fixture()

        assertEquals(ForegroundNarImportState.Recovering, coordinator.state.value)

        dispatcher.runNext()

        assertEquals(ForegroundNarImportState.Idle, coordinator.state.value)
    }

    @Test fun startupCleanedRecoveryBecomesAnActionableInterruptedAttempt() {
        backend.recovery = NarImportRecoveryResult.Cleaned
        val coordinator = fixture(processNonce = "process-current")

        dispatcher.runNext()

        assertEquals(
            ForegroundNarImportState.Interrupted(NarImportAttemptToken("process-current", 1)),
            coordinator.state.value,
        )
    }

    @Test fun startupTypedRecoveryFailureBecomesRecoveryRequired() {
        backend.recovery = NarImportRecoveryResult.Failed("unavailable")
        val coordinator = fixture()

        dispatcher.runNext()

        assertRecoveryRequired(coordinator.state.value, NarImportPrimaryOutcome.Interrupted)
    }

    @Test fun startupThrownRecoveryFailureBecomesRecoveryRequired() {
        backend.throwRecovery = true
        val coordinator = fixture()

        dispatcher.runNext()

        assertRecoveryRequired(coordinator.state.value, NarImportPrimaryOutcome.Interrupted)
    }

    @Test fun armPickerCreatesOneAwaitingAttemptAndRejectsDuplicateArm() {
        val coordinator = recoveredFixture()

        val token = requireNotNull(coordinator.armPicker())

        assertEquals(ForegroundNarImportState.AwaitingSelection(token), coordinator.state.value)
        assertNull(coordinator.armPicker())
    }

    @Test fun pickerCancellationReturnsTheExactAwaitingAttemptToIdle() {
        val coordinator = recoveredFixture()
        val token = requireNotNull(coordinator.armPicker())

        assertTrue(coordinator.consumePickerResult(token, null, importAllowed = true))

        assertEquals(ForegroundNarImportState.Idle, coordinator.state.value)
    }

    @Test fun pickerLaunchFailureIsReplayableTerminalFailure() {
        val coordinator = recoveredFixture()
        val token = requireNotNull(coordinator.armPicker())

        assertTrue(coordinator.failPickerLaunch(token, "The document picker is unavailable."))

        assertEquals(
            ForegroundNarImportState.Failed(token, "The document picker is unavailable.", ArchiveInstallFailure.SourceUnavailable),
            coordinator.state.value,
        )
    }

    @Test fun returnTimeGuardRejectsSelectionWithoutOpeningTheBackend() {
        val coordinator = recoveredFixture()
        val token = requireNotNull(coordinator.armPicker())

        assertTrue(
            coordinator.consumePickerResult(
                expectedToken = token,
                selection = NarDocumentSelection("content://provider/a.nar", "content"),
                importAllowed = false,
            ),
        )

        assertEquals(ForegroundNarImportState.Idle, coordinator.state.value)
        assertEquals(0, backend.importCalls)
    }

    @Test fun validPickerResultIsCopyingBeforeTheQueuedImportRuns() {
        val coordinator = recoveredFixture()
        val token = requireNotNull(coordinator.armPicker())

        assertTrue(coordinator.consumePickerResult(token, selection(), importAllowed = true))

        assertEquals(ForegroundNarImportState.Copying(token), coordinator.state.value)
        assertEquals(0, backend.importCalls)
    }

    @Test fun validPickerResultPublishesInstallingProgressThenInstalled() {
        lateinit var token: NarImportAttemptToken
        lateinit var coordinator: ForegroundNarImportCoordinator
        backend.importAction = { _, _, progress ->
            progress("extracting", 42)
            assertEquals(
                ForegroundNarImportState.Installing(token, "extracting", 42),
                coordinator.state.value,
            )
            ArchiveInstallResult.Installed("/ghost/a", "ghost-a")
        }
        val created = recoveredFixture()
        coordinator = created
        token = requireNotNull(created.armPicker())

        assertTrue(created.consumePickerResult(token, selection(), importAllowed = true))
        dispatcher.runNext()

        assertEquals(ForegroundNarImportState.Installed(token, "/ghost/a", "ghost-a"), created.state.value)
    }

    @Test fun everyTypedImportFailureIsPublishedAfterCleanup() {
        val failures = listOf(
            ArchiveInstallFailure.SourceUnavailable,
            ArchiveInstallFailure.StorageUnavailable,
            ArchiveInstallFailure.InvalidArchive,
            ArchiveInstallFailure.TargetExists,
            ArchiveInstallFailure.StagingFailed,
            ArchiveInstallFailure.ExtractionFailed,
            ArchiveInstallFailure.PublishFailed,
            ArchiveInstallFailure.ArchiveTooLarge,
        )

        failures.forEach { failure ->
            resetFixture()
            backend.importResult = ArchiveInstallResult.Failed("failure-$failure", failure)
            val coordinator = recoveredFixture()
            val token = requireNotNull(coordinator.armPicker())
            assertTrue(coordinator.consumePickerResult(token, selection(), importAllowed = true))

            dispatcher.runNext()

            assertEquals(ForegroundNarImportState.Failed(token, "failure-$failure", failure), coordinator.state.value)
            assertEquals(2, backend.recoveryCalls)
        }
    }

    @Test fun thrownImportBecomesStagingFailureAfterCleanup() {
        backend.throwImport = true
        val coordinator = recoveredFixture()
        val token = requireNotNull(coordinator.armPicker())

        assertTrue(coordinator.consumePickerResult(token, selection(), importAllowed = true))
        dispatcher.runNext()

        assertEquals(
            ForegroundNarImportState.Failed(
                token,
                "Nanidroid could not complete the selected document import.",
                ArchiveInstallFailure.StagingFailed,
            ),
            coordinator.state.value,
        )
        assertEquals(2, backend.recoveryCalls)
    }

    @Test fun wrappedCancellationExceptionBecomesInterruptedAndStillReconciles() {
        backend.importAction = { _, _, _ ->
            throw IllegalStateException("wrapped", CancellationException())
        }
        val coordinator = recoveredFixture()
        val token = requireNotNull(coordinator.armPicker())

        assertTrue(coordinator.consumePickerResult(token, selection(), importAllowed = true))
        dispatcher.runNext()

        assertEquals(ForegroundNarImportState.Interrupted(token), coordinator.state.value)
        assertEquals(2, backend.recoveryCalls)
    }

    @Test fun unexpectedInFlightCancellationBecomesInterrupted() {
        backend.importResult = ArchiveInstallResult.Cancelled
        val coordinator = recoveredFixture()
        val token = requireNotNull(coordinator.armPicker())

        assertTrue(coordinator.consumePickerResult(token, selection(), importAllowed = true))
        dispatcher.runNext()

        assertEquals(ForegroundNarImportState.Interrupted(token), coordinator.state.value)
    }

    @Test fun stalePickerACannotConsumeAwaitingPickerB() {
        val coordinator = fixture(processNonce = "process-current")
        dispatcher.runNext()
        val tokenA = requireNotNull(coordinator.armPicker())
        assertTrue(coordinator.abandonPicker(tokenA))
        val tokenB = requireNotNull(coordinator.armPicker())

        assertFalse(
            coordinator.consumePickerResult(
                expectedToken = tokenA,
                selection = NarDocumentSelection("content://provider/a.nar", "content"),
                importAllowed = true,
            ),
        )
        assertEquals(ForegroundNarImportState.AwaitingSelection(tokenB), coordinator.state.value)
        assertEquals(0, backend.importCalls)
    }

    @Test fun deadProcessTokenCannotConsumeCurrentProcessAttemptWithTheSameSequence() {
        val coordinator = fixture(processNonce = "new-process")
        dispatcher.runNext()
        val current = requireNotNull(coordinator.armPicker())
        val deadProcess = NarImportAttemptToken("dead-process", 1)

        assertFalse(coordinator.consumePickerResult(deadProcess, selection(), importAllowed = true))

        assertEquals(NarImportAttemptToken("new-process", 1), current)
        assertEquals(ForegroundNarImportState.AwaitingSelection(current), coordinator.state.value)
    }

    @Test fun stateFlowReplaysTheLatestTerminalStateToANewObserver() {
        backend.importResult = ArchiveInstallResult.Cancelled
        val coordinator = recoveredFixture()
        val token = requireNotNull(coordinator.armPicker())
        assertTrue(coordinator.consumePickerResult(token, selection(), importAllowed = true))
        dispatcher.runNext()

        val replayed = runBlocking { coordinator.state.first() }

        assertEquals(ForegroundNarImportState.Interrupted(token), replayed)
    }

    @Test fun acknowledgementOnlyAcceptsMatchingTerminalAttempt() {
        backend.importResult = ArchiveInstallResult.Cancelled
        val coordinator = recoveredFixture(processNonce = "current")
        val token = requireNotNull(coordinator.armPicker())
        assertTrue(coordinator.consumePickerResult(token, selection(), importAllowed = true))
        dispatcher.runNext()

        assertFalse(coordinator.acknowledge(NarImportAttemptToken("stale", token.sequence)))
        assertTrue(coordinator.acknowledge(token))
        assertEquals(ForegroundNarImportState.Idle, coordinator.state.value)
    }

    @Test fun failedCleanupIsRecoveryRequiredAndRetryReturnsToRecordedTerminal() {
        backend.recovery = NarImportRecoveryResult.Failed("unclean")
        backend.recoveryResults += NarImportRecoveryResult.Clean
        backend.importResult = ArchiveInstallResult.Cancelled
        val coordinator = recoveredFixture()
        val token = requireNotNull(coordinator.armPicker())
        assertTrue(coordinator.consumePickerResult(token, selection(), importAllowed = true))
        dispatcher.runNext()
        assertRecoveryRequired(coordinator.state.value, NarImportPrimaryOutcome.Interrupted)

        backend.recovery = NarImportRecoveryResult.Clean
        assertTrue(coordinator.retryCleanup(token))
        assertTrue(coordinator.state.value is ForegroundNarImportState.Cleaning)
        dispatcher.runNext()

        assertEquals(ForegroundNarImportState.Interrupted(token), coordinator.state.value)
    }

    @Test fun failedCleanupRetryPreservesRecoveryRequiredWithoutImportingAgain() {
        backend.recovery = NarImportRecoveryResult.Failed("unclean")
        backend.recoveryResults += NarImportRecoveryResult.Clean
        backend.importResult = ArchiveInstallResult.Cancelled
        val coordinator = recoveredFixture()
        val token = requireNotNull(coordinator.armPicker())
        assertTrue(coordinator.consumePickerResult(token, selection(), importAllowed = true))
        dispatcher.runNext()
        val importsBeforeRetry = backend.importCalls

        assertTrue(coordinator.retryCleanup(token))
        dispatcher.runNext()

        assertRecoveryRequired(coordinator.state.value, NarImportPrimaryOutcome.Interrupted)
        assertEquals(importsBeforeRetry, backend.importCalls)
    }

    @Test fun thrownCleanupRetryPreservesRecoveryRequiredWithoutImportingAgain() {
        backend.recovery = NarImportRecoveryResult.Failed("unclean")
        backend.recoveryResults += NarImportRecoveryResult.Clean
        backend.importResult = ArchiveInstallResult.Cancelled
        val coordinator = recoveredFixture()
        val token = requireNotNull(coordinator.armPicker())
        assertTrue(coordinator.consumePickerResult(token, selection(), importAllowed = true))
        dispatcher.runNext()
        val importsBeforeRetry = backend.importCalls
        backend.throwRecovery = true

        assertTrue(coordinator.retryCleanup(token))
        dispatcher.runNext()

        assertRecoveryRequired(coordinator.state.value, NarImportPrimaryOutcome.Interrupted)
        assertEquals(importsBeforeRetry, backend.importCalls)
    }

    private fun recoveredFixture(processNonce: String = "process") = fixture(processNonce).also { dispatcher.runNext() }

    private fun fixture(processNonce: String = "process"): ForegroundNarImportCoordinator {
        return ForegroundNarImportCoordinator(backend, dispatcher, processNonce)
    }

    private fun resetFixture() {
        backend = FakeBackend()
        dispatcher = QueuedDispatcher()
    }

    private fun selection() = NarDocumentSelection("content://provider/archive.nar", "content")

    private fun assertRecoveryRequired(state: ForegroundNarImportState, primary: NarImportPrimaryOutcome) {
        val recovery = state as? ForegroundNarImportState.RecoveryRequired
            ?: throw AssertionError("Expected RecoveryRequired but was $state")
        assertEquals(primary, recovery.primary)
        assertEquals("Nanidroid could not reconcile its private import staging.", recovery.message)
    }

    private var backend = FakeBackend()
    private var dispatcher = QueuedDispatcher()

    private class FakeBackend : ForegroundNarImportBackend {
        var recovery: NarImportRecoveryResult = NarImportRecoveryResult.Clean
        val recoveryResults = ArrayDeque<NarImportRecoveryResult>()
        var importResult: ArchiveInstallResult = ArchiveInstallResult.Installed("/ghost/a", "ghost-a")
        var importAction: ((NarDocumentSelection, () -> Boolean, (String, Long) -> Unit) -> ArchiveInstallResult)? = null
        var throwRecovery = false
        var throwImport = false
        var importCalls = 0
        var recoveryCalls = 0

        override fun recoverOwnedStaging(): NarImportRecoveryResult {
            recoveryCalls += 1
            if (throwRecovery) throw IllegalStateException("recovery failure")
            return recoveryResults.removeFirstOrNull() ?: recovery
        }

        override fun importDocument(
            selection: NarDocumentSelection,
            isCancelled: () -> Boolean,
            onInstallingProgress: (phase: String, completed: Long) -> Unit,
        ): ArchiveInstallResult {
            importCalls += 1
            if (throwImport) throw IllegalStateException("import failure")
            return importAction?.invoke(selection, isCancelled, onInstallingProgress) ?: importResult
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runNext() = requireNotNull(tasks.removeFirstOrNull()).run()
    }
}
