package com.cattailsw.nanidroid.compose

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.os.SystemClock
import com.cattailsw.nanidroid.SurfaceDefinition
import com.cattailsw.nanidroid.SurfaceManager
import com.cattailsw.nanidroid.toSurfaceDefinition
import com.cattailsw.nanidroid.runtime.GhostPresentationRuntimeState
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import com.cattailsw.nanidroid.runtime.KotlinGhostPresentationRuntime
import kotlinx.coroutines.delay

/**
 * Production Compose ghost-stage state.  This is deliberately the only bridge
 * from the script runner to the visual stage: it owns immutable surface plans
 * and never creates a SakuraView, KeroView, Balloon, or FrameLayout.
 */
class ComposeGhostStageHost(
    private val interactionPort: SurfaceInteractionPort,
) {
    var runtimeState: GhostPresentationRuntimeState by mutableStateOf(GhostPresentationRuntimeState.Initial)
        private set
    private var activeSurfaceManager: SurfaceManager? by mutableStateOf(null)
    private var sakuraFrame: SurfaceRenderFrame? by mutableStateOf(null)
    private var keroFrame: SurfaceRenderFrame? by mutableStateOf(null)
    private var sakuraScheduler: SurfaceAnimationScheduler? = null
    private var keroScheduler: SurfaceAnimationScheduler? = null
    /* A SurfaceManager describes immutable ghost assets. Cache both the parsed
       plans and their rasterized frames for that manager; recomposition must
       never reopen/decode assets merely because an animation clock ticked. */
    private val speakerSurfaces = mutableMapOf<SpeakerSurfaceKey, SpeakerSurface>()
    private val renderedFrames = mutableMapOf<RenderedFrameKey, SurfacePixelImage>()

    val renderer = KotlinGhostPresentationRuntime { transition ->
        runtimeState = transition.state
        val manager = activeSurfaceManager ?: return@KotlinGhostPresentationRuntime
        // The Kotlin runtime owns the legacy shared talk cadence. Passing its
        // gate through directly also keeps activity recreation from resetting it.
        val talkUpdate = SurfaceTalkCadence.Update(transition.state.talkingAnimationEnabled)
        schedule(GhostSpeaker.SAKURA, manager.speakerSurface(transition.state.presentation.sakura.surfaceId, true).plan,
            transition.state.presentation.sakura, talkUpdate)
        schedule(GhostSpeaker.KERO, manager.speakerSurface(transition.state.presentation.kero.surfaceId, false).plan,
            transition.state.presentation.kero, talkUpdate)
    }

    fun setSurfaceManager(manager: SurfaceManager?) {
        if (activeSurfaceManager !== manager) {
            sakuraScheduler = null
            keroScheduler = null
            sakuraFrame = null
            keroFrame = null
            schedulerSurfaceIds.clear()
            speakerSurfaces.clear()
            renderedFrames.clear()
        }
        activeSurfaceManager = manager
    }

    @Composable
    fun Stage(modifier: Modifier = Modifier) {
        val manager = activeSurfaceManager
        val state = runtimeState
        val plans = remember(manager) { manager?.getSurfaceKeys()
            ?.mapNotNull { id -> manager.getSurface(id)?.toSurfaceDefinition()?.toSurfaceRenderPlan() }.orEmpty() }
        val compositor = remember(manager) { SurfaceCompositor(AndroidSurfacePixelAssets, SurfacePlanRegistry(plans)) }
        val sakura = manager.speakerSurface(state.presentation.sakura.surfaceId, true)
        val kero = manager.speakerSurface(state.presentation.kero.surfaceId, false)
        val sakuraImage = renderedImage(compositor, SurfaceSpeaker.SAKURA, state.presentation.sakura.surfaceId, sakura.plan, sakuraFrame)
        val keroImage = renderedImage(compositor, SurfaceSpeaker.KERO, state.presentation.kero.surfaceId, kero.plan, keroFrame)
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        var stageStarted by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, _ ->
                stageStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(sakuraScheduler, keroScheduler, stageStarted) {
            if (!stageStarted) return@LaunchedEffect
            while (true) { delay(16); tickSchedulers() }
        }
        GhostPresentationStage(
            presentation = state.presentation,
            sakuraSurfaceSize = IntSize(sakuraImage.width, sakuraImage.height),
            keroSurfaceSize = IntSize(keroImage.width, keroImage.height),
            modifier = modifier,
            sakuraSurface = { if (sakura.visible) SurfaceNode(SurfaceSpeaker.SAKURA, sakura.definition, sakuraImage) },
            keroSurface = { if (kero.visible) SurfaceNode(SurfaceSpeaker.KERO, kero.definition, keroImage) },
        )
    }

    @Composable
    private fun BoxScope.SurfaceNode(
        speaker: SurfaceSpeaker,
        definition: SurfaceDefinition?,
        image: SurfacePixelImage,
    ) {
        var renderedSize by remember { mutableStateOf(IntSize.Zero) }
        SurfaceCompositorImage(
            image = image,
            modifier = Modifier
                .onSizeChanged { renderedSize = it }
                .pointerInput(speaker, definition, image, renderedSize) {
                    detectTapGestures(
                        onTap = { position ->
                            val resolution = SurfacePointerInteractionMapper.map(
                                speaker = speaker,
                                definition = definition,
                                image = image,
                                transform = SurfacePointerTransform(
                                    left = 0f,
                                    top = 0f,
                                    renderedWidth = renderedSize.width.toFloat(),
                                    renderedHeight = renderedSize.height.toFloat(),
                                    sourceWidth = image.width,
                                    sourceHeight = image.height,
                                ),
                                position = SurfacePointerPosition(position.x, position.y),
                            )
                            SurfacePointerInteractionDispatcher(interactionPort).dispatch(resolution)
                        },
                    )
                },
        )
    }

    private data class SpeakerSurface(val definition: SurfaceDefinition?, val plan: SurfaceRenderPlan, val visible: Boolean)
    private data class SpeakerSurfaceKey(val sakura: Boolean, val surfaceId: String)
    private data class RenderedFrameKey(val speaker: SurfaceSpeaker, val surfaceId: String, val frame: SurfaceRenderFrame?)

    private fun renderedImage(
        compositor: SurfaceCompositor,
        speaker: SurfaceSpeaker,
        surfaceId: String,
        plan: SurfaceRenderPlan,
        frame: SurfaceRenderFrame?,
    ): SurfacePixelImage = renderedFrames.getOrPut(RenderedFrameKey(speaker, surfaceId, frame)) {
        frame?.let { compositor.frame(plan, it) } ?: compositor.normal(plan)
    }

    private fun schedule(
        speaker: GhostSpeaker,
        plan: SurfaceRenderPlan,
        presentation: com.cattailsw.nanidroid.runtime.GhostSpeakerPresentation,
        talkUpdate: SurfaceTalkCadence.Update,
    ) {
        val scheduler: SurfaceAnimationScheduler = when (speaker) {
            GhostSpeaker.SAKURA -> sakuraScheduler?.takeIf { itPlan(it) == plan.surfaceId }
                ?: newScheduler(plan).also { sakuraScheduler = it; sakuraFrame = null }
            GhostSpeaker.KERO -> keroScheduler?.takeIf { itPlan(it) == plan.surfaceId }
                ?: newScheduler(plan).also { keroScheduler = it; keroFrame = null }
        }
        scheduler.presentationUpdated(presentation.balloonVisible, presentation.animationId, talkUpdate)
            .filterIsInstance<SurfaceAnimationScheduleEffect.Frame>()
            .lastOrNull()
            ?.frame
            ?.also { frame -> if (speaker == GhostSpeaker.SAKURA) sakuraFrame = frame else keroFrame = frame }
    }

    /* Scheduler has no plan getter by design; retaining the selected surface id
       at this host boundary keeps scheduler state scoped to one surface. */
    private val schedulerSurfaceIds = java.util.IdentityHashMap<SurfaceAnimationScheduler, Int?>()
    private fun newScheduler(plan: SurfaceRenderPlan): SurfaceAnimationScheduler =
        SurfaceAnimationScheduler(plan, SurfaceRenderClock { SystemClock.uptimeMillis() }, SurfaceRenderEntropy { Math.random() })
            .also { schedulerSurfaceIds[it] = plan.surfaceId }
    private fun itPlan(scheduler: SurfaceAnimationScheduler): Int? = schedulerSurfaceIds[scheduler]

    private fun tickSchedulers() {
        sakuraScheduler?.tick().applyFrames(GhostSpeaker.SAKURA)
        keroScheduler?.tick().applyFrames(GhostSpeaker.KERO)
    }
    private fun List<SurfaceAnimationScheduleEffect>?.applyFrames(speaker: GhostSpeaker) {
        this?.filterIsInstance<SurfaceAnimationScheduleEffect.Frame>()?.lastOrNull()?.frame?.let {
            if (speaker == GhostSpeaker.SAKURA) sakuraFrame = it else keroFrame = it
        }
    }

    private fun SurfaceManager?.speakerSurface(id: String, sakura: Boolean): SpeakerSurface {
        if (id == "-1") return SpeakerSurface(null, SurfaceRenderPlan.Missing, false)
        val key = SpeakerSurfaceKey(sakura, id)
        return speakerSurfaces.getOrPut(key) {
            val shell = if (sakura) this?.getSakuraSurface(id) else this?.getKeroSurface(id)
            val definition = shell?.toSurfaceDefinition()
            SpeakerSurface(definition, definition.toSurfaceRenderPlan(), true)
        }
    }
}
