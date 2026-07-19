package com.cattailsw.nanidroid.test

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cattailsw.nanidroid.Ghost
import com.cattailsw.nanidroid.ScriptEngine
import com.cattailsw.nanidroid.SScriptRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HostRefcountTest {

    private lateinit var context: Context
    private lateinit var testDispatcher: TestDispatcher

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newRunner(): SScriptRunner =
        SScriptRunner(context, testDispatcher, testDispatcher)

    private fun newTestGhost(): Ghost = object : Ghost("testPath") {
        override fun loadGhostInfo() {}
        override fun incrementCreateCount() {}
        override fun getCreateCount(): Long = 1L
    }

    /** Reads the private `g` field of the runner's ScriptEngine. */
    private fun engineGhost(runner: SScriptRunner): Any? {
        val engine: ScriptEngine = runner.getScriptEngine()
        val gField = ScriptEngine::class.java.getDeclaredField("g").apply { isAccessible = true }
        return gField.get(engine)
    }

    /** Reflects the private `hostCount` field on SScriptRunner. */
    private fun hostCount(runner: SScriptRunner): Int {
        val field = SScriptRunner::class.java.getDeclaredField("hostCount").apply { isAccessible = true }
        return field.getInt(runner)
    }

    @Test
    fun attachThenDetach_tearsDownAtZero() {
        val runner = newRunner()
        runner.setGhost(newTestGhost())
        assertNotNull("ghost should be set before detach", engineGhost(runner))

        runner.attach()
        val tornDown = runner.detach()

        assertTrue("detach at count 0 should run teardown and return true", tornDown)
        assertNull("teardown ran setGhost(null), engine g must be null", engineGhost(runner))
    }

    @Test
    fun twoAttach_singleDetach_doesNotTearDown() {
        val runner = newRunner()
        runner.attach()
        runner.attach()
        runner.setGhost(newTestGhost())

        val first = runner.detach()
        assertFalse("first detach (count 2->1) must NOT tear down", first)
        assertNotNull("ghost retained after first detach", engineGhost(runner))

        val second = runner.detach()
        assertTrue("second detach (count 1->0) tears down", second)
        assertNull("ghost cleared after teardown", engineGhost(runner))
    }

    @Test
    fun detachBelowZero_isClampedAndDoesNotThrow() {
        val runner = newRunner()

        // count is 0 here; detaching must be a clamped no-op returning false.
        val belowZero = runner.detach()
        assertFalse("detach at count 0 returns false", belowZero)

        // Count integrity preserved: a subsequent attach()/detach() still tears down once.
        runner.setGhost(newTestGhost())
        runner.attach()
        val tornDown = runner.detach()
        assertTrue("attach+detach after underflow still tears down", tornDown)
        assertNull("ghost cleared after teardown", engineGhost(runner))
    }

    /**
     * Hammers attach()/detach() from many real threads released simultaneously via a start gate.
     * Equal numbers of attach and detach overall must leave hostCount at exactly 0, and the
     * synchronized critical section must keep the count consistent (no lost updates / no underflow
     * exceptions) under contention. This exercises the atomicity claim across true parallelism,
     * unlike the single-threaded tests above.
     */
    @Test
    fun concurrentAttachDetach_maintainsCountIntegrity() {
        val runner = newRunner()

        val threadCount = 100
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threadCount)
        val failures = CopyOnWriteArrayList<Throwable>()
        val pool = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) {
            pool.execute {
                try {
                    // All threads block here until released together to maximize contention.
                    startGate.await()
                    repeat(200) {
                        runner.attach()
                        runner.detach()
                    }
                } catch (t: Throwable) {
                    failures.add(t)
                } finally {
                    doneGate.countDown()
                }
            }
        }

        startGate.countDown()
        assertTrue(
            "all worker threads should finish within timeout",
            doneGate.await(30, TimeUnit.SECONDS)
        )
        pool.shutdown()
        assertTrue(
            "thread pool should terminate",
            pool.awaitTermination(30, TimeUnit.SECONDS)
        )

        assertTrue(
            "no thread should have thrown; got: ${failures.joinToString { it.toString() }}",
            failures.isEmpty()
        )
        assertEquals(
            "balanced attach/detach across all threads must leave hostCount at 0",
            0,
            hostCount(runner)
        )
    }
}
