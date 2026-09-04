package com.cattailsw.nanidroid

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Guards the legacy distinction between a missing shell descriptor and a missing name. */
class GhostShellNameCompatibilityTest {
    @Rule
    @JvmField
    val androidStubs: HostAndroidStubRule = HostAndroidStubRule()

    @Test
    fun missingNameInParsedShellDescriptorRemainsNull() = fixture(shellName = null).use { fixture ->
        assertEquals(null, fixture.requireHandle().ghost.shellName)
    }

    @Test
    fun unavailableShellDescriptorFallsBackToMaster() = fixture(shellName = "master").use { fixture ->
        assertEquals("master", fixture.requireHandle().ghost.shellName)
    }

    private fun fixture(shellName: String?) = RuntimeFixture(
        autoAttach = false,
        preparedFactory = { operationId, ghostId, root ->
            preparedGhost(operationId, ghostId, root, shellName = shellName)
        },
    )
}
