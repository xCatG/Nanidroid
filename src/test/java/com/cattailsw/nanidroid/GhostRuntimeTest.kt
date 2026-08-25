package com.cattailsw.nanidroid

import java.io.File
import org.junit.Assert
import org.junit.Rule
import org.junit.Test

class GhostRuntimeTest {
    @Rule
    @JvmField
    val androidStubs = HostAndroidStubRule()

    @Test
    fun reservationCanOnlyBeConsumedByItsRuntimeRunner() {
        val owner = GhostRuntime(null)
        val other = GhostRuntime(null)
        val root = File("build/ghost-runtime-test/owner").canonicalFile
        val reservation = owner.beginGhostConstruction(root.name, root)
            .bind(FakeGhost(root))

        Assert.assertFalse(other.runner.attachReservedGhost(reservation))
        Assert.assertTrue(owner.runner.abandonReservedGhost(reservation))
    }

    @Test
    fun explicitRuntimesHaveIndependentRunnerAndCoordinatorAuthority() {
        val first = GhostRuntime(null)
        val second = GhostRuntime(null)
        val firstRoot = File("build/ghost-runtime-test/first").canonicalFile
        val secondRoot = File("build/ghost-runtime-test/second").canonicalFile

        val firstConstruction = first.beginGhostConstruction(firstRoot.name, firstRoot)
        val secondConstruction = second.beginGhostConstruction(secondRoot.name, secondRoot)

        Assert.assertNotSame(first.runner, second.runner)
        firstConstruction.failConstruction()
        secondConstruction.failConstruction()
    }

    private class FakeGhost(root: File) : Ghost(root.path) {
        override fun loadGhostInfo() = Unit
        override fun incrementCreateCount() = Unit
        override fun getCreateCount(): Long = 0L
        override fun unload() = Unit
    }
}
