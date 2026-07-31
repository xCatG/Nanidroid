package com.cattailsw.nanidroid

import org.junit.Assert
import org.junit.Test

/** Characterizes the UI-free runtime-to-renderer presentation trace.  */
class SScriptRunnerPresentationTest {
    @Test
    fun emitsTextSurfaceAndOneShotAnimationFramesWithoutAndroidViews() {
        val frames: MutableList<String> = ArrayList<String>()
        val runner: com.cattailsw.nanidroid.SScriptRunner =
            com.cattailsw.nanidroid.SScriptRunner(null)
        runner.setNoWaitMode(true)
        // Production installs the Compose-backed adapter through this seam;
        // the runtime trace must remain independent of the chosen UI toolkit.
        runner.setPresentationRenderer(object :
            com.cattailsw.nanidroid.GhostPresentationRenderer {
            public override fun render(frame: com.cattailsw.nanidroid.GhostPresentationFrame) {
                frames.add(
                    frame.sakura.text + ":" + frame.sakura.surfaceId + ":" +
                            frame.sakura.animationId + ":" + frame.kero.text + ":" +
                            frame.kero.surfaceId + ":" + frame.kero.animationId
                )
            }
        })

        runner.addMsgToQueue(arrayOf<String>("\\hA\\s[120]\\i[3]\\uB\\s[11]\\i[4]\\e"))
        runner.run()

        Assert.assertEquals(
            mutableListOf<String>(
                "A:0:null::10:null",
                "A:120:null::10:null",
                "A:120:3::10:null",
                "A:120:null:B:10:null",
                "A:120:null:B:11:null",
                "A:120:null:B:11:4",
                "A:120:null:B:11:null",
                ":120:null::11:null"
            ),
            frames
        )
    }
}
