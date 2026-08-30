package com.cattailsw.nanidroid

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeHostLease
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device proof through the real application-owned runtime and production Activity host. */
@RunWith(AndroidJUnit4::class)
class NanidroidLifecycleInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application
        get() = instrumentation.targetContext.applicationContext as CatTailApplication
    private val runtime
        get() = application.ghostRuntime

    @Test
    fun productionEntryUsesApplicationRuntimeMainThreadAndExactIncreasingLeasesAcrossRecreation() {
        ActivityScenario.launch(Nanidroid::class.java).use { scenario ->
            var first: RuntimeHostLease? = null
            scenario.onActivity { activity ->
                assertSame(application, activity.application)
                assertSame(Looper.getMainLooper(), Looper.myLooper())
                first = activity.hostLeaseForTesting()
            }
            await { runtime.snapshots.value.foregroundHost == first }

            var expired: RuntimeHostLease? = null
            scenario.onActivity { activity ->
                activity.onTopResumedActivityChanged(false)
                expired = activity.hostLeaseForTesting()
            }
            await { runtime.snapshots.value.foregroundHost == null }
            assertEquals(first?.hostId, expired?.hostId)
            assertTrue(requireNotNull(expired).hostEpoch > requireNotNull(first).hostEpoch)

            var resumed: RuntimeHostLease? = null
            scenario.onActivity { activity ->
                activity.onTopResumedActivityChanged(true)
                resumed = activity.hostLeaseForTesting()
            }
            await { runtime.snapshots.value.foregroundHost == resumed }
            assertEquals(first?.hostId, resumed?.hostId)
            assertTrue(requireNotNull(resumed).hostEpoch > requireNotNull(expired).hostEpoch)

            scenario.recreate()
            var recreated: RuntimeHostLease? = null
            scenario.onActivity { activity -> recreated = activity.hostLeaseForTesting() }
            await { runtime.snapshots.value.foregroundHost == recreated }

            assertNotEquals(first?.hostId, recreated?.hostId)
            assertTrue(requireNotNull(recreated).hostEpoch > 0L)
            await {
                var collected = false
                scenario.onActivity { activity -> collected = activity.snapshotForTesting().foregroundHost == recreated }
                collected
            }
        }
    }

    @Test
    fun productionStartedCollectorExpiresOldLeaseAndComposeAcknowledgesOnlyExactCueLease() {
        ActivityScenario.launch(Nanidroid::class.java).use { scenario ->
            awaitAttachedAndIdle()
            var activity: Nanidroid? = null
            scenario.onActivity { activity = it }

            scenario.moveToState(Lifecycle.State.CREATED)
            await { runtime.snapshots.value.foregroundHost == null }
            var staleLease: RuntimeHostLease? = null
            instrumentation.runOnMainSync { staleLease = requireNotNull(activity).hostLeaseForTesting() }

            runtime.enqueueScriptForTesting(
                buildString {
                    repeat(65) { append("\\i[").append(it).append(']') }
                    append("\\hHOSTLESS_FINAL\\e")
                },
            )
            await(30_000L) {
                runtime.snapshots.value.presentation.sakura.text.contains("HOSTLESS_FINAL") &&
                    runtime.snapshots.value.cues.isEmpty()
            }

            scenario.moveToState(Lifecycle.State.RESUMED)
            var exactLease: RuntimeHostLease? = null
            scenario.onActivity { exactLease = it.hostLeaseForTesting() }
            await { runtime.snapshots.value.foregroundHost == exactLease }
            await { !runtime.snapshots.value.mode.playingTalk }

            val mainEntered = CountDownLatch(1)
            val releaseMain = CountDownLatch(1)
            val blocker = Thread {
                instrumentation.runOnMainSync {
                    mainEntered.countDown()
                    check(releaseMain.await(10L, TimeUnit.SECONDS))
                }
            }
            blocker.start()
            assertTrue(mainEntered.await(5L, TimeUnit.SECONDS))
            try {
                runtime.enqueueScriptForTesting("\\i[exact-lease]\\w9\\e")
                await { runtime.snapshots.value.cues.isNotEmpty() }
                val cue = runtime.snapshots.value.cues.single()
                assertEquals(exactLease, cue.hostLease)
                val beforeStaleAck = runtime.snapshots.value.revision

                runtime.submit(RuntimeCommand.AcknowledgeCues(requireNotNull(staleLease), cue.cueId))
                await { runtime.snapshots.value.revision > beforeStaleAck }
                assertEquals(cue, runtime.snapshots.value.cues.single())
            } finally {
                releaseMain.countDown()
                blocker.join(5_000L)
            }

            await { runtime.snapshots.value.cues.isEmpty() }
            assertTrue(runtime.snapshotCommandTraceForTesting().contains("AcknowledgeCues"))
        }
    }

    @Test
    fun blockedProductionBackSurvivesHostLossThenClaimsFinishesAndAcknowledgesExactNewLease() {
        ActivityScenario.launch(Nanidroid::class.java).use { scenario ->
            awaitAttachedAndIdle()
            var exiting: Nanidroid? = null
            scenario.onActivity { exiting = it }
            val nativeEntered = CountDownLatch(1)
            val releaseNative = CountDownLatch(1)
            runtime.blockSnapshotNativeLaneForTesting {
                nativeEntered.countDown()
                check(releaseNative.await(10L, TimeUnit.SECONDS))
            }
            assertTrue(nativeEntered.await(5L, TimeUnit.SECONDS))

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await { runtime.snapshotCommandTraceForTesting().lastOrNull() == "Back" }
            scenario.moveToState(Lifecycle.State.CREATED)
            await { runtime.snapshots.value.foregroundHost == null }
            releaseNative.countDown()
            await { runtime.snapshots.value.exit != null }
            assertEquals(null, runtime.snapshots.value.exit?.offeredLease)
            assertFalse(requireNotNull(exiting).isFinishing)

            scenario.moveToState(Lifecycle.State.RESUMED)
            await { requireNotNull(exiting).lifecycleTraceForTesting().contains("claim") }
            await {
                requireNotNull(exiting).lifecycleTraceForTesting().takeLast(3) ==
                    listOf("topResumedFalse", "onPause", "onStop")
            }
            assertEquals(
                listOf("claim", "finish", "acknowledge", "topResumedFalse", "onPause", "onStop"),
                requireNotNull(exiting).lifecycleTraceForTesting().filter {
                    it in setOf("claim", "finish", "acknowledge", "topResumedFalse", "onPause", "onStop")
                }.takeLast(6),
            )
            await { runtime.snapshots.value.exit == null }
        }

        ActivityScenario.launch(Nanidroid::class.java).use { later ->
            var laterHost: RuntimeHostLease? = null
            later.onActivity { activity ->
                laterHost = activity.hostLeaseForTesting()
                assertFalse(activity.isFinishing)
            }
            await { runtime.snapshots.value.foregroundHost == laterHost }
            assertEquals(null, runtime.snapshots.value.exit)
        }
    }

    private fun awaitAttachedAndIdle() {
        await(60_000L) { runtime.snapshots.value.phase == GhostRuntimePhase.Attached }
        await(30_000L) { !runtime.snapshots.value.mode.playingTalk }
    }

    private fun await(timeoutMillis: Long = 10_000L, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!predicate()) {
            if (System.nanoTime() >= deadline) {
                throw AssertionError("production runtime condition did not settle: ${runtime.snapshots.value}")
            }
            Thread.sleep(10L)
        }
    }
}
