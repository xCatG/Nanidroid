package com.cattailsw.nanidroid

import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Modifier

class SScriptRunnerAuthorityTest {
    @Rule
    @JvmField
    val androidStubs = HostAndroidStubRule()

    @Test
    fun runnersCannotConsumeOrClearEachOthersQueuedScripts() {
        val first = SScriptRunner(null, GhostSessionCoordinator()).apply {
            setNoWaitMode(true)
        }
        val second = SScriptRunner(null, GhostSessionCoordinator()).apply {
            setNoWaitMode(true)
        }

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

    @Test
    fun runnerHasNoStaticMutableSessionOrQueueAuthority() {
        val runnerFields = SScriptRunner::class.java.declaredFields.associateBy { it.name }
        Assert.assertFalse(runnerFields.containsKey("self"))
        Assert.assertFalse(runnerFields.containsKey("productionSessionCoordinator"))
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
                .all { GhostSessionCoordinator::class.java in it.parameterTypes },
        )
    }
}
