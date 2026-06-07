package com.cattailsw.nanidroid.test

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cattailsw.nanidroid.Ghost
import com.cattailsw.nanidroid.SScriptRunner
import com.cattailsw.nanidroid.test.support.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SelectionPauseTest {

    private lateinit var mContext: Context
    private var sakura: DummySakuraView? = null
    private var kero: DummyKeroView? = null
    private var bSakura: DummyBalloon? = null
    private var bKero: DummyBalloon? = null
    private var sr: SScriptRunner? = null
    private lateinit var fakeShiori: FakeShiori
    private lateinit var testGhost: TestGhost
    private lateinit var testDispatcher: TestDispatcher

    class TestGhost(ctx: Context) : Ghost("fake_path", ctx) {
        override fun loadGhostInfo() {}
        override fun incrementCreateCount() {}
        override fun getCreateCount(): Long = 2L
    }

    private var stopCalled = false
    private val c = object : SScriptRunner.StatusCallback {
        override fun stop() {
            stopCalled = true
        }
        override fun canExit() {}
        override fun ghostSwitchScriptComplete() {}
    }

    private var selectionCallbackCalled = false
    private var lastLabels: Array<String>? = null
    private var lastIds: Array<String>? = null

    private val ucb = object : SScriptRunner.UICallback {
        override fun showUserInputBox(id: String) {}
        override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) {
            selectionCallbackCalled = true
            lastLabels = textlabel
            lastIds = ids
        }
    }

    @Before
    fun setUp() {
        mContext = ApplicationProvider.getApplicationContext()
        testDispatcher = StandardTestDispatcher()
        sakura = DummySakuraView(mContext)
        kero = DummyKeroView(mContext)
        bSakura = DummyBalloon(mContext)
        bKero = DummyBalloon(mContext)

        fakeShiori = FakeShiori(
            responses = mapOf(
                "OnChoiceSelect" to "\\h\\s[0]chosen!\\e"
            )
        )
        testGhost = TestGhost(mContext).apply {
            shiori = fakeShiori
        }

        val runner = SScriptRunner(mContext, testDispatcher, testDispatcher)
        SScriptRunner.setTestInstance(runner)
        sr = runner

        sr!!.setViews(sakura, kero, bSakura, bKero)
        sr!!.setGhost(testGhost)
        sr!!.setUICallback(ucb)
        sr!!.setCallback(c)
        stopCalled = false
        selectionCallbackCalled = false
        lastLabels = null
        lastIds = null
    }

    @After
    fun tearDown() {
        sr?.setUICallback(null)
        sr?.setCallback(null)
        sr?.cancel()
        SScriptRunner.setTestInstance(null)
    }

    @Test
    fun choice_pausesRunner_andDoesNotDumpLabelsIntoBalloon() = runTest(testDispatcher) {
        val s = "\\0abcde\\q[fgh,id1]ijk\\q[lmno,id2]\\e"
        sr!!.addMsgToQueue(arrayOf(s))
        advanceUntilIdle()

        // Behavioral assertions for "paused":
        // 1. Balloon should show only the pre-choice text (abcde), not choice labels or post-choice text.
        assertEquals("abcde", bSakura!!.dispText)

        // 2. StatusCallback.stop() should NOT have been called.
        assertFalse(stopCalled)

        // 3. Selection callback should have been called.
        assertTrue(selectionCallbackCalled)
        assertArrayEquals(arrayOf("fgh", "lmno"), lastLabels)
        assertArrayEquals(arrayOf("id1", "id2"), lastIds)
    }

    @Test
    fun choiceSelect_discardsPausedScript_andFiresOnChoiceSelect() = runTest(testDispatcher) {
        val s = "\\0abcde\\q[fgh,id1]ijk\\q[lmno,id2]\\e"
        sr!!.addMsgToQueue(arrayOf(s))
        advanceUntilIdle()

        // Now fire selection
        sr!!.doOnChoiceSelect("id1")
        advanceUntilIdle()

        // Assert that FakeShiori got OnChoiceSelect with id1
        val choiceCall = fakeShiori.calls.firstOrNull { it.request.contains("ID: OnChoiceSelect") }
        assertNotNull("Should have recorded a call to OnChoiceSelect", choiceCall)
        assertTrue(choiceCall!!.request.contains("Reference0: id1"))

        // The script runner will have stopCalled = true from doOnChoiceSelect/stop,
        // and we expect the remaining text ("ijk", "lmno") never reaches the balloon.
        // Actually, we executed the new script "chosen!", so the final text in the balloon
        // should contain "chosen!", but NOT the remaining characters from the previous script ("ijk").
        assertTrue(bSakura!!.textVal?.contains("chosen!") == true)
        assertFalse(bSakura!!.textVal?.contains("ijk") == true)
    }
}
