package com.cattailsw.nanidroid;

import android.test.ActivityInstrumentationTestCase2;

/** Real-device smoke coverage for main-activity launch and configuration recreation. */
public final class NanidroidLifecycleInstrumentationTest
        extends ActivityInstrumentationTestCase2<Nanidroid> {
    public NanidroidLifecycleInstrumentationTest() {
        super(Nanidroid.class);
    }

    public void testLaunchAndRecreateKeepsMainActivityAvailable() throws Throwable {
        Nanidroid initial = getActivity();
        assertNotNull(initial);
        getInstrumentation().waitForIdleSync();

        runTestOnUiThread(initial::recreate);
        getInstrumentation().waitForIdleSync();

        Nanidroid recreated = getActivity();
        assertNotNull(recreated);
        assertFalse(recreated.isFinishing());
    }
}
