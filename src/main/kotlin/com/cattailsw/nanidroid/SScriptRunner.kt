package com.cattailsw.nanidroid

import android.content.Context
import android.os.Handler
import android.os.Message
import android.os.SystemClock
import android.util.Log
import com.cattailsw.nanidroid.di.MonotonicClock
import com.cattailsw.nanidroid.runtime.dialogue.AnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueRuntimeState
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.dialogue.GhostRuntimeMode
import com.cattailsw.nanidroid.runtime.dialogue.InputDispatch
import com.cattailsw.nanidroid.runtime.dialogue.PendingInputState
import com.cattailsw.nanidroid.runtime.dialogue.SakuraScriptTokenizer
import com.cattailsw.nanidroid.runtime.dialogue.ShioriMethod
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionProtocol
import com.cattailsw.nanidroid.util.AnalyticsUtils
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.UUID

/** Executes Sakura Script while keeping the legacy Java-facing runner contract. */
open class SScriptRunner internal constructor(
    ctx: Context?,
    private val sessionCoordinator: GhostSessionCoordinator,
    private val monotonicClock: MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() },
) : Runnable {
    constructor(ctx: Context?) : this(ctx, productionSessionCoordinator)
    interface StatusCallback { fun stop(); fun canExit(); fun ghostSwitchScriptComplete() }
    interface UICallback { fun showUserInputBox(id: String); fun showUserSelection(textlabel: Array<String>, ids: Array<String>) }

    companion object {
        private const val TAG = "SScriptRunner"
        @JvmField val WAIT_UNIT: Long = 50
        @JvmField val WAIT_YEN_E: Long = 1000
        private const val RUN = 42; private const val STOP = 43; private const val INC_CLOCK = 44; private const val CLOCK_STEP = 1000L
        private val PASSIVE_MODE = Regex("""^\[(enter|leave),passivemode]""")
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
        ): T {
            return productionSessionCoordinator.withMutation(
                ghostId,
                ghostRoot,
                onStopped = { onFailure(IOException("ghost mutation was interrupted")) },
                onFailure = onFailure,
                onActiveSessionInvalidated = { self?.invalidateForSessionUnload(it) },
                action = action,
            )
        }
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
    private var sync = false; private var wholeline = false; private var sakuraTalk = false
    private val sakuraMsg = StringBuilder(); private val keroMsg = StringBuilder(); private var waitTime = WAIT_UNIT; private var charIndex = 0
    private var sakuraSurfaceId = "0"; private var keroSurfaceId = "10"; private var sakuraAnimationId: String? = null; private var keroAnimationId: String? = null
    private var bSakuraId = "0"; private var bKeroId = "-1"; private var talkAnimeControl = 0
    private var lastSec = 0; private var lastMin = 0; private var lastHour = 0L; private var restore = false; private var exitPending = false; private var changingPending = false; private var paused = false; private val bootDispatchState = BootDispatchState()
    private var dialogueState = DialogueRuntimeState()
    private var nextInputGeneration = 0L
    private var dialogueDialogOwner = UUID.randomUUID().toString()
    private var nextChoiceGeneration = 0L
    private var pendingChoiceGeneration: Long? = null
    private var passive = false
    @Volatile private var dialogueClaimHookForTesting: (() -> Unit)? = null

    internal fun setPresentationRendererForTesting(renderer: GhostPresentationRenderer?) { presentationRenderer = renderer }
    internal fun setDialogueClaimHookForTesting(hook: (() -> Unit)?) { dialogueClaimHookForTesting = hook }
    fun setPresentationRenderer(renderer: GhostPresentationRenderer?) { presentationRenderer = renderer }
    fun dispatchSurfaceInteraction(effect: SurfaceInteractionEffect): Boolean = withCurrentGhost { target ->
        val eventId = SurfaceInteractionProtocol.eventFor(effect, target.pointerEventCapabilities())
            ?: return@withCurrentGhost false
        val passiveSequence = runtimeModeSnapshot().passive
        if (!passiveSequence && effect.speaker.legacyReference == "1") clearMsgQueue()
        if (!isPinnedDialogueGhost(target)) return@withCurrentGhost false
        val response = target.requestRaw(ShioriMethod.GET, eventId, SurfaceInteractionProtocol.references(effect))
        if (!passiveSequence && !runtimeModeSnapshot().passive && isPinnedDialogueGhost(target)) {
            parseShioriResponseAndInsert(response)
        }
        true
    } ?: false
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
        sessionCoordinator.markActiveUnloaded(ghost).also { unloaded ->
            if (unloaded) synchronized(this) {
                if (g === ghost) passive = false
            }
        }
    private fun setGhostInternal(newGhost: Ghost?, reservation: ReservedGhost?): Boolean {
        val outgoing = synchronized(this) { g }
        val firstActivation = newGhost?.getCreateCount() == 0L
        var outgoingName: String? = null
        val assign = {
            synchronized(this) {
                outgoingName = g?.getGhostName()
                g = newGhost
                if (outgoing != null && outgoing !== newGhost) clearDialogueStateLocked()
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
    fun startClock() { LegacyPlatform.debug(TAG,"startClock called"); val start = bootDispatchState.startClock(); if (!start.started) return;LegacyPlatform.scheduleDelayed(CLOCK_STEP) { clockHandler.sendEmptyMessageDelayed(INC_CLOCK,CLOCK_STEP) };if(restore)doShioriEvent("OnWindowStateRestore",null)else if(start.dispatchBoot){doBoot();bootDispatchState.markBootDispatched()};restore=false }
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
    private fun getFromQueue() = rewriteMsg(msgQueue.poll()).also { script ->
        script?.let(::recordDialogueScript)
    }
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
        isRunning=false
        if (unloadTarget != null) passive = false
        bSakuraId="-1";bKeroId="-1";updateUI();cb?.let { callback ->
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
    private fun handleExclaim(){
        val remaining = msg!!.substring(charIndex)
        val passive = PASSIVE_MODE.find(remaining)
        if (passive != null) {
            charIndex += passive.value.length
            this.passive = passive.groupValues[1] == "enter"
            return
        }
        val m=PatternHolders.open_input.matcher(remaining)
        if(m.find()){charIndex+=m.group().length;openUserInputBox(m.group(1))}
    }
    private fun openUserInputBox(id:String?){if(id==null)return;ucb?.let{paused=true;it.showUserInputBox(id)}}
    private fun handleSurface():Boolean{val left=msg!!.substring(charIndex);val m=PatternHolders.surface_ptrn.matcher(left);if(!m.find())return false;changeSurface(m.group(2)?:m.group(1));charIndex+=m.group().length;return true}
    private fun handleBalloon():Boolean{val m=PatternHolders.balloon_ptrn.matcher(msg!!.substring(charIndex));if(!m.find())return false;changeBalloon(m.group(2)?:m.group(1));charIndex+=m.group().length;return true}
    private fun handleAnimation():Boolean{val m=PatternHolders.ani_ptrn.matcher(msg!!.substring(charIndex));if(!m.find())return false;queueAnimation(m.group(1));charIndex+=m.group().length;return true}
    private fun handleSelection():Boolean{charIndex-=2;var matcher=PatternHolders.q_choice_ptrn.matcher(msg!!);val labels=ArrayList<String>();val ids=ArrayList<String>();while(matcher.find()){msg=matcher.replaceFirst(matcher.group(1));labels.add(matcher.group(1));ids.add(matcher.group(2));matcher=PatternHolders.q_choice_ptrn.matcher(msg!!)};ucb?.let{wholeline=true;it.showUserSelection(labels.toTypedArray(),ids.toTypedArray())};return false}
    private fun changeSurface(id:String){if(sakuraTalk)sakuraSurfaceId=id else keroSurfaceId=id;doShioriEvent("OnSurfaceChange",arrayOf("Reference0: $sakuraSurfaceId","Reference1: $keroSurfaceId"))}
    private fun changeBalloon(id:String){if(sakuraTalk)bSakuraId=id else bKeroId=id}; private fun queueAnimation(id:String){if(sakuraTalk)sakuraAnimationId=id else keroAnimationId=id}
    private fun updateUI(){val sa=sakuraAnimationId!=null;val ka=keroAnimationId!=null;presentationRenderer?.render(GhostPresentationFrame(GhostPresentationFrame.Speaker(sakuraMsg.toString(),sakuraSurfaceId,sakuraAnimationId,bSakuraId),GhostPresentationFrame.Speaker(keroMsg.toString(),keroSurfaceId,keroAnimationId,bKeroId),talkAnimeControl==0));if(sa)sakuraAnimationId=null;if(ka)keroAnimationId=null;talkAnimeControl++;if(talkAnimeControl==10)talkAnimeControl=0}
    private fun doPerSecondEvent(hr: Long) { dispatchTimerEvent("OnSecondChange", hr) }
    private fun doPerMinuteEvent(hr: Long) { dispatchTimerEvent("OnMinuteChange", hr) }
    private fun dispatchTimerEvent(event: String, uptimeHours: Long) {
        withCurrentGhost { target ->
            val canTalk = runtimeModeSnapshot().canTalk
            val method = if (canTalk) ShioriMethod.GET else ShioriMethod.NOTIFY
            val response = target.requestRaw(method, event, listOf(uptimeHours.toString(), "0", "0", if (canTalk) "1" else "0"))
            if (canTalk && runtimeModeSnapshot().canTalk && isPinnedDialogueGhost(target)) {
                parseShioriResponseAndInsert(response)
            }
        }
    }
    private fun perClockEvent() {
        val secondsAll = monotonicClock.nowMillis() / 1_000L
        var minute = secondsAll / 60L
        val hour = minute / 60L
        val seconds = (secondsAll % 60L).toInt()
        minute %= 60L
        if (seconds - lastSec >= 1 || seconds == 0) { doPerSecondEvent(hour); lastSec = seconds }
        if (minute - lastMin >= 1 || lastMin == 59 && minute == 0L) { doPerMinuteEvent(hour); lastMin = minute.toInt() }
        if (hour - lastHour >= 1) lastHour = hour
    }
    internal fun dispatchClockTickForTesting() = perClockEvent()
    private fun parseShioriResponseAndInsert(res:ShioriResponse?){if(res==null||res.getStatusCode()!=200)return;msg=res.getKey("Value");addMsgToQueue(arrayOf(msg!!));if(!isRunning)run()}
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
    ): T {
        return sessionCoordinator.withMutation(
            ghostId,
            ghostRoot,
            shouldStop,
            onStopped,
            onFailure,
            onActiveSessionInvalidated = ::invalidateForSessionUnload,
            action = action,
        )
    }

    /** Removes state that cannot survive a true unload/reload of the live SHIORI session. */
    private fun invalidateForSessionUnload(target: Ghost) = synchronized(this) {
        if (g === target) clearDialogueStateLocked()
    }

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
    /** A UI host observes this immutable value; it never owns pending actions. */
    internal fun dialogueStateSnapshot(): DialogueRuntimeState = synchronized(this) { dialogueState }
    internal fun dialogueDialogRuntimeSnapshot(): DialogueDialogRuntimeSnapshot = synchronized(this) {
        DialogueDialogRuntimeSnapshot(dialogueDialogOwner, pendingChoiceGeneration, dialogueState)
    }
    internal fun runtimeModeSnapshot(): GhostRuntimeMode = synchronized(this) {
        GhostRuntimeMode(
            playingTalk = isRunning || msgQueue.isNotEmpty() || !msg.isNullOrEmpty(),
            pendingUserAction = dialogueState.pendingChoices.isNotEmpty() || dialogueState.pendingInput != null,
            passive = passive,
        )
    }

    internal fun activateChoice(action: DialogueAction) {
        when (action) {
            is DialogueAction.Normal -> {
                dispatchDialogueTransaction(
                    claim = { action.takeIf { takePendingChoice(it) } },
                    primary = {
                        DialogueEvent("OnChoiceSelectEx", listOf(it.label, it.id) + it.extraReferences)
                    },
                    fallback = { DialogueEvent("OnChoiceSelect", listOf(it.id)) },
                )
            }
            is DialogueAction.DirectEvent -> dispatchDialogueTransaction(
                claim = { action.takeIf { takePendingChoice(it) } },
                primary = { DialogueEvent(it.eventId, it.references) },
            )
            is DialogueAction.Script -> enqueueLocalDialogueScript(
                claim = { takePendingChoice(action) },
                script = action.sakuraScript,
            )
        }
    }

    internal fun activateAnchor(action: AnchorAction) {
        when (action) {
            is AnchorAction.Normal -> {
                dispatchDialogueTransaction(
                    claim = { action.takeIf { isCurrentAnchor(it) } },
                    primary = {
                        DialogueEvent("OnAnchorSelectEx", listOf(it.label, it.id) + it.extraReferences)
                    },
                    fallback = { DialogueEvent("OnAnchorSelect", listOf(it.id)) },
                )
            }
            is AnchorAction.DirectEvent -> dispatchDialogueTransaction(
                claim = { action.takeIf { isCurrentAnchor(it) } },
                primary = { DialogueEvent(it.eventId, it.references) },
            )
        }
    }

    internal fun submitInput(generation: Long, value: String) {
        dispatchDialogueTransaction(
            claim = { takePendingInput(generation) },
            primary = { pending ->
                when (val dispatch = pending.spec.dispatch) {
                    is InputDispatch.Normal -> DialogueEvent(
                        "OnUserInput",
                        listOf(dispatch.id, value, pending.spec.supplement) + pending.spec.extraReferences,
                    )
                    is InputDispatch.DirectEvent -> DialogueEvent(
                        dispatch.eventId,
                        listOf(value, pending.spec.supplement) + pending.spec.extraReferences,
                    )
                }
            },
        )
    }

    internal fun dismissInput(generation: Long) {
        cancelInput({ takePendingInput(generation) }, "close", fallback = false)
    }

    internal fun processExpiredInput() {
        cancelInput(::takeExpiredPendingInput, "timeout", fallback = true)
    }

    private fun cancelInput(claim: () -> PendingInputState?, reason: String, fallback: Boolean) {
        dispatchDialogueTransaction(
            claim = claim,
            primary = { pending ->
                DialogueEvent("OnUserInputCancel", cancelReferences(pending, reason))
            },
            fallback = if (fallback) {
                { pending -> DialogueEvent("OnUserInput", cancelReferences(pending, "timeout")) }
            } else null,
        )
    }

    private fun cancelReferences(pending: PendingInputState, reason: String): List<String> {
        val id = when (val dispatch = pending.spec.dispatch) {
            is InputDispatch.Normal -> dispatch.id
            is InputDispatch.DirectEvent -> dispatch.eventId
        }
        return listOf(id, reason, pending.spec.supplement) + pending.spec.extraReferences
    }

    private fun takePendingInput(generation: Long): PendingInputState? = synchronized(this) {
        val pending = dialogueState.pendingInput ?: return@synchronized null
        if (pending.generation != generation) return@synchronized null
        dialogueState = dialogueState.copy(revision = dialogueState.revision + 1, pendingInput = null)
        pending
    }

    private fun takePendingChoice(action: DialogueAction): Boolean = synchronized(this) {
        if (dialogueState.pendingChoices.none { it === action }) return@synchronized false
        pendingChoiceGeneration = null
        dialogueState = dialogueState.copy(
            revision = dialogueState.revision + 1,
            pendingChoices = emptyList(),
        )
        true
    }

    /** Keeps primary, fallback, and response enqueue on the same live SHIORI generation. */
    private fun <T> dispatchDialogueTransaction(
        claim: () -> T?,
        primary: (T) -> DialogueEvent,
        fallback: ((T) -> DialogueEvent)? = null,
    ): Boolean {
        var shouldRun = false
        val played = withCurrentGhost { target ->
            val claimed = claim() ?: return@withCurrentGhost false
            dialogueClaimHookForTesting?.invoke()
            if (!isPinnedDialogueGhost(target)) return@withCurrentGhost false
            fun enqueueIfPlayable(response: ShioriResponse?): Boolean {
                val value = response?.takeIf { it.getStatusCode() == 200 }?.getKey("Value")
                if (value.isNullOrEmpty() || !isPinnedDialogueGhost(target)) return false
                addMsgToQueue(arrayOf(value))
                shouldRun = true
                return true
            }

            val primaryEvent = primary(claimed)
            if (enqueueIfPlayable(target.doShioriEvent(primaryEvent.event, primaryEvent.references.toTypedArray()))) {
                return@withCurrentGhost true
            }
            if (!isPinnedDialogueGhost(target) || fallback == null) return@withCurrentGhost false
            val fallbackEvent = fallback(claimed)
            enqueueIfPlayable(target.doShioriEvent(fallbackEvent.event, fallbackEvent.references.toTypedArray()))
        } ?: false
        if (shouldRun) run()
        return played
    }

    private fun isPinnedDialogueGhost(target: Ghost): Boolean =
        synchronized(this) { g === target } && sessionCoordinator.withGhostGate(target) { it }

    private fun enqueueLocalDialogueScript(claim: () -> Boolean, script: String) {
        var shouldRun = false
        withCurrentGhost {
            if (!claim()) return@withCurrentGhost false
            addMsgToQueue(arrayOf(script))
            shouldRun = true
            true
        }
        if (shouldRun) run()
    }

    private fun isCurrentAnchor(action: AnchorAction): Boolean =
        dialogueState.contents.asSequence()
            .flatMap { it.segments.asSequence() }
            .mapNotNull { (it as? DialogueSegment.Anchor)?.action }
            .any { it === action }

    private fun takeExpiredPendingInput(): PendingInputState? = synchronized(this) {
        val pending = dialogueState.pendingInput ?: return@synchronized null
        if (monotonicClock.nowMillis() < pending.deadlineElapsedMillis) return@synchronized null
        takePendingInput(pending.generation)
    }

    private data class DialogueEvent(val event: String, val references: List<String>)

    private fun recordDialogueScript(script: String) {
        val contents = SakuraScriptTokenizer.tokenize(script) { LegacyPlatform.debug(TAG, it) }
        val choices = contents.flatMap { content ->
            content.segments.mapNotNull { (it as? DialogueSegment.Choice)?.action }
        }
        val input = contents.asSequence()
            .flatMap { it.segments.asSequence() }
            .mapNotNull { (it as? DialogueSegment.InputBox)?.spec }
            .lastOrNull()
        val passiveOnly = contents.asSequence()
            .flatMap { it.segments.asSequence() }
            .let { segments -> segments.any { it is DialogueSegment.PassiveMode } && segments.all { it is DialogueSegment.PassiveMode } }
        synchronized(this) {
            if (passiveOnly) {
                dialogueState = dialogueState.copy(revision = dialogueState.revision + 1)
            } else {
                val pendingInput = input?.let { spec ->
                    val deadline = inputDeadline(spec)
                    PendingInputState(++nextInputGeneration, spec, deadline)
                } ?: dialogueState.pendingInput
                pendingChoiceGeneration = choices.takeIf { it.isNotEmpty() }
                    ?.let { ++nextChoiceGeneration }
                dialogueState = DialogueRuntimeState(
                    revision = dialogueState.revision + 1,
                    contents = contents,
                    pendingChoices = choices,
                    pendingInput = pendingInput,
                )
            }
        }
    }

    private fun inputDeadline(spec: com.cattailsw.nanidroid.runtime.dialogue.InputBoxSpec): Long {
        val timeout = spec.timeoutMillis ?: return Long.MAX_VALUE
        if (timeout <= 0L) return Long.MAX_VALUE
        val now = monotonicClock.nowMillis()
        return if (now > Long.MAX_VALUE - timeout) Long.MAX_VALUE else now + timeout
    }

    private fun clearDialogueStateLocked() {
        dialogueDialogOwner = UUID.randomUUID().toString()
        pendingChoiceGeneration = null
        dialogueState = DialogueRuntimeState(revision = dialogueState.revision + 1)
        msgQueue.clear()
        msg = null
        passive = false
    }
    fun doBoot(){g?.let{val shell=it.getShellName();val count=it.getCreateCount();if(count>1){doShioriEvent("OnBoot",arrayOf(shell) as Array<String>);AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_PGM_FLOW,"onboot",it.getGhostId(),count.toInt())}else{doShioriEvent("OnFirstBoot",arrayOf("0"));AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_PGM_FLOW,"onfirstboot",it.getGhostId(),0)}}}
    fun getStringValueFromShiori(id:String):String?=withCurrentGhost { it.getStringFromShiori(id) };fun doUserInput(id:String,input:String){doShioriEvent("OnUserInput",arrayOf(id,input))};fun doOnChoiceSelect(id:String){clearMsgQueue();doShioriEvent("OnChoiceSelect",arrayOf(id))}
}
