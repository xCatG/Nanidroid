package com.cattailsw.nanidroid.test

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cattailsw.nanidroid.Ghost
import com.cattailsw.nanidroid.NanidroidService
import com.cattailsw.nanidroid.ScriptEngine
import com.cattailsw.nanidroid.SScriptRunner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * R7-B part 1: NanidroidService.onDestroy must NOT tear down shared engine rendering state.
 *
 * SScriptRunner is a PROCESS-WIDE SINGLETON. NanidroidService is only a background
 * update/sensor service — it must never call setGhost(null), stopClock(), clearMsgQueue(),
 * or clearViews() on the shared engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NanidroidServiceTeardownTest {

    private lateinit var context: Context
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testRunner: SScriptRunner

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDispatcher = StandardTestDispatcher()
        testRunner = SScriptRunner(context, testDispatcher, testDispatcher)
        SScriptRunner.setTestInstance(testRunner)
    }

    @After
    fun tearDown() {
        SScriptRunner.setTestInstance(null)
    }

    @Test
    fun onDestroy_doesNotClearSharedEngineGhost() {
        // Build a test Ghost that avoids filesystem/Context access.
        val ghost = object : Ghost("testPath") {
            override fun loadGhostInfo() {}
            override fun incrementCreateCount() {}
            override fun getCreateCount(): Long = 1L
        }

        // Set the ghost on the shared singleton runner.
        testRunner.setGhost(ghost)

        // Verify ghost is set before we proceed.
        val engineGhostField = ScriptEngine::class.java.getDeclaredField("g").apply { isAccessible = true }
        val engineBefore = engineGhostField.get(testRunner.getScriptEngine())
        assertNotNull("Precondition: ghost must be set on engine before destroy", engineBefore)

        // Build the NanidroidService.
        val controller = Robolectric.buildService(NanidroidService::class.java)
        controller.create()

        // Inject the singleton runner into the service's private `runner` field so that
        // the current onDestroy teardown path is exercised even when runner is non-null.
        val runnerField = NanidroidService::class.java.getDeclaredField("runner").apply { isAccessible = true }
        runnerField.set(controller.get(), testRunner)

        // Destroy the service — this is the action under test.
        controller.destroy()

        // Assert: the shared engine must still hold the ghost after NanidroidService.onDestroy.
        // If the old teardown block (setGhost(null), stopClock(), …) runs, this will be null → RED.
        val engineAfter = engineGhostField.get(testRunner.getScriptEngine())
        assertNotNull(
            "NanidroidService.onDestroy must NOT clear the shared engine ghost " +
                "(the ghost belongs to the rendering host, not to this background service)",
            engineAfter
        )
    }
}
