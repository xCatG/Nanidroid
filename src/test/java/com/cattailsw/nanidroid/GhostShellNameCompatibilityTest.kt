package com.cattailsw.nanidroid

import org.junit.Rule
import org.junit.Test

/** Guards the legacy distinction between a missing shell descriptor and a missing name.  */
class GhostShellNameCompatibilityTest {
    @Rule
    @JvmField
    val androidStubs: HostAndroidStubRule = HostAndroidStubRule()

    @Test
    fun missingNameInParsedShellDescriptorRemainsNull() {
        val ghost = TestGhost()
        ghost.setShellDescription(mutableMapOf<String, String>())

        org.junit.Assert.assertEquals(null, ghost.getShellName())
    }

    @Test
    fun unavailableShellDescriptorFallsBackToMaster() {
        val ghost = TestGhost()

        org.junit.Assert.assertEquals("master", ghost.getShellName())
    }

    private class TestGhost : Ghost("shell-name-contract") {
        protected override fun loadGhostInfo() {
            // The test supplies its descriptor state without filesystem or SHIORI setup.
        }

        protected override fun incrementCreateCount() {
            // The test has no persistent creation count.
        }

        fun setShellDescription(description: MutableMap<String, String>?) {
            shellDesc = description
        }
    }
}
