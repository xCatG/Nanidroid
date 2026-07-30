package com.cattailsw.nanidroid;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

/** Characterizes boot delivery across runner clock and ghost lifecycles. */
public final class SScriptRunnerBootDispatchTest {
    @Rule
    public final HostAndroidStubRule androidStubs = new HostAndroidStubRule();

    private final List<SScriptRunner> runners = new ArrayList<SScriptRunner>();

    @After
    public void stopClocks() {
        for (SScriptRunner runner : runners) {
            runner.stopClock();
        }
    }

    @Test
    public void dispatchesBootOnceAcrossDuplicateStartResumeAndNamedGhostHandoff() {
        List<String> trace = new ArrayList<String>();
        SScriptRunner runner = runner();
        RecordingGhost initial = new RecordingGhost("initial", "Initial Ghost", 2, trace);
        RecordingGhost replacement = new RecordingGhost("replacement", "Replacement Ghost", 2, trace);

        runner.setGhost(initial);
        runner.startClock();
        runner.startClock();
        runner.stopClock();
        runner.startClock();
        runner.stopClock();
        runner.setGhost(replacement);
        runner.startClock();

        assertEquals(
                Arrays.asList(
                        "initial:OnBoot:[master]",
                        "replacement:OnGhostChanged:[Initial Ghost, null]"),
                trace);
    }

    @Test
    public void newlyConstructedRunnerDispatchesBootOnceAfterAppRecreation() {
        List<String> trace = new ArrayList<String>();
        SScriptRunner runner = runner();

        runner.setGhost(new RecordingGhost("recreated", "Recreated Ghost", 2, trace));
        runner.startClock();

        assertEquals(Arrays.asList("recreated:OnBoot:[master]"), trace);
    }

    private SScriptRunner runner() {
        SScriptRunner runner = new SScriptRunner(null);
        runners.add(runner);
        return runner;
    }

    private static final class RecordingGhost extends Ghost {
        private final String id;
        private final String ghostName;
        private final long createCount;
        private final List<String> trace;

        RecordingGhost(String id, String ghostName, long createCount, List<String> trace) {
            super(id);
            this.id = id;
            this.ghostName = ghostName;
            this.createCount = createCount;
            this.trace = trace;
        }

        @Override
        protected void loadGhostInfo() {
            // The fake owns all metadata needed by this lifecycle trace.
        }

        @Override
        protected void incrementCreateCount() {
            // Creation counts are fixed test fixtures, not persisted state.
        }

        @Override
        public String getGhostName() {
            return ghostName;
        }

        @Override
        public long getCreateCount() {
            return createCount;
        }

        @Override
        public String getGhostId() {
            return id;
        }

        @Override
        public ShioriResponse doShioriEvent(String event, String[] references) {
            trace.add(id + ":" + event + ":" + Arrays.toString(references));
            return new ShioriResponse("SHIORI/3.0 204 No Content");
        }
    }
}