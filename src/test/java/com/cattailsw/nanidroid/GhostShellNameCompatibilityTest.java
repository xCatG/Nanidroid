package com.cattailsw.nanidroid;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import java.util.Map;

import org.junit.Test;
import org.junit.Rule;

/** Guards the legacy distinction between a missing shell descriptor and a missing name. */
public final class GhostShellNameCompatibilityTest {
    @Rule
    public final HostAndroidStubRule androidStubs = new HostAndroidStubRule();
    @Test
    public void missingNameInParsedShellDescriptorRemainsNull() {
        TestGhost ghost = new TestGhost();
        ghost.setShellDescription(Collections.<String, String>emptyMap());

        assertEquals(null, ghost.getShellName());
    }

    @Test
    public void unavailableShellDescriptorFallsBackToMaster() {
        TestGhost ghost = new TestGhost();

        assertEquals("master", ghost.getShellName());
    }

    private static final class TestGhost extends Ghost {
        TestGhost() {
            super("shell-name-contract");
        }

        @Override
        protected void loadGhostInfo() {
            // The test supplies its descriptor state without filesystem or SHIORI setup.
        }

        @Override
        protected void incrementCreateCount() {
            // The test has no persistent creation count.
        }

        void setShellDescription(Map<String, String> description) {
            shellDesc = description;
        }
    }
}
