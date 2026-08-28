package com.cattailsw.nanidroid

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GhostSwitchRequestTest {
    @get:Rule
    val androidStubs = HostAndroidStubRule()

    @Test
    fun constructingAStaleGhostDoesNotConsumeItsFirstBootCount() = RuntimeFixture(
        autoAttach = false,
    ).use { fixture ->
        assertTrue(fixture.persistence.activationWrites.isEmpty())

        runBlocking {
            assertIs<RuntimeResult.Success<AttachmentReceipt>>(
                fixture.runtime.attachHost(fixture.requireHandle().generation),
            )
        }

        assertEquals(listOf(fixture.id to 1L), fixture.persistence.activationWrites)
    }

    @Test
    fun activationPersistsItsCountThroughTheDeferredPath() = RuntimeFixture(
        autoAttach = false,
    ).use { fixture ->
        runBlocking {
            assertIs<RuntimeResult.Success<AttachmentReceipt>>(
                fixture.runtime.attachHost(fixture.requireHandle().generation),
            )
        }

        assertEquals(1L, fixture.persistence.activationCounts[fixture.id])
    }
}
