package com.cattailsw.nanidroid.compose

import com.cattailsw.nanidroid.runtime.GhostSpeaker
import com.cattailsw.nanidroid.runtime.RuntimeSnapshot
import com.cattailsw.nanidroid.runtime.RuntimeSpeakerPresentation
import com.cattailsw.nanidroid.runtime.RuntimeSurfaceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposeCueTargetTest {
    @Test
    fun delayedCueCannotSelectReplacementOrReusedSurfaceEpoch() {
        val target = RuntimeSurfaceIdentity(7L, GhostSpeaker.SAKURA, "12", 4L)
        val replacement = snapshot(generation = 7L, surfaceId = "13", surfaceEpoch = 5L)
        val reused = snapshot(generation = 7L, surfaceId = "12", surfaceEpoch = 6L)

        assertNull(replacement.currentPresentation(target))
        assertNull(reused.currentPresentation(target))
    }

    @Test
    fun exactCurrentCueTargetResolvesItsSpeakerPresentation() {
        val target = RuntimeSurfaceIdentity(7L, GhostSpeaker.SAKURA, "12", 4L)

        assertEquals("12", snapshot(7L, "12", 4L).currentPresentation(target)?.surfaceId)
    }

    private fun snapshot(
        generation: Long,
        surfaceId: String,
        surfaceEpoch: Long,
    ): RuntimeSnapshot = RuntimeSnapshot.initial().copy(
        generation = generation,
        presentation = RuntimeSnapshot.initial().presentation.copy(
            sakura = RuntimeSpeakerPresentation("", surfaceId, surfaceEpoch, true),
        ),
    )
}
