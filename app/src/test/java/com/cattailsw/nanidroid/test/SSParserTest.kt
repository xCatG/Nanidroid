package com.cattailsw.nanidroid.test

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.cattailsw.nanidroid.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Vector
import java.util.regex.Matcher
import java.util.regex.Pattern

@RunWith(RobolectricTestRunner::class)
class SSParserTest {

    companion object {
        private const val TAG = "SSParserTest"
    }

    lateinit var mContext: Context
    var sakura: DummySakuraView? = null
    var kero: DummyKeroView? = null
    var bSakura: DummyBalloon? = null
    var bKero: DummyBalloon? = null
    var sr: SScriptRunner? = null

    inner class DummyKeroView(ctx: Context) : KeroView(ctx) {
        var sid: String? = null
        var aid: String? = null
        var aidz: String? = null

        override fun changeSurface(id: String) {
            if (this.sid == null) {
                this.sid = id
            } else {
                this.sid = "${this.sid},$id"
            }
        }

        override fun loadAnimation(id: String) {
            aid = id
            if (aidz == null) {
                aidz = id
            } else {
                aidz += ",$id"
            }
        }

        override fun startTalkingAnimation() {}
        override fun startAnimation() {}
    }

    inner class DummySakuraView(ctx: Context) : SakuraView(ctx) {
        var sid: String? = null
        var stext: String? = null
        var talkCalledTime = 0
        var aid: String? = null
        var aidz: String? = null

        override fun changeSurface(id: String) {
            Log.d(TAG, " sid => $id")
            if (this.sid == null) {
                this.sid = id
                this.stext = id
            } else if (!sid.equals(id, ignoreCase = true)) {
                this.sid = id
                this.stext = "${this.stext},$id"
            }
        }

        override fun startTalkingAnimation() {
            talkCalledTime++
            Log.d(TAG, "startTalkingAnimation called $talkCalledTime times")
        }

        override fun loadAnimation(id: String) {
            aid = id
            if (aidz == null) {
                aidz = id
            } else {
                aidz += ",$id"
            }
        }

        override fun startAnimation() {}
    }

    inner class DummyBalloon(ctx: Context) : Balloon(ctx) {
        var dispText: String? = null
        var textVal: String? = null

        override fun setText(str: String) {
            Log.d(TAG, "got text:$str")
            if (this.textVal == null) {
                this.textVal = str
                dispText = str
            } else {
                dispText = str
                this.textVal = this.textVal + str
            }
        }
    }

    var stopCalled = false
    private val c = object : SScriptRunner.StatusCallback {
        override fun stop() {
            stopCalled = true
        }

        override fun canExit() {}
        override fun ghostSwitchScriptComplete() {}
    }

    @Before
    fun setUp() {
        mContext = ApplicationProvider.getApplicationContext()
        sakura = DummySakuraView(mContext)
        kero = DummyKeroView(mContext)
        bSakura = DummyBalloon(mContext)
        bKero = DummyBalloon(mContext)
        sr = SScriptRunner.getInstance(mContext)
        sr!!.clearMsgQueue()
        sr!!.setViews(sakura, kero, bSakura, bKero)
        sr!!.setNoWaitMode(true)
    }

    @After
    fun tearDown() {
        sr?.stop()
    }

    @Test
    fun testSakuraSpeak() {
        sr!!.stop()
        val cmd = "\\_q\\hlalala\\e"
        sr!!.addMsgToQueue(arrayOf(cmd))
        sr!!.run()
        assertEquals("lalala", bSakura!!.textVal)

        val cmd2 = "\\_q\\0abcdefg\\n"
        sr!!.addMsgToQueue(arrayOf(cmd2))
        sr!!.run()
        assertEquals("lalalaabcdefg\n", bSakura!!.textVal)
    }

    @Test
    fun testSakuraSpeakNormal() {
        val cmd = "\\habcde\\e"
        sr!!.addMsgToQueue(arrayOf(cmd))
        sr!!.run()
        assertEquals("aababcabcdabcdeabcde", bSakura!!.textVal)
    }

    @Test
    fun testKeroSpeak() {
        val cmd = "\\_q\\1xxxxxx\\e"
        sr!!.addMsgToQueue(arrayOf(cmd))
        sr!!.run()
        assertEquals("xxxxxx", bKero!!.textVal)

        val cmd2 = "\\_q\\habcde\\uyyyyyy\\e"
        sr!!.addMsgToQueue(arrayOf(cmd2))
        sr!!.run()
        assertEquals("xxxxxxyyyyyy", bKero!!.textVal)
        sr!!.stop()
    }

    @Test
    fun testIgnoreCommands() {
        var cmd = "\\4\\5\\6\\v\\_n\\_V\\e"
        sr!!.addMsgToQueue(arrayOf(cmd))
        sr!!.run()

        cmd = "\\_q\\habcde\\_l[100]\\_a[45]\\_v[000]fghijk\\_n\\_V\\e"
        sr!!.addMsgToQueue(arrayOf(cmd))
        sr!!.run()
        assertEquals("abcdefghijk", bSakura!!.textVal)
    }

    @Test
    fun testParsingRegExp() {
        var sqbreket_tester = "[half]efghi"
        var m = PatternHolders.sqbracket_half_number.matcher(sqbreket_tester)
        assertTrue(m.find())
        assertEquals("[half]", m.group())
        assertEquals("half", m.group(1))

        sqbreket_tester = "[100]ksjdlaksjklwje"
        m = PatternHolders.sqbracket_half_number.matcher(sqbreket_tester)
        assertTrue(m.find())
        assertEquals("[100]", m.group())
        assertEquals("100", m.group(1))

        sqbreket_tester = "[100%]ksjdlaksjklwje"
        m = PatternHolders.sqbracket_half_number.matcher(sqbreket_tester)
        assertTrue(m.find())
        assertEquals("100%", m.group(1))

        sqbreket_tester = "[1xaw]"
        m = PatternHolders.sqbracket_half_number.matcher(sqbreket_tester)
        assertFalse(m.find())

        sqbreket_tester = "[]"
        m = PatternHolders.sqbracket_half_number.matcher(sqbreket_tester)
        assertFalse(m.find())
    }

    @Test
    fun testParsingSurfaceRegExp() {
        var t = "[10]"
        var m = PatternHolders.surface_ptrn.matcher(t)
        assertTrue(m.find())

        t = "0"
        m = PatternHolders.surface_ptrn.matcher(t)
        assertTrue(m.find())

        t = "9"
        m = PatternHolders.surface_ptrn.matcher(t)
        assertTrue(m.find())

        t = "a"
        m = PatternHolders.surface_ptrn.matcher(t)
        assertFalse(m.find())

        t = "[a]"
        m = PatternHolders.surface_ptrn.matcher(t)
        assertFalse(m.find())

        t = "[-1]"
        m = PatternHolders.surface_ptrn.matcher(t)
        assertTrue(m.find())
    }

    @Test
    fun testSurfaceChangeSakura() {
        sakura = DummySakuraView(mContext)
        bSakura = DummyBalloon(mContext)
        sr!!.setViews(sakura, kero, bSakura, bKero)
        var t = "\\h\\s0\\e"
        sr!!.addMsgToQueue(arrayOf(t))
        sr!!.run()
        assertEquals("0", sakura!!.stext)

        t = "\\s[120]\\e"
        sr!!.addMsgToQueue(arrayOf(t))
        sr!!.run()
        assertEquals("120", sakura!!.sid)
        assertEquals("0,120", sakura!!.stext)

        t = "\\h\\s10wrong\\s[10]\\e"
        sr!!.addMsgToQueue(arrayOf(t))
        sr!!.run()
        assertEquals("10", sakura!!.sid)
        assertEquals("0,120,1,10", sakura!!.stext)
        assertEquals("0wrong", bSakura!!.dispText)

        t = "\\t\\h\\s[20]\\n\\w9\\u\\s[10]\\n\\h\\s0"
        sr!!.addMsgToQueue(arrayOf(t))
        sr!!.run()
        assertEquals("0", sakura!!.sid)
        assertEquals("0,120,1,10,20,0", sakura!!.stext)
        assertEquals("\n", bSakura!!.dispText)
    }

    @Test
    fun testParsingAnimationRegExp() {
        var t = "[0]"
        var m = PatternHolders.ani_ptrn.matcher(t)
        assertTrue(m.find())
        assertEquals("0", m.group(1))

        t = "[10,wait]"
        m = PatternHolders.ani_ptrn.matcher(t)
        assertTrue(m.find())
        assertEquals("10", m.group(1))

        t = "[abc,xxx]"
        m = PatternHolders.ani_ptrn.matcher(t)
        assertFalse(m.find())

        t = "[102,]"
        m = PatternHolders.ani_ptrn.matcher(t)
        assertFalse(m.find())

        t = "[-1,wait]"
        m = PatternHolders.ani_ptrn.matcher(t)
        assertFalse(m.find())
    }

    @Test
    fun testAnimation() {
        sakura = DummySakuraView(mContext)
        sr!!.setViews(sakura, kero, bSakura, bKero)

        val cmd = "\\halala\\i[0]opqrstmnopqrst\\e"
        sr!!.addMsgToQueue(arrayOf(cmd))
        sr!!.run()

        assertEquals(2, sakura!!.talkCalledTime)
        assertEquals("0", sakura!!.aid)
    }

    @Test
    fun testCallback() {
        stopCalled = false
        val cmd = "\\habcde\\e"
        sr!!.addMsgToQueue(arrayOf(cmd))
        sr!!.run()
        assertFalse(stopCalled)

        sr!!.setCallback(c)
        sr!!.addMsgToQueue(arrayOf(cmd))
        sr!!.run()
        assertTrue(stopCalled)
    }

    @Test
    fun testUrlFilterPattern() {
        val url = Pattern.compile(".*.nar")
        val s = "http://xxx.xx.xx/xxx/xxx.nar"
        var m = url.matcher(s)
        assertTrue(m.find())

        m = PatternHolders.url_ptrn.matcher(s)
        assertTrue(m.find())

        val s2 = "https://xxx.xxx.xxx/xxx/xxx.nar"
        m = PatternHolders.url_ptrn.matcher(s2)
        assertTrue(m.find())

        val s3 = "http://11.22.33.44/~xxx/asdasdas_asdas/xx.nar"
        m = PatternHolders.url_ptrn.matcher(s3)
        assertTrue(m.find())

        val s4 = "http://1.2.3.4/~%20easdjkasjdk/asjdklasjkl/sajdkls.asdkjl.nar"
        m = PatternHolders.url_ptrn.matcher(s4)
        assertTrue(m.find())

        val s5 = "http://11.22.33.44/~xxx/asdasdas_asdas/xx.zip"
        m = PatternHolders.url_ptrn.matcher(s5)
        assertTrue(m.find())

        val s6 = "http://1.2.3.4/~%20easdjkasjdk/asjdklasjkl/sajdkls.asdkjl_.zip"
        m = PatternHolders.url_ptrn.matcher(s6)
        assertTrue(m.find())

        val s7 = "http://www.google.com/"
        m = PatternHolders.url_ptrn.matcher(s7)
        assertFalse(m.find())

        val s8 = "http://www.google.com/.mp3"
        m = PatternHolders.url_ptrn.matcher(s8)
        assertFalse(m.find())
    }

    var uscbCalled = false
    private val tcwr_ucb = object : SScriptRunner.UICallback {
        override fun showUserInputBox(id: String) {}
        override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) {
            uscbCalled = true
            Log.d(TAG, "showUserSelection called")
            assertEquals(2, textlabel.size)
            assertEquals(2, ids.size)
            assertEquals("fgh", textlabel[0])
            assertEquals("lmno", textlabel[1])
            assertEquals("lalala asda", ids[0])
            assertEquals("lalala aaaa", ids[1])
        }
    }

    @Test
    fun testChoiceWithRunner() {
        val s = "\\0abcde\\q[fgh,lalala asda]ijk\\q[lmno,lalala aaaa]\\e"
        bSakura = DummyBalloon(mContext)
        sr!!.setViews(sakura, kero, bSakura, bKero)
        uscbCalled = false
        sr!!.setUICallback(tcwr_ucb)
        sr!!.addMsgToQueue(arrayOf(s))
        sr!!.run()

        Log.d(TAG, "sakura text" + bSakura!!.dispText)
        assertEquals("abcdefghijklmno", bSakura!!.dispText)
        assertTrue(uscbCalled)
        sr!!.setUICallback(null)
    }

    @Test
    fun testQChoice() {
        var s = "[abc,asdkllaskdl;asdkl asdsa]"
        var m = PatternHolders.sqbracket_q_title.matcher(s)
        assertTrue(m.find())
        assertEquals(3, m.groupCount())
        assertEquals("abc", m.group(1))
        assertEquals("asdkllaskdl;asdkl asdsa", m.group(2))
        assertEquals("", m.group(3))

        s = "[abc asds,asdkllaskdl;asdkl asdsa,askdlaks;,qwewqew]"
        m = PatternHolders.sqbracket_q_title.matcher(s)
        assertTrue(m.find())
        assertEquals("abc asds", m.group(1))
        assertEquals("asdkllaskdl;asdkl asdsa", m.group(2))
        assertEquals("askdlaks;,qwewqew", m.group(3))

        s = "[abc asds,asdkllaskdl asdkl asdsa 1,askdlaks;,qwewqew]"
        m = PatternHolders.sqbracket_q_title.matcher(s)
        assertTrue(m.find())

        s = "[abc,asds]\\nasdkllaskdl asdkl asdsa 1,askdlaks;,qwewqew]"
        m = PatternHolders.sqbracket_q_title.matcher(s)
        assertTrue(m.find())
        assertEquals("abc", m.group(1))
        assertEquals("asds", m.group(2))

        s = "[aagasdds asdqwewe]"
        m = PatternHolders.sqbracket_q_title.matcher(s)
        assertFalse(m.find())

        s = "[qkw qwe qwewe,OnXXXXXX XXXX]"
        m = PatternHolders.sqbracket_q_title.matcher(s)
        assertTrue(m.find())

        m = PatternHolders.sqbracket_q_withOn.matcher(s)
        assertTrue(m.find())
        assertEquals("qkw qwe qwewe", m.group(1))
        assertEquals("OnXXXXXX XXXX", m.group(2))
    }

    @Test
    fun testFindQ() {
        var s = "[a,xxx]\\n\\q[b,yyy]\\n\\q[c,zzz]\\n\\e"
        var m = PatternHolders.sqbracket_q_title.matcher(s)
        assertTrue(m.find())
        Log.d(TAG, "matched:" + m.group())
        val vtext = Vector<String>()
        val vidz = Vector<String>()

        vtext.add(m.group(1))
        vidz.add(m.group(2))
        val l = s.substring(m.group().length)
        Log.d(TAG, "left=$l")
        m = PatternHolders.q_choice_ptrn.matcher(l)

        assertTrue(m.find())
        assertEquals("b", m.group(1))
        assertEquals("yyy", m.group(2))
        vtext.add(m.group(1))
        vidz.add(m.group(2))
        while (m.find()) {
            vtext.add(m.group(1))
            vidz.add(m.group(2))
        }

        assertEquals(3, vtext.size)
        assertEquals(3, vidz.size)

        s = "\\q[a,sdkjkl asdas,lwkel;qwk]\\nakjkaljsdkladkjlasd\\q[aksdjkkjkl, oqwjeop lalk;,aijpqjl ,asldkl;]\\n\\e"
        m = PatternHolders.q_choice_ptrn.matcher(s)
        assertTrue(m.find())
        assertEquals("a", m.group(1))
        assertEquals("sdkjkl asdas", m.group(2))

        assertTrue(m.find())
        assertEquals("aksdjkkjkl", m.group(1))
        assertEquals(" oqwjeop lalk;", m.group(2))
    }

    @Test
    fun testReplaceQ() {
        var s = "\\q[aaaa,bbbb]\\n\\q[bbbb,askjdaklsdj]\\n\\q[cccc,askjasd]\\e"
        var m = PatternHolders.q_choice_ptrn.matcher(s)
        while (m.find()) {
            s = m.replaceFirst(m.group(1))
            m = PatternHolders.q_choice_ptrn.matcher(s)
        }
        assertEquals("aaaa\\nbbbb\\ncccc\\e", s)
    }

    @Test
    fun testInputCmd() {
        var s = "[open,inputbox,XXXX XXX XXXX]"
        var m = PatternHolders.open_input.matcher(s)
        assertTrue(m.find())
        assertEquals(1, m.groupCount())
        assertEquals("XXXX XXX XXXX", m.group(1))

        s = "[abc asds,asdkllaskdl asdkl asdsa 1,askdlaks;,qwewqew]"
        m = PatternHolders.open_input.matcher(s)
        assertFalse(m.find())

        s = "[open,kakaka]"
        m = PatternHolders.open_input.matcher(s)
        assertFalse(m.find())
    }
}
