package com.cattailsw.nanidroid.test

import android.content.Context
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.cattailsw.nanidroid.Ghost
import com.cattailsw.nanidroid.SakuraView
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
class TouchEventTest {

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
                "OnMouseClick" to "\\h\\s[0]clicked\\e"
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
    }

    @After
    fun tearDown() {
        sr?.clearViews()
        sr?.cancel()
        SScriptRunner.setTestInstance(null)
    }

    @Test
    fun mapToSurfaceCoords_calculatesCorrectCoordinates() {
        val mapped = SakuraView.mapToSurfaceCoords(
            x = 50f, y = 100f,
            viewWidth = 100, viewHeight = 200,
            origW = 50, origH = 50
        )
        assertEquals(25, mapped.x)
        assertEquals(25, mapped.y)
    }

    @Test
    fun onlyActionUp_emitsExactlyOneOnHit() {
        val view = SakuraView(mContext)
        val callback = RecordingUiCallback()
        view.setUiEventCallback(callback)

        val downTime = 1000L
        val eventTime = 1000L
        
        val downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, 10f, 20f, 0)
        view.onTouchEvent(downEvent)
        downEvent.recycle()

        val moveEvent = MotionEvent.obtain(downTime, eventTime + 50L, MotionEvent.ACTION_MOVE, 12f, 22f, 0)
        view.onTouchEvent(moveEvent)
        moveEvent.recycle()

        assertEquals(0, callback.events.size)

        val upEvent = MotionEvent.obtain(downTime, eventTime + 100L, MotionEvent.ACTION_UP, 10f, 20f, 0)
        view.onTouchEvent(upEvent)
        upEvent.recycle()

        assertEquals(1, callback.events.size)
    }

    @Test
    fun keroTouch_doesNotClearQueueMidScript() = runTest(testDispatcher) {
        val localFakeShiori = FakeShiori(
            responses = mapOf(
                "OnMouseClick" to "\\uclicked\\e"
            )
        )
        testGhost.shiori = localFakeShiori

        val cmd2 = "\\ughijk\\e"
        
        sr!!.addMsgToQueue(arrayOf(cmd2))
        
        assertNotNull(kero!!.mUCB)
        
        // Let's trigger a single click on Kero
        kero!!.mUCB!!.onHit(
            SakuraView.UIEventCallback.TYPE_SINGLE_CLICK,
            10, 20, 0, -1, 0
        )
        
        // Let all background processing execute
        advanceUntilIdle()
        
        // SScriptRunner should NOT have cleared the queue, so cmd2 is still processed.
        // The mock response to Kero single click is cmd1 ("clicked"), which is added to the queue.
        // Therefore, both "clicked" and "ghijk" should be processed.
        assertTrue(bKero!!.textVal?.contains("clicked") == true)
        assertTrue(bKero!!.textVal?.contains("ghijk") == true)
    }
}
