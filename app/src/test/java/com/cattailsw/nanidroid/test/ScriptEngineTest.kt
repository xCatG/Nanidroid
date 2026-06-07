package com.cattailsw.nanidroid.test

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cattailsw.nanidroid.*
import com.cattailsw.nanidroid.test.support.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScriptEngineTest {

    private lateinit var context: Context
    private lateinit var testDispatcher: TestDispatcher
    
    // A separate single-threaded dispatcher to prove JNI runs off-Main.
    private lateinit var engineExecutorDispatcher: CoroutineDispatcher

    private var sakura: DummySakuraView? = null
    private var kero: DummyKeroView? = null
    private var bSakura: DummyBalloon? = null
    private var bKero: DummyBalloon? = null

    private var statusStopCalled = false
    private val statusCallback = object : SScriptRunner.StatusCallback {
        override fun stop() {
            statusStopCalled = true
        }
        override fun canExit() {}
        override fun ghostSwitchScriptComplete() {}
    }

    private var selectionCallbackCalled = false
    private val uiCallback = object : SScriptRunner.UICallback {
        override fun showUserInputBox(id: String) {}
        override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) {
            selectionCallbackCalled = true
            assertEquals(2, textlabel.size)
            assertEquals(2, ids.size)
            assertEquals("fgh", textlabel[0])
            assertEquals("lmno", textlabel[1])
            assertEquals("lalala asda", ids[0])
            assertEquals("lalala aaaa", ids[1])
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDispatcher = StandardTestDispatcher()
        
        // Register testDispatcher as Main
        Dispatchers.setMain(testDispatcher)
        
        // Single thread dispatcher for engine execution in test
        engineExecutorDispatcher = testDispatcher

        sakura = DummySakuraView(context)
        kero = DummyKeroView(context)
        bSakura = DummyBalloon(context)
        bKero = DummyBalloon(context)
        
        statusStopCalled = false
        selectionCallbackCalled = false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun CoroutineScope.bindViews(engine: ScriptEngine) {
        launch {
            println("DEBUG: Collector launched")
            engine.uiState.collect { state ->
                println("DEBUG: Collected state: $state")
                sakura?.changeSurface(state.sakuraSurfaceId)
                kero?.changeSurface(state.keroSurfaceId)

                if (state.sakuraBalloonVisible) {
                    println("DEBUG: Sakura balloon visible, text: '${state.sakuraBalloonText}'")
                    bSakura?.setText(state.sakuraBalloonText)
                    if (state.sakuraAnimationId == null && state.talkAnimeControl == 0) {
                        sakura?.startTalkingAnimation()
                    }
                }
                
                if (state.keroBalloonVisible) {
                    println("DEBUG: Kero balloon visible, text: '${state.keroBalloonText}'")
                    bKero?.setText(state.keroBalloonText)
                    if (state.keroAnimationId == null && state.talkAnimeControl == 0) {
                        kero?.startTalkingAnimation()
                    }
                }

                state.sakuraAnimationId?.let { animId ->
                    sakura?.loadAnimation(animId)
                    sakura?.startAnimation()
                }
                state.keroAnimationId?.let { animId ->
                    kero?.loadAnimation(animId)
                    kero?.startAnimation()
                }
            }
        }
    }

    @Test
    fun waitTag_suspendsForVirtualTime() = runTest(testDispatcher) {
        val customScope = CoroutineScope(testDispatcher + SupervisorJob())
        val engine = ScriptEngine(null, testDispatcher, testDispatcher)
        customScope.bindViews(engine)

        engine.addMsgToQueue(arrayOf("\\habc\\w5def\\e"))
        customScope.launch { engine.run() }

        // Let characters 'a', 'b', 'c' print. They have 50ms delay each.
        // At t=100, 'c' is scheduled. We advance time and run it.
        advanceTimeBy(100)
        runCurrent()
        assertEquals("abc", bSakura?.dispText)

        // Advance to t=150 and run to parse the \\w5 tag (which delays 250ms).
        advanceTimeBy(50)
        runCurrent()

        // From t=150 to t=399, it is suspended. At t=399, it should still be "abc".
        advanceTimeBy(249) // Total 399ms
        runCurrent()
        assertEquals("abc", bSakura?.dispText)

        // At t=400, 'd' is printed. By t=500, 'def' is finished.
        advanceTimeBy(101) // Total 500ms
        runCurrent()
        assertEquals("abcdef", bSakura?.dispText)
        customScope.cancel()
    }

    @Test
    fun shioriRequest_runsOffMainDispatcher() = runBlocking {
        // Here we must prove JNI/Shiori runs off main thread.
        val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val fakeShiori = FakeShiori(responses = mapOf("OnFirstBoot" to "\\hhi\\e"))
        val ghost = object : Ghost("testPath") {
            init {
                this.shiori = fakeShiori
            }
            override fun loadGhostInfo() {}
            override fun incrementCreateCount() {}
            override fun getCreateCount(): Long = 1L
        }

        val engine = ScriptEngine(ghost, thread, Dispatchers.Main)
        engine.setGhost(ghost)
        
        // Trigger boot which calls Shiori
        engine.startClock()

        // Wait a bit for background thread to run (real sleep)
        delay(200)

        engine.stopClock()
        engine.cancel()
        thread.close()

        assertTrue(fakeShiori.calls.isNotEmpty())
        for (call in fakeShiori.calls) {
            // It should be running on the executor's thread, not main
            assertFalse(call.thread.name.contains("main", ignoreCase = true))
        }
    }

    @Test
    fun cancellation_stopsClockAndInFlightWork() = runTest(testDispatcher) {
        val fakeShiori = FakeShiori(defaultResponse = "\\hhello\\e")
        val ghost = object : Ghost("testPath") {
            init {
                this.shiori = fakeShiori
            }
            override fun loadGhostInfo() {}
            override fun incrementCreateCount() {}
            override fun getCreateCount(): Long = 1L
        }
        val engine = ScriptEngine(ghost, testDispatcher, testDispatcher)
        engine.setGhost(ghost)

        engine.startClock()
        
        // Let it tick once
        advanceTimeBy(1100)
        val initialCalls = fakeShiori.calls.size
        assertTrue(initialCalls > 0)

        // Cancel engine
        engine.cancel()

        // Let virtual time advance further
        advanceTimeBy(3000)
        assertEquals(initialCalls, fakeShiori.calls.size)
    }

    @Test
    fun testSakuraSpeak() = runTest(testDispatcher) {
        System.err.println("DEBUG ERR: testSakuraSpeak started")
        val customScope = CoroutineScope(testDispatcher + SupervisorJob())
        val engine = ScriptEngine(null, testDispatcher, testDispatcher)
        
        System.err.println("DEBUG ERR: launching collector")
        customScope.launch {
            System.err.println("DEBUG ERR: collector coroutine started")
            engine.uiState.collect { state ->
                System.err.println("DEBUG ERR: collected state: $state")
                bSakura?.setText(state.sakuraBalloonText)
            }
        }

        engine.addMsgToQueue(arrayOf("\\_q\\hlalala\\e"))
        System.err.println("DEBUG ERR: launching engine run")
        customScope.launch { 
            System.err.println("DEBUG ERR: engine run coroutine started")
            engine.run() 
        }
        System.err.println("DEBUG ERR: calling advanceUntilIdle")
        advanceUntilIdle()
        System.err.println("DEBUG ERR: after advanceUntilIdle, textVal = ${bSakura?.textVal}")
        assertEquals("lalala", bSakura?.textVal)

        engine.addMsgToQueue(arrayOf("\\_q\\0abcdefg\\n"))
        advanceUntilIdle()
        assertEquals("lalalaabcdefg\n", bSakura?.textVal)
        
        customScope.cancel()
    }

    @Test
    fun testSakuraSpeakNormal() = runTest(testDispatcher) {
        val customScope = CoroutineScope(testDispatcher + SupervisorJob())
        val engine = ScriptEngine(null, testDispatcher, testDispatcher)
        customScope.bindViews(engine)

        engine.addMsgToQueue(arrayOf("\\habcde\\e"))
        customScope.launch { engine.run() }
        
        // Wait unit is 50ms. Let's advance until idle to make it run.
        advanceUntilIdle()
        assertEquals("aababcabcdabcdeabcde", bSakura?.textVal)
        customScope.cancel()
    }

    @Test
    fun testKeroSpeak() = runTest(testDispatcher) {
        val customScope = CoroutineScope(testDispatcher + SupervisorJob())
        val engine = ScriptEngine(null, testDispatcher, testDispatcher)
        customScope.bindViews(engine)

        engine.addMsgToQueue(arrayOf("\\_q\\1xxxxxx\\e"))
        customScope.launch { engine.run() }
        advanceUntilIdle()
        assertEquals("xxxxxx", bKero?.textVal)

        engine.addMsgToQueue(arrayOf("\\_q\\habcde\\uyyyyyy\\e"))
        advanceUntilIdle()
        assertEquals("xxxxxxyyyyyy", bKero?.textVal)
        customScope.cancel()
    }

    @Test
    fun testIgnoreCommands() = runTest(testDispatcher) {
        val customScope = CoroutineScope(testDispatcher + SupervisorJob())
        val engine = ScriptEngine(null, testDispatcher, testDispatcher)
        customScope.bindViews(engine)

        var cmd = "\\4\\5\\6\\v\\_n\\_V\\e"
        engine.addMsgToQueue(arrayOf(cmd))
        customScope.launch { engine.run() }
        advanceUntilIdle()

        cmd = "\\_q\\habcde\\_l[100]\\_a[45]\\_v[000]fghijk\\_n\\_V\\e"
        engine.addMsgToQueue(arrayOf(cmd))
        advanceUntilIdle()
        assertEquals("abcdefghijk", bSakura?.textVal)
        customScope.cancel()
    }

    @Test
    fun testSurfaceChangeSakura() = runTest(testDispatcher) {
        val customScope = CoroutineScope(testDispatcher + SupervisorJob())
        val engine = ScriptEngine(null, testDispatcher, testDispatcher)
        customScope.bindViews(engine)

        var t = "\\h\\s0\\e"
        engine.addMsgToQueue(arrayOf(t))
        customScope.launch { engine.run() }
        advanceUntilIdle()
        assertEquals("0", sakura?.stext)

        t = "\\s[120]\\e"
        engine.addMsgToQueue(arrayOf(t))
        advanceUntilIdle()
        assertEquals("120", sakura?.sid)
        assertEquals("0,120", sakura?.stext)

        t = "\\h\\s10wrong\\s[10]\\e"
        engine.addMsgToQueue(arrayOf(t))
        advanceUntilIdle()
        assertEquals("10", sakura?.sid)
        assertEquals("0,120,1,10", sakura?.stext)
        assertEquals("0wrong", bSakura?.dispText)

        t = "\\t\\h\\s[20]\\n\\w9\\u\\s[10]\\n\\h\\s0"
        engine.addMsgToQueue(arrayOf(t))
        advanceUntilIdle()
        assertEquals("0", sakura?.sid)
        assertEquals("0,120,1,10,20,0", sakura?.stext)
        assertEquals("\n", bSakura?.dispText)
        customScope.cancel()
    }

    @Test
    fun testAnimation() = runTest(testDispatcher) {
        val customScope = CoroutineScope(testDispatcher + SupervisorJob())
        val engine = ScriptEngine(null, testDispatcher, testDispatcher)
        customScope.bindViews(engine)

        val cmd = "\\halala\\i[0]opqrstmnopqrst\\e"
        engine.addMsgToQueue(arrayOf(cmd))
        customScope.launch { engine.run() }
        advanceUntilIdle()

        assertEquals(3, sakura?.talkCalledTime)
        assertEquals("0", sakura?.aid)
        customScope.cancel()
    }

    @Test
    fun testCallback() = runTest(testDispatcher) {
        val customScope = CoroutineScope(testDispatcher + SupervisorJob())
        val engine = ScriptEngine(null, testDispatcher, testDispatcher)
        engine.setStatusCallback(statusCallback)
        customScope.bindViews(engine)

        val cmd = "\\habcde\\e"
        engine.addMsgToQueue(arrayOf(cmd))
        customScope.launch { engine.run() }
        advanceUntilIdle()
        assertTrue(statusStopCalled)
        customScope.cancel()
    }

    @Test
    fun testChoiceWithRunner() = runTest(testDispatcher) {
        val customScope = CoroutineScope(testDispatcher + SupervisorJob())
        val engine = ScriptEngine(null, testDispatcher, testDispatcher)
        engine.setUICallback(uiCallback)
        customScope.bindViews(engine)

        val s = "\\0abcde\\q[fgh,lalala asda]ijk\\q[lmno,lalala aaaa]\\e"
        engine.addMsgToQueue(arrayOf(s))
        customScope.launch { engine.run() }
        advanceUntilIdle()

        assertEquals("abcde", bSakura?.dispText)
        assertTrue(selectionCallbackCalled)
        customScope.cancel()
    }

    @Test
    fun choiceSelect_discardsPausedScript_andFiresOnChoiceSelect() = runTest(testDispatcher) {
        val customScope = CoroutineScope(testDispatcher + SupervisorJob())
        val fakeShiori = FakeShiori(
            responses = mapOf(
                "OnChoiceSelect" to "\\h\\s[0]chosen!\\e"
            )
        )
        val ghost = object : Ghost("test_ghost_id") {
            init {
                this.shiori = fakeShiori
            }
            override fun loadGhostInfo() {}
            override fun incrementCreateCount() {}
            override fun getCreateCount(): Long = 2L
        }
        val engine = ScriptEngine(ghost, testDispatcher, testDispatcher)
        engine.setGhost(ghost)
        customScope.bindViews(engine)

        val s = "\\0abcde\\q[fgh,id1]ijk\\q[lmno,id2]\\e"
        engine.addMsgToQueue(arrayOf(s))
        customScope.launch { engine.run() }
        
        advanceUntilIdle()

        // Assert it is paused and showing pre-choice text
        assertEquals("abcde", bSakura?.dispText)

        // Now select choice
        engine.doOnChoiceSelect("id1")
        advanceUntilIdle()

        // Assert OnChoiceSelect was called with reference "id1"
        val choiceCall = fakeShiori.calls.firstOrNull { it.request.contains("ID: OnChoiceSelect") }
        assertNotNull("Should have recorded a call to OnChoiceSelect", choiceCall)
        assertTrue(choiceCall!!.request.contains("Reference0: id1"))

        // Assert balloon contains "chosen!" but not "ijk"
        assertTrue(bSakura?.textVal?.contains("chosen!") == true)
        assertFalse(bSakura?.textVal?.contains("ijk") == true)

        customScope.cancel()
    }

    @Test
    fun clockStartedTwice_firesOnSecondChangeOncePerSecond() = runTest(testDispatcher) {
        val fakeShiori = FakeShiori(defaultResponse = "\\hhello\\e")
        val ghost = object : Ghost("test_ghost_id") {
            init {
                this.shiori = fakeShiori
            }
            override fun loadGhostInfo() {}
            override fun incrementCreateCount() {}
            override fun getCreateCount(): Long = 2L
        }
        val engine = ScriptEngine(ghost, testDispatcher, testDispatcher)
        engine.setGhost(ghost)

        // Start clock once
        engine.startClock()
        advanceTimeBy(1050) // 1 second
        val firstCallCount = fakeShiori.calls.filter { it.request.contains("ID: OnSecondChange") }.size

        // Start clock again
        engine.startClock()
        advanceTimeBy(1000) // another second
        val secondCallCount = fakeShiori.calls.filter { it.request.contains("ID: OnSecondChange") }.size

        // If clock was stacked, we would have 1 call in 1st sec, and 2 additional calls in 2nd sec (total 3).
        // Since it is idempotent, we should have exactly 1 additional call (total 2).
        assertEquals(2, secondCallCount)

        engine.cancel()
    }

    @Test
    fun startClockTwiceWithStop_doesNotRebootGhost() = runTest(testDispatcher) {
        val fakeShiori = FakeShiori(defaultResponse = "\\hhello\\e")
        val ghost = object : Ghost("test_ghost_id") {
            init {
                this.shiori = fakeShiori
            }
            override fun loadGhostInfo() {}
            override fun incrementCreateCount() {}
            override fun getCreateCount(): Long = 2L
        }
        val engine = ScriptEngine(ghost, testDispatcher, testDispatcher)
        engine.setGhost(ghost)

        // First clock start -> should boot
        engine.startClock()
        runCurrent()
        val bootCalls1 = fakeShiori.calls.filter { it.request.contains("ID: OnBoot") }.size
        assertEquals(1, bootCalls1)

        // Stop clock (simulates onPause)
        engine.stopClock()
        runCurrent()

        // Second clock start (simulates onResume) -> should NOT boot again
        engine.startClock()
        runCurrent()
        val bootCalls2 = fakeShiori.calls.filter { it.request.contains("ID: OnBoot") }.size
        assertEquals(1, bootCalls2)

        engine.cancel()
    }

    @Test
    fun engine_suspendsWhenQueueEmpty_resumesOnSend() = runTest(testDispatcher) {
        val customScope = CoroutineScope(testDispatcher + SupervisorJob())
        val engine = ScriptEngine(null, testDispatcher, testDispatcher)
        customScope.bindViews(engine)

        // Start engine with empty queue
        customScope.launch { engine.run() }
        runCurrent() // should suspend on channel receive

        // Balloon text should be empty
        assertNull(bSakura?.dispText)

        // Send a message
        engine.addMsgToQueue(arrayOf("\\habc\\e"))
        runCurrent() // should resume and start processing
        
        // Let it run to completion
        advanceUntilIdle()
        assertEquals("abc", bSakura?.dispText)

        customScope.cancel()
    }
}
