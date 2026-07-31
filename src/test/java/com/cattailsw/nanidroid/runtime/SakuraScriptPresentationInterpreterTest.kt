package com.cattailsw.nanidroid.runtime

import org.junit.Assert
import org.junit.Test

/** Characterizes the visible-frame order for the Kotlin presentation subset.  */
class SakuraScriptPresentationInterpreterTest {
    @Test
    fun requiredMigrationInvariant_textSurfaceAnimationAndStopFramesMatchLegacyTrace() {
        val frames = SakuraScriptPresentationInterpreter.interpret(
            "\\hA\\s[120]\\i[3]\\uB\\s[11]\\i[4]\\e"
        )

        Assert.assertEquals(
            listOf(
                "A:0:null::10:null",
                "A:120:null::10:null",
                "A:120:3::10:null",
                "A:120:null:B:10:null",
                "A:120:null:B:11:null",
                "A:120:null:B:11:4",
                "A:120:null:B:11:null",
                ":120:null::11:null"
            ),
            trace(frames)
        )
    }

    @Test
    fun requiredMigrationInvariant_repeatedSpeakerAndNewlineCommandsKeepVisibleText() {
        val frames = SakuraScriptPresentationInterpreter.interpret(
            "\\hA\\hB\\n[half]C\\e"
        )

        Assert.assertEquals("A", frames[0].sakura.text)
        Assert.assertEquals("AB", frames[1].sakura.text)
        Assert.assertEquals("AB\n", frames[2].sakura.text)
        Assert.assertEquals("AB\nC", frames[3].sakura.text)
    }

    companion object {
        private fun trace(frames: List<GhostPresentationState>): List<String> = frames.map { frame ->
            frame.sakura.text + ":" +
                frame.sakura.surfaceId + ":" +
                frame.sakura.animationId + ":" +
                frame.kero.text + ":" +
                frame.kero.surfaceId + ":" +
                frame.kero.animationId
        }
    }
}