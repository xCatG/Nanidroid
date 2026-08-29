package com.cattailsw.nanidroid

import android.os.Bundle
import com.cattailsw.nanidroid.install.ArchiveInstallResult
import com.cattailsw.nanidroid.install.ForegroundNarImportBackend
import com.cattailsw.nanidroid.install.ForegroundNarImportCoordinator
import com.cattailsw.nanidroid.install.ForegroundNarImportState
import com.cattailsw.nanidroid.install.NarDocumentSelection
import com.cattailsw.nanidroid.install.NarImportAttemptToken
import com.cattailsw.nanidroid.install.NarImportRecoveryResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class ForegroundNarPickerOwnershipTest {
    @Test
    fun bundleRoundTripPreservesTheExactOwnerToken() {
        val values = mutableMapOf<String, Any?>()
        val bundle = mockk<Bundle>()
        every { bundle.putString(any(), any()) } answers {
            values[firstArg()] = secondArg<String?>()
        }
        every { bundle.putLong(any(), any()) } answers {
            values[firstArg()] = secondArg<Long>()
        }
        every { bundle.putInt(any(), any()) } answers {
            values[firstArg()] = secondArg<Int>()
        }
        every { bundle.getString(any()) } answers { values[firstArg()] as? String }
        every { bundle.containsKey(any()) } answers { values.containsKey(firstArg()) }
        every { bundle.getLong(any()) } answers { values[firstArg()] as? Long ?: 0L }
        every { bundle.getInt(any()) } answers { values[firstArg()] as? Int ?: 0 }
        val token = NarImportAttemptToken("same-process", 8, 42)

        bundle.writeNarPickerOwnerToken(token)

        assertEquals(token, bundle.readNarPickerOwnerToken())
        assertEquals("same-process", values["nar_picker_owner_process_nonce"])
        assertEquals(8L, values["nar_picker_owner_sequence"])
        assertEquals(42, values["nar_picker_owner_task_id"])
    }

    @Test
    fun malformedBundleValuesDoNotRestoreAnOwner() {
        val malformed = listOf(
            rawOwner(nonce = null, sequence = 4L, ownerTaskId = 42),
            rawOwner(nonce = "", sequence = 4L, ownerTaskId = 42),
            rawOwner(nonce = "process", sequence = null, ownerTaskId = 42),
            rawOwner(nonce = "process", sequence = 0L, ownerTaskId = 42),
            rawOwner(nonce = "process", sequence = -1L, ownerTaskId = 42),
            rawOwner(nonce = "process", sequence = 4L, ownerTaskId = null),
            rawOwner(nonce = "process", sequence = 4L, ownerTaskId = -1),
            throwingOwner(),
        )

        malformed.forEach { bundle ->
            assertNull(bundle.readNarPickerOwnerToken())
        }
    }

    @Test
    fun sameProcessRecreationKeepsTheExactAwaitingOwner() {
        val token = NarImportAttemptToken("live-process", 4)

        val owner = reconcileNarPickerOwner(
            restored = token,
            state = ForegroundNarImportState.AwaitingSelection(token),
        )

        assertSame(token, owner)
    }

    @Test
    fun deadProcessNonceMismatchDoesNotAbandonAnotherLiveAwaitingAttempt() {
        val restored = NarImportAttemptToken("dead-process", 4)
        val current = NarImportAttemptToken("live-process", 4)
        val coordinator = coordinatorAt(ForegroundNarImportState.AwaitingSelection(current))

        val owner = reconcileNarPickerOwner(
            restored = restored,
            state = coordinator.state.value,
        )

        assertNull(owner)
        assertEquals(ForegroundNarImportState.AwaitingSelection(current), coordinator.state.value)
    }

    @Test
    fun newTaskWithoutRegistryOwnerDoesNotAbandonTheLiveAwaitingSelection() {
        val token = NarImportAttemptToken("live-process", 4)
        val coordinator = coordinatorAt(ForegroundNarImportState.AwaitingSelection(token))

        val owner = reconcileNarPickerOwner(
            restored = null,
            state = coordinator.state.value,
        )

        assertNull(owner)
        assertEquals(ForegroundNarImportState.AwaitingSelection(token), coordinator.state.value)
    }

    @Test
    fun staleOwnerARejectsWithoutAbandoningCurrentAwaitingOwnerB() {
        val stale = NarImportAttemptToken("live-process", 3)
        val current = NarImportAttemptToken("live-process", 4)
        val coordinator = coordinatorAt(ForegroundNarImportState.AwaitingSelection(current))

        val owner = reconcileNarPickerOwner(
            restored = stale,
            state = coordinator.state.value,
        )

        assertNull(owner)
        assertEquals(ForegroundNarImportState.AwaitingSelection(current), coordinator.state.value)
    }

    @Test
    fun restoredOwnerIsRejectedForANonAwaitingState() {
        val restored = NarImportAttemptToken("live-process", 4)

        val owner = reconcileNarPickerOwner(
            restored = restored,
            state = ForegroundNarImportState.Idle,
        )

        assertNull(owner)
    }

    @Test
    fun finalOwnerDestroyAbandonsItsOwnAwaitingSelection() {
        val token = NarImportAttemptToken("live-process", 4)
        val coordinator = coordinatorAt(ForegroundNarImportState.AwaitingSelection(token))

        abandonNarPickerOwnerOnFinalDestroy(
            owner = token,
            isFinishing = true,
            isChangingConfigurations = false,
            abandon = coordinator::abandonPicker,
        )

        assertEquals(ForegroundNarImportState.Idle, coordinator.state.value)
    }

    @Test
    fun configurationDestroyPreservesItsAwaitingSelectionForRestoration() {
        val token = NarImportAttemptToken("live-process", 4)
        val coordinator = coordinatorAt(ForegroundNarImportState.AwaitingSelection(token))

        abandonNarPickerOwnerOnFinalDestroy(
            owner = token,
            isFinishing = false,
            isChangingConfigurations = true,
            abandon = coordinator::abandonPicker,
        )

        assertEquals(ForegroundNarImportState.AwaitingSelection(token), coordinator.state.value)
    }

    @Test
    fun runtimeLaunchExceptionClearsOwnershipAndPublishesReplayableFailure() {
        val coordinator = idleCoordinator()
        var owner: NarImportAttemptToken? = null

        val launched = armAndLaunchNarDocumentPicker(
            coordinator = coordinator,
            ownerTaskId = 42,
            currentOwner = { owner },
            setOwner = { owner = it },
            launch = { throw IllegalStateException("registry unavailable") },
            failureMessage = "The document picker is unavailable.",
        )

        assertFalse(launched)
        assertNull(owner)
        val failed = coordinator.state.value as ForegroundNarImportState.Failed
        assertEquals("The document picker is unavailable.", failed.message)
        assertEquals(NarImportAttemptToken("test-process", 1, 42), failed.token)
    }

    @Test
    fun ownerlessLaunchCannotInferThatADifferentTaskWasRemoved() {
        val stale = NarImportAttemptToken("live-process", 4, 41)
        val coordinator = coordinatorAt(ForegroundNarImportState.AwaitingSelection(stale))
        var owner: NarImportAttemptToken? = null
        var launchCalls = 0

        val launched = armAndLaunchNarDocumentPicker(
            coordinator = coordinator,
            ownerTaskId = 42,
            currentOwner = { owner },
            setOwner = { owner = it },
            launch = { launchCalls += 1 },
            failureMessage = "The document picker is unavailable.",
        )

        assertFalse(launched)
        assertEquals(0, launchCalls)
        assertNull(owner)
        assertEquals(ForegroundNarImportState.AwaitingSelection(stale), coordinator.state.value)
    }

    @Test
    fun explicitOwnerlessLaunchReclaimsLostStateWithinTheSameTask() {
        val stale = NarImportAttemptToken("live-process", 4, 42)
        val coordinator = coordinatorAt(ForegroundNarImportState.AwaitingSelection(stale))
        var owner: NarImportAttemptToken? = null
        var launchCalls = 0

        val launched = armAndLaunchNarDocumentPicker(
            coordinator = coordinator,
            ownerTaskId = 42,
            currentOwner = { owner },
            setOwner = { owner = it },
            launch = { launchCalls += 1 },
            failureMessage = "The document picker is unavailable.",
        )

        assertTrue(launched)
        assertEquals(1, launchCalls)
        assertEquals(42, requireNotNull(owner).ownerTaskId)
    }

    @Test
    fun explicitLaunchFromTheExistingOwnerDoesNotReclaimItsLiveSelection() {
        val token = NarImportAttemptToken("live-process", 4, 42)
        val coordinator = coordinatorAt(ForegroundNarImportState.AwaitingSelection(token))
        var owner: NarImportAttemptToken? = token
        var launchCalls = 0

        val launched = armAndLaunchNarDocumentPicker(
            coordinator = coordinator,
            ownerTaskId = 42,
            currentOwner = { owner },
            setOwner = { owner = it },
            launch = { launchCalls += 1 },
            failureMessage = "The document picker is unavailable.",
        )

        assertFalse(launched)
        assertEquals(0, launchCalls)
        assertEquals(token, owner)
        assertEquals(ForegroundNarImportState.AwaitingSelection(token), coordinator.state.value)
    }

    @Test
    fun ownerlessLaunchCannotReclaimAnotherLiveTasksSelection() {
        val token = NarImportAttemptToken("live-process", 4, 41)
        val coordinator = coordinatorAt(ForegroundNarImportState.AwaitingSelection(token))
        var owner: NarImportAttemptToken? = null
        var launchCalls = 0

        val launched = armAndLaunchNarDocumentPicker(
            coordinator = coordinator,
            ownerTaskId = 42,
            currentOwner = { owner },
            setOwner = { owner = it },
            launch = { launchCalls += 1 },
            failureMessage = "The document picker is unavailable.",
        )

        assertFalse(launched)
        assertEquals(0, launchCalls)
        assertNull(owner)
        assertEquals(ForegroundNarImportState.AwaitingSelection(token), coordinator.state.value)
    }

    @Test
    fun callbackAcquiresAndClearsOwnershipBeforeLazySelectionOrConsume() {
        val token = NarImportAttemptToken("callback-process", 2)
        val expectedSelection = NarDocumentSelection("content://archives/selected.nar", "content")
        var owner: NarImportAttemptToken? = token
        var selectionCalls = 0
        var guardCalls = 0
        var consumeCalls = 0

        val accepted = dispatchNarPickerResult(
            takeOwner = {
                owner.also { owner = null }
            },
            selection = {
                assertNull(owner)
                selectionCalls += 1
                expectedSelection
            },
            importAllowed = {
                assertNull(owner)
                guardCalls += 1
                true
            },
            consume = { expectedToken, selection, importAllowed ->
                assertNull(owner)
                assertEquals(token, expectedToken)
                assertEquals(expectedSelection, selection)
                assertTrue(importAllowed)
                consumeCalls += 1
                true
            },
        )

        assertTrue(accepted)
        assertNull(owner)
        assertEquals(1, selectionCalls)
        assertEquals(1, guardCalls)
        assertEquals(1, consumeCalls)
    }

    @Test
    fun nullOrDeadRestoredOwnerReturnsBeforeLazySelectionGuardOrConsume() {
        val coordinator = idleCoordinator(processNonce = "fresh-process")
        var owner = reconcileNarPickerOwner(
            restored = NarImportAttemptToken("dead-process", 1),
            state = coordinator.state.value,
        )
        var takeCalls = 0

        val accepted = dispatchNarPickerResult(
            takeOwner = {
                takeCalls += 1
                owner.also { owner = null }
            },
            selection = { throw AssertionError("selection conversion must stay lazy") },
            importAllowed = { throw AssertionError("callback guard must not run without an owner") },
            consume = { _, _, _ -> throw AssertionError("coordinator must not be called without an owner") },
        )

        assertFalse(accepted)
        assertEquals(1, takeCalls)
        assertNull(owner)
        assertEquals(ForegroundNarImportState.Idle, coordinator.state.value)
    }

    // Mutation caught: passive mode still arms and launches the external picker.
    @Test
    fun passiveGuardRejectsPickerLaunchBeforeOwnershipOrExternalSideEffects() {
        val coordinator = idleCoordinator()
        var owner: NarImportAttemptToken? = null
        var launchCalls = 0

        val launched = armAndLaunchNarDocumentPicker(
            coordinator = coordinator,
            ownerTaskId = 42,
            currentOwner = { owner },
            setOwner = { owner = it },
            launch = { launchCalls += 1 },
            failureMessage = "unavailable",
            actionAllowed = { false },
        )

        assertFalse(launched)
        assertNull(owner)
        assertEquals(0, launchCalls)
        assertEquals(ForegroundNarImportState.Idle, coordinator.state.value)
    }

    // Mutation caught: a picker opened before passive mode imports its returned document after passive begins.
    @Test
    fun passiveGuardRejectsPickerResultBeforeOwnerSelectionOrImportConsumption() {
        val token = NarImportAttemptToken("callback-process", 3, 42)
        var takeCalls = 0

        val accepted = dispatchNarPickerResult(
            actionAllowed = { false },
            takeOwner = {
                takeCalls += 1
                token
            },
            selection = { throw AssertionError("passive result must not convert the document") },
            importAllowed = { throw AssertionError("passive result must not inspect import storage") },
            consume = { _, _, _ -> throw AssertionError("passive result must not enter import coordination") },
        )

        assertFalse(accepted)
        assertEquals(0, takeCalls)
    }

    // Mutation caught: the callback rereads a false-then-true guard and strands its retained owner.
    @Test
    fun pickerResultCapturesGuardOnceAndAbandonsRejectedOwnerExactlyOnce() {
        val coordinator = idleCoordinator(processNonce = "callback-process")
        val token = requireNotNull(coordinator.armPicker(42))
        var owner: NarImportAttemptToken? = token
        var guardCalls = 0
        var abandonCalls = 0

        val accepted = handleNarPickerResult(
            actionAllowed = {
                guardCalls += 1
                guardCalls > 1
            },
            takeOwner = { owner.also { owner = null } },
            abandon = {
                abandonCalls += 1
                coordinator.abandonPicker(it)
            },
            selection = { throw AssertionError("rejected result must not convert its URI") },
            importAllowed = { throw AssertionError("rejected result must not inspect storage") },
            consume = { _, _, _ -> throw AssertionError("rejected result must not consume an import") },
        )

        assertFalse(accepted)
        assertEquals(1, guardCalls)
        assertEquals(1, abandonCalls)
        assertNull(owner)
        assertEquals(ForegroundNarImportState.Idle, coordinator.state.value)
        assertTrue(coordinator.armPicker(42) != null)
    }

    private fun rawOwner(nonce: String?, sequence: Long?, ownerTaskId: Int?): Bundle = mockk<Bundle>().also { bundle ->
        every { bundle.getString("nar_picker_owner_process_nonce") } returns nonce
        every { bundle.containsKey("nar_picker_owner_sequence") } returns (sequence != null)
        every { bundle.getLong("nar_picker_owner_sequence") } returns (sequence ?: 0L)
        every { bundle.containsKey("nar_picker_owner_task_id") } returns (ownerTaskId != null)
        every { bundle.getInt("nar_picker_owner_task_id") } returns (ownerTaskId ?: 0)
    }

    private fun throwingOwner(): Bundle = mockk<Bundle>().also { bundle ->
        every { bundle.getString("nar_picker_owner_process_nonce") } throws ClassCastException("malformed")
    }

    private fun coordinatorAt(
        state: ForegroundNarImportState.AwaitingSelection,
    ): ForegroundNarImportCoordinator {
        val coordinator = idleCoordinator(state.token.processNonce)
        repeat((state.token.sequence - 1L).toInt()) {
            val prior = requireNotNull(coordinator.armPicker())
            assertTrue(coordinator.abandonPicker(prior))
        }
        val armed = requireNotNull(coordinator.armPicker(state.token.ownerTaskId))
        assertEquals(state.token.sequence, armed.sequence)
        return coordinator
    }

    private fun idleCoordinator(processNonce: String = "test-process"): ForegroundNarImportCoordinator {
        val dispatcher = QueuedDispatcher()
        return ForegroundNarImportCoordinator(
            backend = CleanBackend,
            dispatcher = dispatcher,
            processNonce = processNonce,
        ).also {
            dispatcher.runNext()
            assertEquals(ForegroundNarImportState.Idle, it.state.value)
        }
    }

    private data object CleanBackend : ForegroundNarImportBackend {
        override fun recoverOwnedStaging() = NarImportRecoveryResult.Clean

        override fun importDocument(
            selection: NarDocumentSelection,
            isCancelled: () -> Boolean,
            onInstallingProgress: (phase: String, completed: Long) -> Unit,
        ) = ArchiveInstallResult.Cancelled
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runNext() = requireNotNull(tasks.removeFirstOrNull()).run()
    }
}
