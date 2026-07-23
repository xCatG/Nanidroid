package com.cattailsw.nanidroid;

import static org.junit.Assert.assertEquals;

import android.app.Activity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** Characterizes the API boundary for the bundled legacy debug ViewServer. */
public class ViewServerLifecycleCharacterizationTest {
    @Test
    public void requiredCompatibility_api9And10PreserveLifecycleRouting() {
        for (int sdkInt : new int[] {9, 10}) {
            RecordingBackend backend = new RecordingBackend();

            exerciseLifecycle(sdkInt, backend);

            assertEquals(
                    "API " + sdkInt,
                    Arrays.asList("add", "focus", "remove"),
                    backend.events);
        }
    }

    @Test
    public void intentionalCompatibilityBoundary_api10RoutesAndApi11DoesNot() {
        RecordingBackend api10Backend = new RecordingBackend();
        RecordingBackend api11Backend = new RecordingBackend();

        exerciseLifecycle(10, api10Backend);
        exerciseLifecycle(11, api11Backend);

        assertEquals(Arrays.asList("add", "focus", "remove"), api10Backend.events);
        assertEquals(Collections.<String>emptyList(), api11Backend.events);
    }

    @Test
    public void intentionalModernBehavior_repeatedApi11And36LifecyclesNeverTouchBackend() {
        ViewServerLifecycle.Backend backend = new ThrowingBackend();

        for (int sdkInt : new int[] {11, 36}) {
            for (int cycle = 0; cycle < 3; cycle++) {
                exerciseLifecycle(sdkInt, backend);
            }
        }
    }

    private static void exerciseLifecycle(
            int sdkInt, ViewServerLifecycle.Backend backend) {
        ViewServerLifecycle.onActivityCreated(sdkInt, null, backend);
        ViewServerLifecycle.onActivityResumed(sdkInt, null, backend);
        ViewServerLifecycle.onActivityDestroyed(sdkInt, null, backend);
    }

    private static final class RecordingBackend implements ViewServerLifecycle.Backend {
        final List<String> events = new ArrayList<String>();

        @Override
        public void addWindow(Activity activity) {
            events.add("add");
        }

        @Override
        public void setFocusedWindow(Activity activity) {
            events.add("focus");
        }

        @Override
        public void removeWindow(Activity activity) {
            events.add("remove");
        }
    }

    private static final class ThrowingBackend implements ViewServerLifecycle.Backend {
        @Override
        public void addWindow(Activity activity) {
            throw new AssertionError("Modern Android must not add a ViewServer window");
        }

        @Override
        public void setFocusedWindow(Activity activity) {
            throw new AssertionError("Modern Android must not focus a ViewServer window");
        }

        @Override
        public void removeWindow(Activity activity) {
            throw new AssertionError("Modern Android must not remove a ViewServer window");
        }
    }
}
