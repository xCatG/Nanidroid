package com.cattailsw.nanidroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveIntentIngressGuardTest {
    @Test
    fun recreatedActivityUsesRetainedPassiveRunnerToRejectArchiveIngress() {
        val retainedRunner = passiveRunner()

        assertFalse(allowsArchiveIntentIngress(null) { retainedRunner })
    }

    @Test
    fun recreatedActivityUsesRetainedActiveRunnerToAcceptArchiveIngress() {
        val retainedRunner = SScriptRunner(null)

        assertTrue(allowsArchiveIntentIngress(null) { retainedRunner })
    }

    private fun passiveRunner(): SScriptRunner = SScriptRunner(null).also { runner ->
        runner.setNoWaitMode(true)
        runner.addMsgToQueue(arrayOf("\\![enter,passivemode]\\e"))
        runner.run()
    }
}
