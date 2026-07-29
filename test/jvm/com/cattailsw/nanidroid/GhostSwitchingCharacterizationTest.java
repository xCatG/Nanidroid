package com.cattailsw.nanidroid;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Characterizes the deterministic SScriptRunner ghost-handoff protocol without
 * filesystem discovery, Activity lifecycle, native engines, or view rebinding.
 */
public final class GhostSwitchingCharacterizationTest {
    @Rule
    public final HostAndroidStubRule androidStubs = new HostAndroidStubRule();
    private static final String TRANSITION_SCRIPT = "\\_qSwitching\\e";

    private final Trace trace = new Trace();
    private SScriptRunner runner;
    private RecordingGhost currentGhost;

    @Before
    public void setUp() {
        runner = SScriptRunner.getInstance(null);
        runner.setPresentationRenderer(new TraceRenderer(trace));
        resetRunnerWithPublicApi();
        trace.clear();
    }

    @After
    public void tearDown() {
        resetRunnerWithPublicApi();
    }

    @Test
    public void requiredMigrationInvariant_outgoingScriptRendersBeforeSingleHandoffCallback() {
        RecordingGhost outgoing = new RecordingGhost(
                "outgoing",
                "Old Ghost Metadata",
                "Old Sakura Display",
                1,
                TRANSITION_SCRIPT,
                trace);
        setGhost(outgoing);
        runner.setCallback(new RecordingStatusCallback(trace));

        runner.doGhostChanging("Next Sakura", "manual", "/ghosts/next");

        assertEquals(
                Arrays.asList(
                        "request:outgoing:OnGhostChanging:"
                                + "[Next Sakura, manual, null, /ghosts/next]",
                        "render:Switching",
                        "handoff"),
                trace.events());
    }

    @Test
    public void requiredMigrationInvariant_returningReplacementReceivesChangedFromOutgoingName() {
        RecordingGhost outgoing = new RecordingGhost(
                "outgoing",
                "Old Ghost Metadata",
                "Old Sakura Display",
                1,
                TRANSITION_SCRIPT,
                trace);
        RecordingGhost replacement = new RecordingGhost(
                "replacement",
                "New Ghost Metadata",
                "New Sakura Display",
                2,
                null,
                trace);

        // Prove setup cleanup does not depend on another test having cleared
        // the process singleton's named ghost.
        runner.setGhost(new RecordingGhost(
                "foreign",
                "Foreign Ghost Metadata",
                "Foreign Sakura Display",
                2,
                null,
                trace));
        resetRunnerWithPublicApi();
        trace.clear();

        setGhost(outgoing);
        assertEquals(new ArrayList<String>(), trace.events());
        runner.setCallback(new RecordingStatusCallback(trace));

        runner.doGhostChanging("Next Sakura", "manual", "/ghosts/next");

        setGhost(replacement);

        assertEquals(
                Arrays.asList(
                        "request:outgoing:OnGhostChanging:"
                                + "[Next Sakura, manual, null, /ghosts/next]",
                        "render:Switching",
                        "handoff",
                        "request:replacement:OnGhostChanged:"
                                + "[Old Ghost Metadata, null]"),
                trace.events());
    }

    private void setGhost(RecordingGhost ghost) {
        currentGhost = ghost;
        runner.setGhost(ghost);
    }

    private void resetRunnerWithPublicApi() {
        runner.setNoWaitMode(true);

        // Drain a failed in-flight transition while its callback and inert fake
        // are still installed. This clears changingPending through stop().
        runner.clearMsgQueue();

        runner.setCallback(null);
        runner.setUICallback(null);

        // setGhost(null) dereferences the replacement when the outgoing name is
        // non-null. Suppressing the test fake's name takes the public silent
        // assignment path and avoids coupling this characterization to fields.
        if (currentGhost == null) {
            // A previous or future suite may leave a named ghost in the process
            // singleton. Replacing it with a null-name count-2 fake avoids the
            // production null-replacement dereference without reflection.
            setGhost(new RecordingGhost(
                    "cleanup",
                    null,
                    null,
                    2,
                    null,
                    trace));
        } else {
            currentGhost.suppressOutgoingName();
        }
        runner.setGhost(null);
        currentGhost = null;
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

    private static final class RecordingStatusCallback
            implements SScriptRunner.StatusCallback {
        private final Trace trace;

        RecordingStatusCallback(Trace trace) {
            this.trace = trace;
        }

        @Override
        public void stop() {
            // Generic runner-stop notification is outside the handoff oracle.
        }

        @Override
        public void canExit() {
            // Exit handling is outside the handoff oracle.
        }

        @Override
        public void ghostSwitchScriptComplete() {
            trace.add("handoff");
        }
    }

    private static final class RecordingGhost extends Ghost {
        private final String id;
        private String ghostName;
        private String sakuraName;
        private final long createCount;
        private final String transitionScript;
        private final Trace trace;

        RecordingGhost(
                String id,
                String ghostName,
                String sakuraName,
                long createCount,
                String transitionScript,
                Trace trace) {
            super(id);
            this.id = id;
            this.ghostName = ghostName;
            this.sakuraName = sakuraName;
            this.createCount = createCount;
            this.transitionScript = transitionScript;
            this.trace = trace;
        }

        @Override
        protected void loadGhostInfo() {
            // Test-only fake: no descriptors, surfaces, filesystem, or SHIORI engine.
        }

        @Override
        protected void incrementCreateCount() {
            // Test-only fake: create-count values are supplied explicitly.
        }

        @Override
        public String getGhostId() {
            return id;
        }

        @Override
        public String getGhostName() {
            return ghostName;
        }

        void suppressOutgoingName() {
            ghostName = null;
            sakuraName = null;
        }

        @Override
        public long getCreateCount() {
            return createCount;
        }

        @Override
        public String getSakuraName() {
            return sakuraName;
        }

        @Override
        public String getKeroName() {
            return "Kero";
        }

        @Override
        public String getUsername() {
            return "User";
        }

        @Override
        public ShioriResponse doShioriEvent(String event, String[] references) {
            trace.add(
                    "request:" + id + ":" + event + ":"
                            + Arrays.toString(references));
            if ("OnGhostChanging".equals(event) && transitionScript != null) {
                Hashtable<String, String> values = new Hashtable<String, String>();
                values.put("Value", transitionScript);
                return new ShioriResponse("SHIORI/3.0 200 OK", values);
            }
            return new ShioriResponse("SHIORI/3.0 204 No Content");
        }

        @Override
        public void unload() {
            // Ownership and unload ordering are intentionally deferred.
        }
    }

    /** Fail-fast UI-free collaborator for the runner's complete render frame. */
    private static final class TraceRenderer implements GhostPresentationRenderer {
        private final Trace trace;
        private String previousText = "";

        TraceRenderer(Trace trace) {
            this.trace = trace;
        }

        @Override
        public void render(GhostPresentationFrame frame) {
            String value = frame.sakura.text;
            if (!value.equals(previousText) && value.length() > 0) {
                trace.add("render:" + value);
            }
            previousText = value;
        }
    }
}
