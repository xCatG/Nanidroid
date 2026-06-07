package com.cattailsw.nanidroid

import android.os.SystemClock
import android.util.Log
import com.cattailsw.nanidroid.shiori.Shiori
import com.cattailsw.nanidroid.util.AnalyticsUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

data class MascotUiState(
    val sakuraSurfaceId: String = "0",
    val keroSurfaceId: String = "10",
    val sakuraBalloonVisible: Boolean = false,
    val keroBalloonVisible: Boolean = false,
    val sakuraBalloonText: String = "",
    val keroBalloonText: String = "",
    val sakuraAnimationId: String? = null,
    val keroAnimationId: String? = null,
    val talkAnimeControl: Int = 0
)

class ScriptEngine(
    private var g: Ghost?,
    private val engineDispatcher: CoroutineDispatcher,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    companion object {
        private const val TAG = "ScriptEngine"
        const val WAIT_UNIT: Long = 50 // wait unit is 50ms
        const val WAIT_YEN_E: Long = 1000 // wait one second before clearing
        private const val CLOCK_STEP: Long = 1000
        private const val RESET_TIMEOUT: Long = 5000
    }

    private val _uiState = MutableStateFlow(MascotUiState())
    val uiState: StateFlow<MascotUiState> = _uiState.asStateFlow()

    private val scope = CoroutineScope(engineDispatcher + SupervisorJob())

    private var cb: SScriptRunner.StatusCallback? = null
    private var ucb: SScriptRunner.UICallback? = null

    private val msgQueue = Channel<String>(Channel.UNLIMITED)

    // Running states
    private var isRunning = false
    private var charIndex = 0
    private var sakuraTalk = true
    private var sync = false
    private var wholeline = false
    private val sakuraMsg = StringBuilder()
    private val keroMsg = StringBuilder()
    private var waitTime = WAIT_UNIT
    private var sakuraSurfaceId = "0"
    private var keroSurfaceId = "10"
    private var sakuraAnimationId: String? = null
    private var keroAnimationId: String? = null
    private var bSakuraId = "0"
    private var bKeroId = "-1"
    private var talkAnimeControl = 0
    private var currentScript = ""

    // Clock states
    private var clockJob: Job? = null
    private var startTime: Long = 0
    private var lastSec = 0
    private var lastMin = 0
    private var lastHour = 0
    private var bootedGhostId: String? = null

    // Control states
    private var restore = false
    private var exitPending = false
    private var changingPending = false
    private val _pausedFlow = MutableStateFlow(false)
    private var paused: Boolean
        get() = _pausedFlow.value
        set(value) {
            _pausedFlow.value = value
        }
    private var resetSurfaceJob: Job? = null
    private var scriptJob: Job? = null

    fun setStatusCallback(callback: SScriptRunner.StatusCallback?) {
        cb = callback
    }

    fun setUICallback(callback: SScriptRunner.UICallback?) {
        ucb = callback
    }

    fun setGhost(ghost: Ghost?) {
        var lName: String? = null
        val lScript: String? = null

        if (g != null) {
            lName = g?.getGhostName()
        }

        if (g != ghost) {
            bootedGhostId = null
        }

        g = ghost

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

    fun addMsgToQueue(msgs: Array<String>) {
        for (msg in msgs) {
            msgQueue.trySend(msg)
        }
    }

    fun clearMsgQueue() {
        while (true) {
            val result = msgQueue.tryReceive()
            if (result.isFailure || result.isClosed) break
        }
        cancelResetTimeout()
        scriptJob?.cancel()
        stop()
    }

    fun startClock() {
        Log.d(TAG, "startClock called")
        if (clockJob != null && clockJob?.isActive == true) {
            return // Clock idempotency
        }
        startTime = SystemClock.uptimeMillis()
        clockJob = scope.launch {
            val currentGhost = g
            if (restore && currentGhost != null) {
                val r = doShioriEventOnDispatcher("OnWindowStateRestore", null)
                r?.let { parseShioriResponseAndInsert(it) }
            } else if (currentGhost != null && bootedGhostId != currentGhost.getGhostId()) {
                doBoot()
                bootedGhostId = currentGhost.getGhostId()
            }
            restore = false

            while (isActive) {
                delay(CLOCK_STEP)
                perClockEvent()
            }
        }
    }

    fun stopClock() {
        clockJob?.cancel()
        clockJob = null
    }

    fun cancel() {
        stopClock()
        scope.cancel()
        if (engineDispatcher is java.io.Closeable) {
            (engineDispatcher as java.io.Closeable).close()
        }
    }

    fun resumeEvt() {
        if (paused) {
            paused = false
        }
    }

    suspend fun run() {
        withContext(engineDispatcher) {
            isRunning = true
            try {
                while (isActive) {
                    val rawMsg = msgQueue.receive()
                    val rewritten = rewriteMsg(rawMsg) ?: continue
                    reset()
                    currentScript = rewritten

                    var wasCancelled = false
                    supervisorScope {
                        val job = launch {
                            executeScript()
                        }
                        scriptJob = job
                        try {
                            job.join()
                        } finally {
                            wasCancelled = job.isCancelled
                            scriptJob = null
                        }
                    }

                    if (!wasCancelled && msgQueue.isEmpty) {
                        stop()
                    }
                }
            } finally {
                isRunning = false
            }
        }
    }

    private suspend fun executeScript() {
        charIndex = 0
        while (charIndex < currentScript.length) {
            yield()
            if (paused) {
                _pausedFlow.first { !it }
                continue
            }
            parseMsg()
            emitUiState()
            if (waitTime > 0) {
                delay(waitTime)
            } else {
                yield()
            }
        }
    }

    private fun emitUiState() {
        val sakuraBallVis = !(bSakuraId.equals("-1", ignoreCase = true) && sakuraMsg.isEmpty())
        val keroBallVis = !(bKeroId.equals("-1", ignoreCase = true) && keroMsg.isEmpty())
        
        val state = MascotUiState(
            sakuraSurfaceId = sakuraSurfaceId,
            keroSurfaceId = keroSurfaceId,
            sakuraBalloonVisible = sakuraBallVis,
            keroBalloonVisible = keroBallVis,
            sakuraBalloonText = sakuraMsg.toString(),
            keroBalloonText = keroMsg.toString(),
            sakuraAnimationId = sakuraAnimationId,
            keroAnimationId = keroAnimationId,
            talkAnimeControl = talkAnimeControl
        )
        _uiState.value = state

        // Reset emitted animation IDs after emitting
        sakuraAnimationId = null
        keroAnimationId = null

        talkAnimeControl++
        if (talkAnimeControl == 10) {
            talkAnimeControl = 0
        }
    }

    private fun reset() {
        cancelResetTimeout()
        sync = false
        wholeline = false
        paused = false
        sakuraTalk = true
        sakuraMsg.setLength(0)
        keroMsg.setLength(0)
        charIndex = 0
        bSakuraId = "-1"
        bKeroId = "-1"
        sakuraAnimationId = null
        keroAnimationId = null
        talkAnimeControl = 0
    }

    fun stop() {
        paused = false
        bSakuraId = "-1"
        bKeroId = "-1"
        sakuraMsg.setLength(0)
        keroMsg.setLength(0)
        emitUiState()

        cancelResetTimeout()
        resetSurfaceJob = scope.launch {
            delay(RESET_TIMEOUT)
            resetSurfacesToDefault()
        }

        scope.launch(mainDispatcher) {
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
    }

    private fun resetSurfacesToDefault() {
        sakuraSurfaceId = "0"
        keroSurfaceId = "10"
        emitUiState()
        doShioriEvent("OnSurfaceChange", arrayOf("Reference0: $sakuraSurfaceId", "Reference1: $keroSurfaceId"))
    }

    private fun cancelResetTimeout() {
        resetSurfaceJob?.cancel()
        resetSurfaceJob = null
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
            try {
                if (charIndex >= currentScript.length) return
                c1 = currentScript[charIndex]
                charIndex++
                if (c1 != '\\') {
                    appendChar(c1)
                    if (wholeline) {
                        continue
                    } else {
                        break
                    }
                }
                if (charIndex >= currentScript.length) return
                c2 = currentScript[charIndex]
                charIndex++
                when (c2) {
                    '0', 'h' -> {
                        if (!sakuraTalk) {
                            sakuraTalk = true
                            sakuraMsg.setLength(0)
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
                        charIndex = currentScript.length
                        waitTime = WAIT_YEN_E
                        break
                    }
                    'n' -> {
                        appendChar('\n')
                        val rest = currentScript.substring(charIndex)
                        val m = PatternHolders.sqbracket_half_number.matcher(rest)
                        if (m.find()) {
                            charIndex += m.group().length
                        }
                        break
                    }
                    'c' -> clearMsg()
                    '_' -> {
                        if (handle_underscore_type()) break
                    }
                    '!' -> {
                        if (handle_exclaim_type()) break
                    }
                    'w' -> {
                        if (charIndex >= currentScript.length) return
                        val c = currentScript[charIndex]
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
                        // ignore
                    }
                    else -> {
                        // ignore
                    }
                }
            } catch (e: Exception) {
                break
            }
        }
    }

    private fun handle_underscore_type(): Boolean {
        if (charIndex >= currentScript.length) return false
        val c = currentScript[charIndex]
        charIndex++
        when (c) {
            's' -> sync = !sync
            'q' -> wholeline = !wholeline
            'l', 'a', 'v' -> {
                val rest = currentScript.substring(charIndex)
                val m = PatternHolders.sqbracket_half_number.matcher(rest)
                if (m.find()) {
                    charIndex += m.group().length
                }
            }
            'n', 'V' -> {
                // ignore
            }
            'b' -> return handle_balloon()
            'w' -> {
                val rest = currentScript.substring(charIndex)
                val m = PatternHolders.sqbracket_half_number.matcher(rest)
                if (m.find()) {
                    charIndex += m.group().length
                    try {
                        waitTime = m.group(1)?.toLong() ?: 0L
                        return true
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
        }
        return false
    }

    private fun handle_exclaim_type(): Boolean {
        val leftString = currentScript.substring(charIndex)
        val m = PatternHolders.open_input.matcher(leftString)
        if (m.find()) {
            charIndex += m.group().length
            val id = m.group(1)
            if (id != null) {
                openUserInputBox(id)
            }
        }
        return false
    }

    private fun openUserInputBox(id: String) {
        scope.launch(mainDispatcher) {
            ucb?.showUserInputBox(id)
        }
        paused = true
    }

    private fun handle_surface(): Boolean {
        val left = currentScript.substring(charIndex)
        val m = PatternHolders.surface_ptrn.matcher(left)
        if (m.find()) {
            val sid = m.group(2) ?: m.group(1) ?: "0"
            changeSurface(sid)
            charIndex += m.group().length
            return true
        }
        return false
    }

    private fun handle_balloon(): Boolean {
        val left = currentScript.substring(charIndex)
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
        val left = currentScript.substring(charIndex)
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
        var m = PatternHolders.q_choice_ptrn.matcher(currentScript)
        val labels = mutableListOf<String>()
        val ids = mutableListOf<String>()
        while (m.find()) {
            val g1 = m.group(1) ?: ""
            val g2 = m.group(2) ?: ""
            currentScript = m.replaceFirst(g1)
            labels.add(g1)
            ids.add(g2)
            m = PatternHolders.q_choice_ptrn.matcher(currentScript)
        }

        if (ucb != null) {
            wholeline = true
            val lbl = labels.toTypedArray()
            val idz = ids.toTypedArray()
            scope.launch(mainDispatcher) {
                ucb?.showUserSelection(lbl, idz)
            }
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
        doShioriEvent("OnSurfaceChange", arrayOf("Reference0: $sakuraSurfaceId", "Reference1: $keroSurfaceId"))
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

    private fun rewriteMsg(inStr: String?): String? {
        val currentGhost = g ?: return inStr
        var result = inStr ?: return null
        result = result.replace("%username", currentGhost.getUsername())
        result = result.replace("%selfname2?", currentGhost.getSakuraName() ?: "")
        result = result.replace("%keroname", currentGhost.getKeroName() ?: "")
        return result
    }

    fun doShioriEvent(evt: String, ref: Array<String>?) {
        scope.launch {
            val r = doShioriEventOnDispatcher(evt, ref)
            r?.let { parseShioriResponseAndInsert(it) }
        }
    }

    private suspend fun doShioriEventOnDispatcher(event: String, ref: Array<String>?): ShioriResponse? =
        withContext(engineDispatcher) {
            g?.doShioriEvent(event, ref)
        }

    private fun parseShioriResponseAndInsert(res: ShioriResponse) {
        if (res.statusCode != 200) return
        val value = res.getKey("Value")
        if (!value.isNullOrBlank()) {
            addMsgToQueue(arrayOf(value))
        }
    }

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

    private fun doPerSecondEvent(hr: Int) {
        scope.launch {
            val r = doShioriEventOnDispatcher("OnSecondChange", arrayOf(hr.toString(), "0", "0", "1"))
            r?.let { parseShioriResponseAndInsert(it) }
        }
        scope.launch(mainDispatcher) {
            cb?.onSecondTick()
        }
    }

    private fun doPerMinuteEvent(hr: Int) {
        scope.launch {
            val r = doShioriEventOnDispatcher("OnMinuteChange", arrayOf(hr.toString(), "0", "0", "1"))
            r?.let { parseShioriResponseAndInsert(it) }
        }
    }

    private fun doPerHourEvent() {}

    private fun doBoot() {
        val currentGhost = g ?: return
        val gshellname = currentGhost.getShellName()
        val cCount = currentGhost.getCreateCount()
        scope.launch {
            if (cCount > 1) {
                val r = doShioriEventOnDispatcher("OnBoot", arrayOf(gshellname))
                r?.let { parseShioriResponseAndInsert(it) }
            } else {
                val r = doShioriEventOnDispatcher("OnFirstBoot", arrayOf("0"))
                r?.let { parseShioriResponseAndInsert(it) }
            }
        }
    }

    fun doMinimize() {
        scope.launch {
            doShioriEventOnDispatcher("OnWindowStateMinimize", null)
        }
    }

    fun doRestore() {
        restore = true
    }

    fun doExit() {
        exitPending = true
        doShioriEvent("OnClose", null)
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

    suspend fun getStringValueFromShiori(id: String): String? = withContext(engineDispatcher) {
        g?.getStringFromShiori(id)
    }

    fun doUserInput(id: String, input: String) {
        clearMsgQueue()
        doShioriEvent("OnUserInput", arrayOf(id, input))
    }

    fun doOnChoiceSelect(id: String) {
        clearMsgQueue()
        doShioriEvent("OnChoiceSelect", arrayOf(id))
    }
}
