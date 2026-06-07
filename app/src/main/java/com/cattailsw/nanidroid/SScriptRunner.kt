package com.cattailsw.nanidroid

import android.content.Context
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import android.util.Log
import android.view.View
import com.cattailsw.nanidroid.util.AnalyticsUtils
import kotlinx.coroutines.*

class SScriptRunner(
    context: Context,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    // R5: pin all JNI work to one dedicated thread (the native Satori/Kawari engines
    // keep process-global / per-thread state). The thread is a daemon so a leaked
    // engine loop parked on msgQueue.receive() never blocks JVM (or test-fork) shutdown.
    private val engineDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NanidroidJniThread").apply { isDaemon = true }
    }.asCoroutineDispatcher()
) : Runnable {

    companion object {
        private const val TAG = "SScriptRunner"
        private var _self: SScriptRunner? = null

        @JvmStatic
        fun getInstance(ctx: Context): SScriptRunner {
            if (_self == null) {
                _self = SScriptRunner(ctx.applicationContext)
            }
            return _self!!
        }

        @JvmStatic
        fun setTestInstance(runner: SScriptRunner?) {
            _self = runner
        }
    }

    interface StatusCallback {
        fun stop()
        fun canExit()
        fun ghostSwitchScriptComplete()
        fun onSecondTick() {}
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
    private var clientCallback: StatusCallback? = null

    private val scope = CoroutineScope(mainDispatcher + SupervisorJob())
    private val scriptEngine = ScriptEngine(null, engineDispatcher, mainDispatcher)

    private val engineCallback = object : StatusCallback {
        override fun stop() {
            clientCallback?.stop()
        }
        override fun canExit() {
            clientCallback?.canExit()
        }
        override fun ghostSwitchScriptComplete() {
            clientCallback?.ghostSwitchScriptComplete()
        }
        override fun onSecondTick() {
            val sakuraView = sakura
            if (sakuraView != null) startPerSecondAnimation(sakuraView)
            val keroView = kero
            if (keroView != null) startPerSecondAnimation(keroView)
            clientCallback?.onSecondTick()
        }
    }

    init {
        scriptEngine.setStatusCallback(engineCallback)
        
        scope.launch(mainDispatcher) {
            scriptEngine.uiState.collect { state ->
                updateUI(state)
            }
        }
        
        scope.launch(mainDispatcher) {
            scriptEngine.run()
        }
    }

    fun setViews(s: SakuraView?, k: KeroView?, bS: Balloon?, bK: Balloon?) {
        sakuraRef = s?.let { WeakReference(it) }
        keroRef = k?.let { WeakReference(it) }
        sakuraBalloonRef = bS?.let { WeakReference(it) }
        keroBalloonRef = bK?.let { WeakReference(it) }
        sakura?.setUiEventCallback(cbSakura)
        k?.setUiEventCallback(cbKero)
        
        updateUI(scriptEngine.uiState.value)
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
        g = _g
        scriptEngine.setGhost(_g)
    }

    fun setLayoutMgr(lm: LayoutManager?) {
        layoutMgr = lm
    }

    fun addMsgToQueue(inCol: Collection<String>) {
        scriptEngine.addMsgToQueue(inCol.toTypedArray())
    }

    fun addMsgToQueue(msgs: Array<String>) {
        scriptEngine.addMsgToQueue(msgs)
    }

    fun setNoWaitMode(wait: Boolean) {
        // no-op, virtual time is used in tests instead
    }

    fun setCallback(c: StatusCallback?) {
        clientCallback = c
    }

    fun setUICallback(c: UICallback?) {
        ucb = c
        scriptEngine.setUICallback(c)
    }

    fun resumeEvt() {
        scriptEngine.resumeEvt()
    }

    override fun run() {
        // no-op, engine loop is running in init block
    }

    fun clearMsgQueue() {
        scriptEngine.clearMsgQueue()
    }

    fun stop() {
        scriptEngine.stop()
    }

    fun startClock() {
        scriptEngine.startClock()
    }

    fun stopClock() {
        scriptEngine.stopClock()
    }

    fun cancel() {
        scriptEngine.cancel()
        scope.cancel()
        if (engineDispatcher is Closeable) {
            engineDispatcher.close()
        }
    }

    fun cancelResetTimeout() {
        // managed by ScriptEngine stop resetSurfaceJob
    }

    private fun updateUI(state: MascotUiState) {
        val sakuraView = sakura ?: return
        val keroView = kero ?: return
        val sakuraBall = sakuraBalloon ?: return
        val keroBall = keroBalloon ?: return

        sakuraView.changeSurface(state.sakuraSurfaceId)
        keroView.changeSurface(state.keroSurfaceId)

        val sakuraAnimate = state.sakuraAnimationId != null
        val keroAnimate = state.keroAnimationId != null

        if (!state.sakuraBalloonVisible) {
            sakuraBall.visibility = View.INVISIBLE
        } else {
            sakuraBall.visibility = View.VISIBLE
            sakuraBall.setText(state.sakuraBalloonText)
            if (!sakuraAnimate && state.talkAnimeControl == 0) {
                sakuraView.startTalkingAnimation()
            }
        }

        if (!state.keroBalloonVisible) {
            keroBall.visibility = View.INVISIBLE
        } else {
            keroBall.visibility = View.VISIBLE
            keroBall.setText(state.keroBalloonText)
            if (!keroAnimate && state.talkAnimeControl == 0) {
                keroView.startTalkingAnimation()
            }
        }

        layoutMgr?.checkAndUpdateLayoutParam()

        if (sakuraAnimate) {
            val animId = state.sakuraAnimationId
            if (animId != null) {
                sakuraView.loadAnimation(animId)
                sakuraView.startAnimation()
            }
        }

        if (keroAnimate) {
            val animId = state.keroAnimationId
            if (animId != null) {
                keroView.loadAnimation(animId)
                keroView.startAnimation()
            }
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
            }
        }
    }

    fun doMinimize() {
        scriptEngine.doMinimize()
    }

    fun doRestore() {
        scriptEngine.doRestore()
    }

    fun doExit() {
        scriptEngine.doExit()
    }

    fun doGhostChanging(nextName: String, type: String, nextPath: String) {
        scriptEngine.doGhostChanging(nextName, type, nextPath)
    }

    fun doInstallBegin(ghostId: String) {
        scriptEngine.doInstallBegin(ghostId)
    }

    fun doInstallComplete(ghostId: String) {
        scriptEngine.doInstallComplete(ghostId)
    }

    fun doShioriEvent(evt: String, ref: Array<String>?): Boolean {
        scriptEngine.doShioriEvent(evt, ref)
        return true
    }

    suspend fun getStringValueFromShiori(id: String): String? {
        return scriptEngine.getStringValueFromShiori(id)
    }

    fun doUserInput(id: String, input: String) {
        scriptEngine.doUserInput(id, input)
    }

    fun doOnChoiceSelect(id: String) {
        scriptEngine.doOnChoiceSelect(id)
    }

    @OptIn(InternalCoroutinesApi::class)
    fun getScriptEngine(): ScriptEngine = scriptEngine
}
