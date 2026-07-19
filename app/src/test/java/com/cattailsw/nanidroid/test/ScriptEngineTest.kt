package com.cattailsw.nanidroid.test

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cattailsw.nanidroid.*
import com.cattailsw.nanidroid.ui.main.MainScreenViewModel
import com.cattailsw.nanidroid.shiori.Shiori
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
            engine.uiState.collect { state ->
                sakura?.changeSurface(state.sakuraSurfaceId)
                kero?.changeSurface(state.keroSurfaceId)

                if (state.sakuraBalloonVisible) {
                    bSakura?.setText(state.sakuraBalloonText)
                    if (state.sakuraAnimationId == null && state.talkAnimeControl == 0) {
                        sakura?.startTalkingAnimation()
                    }
                }
                
                if (state.keroBalloonVisible) {
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
        val deferredThread = CompletableDeferred<Thread>()
        val fakeShiori = object : Shiori {
            override fun getModuleName(): String = "Fake"
            override fun terminate() {}
            override fun unloadShiori() {}
            override fun request(req: String): String {
                deferredThread.complete(Thread.currentThread())
                return "SHIORI/3.0 200 OK\r\nSender: Fake\r\nValue: \\hhi\\e\r\nCharset: UTF-8\r\n\r\n"
            }
        }
        val ghost = object : Ghost("testPath") {
            init {
                this.shiori = fakeShiori
            }
            override fun loadGhostInfo() {}
            override fun incrementCreateCount() {}
            override fun getCreateCount(): Long = 1L
        }
        val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val engine = ScriptEngine(ghost, thread, Dispatchers.Main)
        engine.setGhost(ghost)
        engine.startClock()

        val jniThread = withTimeout(2000) { deferredThread.await() }

        engine.stopClock()
        engine.cancel()
        thread.close()

        assertFalse(jniThread.name.contains("main", ignoreCase = true))
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
        val customScope = CoroutineScope(testDispatcher + SupervisorJob())
        val engine = ScriptEngine(null, testDispatcher, testDispatcher)
        
        customScope.launch {
            engine.uiState.collect { state ->
                bSakura?.setText(state.sakuraBalloonText)
            }
        }

        engine.addMsgToQueue(arrayOf("\\_q\\hlalala\\e"))
        customScope.launch { 
            engine.run() 
        }
        advanceUntilIdle()
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
        advanceTimeBy(2000)
        assertEquals("0", sakura?.stext)

        t = "\\s[120]\\e"
        engine.addMsgToQueue(arrayOf(t))
        advanceTimeBy(2000)
        assertEquals("120", sakura?.sid)
        assertEquals("0,120", sakura?.stext)

        t = "\\h\\s10wrong\\s[10]\\e"
        engine.addMsgToQueue(arrayOf(t))
        advanceTimeBy(2000)
        assertEquals("10", sakura?.sid)
        assertEquals("0,120,1,10", sakura?.stext)
        assertEquals("0wrong", bSakura?.dispText)

        t = "\\t\\h\\s[20]\\n\\w9\\u\\s[10]\\n\\h\\s0"
        engine.addMsgToQueue(arrayOf(t))
        advanceTimeBy(5000)
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
        customScope.bindViews(engine)

        val cmd = "\\habcde\\e"
        
        // 1. Without callback registered
        engine.setStatusCallback(null)
        engine.addMsgToQueue(arrayOf(cmd))
        val job1 = customScope.launch { engine.run() }
        advanceUntilIdle()
        assertFalse(statusStopCalled)
        job1.cancel()

        // 2. With callback registered
        engine.setStatusCallback(statusCallback)
        engine.addMsgToQueue(arrayOf(cmd))
        val job2 = customScope.launch { engine.run() }
        
        // Before completion
        advanceTimeBy(100)
        runCurrent()
        assertFalse(statusStopCalled)

        // After completion
        advanceUntilIdle()
        assertTrue(statusStopCalled)
        job2.cancel()

        customScope.cancel()
    }

    @Test
    fun testChoiceWithRunner() = runTest(testDispatcher) {
        val customScope = CoroutineScope(testDispatcher + SupervisorJob())
        val fakeShiori = FakeShiori(
            responses = mapOf(
                "OnChoiceSelect" to "\\habcdechosen!\\e"
            )
        )
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
        engine.setUICallback(uiCallback)
        customScope.bindViews(engine)

        val s = "\\0abcde\\q[fgh,lalala asda]ijk\\q[lmno,lalala aaaa]\\e"
        engine.addMsgToQueue(arrayOf(s))
        customScope.launch { engine.run() }
        advanceUntilIdle()

        assertEquals("abcde", bSakura?.dispText)
        assertTrue(selectionCallbackCalled)

        engine.doOnChoiceSelect("lalala asda")
        advanceUntilIdle()

        assertEquals("abcdechosen!", bSakura?.dispText)
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

    @Test
    fun getStringValueFromShiori_retrievesValueFromShiori() = runTest(testDispatcher) {
        val fakeShiori = FakeShiori(responses = mapOf("homeurl" to "http://example.com"))
        val ghost = object : Ghost("testPath") {
            init {
                this.shiori = fakeShiori
            }
            override fun loadGhostInfo() {}
            override fun incrementCreateCount() {}
            override fun getCreateCount(): Long = 1L
        }

        val engine = ScriptEngine(ghost, testDispatcher, testDispatcher)
        val value = engine.getStringValueFromShiori("homeurl")
        assertEquals("http://example.com", value)
    }

    @Test
    fun userInput_clearsQueueResumesEngineAndPlaysResponse() = runTest(testDispatcher) {
        val fakeShiori = FakeShiori(
            responses = mapOf("OnUserInput" to "\\hresponse_from_input\\e"),
            defaultResponse = ""
        )
        val ghost = object : Ghost("testPath") {
            init {
                this.shiori = fakeShiori
            }
            override fun loadGhostInfo() {}
            override fun incrementCreateCount() {}
            override fun getCreateCount(): Long = 1L
        }

        var inputShown = false
        var inputId: String? = null
        val customUiCallback = object : SScriptRunner.UICallback {
            override fun showUserInputBox(id: String) {
                inputShown = true
                inputId = id
            }
            override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) {}
        }

        val engine = ScriptEngine(ghost, testDispatcher, testDispatcher)
        engine.setGhost(ghost)
        engine.setUICallback(customUiCallback)

        val customScope = CoroutineScope(testDispatcher + SupervisorJob())
        customScope.bindViews(engine)

        engine.addMsgToQueue(arrayOf("\\habc\\![open,inputbox,inputid]\\hdef\\e"))
        customScope.launch { engine.run() }
        advanceUntilIdle()

        assertTrue(inputShown)
        assertEquals("inputid", inputId)
        assertEquals("abc", bSakura?.dispText)

        engine.doUserInput("inputid", "user_entered_text")
        advanceUntilIdle()

        val inputCall = fakeShiori.calls.firstOrNull { it.request.contains("ID: OnUserInput") }
        assertNotNull(inputCall)
        assertTrue(inputCall!!.request.contains("Reference0: inputid"))
        assertTrue(inputCall.request.contains("Reference1: user_entered_text"))

        assertEquals("response_from_input", bSakura?.dispText)
        customScope.cancel()
    }

    @Test
    fun normalCompletion_triggersStop_clearsBalloonAndResetsSurfaces() = runTest(testDispatcher) {
        val customScope = CoroutineScope(testDispatcher + SupervisorJob())
        val engine = ScriptEngine(null, testDispatcher, testDispatcher)
        customScope.bindViews(engine)

        engine.addMsgToQueue(arrayOf("\\h\\s[5]\\u\\s[15]abc\\e"))
        customScope.launch { engine.run() }
        advanceUntilIdle()

        advanceTimeBy(5500)
        runCurrent()

        assertEquals("0", engine.uiState.value.sakuraSurfaceId)
        assertEquals("10", engine.uiState.value.keroSurfaceId)
        assertFalse(engine.uiState.value.sakuraBalloonVisible)
        assertFalse(engine.uiState.value.keroBalloonVisible)
        customScope.cancel()
    }

    @Test
    fun cancellation_guardsAgainstDoubleStop() = runTest(testDispatcher) {
        val customScope = CoroutineScope(testDispatcher + SupervisorJob())
        var stopCount = 0
        val customStatusCallback = object : SScriptRunner.StatusCallback {
            override fun stop() {
                stopCount++
            }
            override fun canExit() {}
            override fun ghostSwitchScriptComplete() {}
        }
        val engine = ScriptEngine(null, testDispatcher, testDispatcher)
        engine.setStatusCallback(customStatusCallback)
        customScope.bindViews(engine)

        engine.addMsgToQueue(arrayOf("\\habc\\e"))
        customScope.launch { engine.run() }
        advanceTimeBy(50)
        runCurrent()

        engine.clearMsgQueue()
        advanceUntilIdle()

        assertEquals(1, stopCount)
        customScope.cancel()
    }

    @Test
    fun newScript_cancelsPendingResetSurfaceJob() = runTest(testDispatcher) {
        val customScope = CoroutineScope(testDispatcher + SupervisorJob())
        val engine = ScriptEngine(null, testDispatcher, testDispatcher)
        customScope.bindViews(engine)

        // 1. Queue first script: execution takes 1150ms (3 chars * 50ms + 1000ms wait at \e)
        engine.addMsgToQueue(arrayOf("\\h\\s[5]abc\\e"))
        customScope.launch { engine.run() }
        
        // Advance 2000ms total. 
        // Script 1 finishes at 1150ms. So reset job has been running for 850ms.
        advanceTimeBy(2000)
        runCurrent()
        assertEquals("5", engine.uiState.value.sakuraSurfaceId)

        // 2. Queue second script: should cancel the pending reset job
        engine.addMsgToQueue(arrayOf("\\h\\s[8]def\\e"))
        
        // Advance another 2000ms.
        // Second script starts at 2000ms, takes 1150ms (finishes at 3150ms).
        // A new reset job is scheduled to run at 3150 + 5000 = 8150ms.
        // At 4000ms (2000 + 2000), surface ID should be "8".
        advanceTimeBy(2000)
        runCurrent()
        assertEquals("8", engine.uiState.value.sakuraSurfaceId)

        // 3. Advance to 7000ms (another 3000ms).
        // If the first reset job was NOT cancelled, it would have fired at 1150ms + 5000ms = 6150ms, resetting surface to 0.
        // Since it was cancelled, surface should still be "8".
        // At 7000ms, we are still before the second reset job's trigger (8150ms).
        advanceTimeBy(3000)
        runCurrent()
        assertEquals("8", engine.uiState.value.sakuraSurfaceId)

        // 4. Advance past second reset job trigger (8150ms, so 2000ms more = 9000ms)
        advanceTimeBy(2000)
        runCurrent()
        assertEquals("0", engine.uiState.value.sakuraSurfaceId)

        customScope.cancel()
    }

    @Test
    fun sequentialJniCalls_separatedByDelay_executeOnSameThread() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "NanidroidJniThread") }.asCoroutineDispatcher()
        val fakeShiori = FakeShiori(defaultResponse = "\\hhello\\e")
        val ghost = object : Ghost("testPath") {
            init {
                this.shiori = fakeShiori
            }
            override fun loadGhostInfo() {}
            override fun incrementCreateCount() {}
            override fun getCreateCount(): Long = 1L
        }
        val engine = ScriptEngine(ghost, dispatcher, Dispatchers.Main)
        engine.setGhost(ghost)

        engine.doShioriEvent("Event1", null)
        delay(100)
        engine.doShioriEvent("Event2", null)
        delay(100)

        engine.cancel()
        dispatcher.close()

        val calls = fakeShiori.calls
        assertTrue(calls.size >= 2)
        val thread1 = calls[0].thread
        val thread2 = calls[1].thread
        assertEquals(thread1, thread2)
        assertEquals("NanidroidJniThread", thread1.name)
    }

    @Test
    fun mainScreenViewModel_onCleared_nullifiesRunnerUICallback() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val runner = SScriptRunner.getInstance(application)
        
        val viewModel = MainScreenViewModel(application)
        viewModel.init()
        
        val ucbField = SScriptRunner::class.java.getDeclaredField("ucb").apply { isAccessible = true }
        assertNotNull(ucbField.get(runner))

        val onClearedMethod = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared").apply {
            isAccessible = true
        }
        onClearedMethod.invoke(viewModel)
        assertNull(ucbField.get(runner))
    }

    @Test
    fun cleanupR7_clearsStateAndStopsJobs() = runTest(testDispatcher) {
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
        engine.addMsgToQueue(arrayOf("\\hhello\\e"))

        engine.setGhost(null)
        engine.stopClock()
        engine.clearMsgQueue()

        val clockJobField = ScriptEngine::class.java.getDeclaredField("clockJob").apply { isAccessible = true }
        assertNull(clockJobField.get(engine))

        val ghostField = ScriptEngine::class.java.getDeclaredField("g").apply { isAccessible = true }
        assertNull(ghostField.get(engine))

        val scriptJobField = ScriptEngine::class.java.getDeclaredField("scriptJob").apply { isAccessible = true }
        assertNull(scriptJobField.get(engine))

        engine.cancel()
    }

    @Test
    fun runner_clearViews_nullifiesReferencesAndCallbacks() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val runner = SScriptRunner.getInstance(application)
        val sakuraView = DummySakuraView(application)
        val keroView = DummyKeroView(application)
        runner.setViews(sakuraView, keroView, null, null)
        
        val sakuraRefField = SScriptRunner::class.java.getDeclaredField("sakuraRef").apply { isAccessible = true }
        assertNotNull(sakuraRefField.get(runner))

        runner.clearViews()
        assertNull(sakuraRefField.get(runner))
    }

    @Test
    fun setGhostNull_unloadsGhostNativeResources() = runTest(testDispatcher) {
        var unloadCalled = false
        val ghost = object : Ghost("testPath") {
            override fun loadGhostInfo() {}
            override fun incrementCreateCount() {}
            override fun getCreateCount(): Long = 1L
            override fun unload() {
                unloadCalled = true
            }
        }
        val engine = ScriptEngine(ghost, testDispatcher, testDispatcher)
        engine.setGhost(ghost)
        advanceUntilIdle()
        assertFalse(unloadCalled)

        engine.setGhost(null)
        // unload() is dispatched onto the engine dispatcher (R5: JNI pinned off-Main), so it runs
        // only after the scheduler advances — not synchronously on the caller's thread.
        advanceUntilIdle()
        assertTrue(unloadCalled)
    }

    @Test
    fun setGhost_dispatchesOutgoingUnloadToEngineDispatcher_notSynchronously() = runTest(testDispatcher) {
        var unloadCount = 0
        val ghostA = object : Ghost("pathA") {
            override fun loadGhostInfo() {}
            override fun incrementCreateCount() {}
            override fun getCreateCount(): Long = 1L
            override fun unload() { unloadCount++ }
        }
        val ghostB = object : Ghost("pathB") {
            override fun loadGhostInfo() {}
            override fun incrementCreateCount() {}
            override fun getCreateCount(): Long = 1L
        }

        val engine = ScriptEngine(null, testDispatcher, testDispatcher)
        engine.setGhost(ghostA)
        engine.setGhost(ghostB)

        // The unload of the outgoing ghostA must NOT run synchronously on the caller (main) thread —
        // it is dispatched to the single engine thread to serialize with in-flight doShioriEvent and
        // keep JNI/disk I/O off the UI thread. Before advancing, nothing has run.
        assertEquals("unload must be dispatched, not run on the caller thread", 0, unloadCount)

        advanceUntilIdle()
        assertEquals("outgoing ghostA unloaded exactly once on the engine dispatcher", 1, unloadCount)
    }

    @Test
    fun setGhost_switchingGhosts_unloadsOutgoingOnly() = runTest(testDispatcher) {
        var ghostAUnloadCount = 0
        var ghostBUnloadCount = 0

        // ghostDirName is derived from the path, and getGhostName() reads ghostDesc["name"]
        // which is empty for test ghosts — that's fine, setGhost uses reference equality (g != ghost).
        val ghostA = object : Ghost("pathA") {
            override fun loadGhostInfo() {}
            override fun incrementCreateCount() {}
            override fun getCreateCount(): Long = 1L
            override fun unload() { ghostAUnloadCount++ }
        }
        val ghostB = object : Ghost("pathB") {
            override fun loadGhostInfo() {}
            override fun incrementCreateCount() {}
            override fun getCreateCount(): Long = 1L
            override fun unload() { ghostBUnloadCount++ }
        }

        val engine = ScriptEngine(null, testDispatcher, testDispatcher)
        engine.setGhost(ghostA)
        engine.setGhost(ghostB)
        // Drain the dispatched unload(s).
        advanceUntilIdle()
        assertEquals("ghostA should be unloaded exactly once", 1, ghostAUnloadCount)
        assertEquals("ghostB (incoming) must NOT be unloaded", 0, ghostBUnloadCount)
    }

    @Test
    fun setGhost_sameGhostTwice_doesNotUnload() = runTest(testDispatcher) {
        var unloadCount = 0
        val ghostA = object : Ghost("pathA") {
            override fun loadGhostInfo() {}
            override fun incrementCreateCount() {}
            override fun getCreateCount(): Long = 1L
            override fun unload() { unloadCount++ }
        }

        val engine = ScriptEngine(null, testDispatcher, testDispatcher)
        engine.setGhost(ghostA)
        engine.setGhost(ghostA)
        advanceUntilIdle()
        assertEquals("same ghost set twice: g != ghost guard prevents unload", 0, unloadCount)
    }

    @Test
    fun ghostSwitchCompletion_doesNotUnloadIncomingGhost() = runTest(testDispatcher) {
        // This test exercises the dangerous race:
        // 1. doGhostChanging() sets changingPending=true with ghostA as current
        // 2. ghostA's OnGhostChanging script finishes, stop() fires
        // 3. stop()'s changingPending branch calls ghostSwitchScriptComplete, which the host uses
        //    to call setGhost(ghostB) — setGhost correctly unloads ghostA (outgoing)
        // 4. If stop()'s changingPending branch ALSO calls g?.unload() AFTER ghostSwitchScriptComplete,
        //    it would now unload ghostB (the incoming ghost) — the bug we're fixing.
        var ghostAUnloadCount = 0
        var ghostBUnloadCount = 0

        val ghostA = object : Ghost("pathA") {
            init {
                this.shiori = FakeShiori(responses = mapOf(
                    "OnGhostChanging" to "\\hSwitching!\\e"
                ))
            }
            override fun loadGhostInfo() {}
            override fun incrementCreateCount() {}
            override fun getCreateCount(): Long = 2L
            override fun unload() { ghostAUnloadCount++ }
        }
        val ghostB = object : Ghost("pathB") {
            init { this.shiori = FakeShiori(defaultResponse = "") }
            override fun loadGhostInfo() {}
            override fun incrementCreateCount() {}
            override fun getCreateCount(): Long = 1L
            override fun unload() { ghostBUnloadCount++ }
        }

        val engine = ScriptEngine(null, testDispatcher, testDispatcher)
        engine.setGhost(ghostA)

        // Track when ghostSwitchScriptComplete fires so we can simulate host calling setGhost(ghostB)
        var switchCompleteCount = 0
        engine.setStatusCallback(object : SScriptRunner.StatusCallback {
            override fun stop() {}
            override fun canExit() {}
            override fun ghostSwitchScriptComplete() {
                switchCompleteCount++
                // Simulate the host swapping ghost in response to ghostSwitchScriptComplete.
                // setGhost(ghostB) here correctly unloads ghostA (outgoing).
                engine.setGhost(ghostB)
            }
        })

        // Trigger the ghost-changing flow
        engine.doGhostChanging("GhostB", "change", "/path/to/ghostB")

        // Run the engine until the OnGhostChanging script completes and stop() fires
        val customScope = CoroutineScope(testDispatcher + SupervisorJob())
        customScope.launch { engine.run() }
        advanceUntilIdle()

        // ghostSwitchScriptComplete should have fired exactly once
        assertEquals("ghostSwitchScriptComplete should fire once", 1, switchCompleteCount)

        // ghostA should be unloaded exactly once (by setGhost(ghostB) inside the callback)
        assertEquals("outgoing ghostA must be unloaded exactly once", 1, ghostAUnloadCount)

        // CRITICAL: ghostB (the incoming ghost) must NEVER be unloaded
        assertEquals("incoming ghostB must NOT be unloaded by stop()'s changingPending branch", 0, ghostBUnloadCount)

        customScope.cancel()
    }
}

