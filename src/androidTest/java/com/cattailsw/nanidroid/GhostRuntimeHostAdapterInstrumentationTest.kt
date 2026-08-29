package com.cattailsw.nanidroid

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeHostId
import com.cattailsw.nanidroid.runtime.RuntimeHostLease
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device proof for the snapshot runtime's future Activity host adapter. */
@RunWith(AndroidJUnit4::class)
class GhostRuntimeHostAdapterInstrumentationTest {
    private var harness: HostAdapterHarness? = null

    @After
    fun tearDown() {
        harness?.close()
        GhostRuntimeHostTestEnvironment.harness = null
    }

    @Test
    fun lifecycleCommandsUseOneHostIdIncreasingEpochsAndMainLoopSubmission() {
        val fixture = installHarness()
        ActivityScenario.launch(GhostRuntimeHostTestActivity::class.java).use { scenario ->
            val first = scenario.hostRecord(fixture)
            fixture.awaitForeground(first.hostId)

            scenario.recreate()

            val recreated = scenario.hostRecord(fixture)
            fixture.awaitForeground(recreated.hostId)
            assertNotEquals(first.hostId, recreated.hostId)
            fixture.await { fixture.submissionsFor(first.hostId).any { it.command == "UnregisterHost" } }
            assertStrictlyIncreasing(fixture.submissionsFor(first.hostId).map { it.epoch })
            assertStrictlyIncreasing(fixture.submissionsFor(recreated.hostId).map { it.epoch })
            assertTrue(fixture.submissions.all { it.mainThread })
        }
    }

    @Test
    fun overlappingActivitiesKeepOldStartedButOnlyNewHostPlaysAndAcknowledgesCues() {
        val fixture = installHarness(autoAcknowledgeCues = true)
        fixture.startAttached()
        fixture.pauseFutureCollectors()
        ActivityScenario.launch(GhostRuntimeHostTestActivity::class.java).use { oldScenario ->
            val old = oldScenario.hostRecord(fixture)
            fixture.awaitForeground(old.hostId)
            oldScenario.onActivity { it.launchOverlappingHostForTesting() }
            val replacement = fixture.awaitReplacement(old.hostId)
            fixture.awaitForeground(replacement.hostId)
            assertTrue(oldScenario.state.isAtLeast(Lifecycle.State.STARTED))

            fixture.enqueueAndAdvance("\\i[1]\\i[2]\\e", cueCount = 2)
            val queued = fixture.runtime.snapshots.value.cues
            assertEquals(2, queued.size)
            assertTrue(queued.all { it.hostLease.hostId == replacement.hostId })

            fixture.releaseCollectors()
            fixture.await { fixture.acknowledgedThrough[replacement.hostId] == queued.last().cueId }
            fixture.await { fixture.runtime.snapshots.value.cues.isEmpty() }
            assertTrue(fixture.playedCues[old.hostId].orEmpty().isEmpty())
            assertEquals(queued.map { it.cueId }, fixture.playedCues[replacement.hostId].orEmpty())
            assertFalse(fixture.acknowledgedThrough.containsKey(old.hostId))
        }
    }

    @Test
    fun stoppedCollectorDoesNotRenderUntilStartedAgain() {
        val fixture = installHarness()
        ActivityScenario.launch(GhostRuntimeHostTestActivity::class.java).use { scenario ->
            val record = scenario.hostRecord(fixture)
            fixture.awaitForeground(record.hostId)
            fixture.await { fixture.renderedRevisions[record.hostId].orEmpty().isNotEmpty() }

            scenario.moveToState(Lifecycle.State.CREATED)
            fixture.await { fixture.lifecycleTrace.contains("onStop") }
            val stoppedCount = fixture.renderedRevisions[record.hostId].orEmpty().size
            fixture.hostRuntime.submit(
                RuntimeCommand.RegisterHost(RuntimeHostLease(RuntimeHostId(9_000L), 1L)),
            )
            Thread.sleep(250L)
            assertEquals(stoppedCount, fixture.renderedRevisions[record.hostId].orEmpty().size)

            scenario.moveToState(Lifecycle.State.STARTED)
            fixture.await { fixture.renderedRevisions[record.hostId].orEmpty().size > stoppedCount }
        }
    }

    @Test
    fun staleSameHostEpochSnapshotCannotPlayAcknowledgeOrDeliverExit() {
        val fixture = installHarness(autoAcknowledgeCues = true, autoDeliverExit = true)
        fixture.startAttached()
        ActivityScenario.launch(GhostRuntimeHostTestActivity::class.java).use { scenario ->
            val record = scenario.hostRecord(fixture)
            fixture.awaitForeground(record.hostId)

            fixture.blockNextDelivery { it.cues.isNotEmpty() }
            fixture.enqueueAndAdvance("\\i[stale]\\e", cueCount = 1)
            val staleCueSnapshot = fixture.awaitBlockedDelivery()
            assertTrue(staleCueSnapshot.cues.isNotEmpty())
            scenario.onActivity { it.setTopResumedForTesting(false) }
            fixture.await { fixture.runtime.snapshots.value.foregroundHost == null }
            fixture.await { fixture.runtime.snapshots.value.cues.isEmpty() }
            val cueRevocation = fixture.submissionsFor(record.hostId).last()
            assertEquals(record.hostId, staleCueSnapshot.foregroundHost?.hostId)
            assertTrue(cueRevocation.epoch > requireNotNull(staleCueSnapshot.foregroundHost).hostEpoch)
            fixture.releaseBlockedDelivery()
            fixture.awaitDeliveryResumed()

            assertTrue(fixture.playedCues[record.hostId].orEmpty().isEmpty())
            assertFalse(fixture.acknowledgedThrough.containsKey(record.hostId))

            fixture.advanceUntilTalkStops()
            scenario.onActivity { it.setTopResumedForTesting(true) }
            fixture.awaitForeground(record.hostId)
            fixture.blockNextDelivery { it.exit?.offeredLease != null }
            scenario.onActivity { it.requestBackForTesting() }
            val staleExitSnapshot = fixture.awaitBlockedDelivery()
            assertNotEquals(null, staleExitSnapshot.exit?.offeredLease)
            scenario.onActivity { it.setTopResumedForTesting(false) }
            fixture.await { fixture.runtime.snapshots.value.foregroundHost == null }
            fixture.await { fixture.runtime.snapshots.value.exit?.offeredLease == null }
            val exitRevocation = fixture.submissionsFor(record.hostId).last()
            val staleExitHost = requireNotNull(staleExitSnapshot.exit?.offeredLease).hostLease
            assertEquals(record.hostId, staleExitHost.hostId)
            assertTrue(exitRevocation.epoch > staleExitHost.hostEpoch)
            fixture.releaseBlockedDelivery()
            fixture.awaitDeliveryResumed()

            assertEquals(0L, record.finishCount.get())
            assertTrue(fixture.deliveryTrace.none { it in setOf("claim", "finish", "acknowledge") })
        }
    }

    @Test
    fun expiredOldHostCueCannotAliasReplacementHostCue() {
        val fixture = installHarness(autoAcknowledgeCues = false)
        fixture.startAttached()
        ActivityScenario.launch(GhostRuntimeHostTestActivity::class.java).use { oldScenario ->
            val old = oldScenario.hostRecord(fixture)
            fixture.awaitForeground(old.hostId)
            fixture.enqueueAndAdvance("\\i[old]\\e", cueCount = 1)
            val oldCue = fixture.runtime.snapshots.value.cues.single()
            assertEquals(
                fixture.runtime.snapshots.value.presentation.sakura.surfaceId,
                oldCue.target.surfaceId,
            )
            assertEquals(
                fixture.runtime.snapshots.value.presentation.sakura.surfaceEpoch,
                oldCue.target.surfaceEpoch,
            )

            oldScenario.onActivity { it.launchOverlappingHostForTesting() }
            val replacement = fixture.awaitReplacement(old.hostId)
            fixture.awaitForeground(replacement.hostId)
            fixture.await { fixture.runtime.snapshots.value.cues.isEmpty() }
            fixture.advanceUntilTalkStops()
            fixture.enqueueAndAdvance("\\i[new]\\e", cueCount = 1)
            val replacementCue = fixture.runtime.snapshots.value.cues.single()
            assertTrue(replacementCue.cueId > oldCue.cueId)
            assertEquals(replacement.hostId, replacementCue.hostLease.hostId)

            fixture.hostRuntime.submit(RuntimeCommand.AcknowledgeCues(oldCue.hostLease, oldCue.cueId))
            Thread.sleep(100L)
            assertEquals(replacementCue, fixture.runtime.snapshots.value.cues.single())
            assertTrue(fixture.playedCues[replacement.hostId].orEmpty().none { it == oldCue.cueId })
        }
    }

    @Test
    fun sixtyFiveHostlessCuesAdvanceWithoutInventoryOrBackpressure() {
        val fixture = installHarness(autoAcknowledgeCues = false)
        fixture.startAttached()
        fixture.enqueueAndAdvance(
            buildString {
                repeat(65) { append("\\i[").append(it).append(']') }
                append("\\hFINAL\\e")
            },
            cueCount = 65,
        )

        assertTrue(fixture.runtime.snapshots.value.cues.isEmpty())
        fixture.advanceUntilPresentationText("FINAL")
        assertEquals("FINAL", fixture.runtime.snapshots.value.presentation.sakura.text)
        fixture.advanceUntilTalkStops()
        assertFalse(fixture.runtime.snapshots.value.mode.playingTalk)
    }

    @Test
    fun exitDeliveryClaimsFinishesAcknowledgesBeforeLifecycleRevocationAndDoesNotFinishLaterHost() {
        val fixture = installHarness(autoDeliverExit = true)
        ActivityScenario.launch(GhostRuntimeHostTestActivity::class.java).use { exitingScenario ->
            val exiting = exitingScenario.hostRecord(fixture)
            fixture.awaitForeground(exiting.hostId)
            exitingScenario.onActivity { it.requestBackForTesting() }
            fixture.await { fixture.lifecycleTrace.contains("onStop") }
            fixture.await { fixture.runtime.snapshots.value.exit == null }

            assertEquals(
                listOf("claim", "finish", "acknowledge", "topResumedFalse", "onPause", "onStop"),
                fixture.deliveryTrace.toList(),
            )
        }

        ActivityScenario.launch(GhostRuntimeHostTestActivity::class.java).use { laterScenario ->
            val later = laterScenario.hostRecord(fixture)
            fixture.awaitForeground(later.hostId)
            assertEquals(0L, later.finishCount.get())
            assertEquals(null, fixture.runtime.snapshots.value.exit)
        }
    }

    private fun installHarness(
        autoAcknowledgeCues: Boolean = true,
        autoDeliverExit: Boolean = false,
    ): HostAdapterHarness = HostAdapterHarness(autoAcknowledgeCues, autoDeliverExit).also {
        harness = it
        GhostRuntimeHostTestEnvironment.harness = it
    }

    private fun ActivityScenario<GhostRuntimeHostTestActivity>.hostRecord(
        fixture: HostAdapterHarness,
    ): ActivityRecord {
        var record: ActivityRecord? = null
        onActivity { record = fixture.recordFor(it) }
        return requireNotNull(record)
    }

    private fun assertStrictlyIncreasing(values: List<Long>) {
        assertTrue("expected multiple lifecycle submissions: $values", values.size >= 2)
        assertTrue("host epochs were not strictly increasing: $values", values.zipWithNext().all { (a, b) -> a < b })
    }
}
