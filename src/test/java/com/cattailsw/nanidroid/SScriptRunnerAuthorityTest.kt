package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.runtime.MonotonicClock
import java.lang.reflect.Modifier
import org.junit.Assert
import org.junit.Rule
import org.junit.Test

class SScriptRunnerAuthorityTest {
    @Rule
    @JvmField
    val androidStubs = HostAndroidStubRule()

    @Test
    fun runnersCannotConsumeOrClearEachOthersQueuedScripts() = RuntimeFixture(
        id = "first",
        autoStart = false,
    ).use { firstFixture ->
        RuntimeFixture(id = "second", autoStart = false).use { secondFixture ->
            val first = firstFixture.runner.apply { setNoWaitMode(true) }
            val second = secondFixture.runner.apply { setNoWaitMode(true) }

            first.addMsgToQueue(arrayOf("\\0first\\e"))
            Assert.assertTrue(first.runtimeModeSnapshot().playingTalk)

            second.run()
            Assert.assertTrue(first.runtimeModeSnapshot().playingTalk)
            Assert.assertFalse(second.runtimeModeSnapshot().playingTalk)

            first.clearMsgQueue()
            first.addMsgToQueue(arrayOf("\\0still-first\\e"))
            second.clearMsgQueue()
            Assert.assertTrue(first.runtimeModeSnapshot().playingTalk)
            Assert.assertFalse(second.runtimeModeSnapshot().playingTalk)

            first.clearMsgQueue()
        }
    }

    @Test
    fun scheduledPlaybackCallbacksRemainOwnedByTheirCreatingRunner() {
        val firstScheduler = ManualRuntimeScheduler()
        val secondScheduler = ManualRuntimeScheduler()
        RuntimeFixture(
            id = "first-scheduler",
            runnerConfiguration = SScriptRunnerConfiguration(
                monotonicClock = MonotonicClock { 10_000L },
                playbackSchedulerFactory = { firstScheduler },
            ),
        ).use { firstFixture ->
            RuntimeFixture(
                id = "second-scheduler",
                runnerConfiguration = SScriptRunnerConfiguration(
                    monotonicClock = MonotonicClock { 10_000L },
                    playbackSchedulerFactory = { secondScheduler },
                ),
            ).use { secondFixture ->
                firstFixture.runner.addMsgToQueue(arrayOf("\\hfirst\\e"))
                firstFixture.runner.run()

                Assert.assertEquals(listOf(0L), firstScheduler.delays())
                Assert.assertTrue(secondScheduler.delays().isEmpty())

                // Clearing another runtime must not cancel first's playback callback.
                secondFixture.runner.clearMsgQueue()
                firstScheduler.runAll()

                Assert.assertFalse(firstFixture.runner.runtimeModeSnapshot().playingTalk)
                Assert.assertTrue(secondScheduler.delays().isEmpty())
            }
        }
    }

    @Test
    fun runnerHasNoStaticMutableSessionOrQueueAuthority() {
        val runnerFields = SScriptRunner::class.java.declaredFields.associateBy { it.name }
        Assert.assertFalse(runnerFields.containsKey("self"))
        Assert.assertFalse(runnerFields.containsKey("productionSessionCoordinator"))
        Assert.assertTrue(runnerFields.containsKey("runtimePort"))
        Assert.assertFalse(Modifier.isStatic(requireNotNull(runnerFields["runtimePort"]).modifiers))
        Assert.assertFalse(Modifier.isStatic(requireNotNull(runnerFields["msgQueue"]).modifiers))

        val forbiddenMethods = setOf(
            "getInstance",
            "beginGhostConstruction",
            "reserveGhostForAttachment",
            "reuseActiveGhost",
            "resetInstanceForTesting",
        )
        Assert.assertTrue(
            SScriptRunner::class.java.declaredMethods.none {
                it.name.substringBefore('$') in forbiddenMethods
            },
        )
        Assert.assertTrue(
            SScriptRunner.Companion::class.java.declaredMethods.none {
                it.name.substringBefore('$') in forbiddenMethods
            },
        )
        Assert.assertTrue(
            SScriptRunner::class.java.declaredConstructors
                .filterNot { it.isSynthetic }
                .all { GhostRuntime::class.java in it.parameterTypes },
        )
    }
}
