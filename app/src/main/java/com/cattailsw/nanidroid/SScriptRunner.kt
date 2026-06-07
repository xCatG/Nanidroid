package com.cattailsw.nanidroid

import android.content.Context
import java.lang.ref.WeakReference
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.View
import com.cattailsw.nanidroid.util.AnalyticsUtils
import java.io.BufferedReader
import java.io.StringReader
import java.util.ArrayList
import java.util.Collections
import java.util.Vector
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.regex.Matcher

class SScriptRunner private constructor(context: Context) : Runnable {
    private val mCtx: Context = context.applicationContext

    companion object {
        private const val TAG = "SScriptRunner"
        private var _self: SScriptRunner? = null

        const val WAIT_UNIT: Long = 50 // wait unit is 50ms
        const val WAIT_YEN_E: Long = 1000 // wait one second before clearing

        private const val RUN = 42
        private const val STOP = RUN + 1
        private const val INC_CLOCK = STOP + 1
        private const val RESET_SURFACE = INC_CLOCK + 1
        private const val CLOCK_STEP: Long = 1000
        private const val RESET_TIMEOUT: Long = 5000

        private val mMsgQueue = ConcurrentLinkedQueue<String>()

        @JvmStatic
        fun getInstance(ctx: Context): SScriptRunner {
            if (_self == null) {
                _self = SScriptRunner(ctx)
            }
            return _self!!
        }
    }

    interface StatusCallback {
        fun stop()
        fun canExit()
        fun ghostSwitchScriptComplete()
    }

    interface UICallback {
        fun showUserInputBox(id: String)
        fun showUserSelection(textlabel: Array<String>, ids: Array<String>)
    }

    private var layoutMgr: LayoutManager? = null
    private var sakuraRef: WeakReference<SakuraView>? = null
    private var keroRef: WeakReference<KeroView>? = null
    private var sakuraBalloonRef: WeakReference<Balloon>? = null
    private var keroBalloonRef: WeakReference<Balloon>? = null

    private val sakura: SakuraView? get() = sakuraRef?.get()
    private val kero: KeroView? get() = keroRef?.get()
    private val sakuraBalloon: Balloon? get() = sakuraBalloonRef?.get()
    private val keroBalloon: Balloon? get() = keroBalloonRef?.get()

    private var g: Ghost? = null
    private var ucb: UICallback? = null
    private var cb: StatusCallback? = null

    @Volatile
    private var isRunning = false
    private var msg: String? = null
    private var no_wait_mode = false
    private var startTime: Long = 0
    private var sync = false
    private var wholeline = false
    private var sakuraTalk = false
    private val sakuraMsg = StringBuilder()
    private val keroMsg = StringBuilder()
    private var waitTime = WAIT_UNIT
    private var charIndex = 0

    private var sakuraSurfaceId = "0"
    private var keroSurfaceId = "10"
    private var sakuraAnimationId: String? = null
    private var keroAnimationId: String? = null

    private var bSakuraId = "0"
    private var bKeroId = "-1"

    private var talkAnimeControl = 0
    private var lastSec = 0
    private var lastMin = 0
    private var lastHour = 0

    private var restore = false
    private var exitPending = false
    private var changingPending = false
    private var paused = false

    private val loopHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(m: Message) {
            if (m.what == RUN) {
                loopControl()
            } else if (m.what == STOP) {
                stop()
            } else if (m.what == RESET_SURFACE) {
                resetSurfacesToDefault()
            }
        }
    }

    private val clockHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(m: Message) {
            if (m.what == INC_CLOCK) {
                perClockEvent()
                sendEmptyMessageAtTime(INC_CLOCK, SystemClock.uptimeMillis() + 1000)
            }
        }
    }

    fun setViews(s: SakuraView?, k: KeroView?, bS: Balloon?, bK: Balloon?) {
        sakuraRef = s?.let { WeakReference(it) }
        keroRef = k?.let { WeakReference(it) }
        sakuraBalloonRef = bS?.let { WeakReference(it) }
        keroBalloonRef = bK?.let { WeakReference(it) }
        sakura?.setUiEventCallback(cbSakura)
        k?.setUiEventCallback(cbKero)
    }

    fun clearViews() {
        sakura?.setUiEventCallback(null)
        kero?.setUiEventCallback(null)
        sakuraRef = null
        keroRef = null
        sakuraBalloonRef = null
        keroBalloonRef = null
    }

    fun setGhost(_g: Ghost?) {
        var lName: String? = null
        val lScript: String? = null

        if (g != null) {
            lName = g?.getGhostName()
        }

        g = _g

        if (lName != null) {
            val currentGhost = g
            if (currentGhost != null && currentGhost.getCreateCount() > 1) {
                doShioriEvent("OnGhostChanged", arrayOf(lName, lScript ?: ""))
            } else {
                doShioriEvent("OnFirstBoot", arrayOf("0"))
                AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_PGM_FLOW, "onfirstboot", g?.getGhostId() ?: "", 0)
            }
        }
    }

    fun setLayoutMgr(lm: LayoutManager?) {
        layoutMgr = lm
    }

    @Synchronized
    fun addMsgToQueue(inCol: Collection<String>) {
        mMsgQueue.addAll(inCol)
    }

    @Synchronized
    fun addMsgToQueue(msgs: Array<String>) {
        for (s in msgs) {
            mMsgQueue.add(s)
        }
    }

    fun setNoWaitMode(wait: Boolean) {
        no_wait_mode = wait
    }

    fun setCallback(c: StatusCallback?) {
        cb = c
    }

    fun setUICallback(c: UICallback?) {
        ucb = c
    }

    fun resumeEvt() {
        if (isRunning && paused) {
            paused = false
            loopHandler.sendEmptyMessage(RUN)
        } else {
            if (paused) paused = false
            run()
        }
    }

    private fun loopControl() {
        if (paused) return
        val currentMsg = msg
        if (currentMsg != null && charIndex < currentMsg.length) {
            parseMsg()
            updateUI()
            if (no_wait_mode) {
                loopControl()
            } else {
                loopHandler.sendEmptyMessageDelayed(RUN, waitTime)
            }
        } else {
            reset()
            msg = getFromQueue()
            if (msg == null) {
                if (no_wait_mode) {
                    stop()
                } else {
                    loopHandler.sendEmptyMessageDelayed(STOP, waitTime)
                }
            } else {
                if (no_wait_mode) {
                    loopControl()
                } else {
                    loopHandler.sendEmptyMessageDelayed(RUN, waitTime)
                }
            }
        }
    }

    fun startClock() {
        Log.d(TAG, "startClock called")
        startTime = SystemClock.uptimeMillis()
        clockHandler.sendEmptyMessageDelayed(INC_CLOCK, CLOCK_STEP)

        val currentGhost = g
        if (restore && currentGhost != null) {
            parseShioriResponseAndInsert(currentGhost.doShioriEvent("OnWindowStateRestore", null))
        } else {
            doBoot()
        }
        restore = false
    }

    fun stopClock() {
        clockHandler.removeMessages(INC_CLOCK)
    }

    @Synchronized
    override fun run() {
        cancelResetTimeout()
        if (isRunning) return
        isRunning = true

        reset()
        msg = getFromQueue()
        if (msg == null) {
            stop()
        } else {
            if (no_wait_mode) {
                loopControl()
            } else {
                loopHandler.sendEmptyMessage(RUN)
            }
        }
    }

    private fun getFromQueue(): String? {
        return rewriteMsg(mMsgQueue.poll())
    }

    private fun rewriteMsg(inStr: String?): String? {
        val currentGhost = g ?: return inStr
        var result = inStr ?: return null
        result = result.replace("%username", currentGhost.getUsername())
        result = result.replace("%selfname2?", currentGhost.getSakuraName() ?: "")
        result = result.replace("%keroname", currentGhost.getKeroName() ?: "")
        return result
    }

    @Synchronized
    fun clearMsgQueue() {
        mMsgQueue.clear()
        msg = null
        cancelResetTimeout()
        stop()
    }

    @Synchronized
    fun stop() {
        isRunning = false
        paused = false
        bSakuraId = "-1"
        bKeroId = "-1"
        sakuraMsg.setLength(0)
        keroMsg.setLength(0)
        updateUI()

        cancelResetTimeout()
        if (!no_wait_mode) {
            loopHandler.sendEmptyMessageDelayed(RESET_SURFACE, RESET_TIMEOUT)
        }

        cb?.let {
            it.stop()
            if (exitPending) {
                it.canExit()
                exitPending = false
            }
            if (changingPending) {
                changingPending = false
                it.ghostSwitchScriptComplete()
                g?.unload()
            }
        }
    }

    private fun reset() {
        sync = false
        wholeline = false
        paused = false
        sakuraTalk = true
        sakuraMsg.setLength(0)
        keroMsg.setLength(0)
        msg = ""
        charIndex = 0
        bSakuraId = "-1"
        bKeroId = "-1"
        sakuraAnimationId = null
        keroAnimationId = null
        talkAnimeControl = 0
    }

    private fun resetSurfacesToDefault() {
        sakuraSurfaceId = "0"
        keroSurfaceId = "10"
        updateUI()
        g?.doShioriEvent("OnSurfaceChange", arrayOf("Reference0: $sakuraSurfaceId", "Reference1: $keroSurfaceId"))
    }

    fun cancelResetTimeout() {
        loopHandler.removeMessages(RESET_SURFACE)
    }

    private fun appendChar(c: Char) {
        if (sync) {
            sakuraMsg.append(c)
            keroMsg.append(c)
        } else if (sakuraTalk) {
            sakuraMsg.append(c)
        } else {
            keroMsg.append(c)
        }

        if (keroMsg.isNotEmpty()) {
            bKeroId = "0"
        }
    }

    private fun clearMsg() {
        if (sakuraTalk) {
            sakuraMsg.setLength(0)
        } else {
            keroMsg.setLength(0)
        }
    }

    private fun parseMsg() {
        waitTime = WAIT_UNIT
        var c1: Char
        var c2: Char
        while (true) {
            val currentMsg = msg ?: return
            try {
                c1 = currentMsg[charIndex]
                charIndex++
                if (c1 != '\\') {
                    appendChar(c1)
                    if (wholeline) {
                        continue
                    } else {
                        break
                    }
                }
                c2 = currentMsg[charIndex]
                charIndex++
                when (c2) {
                    '0', 'h' -> {
                        if (!sakuraTalk) {
                            sakuraTalk = true;
                            sakuraMsg.setLength(0);
                        }
                    }
                    '1', 'u' -> {
                        sakuraTalk = false
                        keroMsg.setLength(0)
                    }
                    's' -> {
                        if (handle_surface()) break
                    }
                    'i' -> {
                        if (handle_animation()) break
                    }
                    'e' -> {
                        charIndex = currentMsg.length
                        waitTime = WAIT_YEN_E
                        break
                    }
                    'n' -> {
                        appendChar('\n')
                        val rest = currentMsg.substring(charIndex, currentMsg.length)
                        val m = PatternHolders.sqbracket_half_number.matcher(rest)
                        if (m.find()) {
                            charIndex += m.group().length
                            Log.d(TAG, "skipping unsupported tag n" + m.group())
                        }
                        break
                    }
                    'c' -> clearMsg()
                    '_' -> {
                        if (handle_underscore_type(currentMsg.substring(charIndex, currentMsg.length))) break
                    }
                    '!' -> {
                        if (handle_exclaim_type(currentMsg.substring(charIndex, currentMsg.length))) break
                    }
                    'w' -> {
                        val c = currentMsg[charIndex]
                        charIndex++
                        if (c.isDigit()) {
                            val cint = c - '0'
                            waitTime = cint * WAIT_UNIT
                            break
                        }
                    }
                    'b' -> {
                        if (handle_balloon()) break
                    }
                    'q' -> {
                        if (handle_selection()) break
                    }
                    '-', '4', '5', '6', 'v' -> {
                        Log.d(TAG, "ignore unsupported $c2 tag")
                        AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_SSC, "tag_unsupport", c2.toString(), -1)
                    }
                    else -> {
                        AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_SSC, "tag_unsupport_other", c2.toString(), -1)
                    }
                }
            } catch (e: Exception) {
                break
            }
        }
    }

    private fun handle_underscore_type(leftString: String): Boolean {
        var rest = leftString
        val c = msg?.get(charIndex) ?: return false
        charIndex++
        when (c) {
            's' -> sync = !sync
            'q' -> wholeline = !wholeline
            'l', 'a', 'v' -> {
                rest = msg?.substring(charIndex, msg?.length ?: 0) ?: ""
                val m = PatternHolders.sqbracket_half_number.matcher(rest)
                if (m.find()) {
                    charIndex += m.group().length
                    Log.d(TAG, "skipping unsupported tag _$c${m.group()}")
                    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_SSC, "tag_unsupport", "_$c${m.group()}", -1)
                }
            }
            'n', 'V' -> {
                Log.d(TAG, "ignore unsupported _$c tag")
                AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_SSC, "tag_unsupport", "_$c", -1)
            }
            'b' -> return handle_balloon()
            'w' -> {
                rest = msg?.substring(charIndex, msg?.length ?: 0) ?: ""
                val m = PatternHolders.sqbracket_half_number.matcher(rest)
                if (m.find()) {
                    charIndex += m.group().length
                    try {
                        waitTime = m.group(1)?.toLong() ?: 0L
                        return true
                    } catch (e: Exception) {
                        AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_SSC, "tag_err", "_$c${m.group()}", -1)
                    }
                }
            }
        }
        return false
    }

    private fun handle_exclaim_type(leftString: String): Boolean {
        val m = PatternHolders.open_input.matcher(leftString)
        if (m.find()) {
            charIndex += m.group().length
            val id = m.group(1)
            if (id != null) {
                openUserInputBox(id)
            }
        } else {
            AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_SSC, "tag_unsupport", "exclaim_type", -1)
        }
        return false
    }

    private fun openUserInputBox(id: String?) {
        if (id == null) {
            AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_SSC, "tag_err", "open input box", -1)
            return
        }
        if (ucb != null) {
            paused = true
            ucb?.showUserInputBox(id)
        }
    }

    private fun handle_surface(): Boolean {
        val currentMsg = msg ?: return false
        val left = currentMsg.substring(charIndex, currentMsg.length)
        val m = PatternHolders.surface_ptrn.matcher(left)
        if (m.find()) {
            val sid = m.group(2) ?: m.group(1) ?: "0"
            changeSurface(sid)
            charIndex += m.group().length
            return true
        } else {
            Log.d(TAG, "malformed \\s tag at index:$charIndex")
            val recordLength = Math.min(left.length, 4)
            AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_SSC, "tag_err", "\\s" + left.substring(0, recordLength), -1)
        }
        return false
    }

    private fun handle_balloon(): Boolean {
        val currentMsg = msg ?: return false
        val left = currentMsg.substring(charIndex, currentMsg.length)
        val m = PatternHolders.balloon_ptrn.matcher(left)
        if (m.find()) {
            val sid = m.group(2) ?: m.group(1) ?: "0"
            changeBalloon(sid)
            charIndex += m.group().length
            return true
        }
        return false
    }

    private fun handle_animation(): Boolean {
        val currentMsg = msg ?: return false
        val left = currentMsg.substring(charIndex, currentMsg.length)
        val m = PatternHolders.ani_ptrn.matcher(left)
        if (m.find()) {
            val aid = m.group(1)
            if (aid != null) {
                queueAnimation(aid)
            }
            charIndex += m.group().length
            return true
        }
        return false
    }

    private fun handle_selection(): Boolean {
        charIndex -= 2
        val currentMsg = msg ?: return false
        var m = PatternHolders.q_choice_ptrn.matcher(currentMsg)
        val labels = Vector<String>(2)
        val ids = Vector<String>(2)
        while (m.find()) {
            val g1 = m.group(1) ?: ""
            val g2 = m.group(2) ?: ""
            msg = m.replaceFirst(g1)
            labels.add(g1)
            ids.add(g2)
            m = PatternHolders.q_choice_ptrn.matcher(msg ?: "")
        }

        if (ucb != null) {
            wholeline = true
            val lbl = Array(labels.size) { labels[it] }
            val idz = Array(ids.size) { ids[it] }
            ucb?.showUserSelection(lbl, idz)
        }
        paused = true
        return true
    }

    private fun changeSurface(sid: String) {
        if (sakuraTalk) {
            sakuraSurfaceId = sid
        } else {
            keroSurfaceId = sid
        }
        g?.doShioriEvent("OnSurfaceChange", arrayOf("Reference0: $sakuraSurfaceId", "Reference1: $keroSurfaceId"))
    }

    private fun changeBalloon(bid: String) {
        if (sakuraTalk) {
            bSakuraId = bid
        } else {
            bKeroId = bid
        }
    }

    private fun queueAnimation(aid: String) {
        if (sakuraTalk) {
            sakuraAnimationId = aid
        } else {
            keroAnimationId = aid
        }
    }

    private fun updateUI() {
        val sakuraView = sakura ?: return
        val keroView = kero ?: return
        val sakuraBall = sakuraBalloon ?: return
        val keroBall = keroBalloon ?: return

        sakuraView.changeSurface(sakuraSurfaceId)
        keroView.changeSurface(keroSurfaceId)

        val sakuraAnimate = sakuraAnimationId != null
        val keroAnimate = keroAnimationId != null

        if (bSakuraId.equals("-1", ignoreCase = true) && sakuraMsg.isEmpty()) {
            sakuraBall.visibility = View.INVISIBLE
        } else {
            sakuraBall.visibility = View.VISIBLE
            sakuraBall.setText(sakuraMsg.toString())
            if (!sakuraAnimate && talkAnimeControl == 0) {
                sakuraView.startTalkingAnimation()
            }
        }

        if (bKeroId.equals("-1", ignoreCase = true) && keroMsg.isEmpty()) {
            keroBall.visibility = View.INVISIBLE
        } else {
            keroBall.visibility = View.VISIBLE
            keroBall.setText(keroMsg.toString())
            if (!keroAnimate && talkAnimeControl == 0) {
                keroView.startTalkingAnimation()
            }
        }

        layoutMgr?.checkAndUpdateLayoutParam()

        if (sakuraAnimate) {
            val animId = sakuraAnimationId
            if (animId != null) {
                sakuraView.loadAnimation(animId)
                sakuraView.startAnimation()
            }
            sakuraAnimationId = null
        }

        if (keroAnimate) {
            val animId = keroAnimationId
            if (animId != null) {
                keroView.loadAnimation(animId)
                keroView.startAnimation()
            }
            keroAnimationId = null
        }
        talkAnimeControl++
        if (talkAnimeControl == 10) {
            talkAnimeControl = 0
        }
    }

    private fun startPerSecondAnimation(target: SakuraView) {
        val p = Math.random()
        if (p < 0.25f) {
            target.startRarelyAnimation()
        } else if (p < 0.50f) {
            target.startSometimesAnimation()
        }
    }

    private fun doPerSecondEvent(hr: Int) {
        val sakuraView = sakura
        if (sakuraView != null) startPerSecondAnimation(sakuraView)
        val keroView = kero
        if (keroView != null) startPerSecondAnimation(keroView)

        val r = g?.sendOnSecondChange(hr)
        if (r != null) {
            parseShioriResponseAndInsert(r)
        }
    }

    private fun doPerMinuteEvent(hr: Int) {
        val r = g?.sendOnMinuteChange(hr)
        if (r != null) {
            parseShioriResponseAndInsert(r)
        }
    }

    private fun doPerHourEvent() {}

    private fun perClockEvent() {
        val mills = SystemClock.uptimeMillis() - startTime
        val seconds = (mills / 1000).toInt()
        var minute = seconds / 60
        val hour = minute / 60
        minute %= 60
        val sec = seconds % 60

        if (sec - lastSec >= 1 || sec == 0) {
            doPerSecondEvent(hour)
            lastSec = sec
        }
        if (minute - lastMin >= 1 || (lastMin == 59 && minute == 0)) {
            doPerMinuteEvent(hour)
            lastMin = minute
        }
        if (hour - lastHour >= 1) {
            doPerHourEvent()
            lastHour = hour
        }
    }

    private fun parseShioriResponseAndInsert(res: ShioriResponse) {
        if (res.statusCode != 200) return
        Log.d(TAG, res.toString())
        msg = res.getKey("Value")
        val currentMsg = msg
        if (currentMsg != null) {
            addMsgToQueue(arrayOf(currentMsg))
        }
        if (!isRunning) {
            run()
        }
    }

    private fun doMouseClick(x: Int, y: Int, sakuraTalk: Boolean, collid: Int, buttonid: Int) {
        doShioriEvent(
            "OnMouseClick",
            arrayOf(
                x.toString(),
                y.toString(),
                "0",
                if (sakuraTalk) "0" else "1",
                if (collid > -1) collid.toString() else "",
                buttonid.toString(),
                "touch"
            )
        )
    }

    private fun doMouseDblClick(x: Int, y: Int, sakuraTalk: Boolean, collid: Int, buttonid: Int) {
        doShioriEvent(
            "OnMouseDoubleClick",
            arrayOf(
                x.toString(),
                y.toString(),
                "0",
                if (sakuraTalk) "0" else "1",
                if (collid > -1) collid.toString() else "",
                buttonid.toString(),
                "touch"
            )
        )
    }

    private fun doMouseWheel(x: Int, y: Int, wheelO: Int, sakuraTalk: Boolean, collid: Int) {
        doShioriEvent(
            "OnMouseWheel",
            arrayOf(
                x.toString(),
                y.toString(),
                wheelO.toString(),
                if (sakuraTalk) "0" else "1",
                if (collid > -1) collid.toString() else "",
                "",
                "touch"
            )
        )
    }

    private fun doMouseMove(x: Int, y: Int, wheelO: Int, sakuraTalk: Boolean, collid: Int) {
        doShioriEvent(
            "OnMouseMove",
            arrayOf(
                x.toString(),
                y.toString(),
                wheelO.toString(),
                if (sakuraTalk) "0" else "1",
                if (collid > -1) collid.toString() else "",
                "",
                "touch"
            )
        )
    }

    private val cbSakura = object : SakuraView.UIEventCallback {
        override fun onHit(type: Int, x: Int, y: Int, orientation: Int, collId: Int, buttonId: Int) {
            when (type) {
                SakuraView.UIEventCallback.TYPE_SINGLE_CLICK -> {
                    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_UI_TOUCH, "sakuraview_touch", "click", collId)
                    doMouseClick(x, y, true, collId, buttonId)
                }
                SakuraView.UIEventCallback.TYPE_DOUBLE_CLICK -> {
                    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_UI_TOUCH, "sakuraview_touch", "dblclick", collId)
                    doMouseDblClick(x, y, true, collId, buttonId)
                }
                SakuraView.UIEventCallback.TYPE_WHEEL -> {
                    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_UI_TOUCH, "sakuraview_touch", "wheel", collId)
                    doMouseWheel(x, y, orientation, true, collId)
                }
                SakuraView.UIEventCallback.TYPE_MOVE -> {
                    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_UI_TOUCH, "sakuraview_touch", "move", collId)
                    doMouseMove(x, y, orientation, true, collId)
                }
            }
        }
    }

    private val cbKero = object : SakuraView.UIEventCallback {
        override fun onHit(type: Int, x: Int, y: Int, orientation: Int, collId: Int, buttonId: Int) {
            when (type) {
                SakuraView.UIEventCallback.TYPE_SINGLE_CLICK -> {
                    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_UI_TOUCH, "keroview_touch", "click", collId)
                    doMouseClick(x, y, false, collId, buttonId)
                }
                SakuraView.UIEventCallback.TYPE_DOUBLE_CLICK -> {
                    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_UI_TOUCH, "keroview_touch", "dblclick", collId)
                    doMouseDblClick(x, y, false, collId, buttonId)
                }
                SakuraView.UIEventCallback.TYPE_WHEEL -> {
                    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_UI_TOUCH, "keroview_touch", "wheel", collId)
                    doMouseWheel(x, y, orientation, false, collId)
                }
                SakuraView.UIEventCallback.TYPE_MOVE -> {
                    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_UI_TOUCH, "keroview_touch", "move", collId)
                    doMouseMove(x, y, orientation, false, collId)
                }
            }
        }
    }

    fun doMinimize() {
        g?.doShioriEvent("OnWindowStateMinimize", null)
    }

    fun doRestore() {
        Log.d(TAG, "doRestore called")
        restore = true
    }

    fun doExit() {
        doShioriEvent("OnClose", null)
        exitPending = true
    }

    fun doGhostChanging(nextName: String, type: String, nextPath: String) {
        changingPending = true
        doShioriEvent("OnGhostChanging", arrayOf(nextName, type, "", nextPath))
    }

    fun doInstallBegin(ghostId: String) {
        val ref = arrayOf("ghost", ghostId, ghostId)
        doShioriEvent("OnInstallBegin", ref)
    }

    fun doInstallComplete(ghostId: String) {
        val ref = arrayOf("ghost", ghostId, ghostId)
        doShioriEvent("OnInstallComplete", ref)
    }

    fun doShioriEvent(evt: String, ref: Array<String>?): Boolean {
        val currentGhost = g ?: return false
        val r = currentGhost.doShioriEvent(evt, ref)
        parseShioriResponseAndInsert(r)
        return true
    }

    fun doBoot() {
        val currentGhost = g ?: return
        val gshellname = currentGhost.getShellName()
        val cCount = currentGhost.getCreateCount()
        Log.d(TAG, "$gshellname create count$cCount")
        if (cCount > 1) {
            doShioriEvent("OnBoot", arrayOf(gshellname))
            AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_PGM_FLOW, "onboot", currentGhost.getGhostId(), cCount.toInt())
        } else {
            Log.d(TAG, "do OnFirstBoot")
            doShioriEvent("OnFirstBoot", arrayOf("0"))
            AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_PGM_FLOW, "onfirstboot", currentGhost.getGhostId(), 0)
        }
    }

    fun getStringValueFromShiori(id: String): String? {
        return g?.getStringFromShiori(id)
    }

    fun doUserInput(id: String, input: String) {
        doShioriEvent("OnUserInput", arrayOf(id, input))
    }

    fun doOnChoiceSelect(id: String) {
        clearMsgQueue()
        doShioriEvent("OnChoiceSelect", arrayOf(id))
    }
}
