package com.cattailsw.nanidroid;

import static org.junit.Assert.assertEquals;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Characterizes Sakura Script as an ordered semantic event trace. */
public class SakuraScriptCharacterizationTest {
    @Rule
    public final HostAndroidStubRule androidStubs = new HostAndroidStubRule();
    private final Trace trace = new Trace();
    private SScriptRunner runner;

    @Before
    public void setUp() {
        runner = SScriptRunner.getInstance(null);
        runner.setNoWaitMode(true);
        runner.setGhost(null);
        runner.setCallback(null);
        runner.setUICallback(new RecordingUiCallback(trace));
        runner.setPresentationRenderer(new RecordingRenderer(trace));
        runner.clearMsgQueue();

        // SScriptRunner is a process singleton and retains surface ids between runs.
        // Drive it to a deterministic baseline before observing each fixture.
        runScript("\\h\\s[0]\\u\\s[10]\\e");
        trace.clear();
    }

    @After
    public void tearDown() {
        runner.setUICallback(null);
        runner.clearMsgQueue();
        runner.setCallback(null);
        runner.setGhost(null);
        runner.setNoWaitMode(true);
    }

    @Test
    public void requiredMigrationInvariant_speakerTextSurfaceAndAnimationHaveOrderedTrace() {
        assertTrace(
                "eb3101be780c6f27d1876911986a544673a4807a1a33dbf87c132faef0bc4cf7",
                "\\hHi\\s[120]\\i[3]\\uYo\\s[11]\\i[4]\\e",
                "text:sakura:H",
                "text:sakura:Hi",
                "surface:sakura:120",
                "animation:sakura:3",
                "text:kero:Y",
                "text:kero:Yo",
                "surface:kero:11",
                "animation:kero:4");
    }

    @Test
    public void requiredMigrationInvariant_newlineModifierAndClearHaveOrderedTextStates() {
        assertTrace(
                "d5c3ca435493d40f213b620286676313a762d113f7db94eaae96b7b9d3ca1893",
                "\\hA\\n[half]B\\cC\\e",
                "text:sakura:A",
                "text:sakura:A\\n",
                "text:sakura:A\\nB",
                "text:sakura:C");
    }

    @Test
    public void requiredMigrationInvariant_quickSessionEmitsOneWholeLineTextState() {
        assertTrace(
                "16116174b6633c28f55373f47a54e84e6be455ce62707009bf39872757bd82eb",
                "\\h\\_qHello, world.\\e",
                "text:sakura:Hello, world.");
    }

    @Test
    public void requiredMigrationInvariant_distinctSurfaceTransitionsAndAnimationStartsAreOrdered() {
        assertTrace(
                "6fdd70fdfa7ba5db2e83a36481a963ed516bd595b7cfeb0fc131edbb94685047",
                "\\h\\s[120]\\s[120]\\i[3]\\i[3]\\e",
                "surface:sakura:120",
                "animation:sakura:3",
                "animation:sakura:3");
    }

    @Test
    public void legacyObserved_choicesAreReportedThenTheirLabelsContinueAsText() {
        assertTrace(
                "85a129feecd76e217ff9495e44e159bc7db0088a830e3aaf28f4f74ecac08687",
                "\\hA\\q[One,id1]B\\q[Two,id2]\\e",
                "text:sakura:A",
                "choice:[One, Two]:[id1, id2]",
                "text:sakura:AOneBTwo");
    }

    @Test
    public void legacyObserved_unsupportedTagsAreConsumedRatherThanRendered() {
        assertTrace(
                "4f983c4271218d8335f2352efd2adcd138e97514f82a6b5cb5537530298c7fbc",
                "\\hA\\4\\5\\6\\v\\_n\\_V\\_l[half]B\\e",
                "text:sakura:A",
                "text:sakura:AB");
    }

    @Test
    public void rendererReceivesAnimationOnlyWhenTheRunnerSchedulesIt() {
        runScript("\\h\\i[3]\\u\\i[4]\\e");
        assertEquals(
                Arrays.<String>asList("animation:sakura:3", "animation:kero:4"),
                trace.events());
    }

    private void runScript(String fixture) {
        runner.addMsgToQueue(new String[] {fixture});
        runner.run();
    }

    private void assertTrace(String hash, String fixture, String... expectedEvents) {
        assertFixtureSha256(hash, fixture);
        runScript(fixture);
        assertEquals(Arrays.<String>asList(expectedEvents), trace.events());
    }

    private static void assertFixtureSha256(String expected, String fixture) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(fixture.getBytes(Charset.forName("UTF-8")));
            StringBuilder actual = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                actual.append(String.format("%02x", value & 0xff));
            }
            assertEquals("Synthetic fixture text changed", expected, actual.toString());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static final class Trace {
        private final List<String> events = new ArrayList<String>();

        void add(String event) {
            events.add(event);
        }

        void clear() {
            events.clear();
        }

        List<String> events() {
            return new ArrayList<String>(events);
        }
    }

    private static final class RecordingUiCallback implements SScriptRunner.UICallback {
        private final Trace trace;

        RecordingUiCallback(Trace trace) {
            this.trace = trace;
        }

        @Override
        public void showUserInputBox(String id) {
            trace.add("input:" + id);
        }

        @Override
        public void showUserSelection(String[] labels, String[] ids) {
            trace.add("choice:" + Arrays.toString(labels) + ":" + Arrays.toString(ids));
        }
    }

    /** UI-free renderer fixture: unexpected frame fields remain observable. */
    private static final class RecordingRenderer implements GhostPresentationRenderer {
        private final Trace trace;
        private String sakuraText = "";
        private String keroText = "";
        private String sakuraSurface;
        private String keroSurface;

        RecordingRenderer(Trace trace) {
            this.trace = trace;
        }

        @Override
        public void render(GhostPresentationFrame frame) {
            recordSpeaker("sakura", frame.sakura, true);
            recordSpeaker("kero", frame.kero, false);
        }

        private void recordSpeaker(
                String speaker, GhostPresentationFrame.Speaker state, boolean sakura) {
            String previousText = sakura ? sakuraText : keroText;
            if (!state.text.equals(previousText)) {
                if (!state.text.isEmpty()) {
                    trace.add("text:" + speaker + ":" + state.text.replace("\n", "\\n"));
                }
                if (sakura) {
                    sakuraText = state.text;
                } else {
                    keroText = state.text;
                }
            }
            String previousSurface = sakura ? sakuraSurface : keroSurface;
            if (!state.surfaceId.equals(previousSurface)) {
                trace.add("surface:" + speaker + ":" + state.surfaceId);
                if (sakura) {
                    sakuraSurface = state.surfaceId;
                } else {
                    keroSurface = state.surfaceId;
                }
            }
            if (state.animationId != null) {
                trace.add("animation:" + speaker + ":" + state.animationId);
            }
        }
    }

}
