package com.cattailsw.nanidroid

import android.content.Context
import android.os.Handler
import android.os.Message
import android.os.SystemClock
import android.util.Log
import com.cattailsw.nanidroid.util.AnalyticsUtils
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

/** Executes Sakura Script while keeping the legacy Java-facing runner contract. */
open class SScriptRunner internal constructor(
    ctx: Context?,
    private val sessionCoordinator: GhostSessionCoordinator,
) : Runnable {
    constructor(ctx: Context?) : this(ctx, productionSessionCoordinator)
    interface StatusCallback { fun stop(); fun canExit(); fun ghostSwitchScriptComplete() }
    interface UICallback { fun showUserInputBox(id: String); fun showUserSelection(textlabel: Array<String>, ids: Array<String>) }

    companion object {
        private const val TAG = "SScriptRunner"
        @JvmField val WAIT_UNIT: Long = 50
        @JvmField val WAIT_YEN_E: Long = 1000
        private const val RUN = 42; private const val STOP = 43; private const val INC_CLOCK = 44; private const val CLOCK_STEP = 1000L
        @Volatile private var self: SScriptRunner? = null
        private val productionSessionCoordinator = GhostSessionCoordinator()
        private val msgQueue = ConcurrentLinkedQueue<String>()
        @JvmStatic fun getInstance(ctx: Context?): SScriptRunner = self ?: synchronized(this) {
            self ?: SScriptRunner(ctx).also { self = it }
        }
        internal fun beginGhostConstruction(ghostId: String, ghostRoot: File): GhostConstructionReservation =
            productionSessionCoordinator.beginConstruction(ghostId, ghostRoot)
        internal fun reserveGhostForAttachment(ghost: Ghost): ReservedGhost =
            productionSessionCoordinator.reserveLoadedGhostForTesting(ghost)
        internal fun reuseActiveGhost(ghostId: String, ghostRoot: File): ReservedGhost? =
            productionSessionCoordinator.reuseActive(ghostId, ghostRoot)
        internal fun <T> withProductionGhostMutation(
            ghostId: String,
            ghostRoot: File,
            onFailure: (Throwable) -> T,
            action: () -> T,
        ): T = productionSessionCoordinator.withMutation(
            ghostId,
            ghostRoot,
            onStopped = { onFailure(IOException("ghost mutation was interrupted")) },
            onFailure = onFailure,
            action = action,
        )
        internal fun resetInstanceForTesting() = synchronized(this) {
            productionSessionCoordinator.clearForTesting()
            self = null
        }
    }

    private var presentationRenderer: GhostPresentationRenderer? = null
    private var g: Ghost? = null
    private val mCtx = ctx?.applicationContext
    private var ucb: UICallback? = null; private var cb: StatusCallback? = null
    private var isRunning = false; private var msg: String? = null; private var noWaitMode = false
    private var startTime = 0L; private var sync = false; private var wholeline = false; private var sakuraTalk = false
    private val sakuraMsg = StringBuilder(); private val keroMsg = StringBuilder(); private var waitTime = WAIT_UNIT; private var charIndex = 0
    private var sakuraSurfaceId = "0"; private var keroSurfaceId = "10"; private var sakuraAnimationId: String? = null; private var keroAnimationId: String? = null
    private var bSakuraId = "0"; private var bKeroId = "-1"; private var talkAnimeControl = 0
    private var lastSec = 0; private var lastMin = 0; private var lastHour = 0; private var restore = false; private var exitPending = false; private var changingPending = false; private var paused = false; private val bootDispatchState = BootDispatchState()

    internal fun setPresentationRendererForTesting(renderer: GhostPresentationRenderer?) { presentationRenderer = renderer }
    fun setPresentationRenderer(renderer: GhostPresentationRenderer?) { presentationRenderer = renderer }
    fun dispatchComposeDoubleClick(x: Int, y: Int, sakura: Boolean, collisionId: Int, buttonId: Int) { if (!sakura) clearMsgQueue(); doMouseDblClick(x,y,sakura,collisionId,buttonId) }
    fun setGhost(newGhost: Ghost?) {
        if (!setGhostInternal(newGhost, null)) throw IllegalStateException("ghost assignment was rejected")
    }
    internal fun attachReservedGhost(reservation: ReservedGhost): Boolean =
        setGhostInternal(reservation.ghost, reservation)
    internal fun abandonReservedGhost(reservation: ReservedGhost): Boolean =
        sessionCoordinator.abandon(reservation)
    internal fun reserveGhostForAttachmentForTesting(ghost: Ghost): ReservedGhost =
        sessionCoordinator.reserveLoadedGhostForTesting(ghost)
    internal fun unloadGhostForSwitchForTesting(ghost: Ghost): Boolean =
        sessionCoordinator.markActiveUnloaded(ghost)
    private fun setGhostInternal(newGhost: Ghost?, reservation: ReservedGhost?): Boolean {
        val outgoing = synchronized(this) { g }
        val firstActivation = newGhost?.getCreateCount() == 0L
        var outgoingName: String? = null
        val assign = {
            synchronized(this) {
                outgoingName = g?.getGhostName()
                g = newGhost
                if (outgoingName == null) bootDispatchState.resetForNoGhost()
            }
        }
        val assigned = if (reservation == null) {
            sessionCoordinator.transition(outgoing, newGhost, assign)
            true
        } else sessionCoordinator.attach(reservation, outgoing, assign)
        if (!assigned) return false
        if (reservation?.reusedActive == true) return true
        try {
            newGhost?.recordActivation()
        } catch (error: RuntimeException) {
            LegacyPlatform.debug(TAG, "ghost activation count update failed: ${error.message}")
        }
        if (outgoingName != null && newGhost != null) {
            if (!firstActivation) doShioriEvent("OnGhostChanged", arrayOf(outgoingName, null) as Array<String>)
            else {
                doShioriEvent("OnFirstBoot", arrayOf("0"))
                AnalyticsUtils.getInstance(null).trackEvent(
                    Setup.ANA_PGM_FLOW, "onfirstboot", newGhost.getGhostId(), 0,
                )
            }
            bootDispatchState.markBootDispatched()
        }
        return true
    }
    @Synchronized fun addMsgToQueue(inCol: Collection<String>) { msgQueue.addAll(inCol) }
    @Synchronized fun addMsgToQueue(msgs: Array<String>) { msgs.forEach { msgQueue.add(it) } }
    fun setNoWaitMode(wait: Boolean) { noWaitMode=wait }; fun setCallback(c: StatusCallback?) { cb=c }; fun setUICallback(c: UICallback?) { ucb=c }
    fun resumeEvt() { if(isRunning&&paused){paused=false;loopHandler.sendEmptyMessage(RUN)} else {if(paused)paused=false;run()} }
    private val loopHandler by lazy { object: Handler() { override fun handleMessage(m: Message) { if(m.what==RUN) loopControl() else if(m.what==STOP) stop() } } }
    private val clockHandler: Handler by lazy { object: Handler() { override fun handleMessage(m: Message) { if(m.what==INC_CLOCK){perClockEvent();sendEmptyMessageDelayed(INC_CLOCK,1000)} } } }
    private fun loopControl() { if(paused)return; val current=msg; if(current!=null&&charIndex<current.length){parseMsg();updateUI();if(noWaitMode)loopControl()else loopHandler.sendEmptyMessageDelayed(RUN,waitTime)}else{reset();msg=getFromQueue();if(msg==null){if(noWaitMode)stop()else loopHandler.sendEmptyMessageDelayed(STOP,waitTime)}else if(noWaitMode)loopControl()else loopHandler.sendEmptyMessageDelayed(RUN,waitTime)} }
    fun startClock() { LegacyPlatform.debug(TAG,"startClock called"); val start = bootDispatchState.startClock(); if (!start.started) return; startTime=LegacyPlatform.uptimeMillis();LegacyPlatform.scheduleDelayed(CLOCK_STEP) { clockHandler.sendEmptyMessageDelayed(INC_CLOCK,CLOCK_STEP) };if(restore)doShioriEvent("OnWindowStateRestore",null)else if(start.dispatchBoot){doBoot();bootDispatchState.markBootDispatched()};restore=false }
    fun stopClock() { LegacyPlatform.cancelDelayed { clockHandler.removeMessages(INC_CLOCK) }; bootDispatchState.stopClock() }
    override fun run() {
        val shouldStop = synchronized(this) {
            if (isRunning) return
            isRunning = true
            reset()
            msg = getFromQueue()
            msg == null
        }
        if (shouldStop) stop() else if (noWaitMode) loopControl() else loopHandler.sendEmptyMessage(RUN)
    }
    private fun getFromQueue()=rewriteMsg(msgQueue.poll())
    private fun rewriteMsg(input:String?):String? { if(g==null||input==null)return input; return input.replace("%username",g!!.getUsername()).replace("%selfname2?",g!!.getSakuraName() ?: "null").replace("%keroname",g!!.getKeroName() ?: "null") }
    fun clearMsgQueue(){synchronized(this){msgQueue.clear();msg=null};stop()}
    fun stop() {
        while (true) {
            val unloadTarget = synchronized(this) { if (changingPending && cb != null) g else null }
            if (unloadTarget == null) {
                synchronized(this) { finishStop(null) }
                return
            }
            if (sessionCoordinator.markActiveUnloaded(unloadTarget)) {
                synchronized(this) { finishStop(unloadTarget) }
                return
            }
        }
    }
    private fun finishStop(unloadTarget: Ghost?) {
        isRunning=false;bSakuraId="-1";bKeroId="-1";updateUI();cb?.let { callback ->
            callback.stop()
            if(exitPending){callback.canExit();exitPending=false}
            if(changingPending && unloadTarget != null){changingPending=false;callback.ghostSwitchScriptComplete()}
        }
    }
    private fun reset(){sync=false;wholeline=false;sakuraTalk=true;sakuraMsg.setLength(0);keroMsg.setLength(0);msg="";charIndex=0;bSakuraId="-1";bKeroId="-1";sakuraAnimationId=null;keroAnimationId=null}
    private fun appendChar(c:Char){if(sync){sakuraMsg.append(c);keroMsg.append(c)}else if(sakuraTalk)sakuraMsg.append(c)else keroMsg.append(c);if(keroMsg.isNotEmpty())bKeroId="0"}
    private fun clearMsg(){if(sakuraTalk)sakuraMsg.setLength(0)else keroMsg.setLength(0)}
    private fun parseMsg(){waitTime=WAIT_UNIT;while(true)try{val text=msg!!;val c1=text[charIndex++];if(c1!='\\'){appendChar(c1);if(wholeline)continue else break};when(val c2=text[charIndex++]){'0','h'->{if(!sakuraTalk){sakuraTalk=true;sakuraMsg.setLength(0)}};'1','u'->{sakuraTalk=false;keroMsg.setLength(0)};'s'->if(handleSurface())break;'i'->if(handleAnimation())break;'e'->{charIndex=text.length;waitTime=WAIT_YEN_E;break};'n'->{appendChar('\n');val m=PatternHolders.sqbracket_half_number.matcher(text.substring(charIndex));if(m.find())charIndex+=m.group().length;break};'c'->clearMsg();'_'->if(handleUnderscore())break;'!'->handleExclaim();'w'->{val c=text[charIndex++];if(c.isDigit()){waitTime=(c-'0')*WAIT_UNIT;break}};'b'->if(handleBalloon())break;'q'->handleSelection();'-','4','5','6','v'->Log.d(TAG,"ignore unsupported $c2 tag");else->AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_SSC,"tag_unsupport_other","$c2",-1)}}catch(_:Exception){break}}
    private fun handleUnderscore():Boolean{val text=msg!!;when(val c=text[charIndex++]){'s'->sync=!sync;'q'->wholeline=!wholeline;'l','a','v'->{val m=PatternHolders.sqbracket_half_number.matcher(text.substring(charIndex));if(m.find())charIndex+=m.group().length};'b'->return handleBalloon();'w'->{val m=PatternHolders.sqbracket_half_number.matcher(text.substring(charIndex));if(m.find()){charIndex+=m.group().length;try{waitTime=m.group(1).toLong();return true}catch(_:Exception){}}}};return false}
    private fun handleExclaim(){val m=PatternHolders.open_input.matcher(msg!!.substring(charIndex));if(m.find()){charIndex+=m.group().length;openUserInputBox(m.group(1))}}
    private fun openUserInputBox(id:String?){if(id==null)return;ucb?.let{paused=true;it.showUserInputBox(id)}}
    private fun handleSurface():Boolean{val left=msg!!.substring(charIndex);val m=PatternHolders.surface_ptrn.matcher(left);if(!m.find())return false;changeSurface(m.group(2)?:m.group(1));charIndex+=m.group().length;return true}
    private fun handleBalloon():Boolean{val m=PatternHolders.balloon_ptrn.matcher(msg!!.substring(charIndex));if(!m.find())return false;changeBalloon(m.group(2)?:m.group(1));charIndex+=m.group().length;return true}
    private fun handleAnimation():Boolean{val m=PatternHolders.ani_ptrn.matcher(msg!!.substring(charIndex));if(!m.find())return false;queueAnimation(m.group(1));charIndex+=m.group().length;return true}
    private fun handleSelection():Boolean{charIndex-=2;var matcher=PatternHolders.q_choice_ptrn.matcher(msg!!);val labels=ArrayList<String>();val ids=ArrayList<String>();while(matcher.find()){msg=matcher.replaceFirst(matcher.group(1));labels.add(matcher.group(1));ids.add(matcher.group(2));matcher=PatternHolders.q_choice_ptrn.matcher(msg!!)};ucb?.let{wholeline=true;it.showUserSelection(labels.toTypedArray(),ids.toTypedArray())};return false}
    private fun changeSurface(id:String){if(sakuraTalk)sakuraSurfaceId=id else keroSurfaceId=id;doShioriEvent("OnSurfaceChange",arrayOf("Reference0: $sakuraSurfaceId","Reference1: $keroSurfaceId"))}
    private fun changeBalloon(id:String){if(sakuraTalk)bSakuraId=id else bKeroId=id}; private fun queueAnimation(id:String){if(sakuraTalk)sakuraAnimationId=id else keroAnimationId=id}
    private fun updateUI(){val sa=sakuraAnimationId!=null;val ka=keroAnimationId!=null;presentationRenderer?.render(GhostPresentationFrame(GhostPresentationFrame.Speaker(sakuraMsg.toString(),sakuraSurfaceId,sakuraAnimationId,bSakuraId),GhostPresentationFrame.Speaker(keroMsg.toString(),keroSurfaceId,keroAnimationId,bKeroId),talkAnimeControl==0));if(sa)sakuraAnimationId=null;if(ka)keroAnimationId=null;talkAnimeControl++;if(talkAnimeControl==10)talkAnimeControl=0}
    private fun doPerSecondEvent(hr:Int){doShioriEvent("OnSecondChange",arrayOf("$hr","0","0","1"))}; private fun doPerMinuteEvent(hr:Int){doShioriEvent("OnMinuteChange",arrayOf("$hr","0","0","1"))}
    private fun perClockEvent(){val secondsAll=((SystemClock.uptimeMillis()-startTime)/1000).toInt();var minute=secondsAll/60;val hour=minute/60;val seconds=secondsAll%60;minute%=60;if(seconds-lastSec>=1||seconds==0){doPerSecondEvent(hour);lastSec=seconds};if(minute-lastMin>=1||(lastMin==59&&minute==0)){doPerMinuteEvent(hour);lastMin=minute};if(hour-lastHour>=1){lastHour=hour}}
    private fun parseShioriResponseAndInsert(res:ShioriResponse?){if(res==null||res.getStatusCode()!=200)return;msg=res.getKey("Value");addMsgToQueue(arrayOf(msg!!));if(!isRunning)run()}
    private fun doMouseClick(x:Int,y:Int,s:Boolean,c:Int,b:Int)=doShioriEvent("OnMouseClick",arrayOf("$x","$y","0",if(s)"0" else "1",if(c>-1)"$c" else "","$b","touch"))
    private fun doMouseDblClick(x:Int,y:Int,s:Boolean,c:Int,b:Int)=doShioriEvent("OnMouseDoubleClick",arrayOf("$x","$y","0",if(s)"0" else "1",if(c>-1)"$c" else "","$b","touch"))
    private fun doMouseWheel(x:Int,y:Int,w:Int,s:Boolean,c:Int)=doShioriEvent("OnMouseWheel",arrayOf("$x","$y","$w",if(s)"0" else "1",if(c>-1)"$c" else "",null,"touch"))
    private fun doMouseMove(x:Int,y:Int,w:Int,s:Boolean,c:Int)=doShioriEvent("OnMouseMove",arrayOf("$x","$y","$w",if(s)"0" else "1",if(c>-1)"$c" else "",null,"touch"))
    fun doMinimize(){doShioriEvent("OnWindowStateMinimize",null)};fun doRestore(){restore=true};fun doExit(){doShioriEvent("OnClose",null);exitPending=true};fun doGhostChanging(nextName:String,type:String,nextPath:String){changingPending=true;doShioriEvent("OnGhostChanging",arrayOf(nextName,type,null,nextPath))}
    fun doInstallBegin(id:String){doShioriEvent("OnInstallBegin",arrayOf("ghost",id,id))};fun doInstallComplete(id:String){doShioriEvent("OnInstallComplete",arrayOf("ghost",id,id))}
    @Suppress("UNCHECKED_CAST")
    fun doShioriEvent(evt: String, ref: Array<out String?>?): Boolean {
        return withCurrentGhost { target ->
            val response = target.doShioriEvent(evt, ref as Array<String>?)
            parseShioriResponseAndInsert(response)
            true
        } ?: false
    }
    @Suppress("UNCHECKED_CAST")
    internal fun doShioriEventForGhost(
        expectedGhostId: String,
        expectedGhostRoot: File,
        evt: String,
        ref: Array<out String?>?,
    ): Boolean = withCurrentGhost { target ->
        if (
            target.getGhostId() != expectedGhostId ||
            File(target.getGhostPath()).canonicalFile != expectedGhostRoot.canonicalFile
        ) return@withCurrentGhost false
        val response = target.doShioriEvent(evt, ref as Array<String>?)
        parseShioriResponseAndInsert(response)
        true
    } ?: false

    internal fun doShioriEventForGhost(
        expectedGhostId: String,
        evt: String,
        ref: Array<out String?>?,
    ): Boolean {
        val root = synchronized(this) { g?.let { File(it.getGhostPath()) } } ?: return false
        return doShioriEventForGhost(expectedGhostId, root, evt, ref)
    }

    internal fun <T> withGhostUpdateQuiesced(ghostId: String, action: () -> T): T {
        val expected = synchronized(this) { g?.takeIf { it.getGhostId() == ghostId } }
            ?: return action()
        return sessionCoordinator.withGhostGate(expected) { action() }
    }

    internal fun <T> withGhostUpdateCommitQuiesced(
        ghostId: String,
        ghostRoot: java.io.File,
        onFailure: (Throwable) -> T = { throw it },
        shouldStop: () -> Boolean = { false },
        onStopped: () -> T = { onFailure(IOException("ghost update stopped while awaiting attachment")) },
        action: () -> T,
    ): T = sessionCoordinator.withMutation(
        ghostId, ghostRoot, shouldStop, onStopped, onFailure, action,
    )

    private fun <T> withCurrentGhost(action: (Ghost) -> T): T? {
        while (true) {
            val expected = synchronized(this) { g } ?: return null
            val result = sessionCoordinator.withGhostGate(expected) { live ->
                synchronized(this) {
                    if (g !== expected) CurrentGhostCall<T>(false, null)
                    else if (!live) CurrentGhostCall(true, null)
                    else CurrentGhostCall(true, action(expected))
                }
            }
            if (result.matched) return result.value
        }
    }

    private data class CurrentGhostCall<T>(val matched: Boolean, val value: T?)
    fun doBoot(){g?.let{val shell=it.getShellName();val count=it.getCreateCount();if(count>1){doShioriEvent("OnBoot",arrayOf(shell) as Array<String>);AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_PGM_FLOW,"onboot",it.getGhostId(),count.toInt())}else{doShioriEvent("OnFirstBoot",arrayOf("0"));AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_PGM_FLOW,"onfirstboot",it.getGhostId(),0)}}}
    fun getStringValueFromShiori(id:String):String?=withCurrentGhost { it.getStringFromShiori(id) };fun doUserInput(id:String,input:String){doShioriEvent("OnUserInput",arrayOf(id,input))};fun doOnChoiceSelect(id:String){clearMsgQueue();doShioriEvent("OnChoiceSelect",arrayOf(id))}
}
