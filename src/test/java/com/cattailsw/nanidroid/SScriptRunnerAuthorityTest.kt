package com.cattailsw.nanidroid

import org.junit.Assert
import org.junit.Rule
import org.junit.Test

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
}
