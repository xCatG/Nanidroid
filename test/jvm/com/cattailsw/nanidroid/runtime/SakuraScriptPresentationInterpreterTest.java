package com.cattailsw.nanidroid.runtime;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** Characterizes the visible-frame order for the Kotlin presentation subset. */
public final class SakuraScriptPresentationInterpreterTest {
    @Test
    public void requiredMigrationInvariant_textSurfaceAnimationAndStopFramesMatchLegacyTrace() {
        List<GhostPresentationState> frames = SakuraScriptPresentationInterpreter.interpret(
                "\\hA\\s[120]\\i[3]\\uB\\s[11]\\i[4]\\e");

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
                trace(frames));
    }

    @Test
    public void requiredMigrationInvariant_repeatedSpeakerAndNewlineCommandsKeepVisibleText() {
        List<GhostPresentationState> frames = SakuraScriptPresentationInterpreter.interpret(
                "\\hA\\hB\\n[half]C\\e");

        assertEquals("A", frames.get(0).getSakura().getText());
        assertEquals("AB", frames.get(1).getSakura().getText());
        assertEquals("AB\n", frames.get(2).getSakura().getText());
        assertEquals("AB\nC", frames.get(3).getSakura().getText());
    }

    private static List<String> trace(List<GhostPresentationState> frames) {
        List<String> trace = new ArrayList<String>();
        for (GhostPresentationState frame : frames) {
            trace.add(
                    frame.getSakura().getText() + ":" +
                    frame.getSakura().getSurfaceId() + ":" +
                    frame.getSakura().getAnimationId() + ":" +
                    frame.getKero().getText() + ":" +
                    frame.getKero().getSurfaceId() + ":" +
                    frame.getKero().getAnimationId());
        }
        return trace;
    }
}
