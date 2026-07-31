package com.cattailsw.nanidroid;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/** Real-device smoke coverage for main-activity launch and configuration recreation. */
@RunWith(AndroidJUnit4.class)
public final class NanidroidLifecycleInstrumentationTest {

    @Test
    public void launchAndRecreateKeepsMainActivityAvailable() {
        try (ActivityScenario<Nanidroid> scenario = ActivityScenario.launch(Nanidroid.class)) {
            AtomicReference<Nanidroid> initial = new AtomicReference<>();
            scenario.onActivity(initial::set);
            assertNotNull(initial.get());

            scenario.recreate();

            AtomicReference<Nanidroid> recreated = new AtomicReference<>();
            scenario.onActivity(recreated::set);
            assertNotNull(recreated.get());
            assertFalse(recreated.get().isFinishing());
        }
    }
}
