package com.cattailsw.nanidroid;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** Characterizes the UI-free runtime-to-renderer presentation trace. */
public class SScriptRunnerPresentationTest {
    @Test
    public void emitsTextSurfaceAndOneShotAnimationFramesWithoutAndroidViews() {
        final List<String> frames = new ArrayList<String>();
        SScriptRunner runner = new SScriptRunner(null);
        runner.setNoWaitMode(true);
        runner.setPresentationRendererForTesting(new GhostPresentationRenderer() {
            @Override
            public void render(GhostPresentationFrame frame) {
                frames.add(
                        frame.sakura.text + ":" + frame.sakura.surfaceId + ":" +
                        frame.sakura.animationId + ":" + frame.kero.text + ":" +
                        frame.kero.surfaceId + ":" + frame.kero.animationId);
            }
        });

        runner.addMsgToQueue(new String[] {"\\hA\\s[120]\\i[3]\\uB\\s[11]\\i[4]\\e"});
        runner.run();

        assertEquals(
                Arrays.asList(
                        "A:0:null::10:null",
                        "A:120:null::10:null",
                        "A:120:3::10:null",
                        "A:120:null:B:10:null",
                        "A:120:null:B:11:null",
                        "A:120:null:B:11:4",
                        "A:120:null:B:11:null",
                        ":120:null::11:null"),
                frames);
    }
}
