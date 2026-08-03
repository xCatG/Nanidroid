package com.cattailsw.nanidroid.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.os.SystemClock
import com.cattailsw.nanidroid.SurfaceManager
import com.cattailsw.nanidroid.toSurfaceDefinition
import com.cattailsw.nanidroid.runtime.GhostPresentationRuntimeState
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import com.cattailsw.nanidroid.runtime.KotlinGhostPresentationRuntime
import com.cattailsw.nanidroid.compose.stage.GhostStageMeasureState
import com.cattailsw.nanidroid.compose.stage.RenderedSurfaceLayer
import com.cattailsw.nanidroid.runtime.stage.SurfaceKey
import kotlinx.coroutines.delay

/**
 * Production Compose ghost-stage state.  This is deliberately the only bridge
 * from the script runner to the visual stage: it owns immutable surface plans
 * and never creates a SakuraView, KeroView, Balloon, or FrameLayout.
 */
class ComposeGhostStageHost private constructor(
    private val interactionPort: SurfaceInteractionPort,
    private val pixelAssets: SurfacePixelAssets,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) {
    constructor(interactionPort: SurfaceInteractionPort) : this(
        interactionPort,
        AndroidSurfacePixelAssets,
        Unit,
    )

    internal constructor(
        interactionPort: SurfaceInteractionPort,
        pixelAssets: SurfacePixelAssets,
    ) : this(interactionPort, pixelAssets, Unit)

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
    private val renderedFrames = LinkedHashMap<RenderedFrameKey, ComposedSurface>(16, 0.75f, true)
    // The two visible speakers are not historical cache entries. Keeping them
    // independently avoids re-decoding a valid large surface on every script
    // character while the bounded LRU protects the rest of the app heap.
    private val activeComposedSurfaces = mutableMapOf<SurfaceSpeaker, ActiveComposedSurface>()
    private var renderedFramePixels = 0L
    private var nextComposedRevision = 1L
    private var surfaceManagerInputEpoch = 0L
    private val stageMeasureState = GhostStageMeasureState()
    internal val latestMeasuredSnapshot get() = stageMeasureState.latest

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
            surfaceManagerInputEpoch++
            sakuraScheduler = null
            keroScheduler = null
            sakuraFrame = null
            keroFrame = null
            schedulerSurfaceIds.clear()
            nextPeriodicTicks.clear()
            speakerSurfaces.clear()
            clearRenderedFrames()
            stageMeasureState.resetFor(manager)
        }
        activeSurfaceManager = manager
    }

    @Composable
    fun Stage(
        modifier: Modifier = Modifier,
        blockingInput: () -> Boolean = { false },
        blockingInputEpoch: () -> Long = { 0L },
        onSurfaceTap: () -> Unit = {},
    ) {
        val manager = activeSurfaceManager
        val state = runtimeState
        val plans = remember(manager) { manager?.getSurfaceKeys()
            ?.mapNotNull { id -> manager.getSurface(id)?.toSurfaceDefinition()?.toSurfaceRenderPlan() }
            ?.filter { it.isRenderableSurface() }
            .orEmpty() }
        val compositor = remember(manager, pixelAssets) { SurfaceCompositor(pixelAssets, SurfacePlanRegistry(plans)) }
        val sakura = manager.speakerSurface(state.presentation.sakura.surfaceId, true)
        val kero = manager.speakerSurface(state.presentation.kero.surfaceId, false)
        val sakuraComposed = safeComposedSurface(
            compositor,
            SurfaceSpeaker.SAKURA,
            state.presentation.sakura.surfaceId,
            sakura.plan,
            sakuraFrame,
            explicitlyHidden = !sakura.visible,
        )
        val keroComposed = safeComposedSurface(
            compositor,
            SurfaceSpeaker.KERO,
            state.presentation.kero.surfaceId,
            kero.plan,
            keroFrame,
            explicitlyHidden = !kero.visible,
        )
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
            rearmPeriodicTicks()
            while (true) { delay(16); tickSchedulers() }
        }
        GhostPresentationStage(
            presentation = state.presentation,
            sakuraComposedSurface = sakuraComposed,
            keroComposedSurface = keroComposed,
            measureState = stageMeasureState,
            ghostKey = manager?.let { "manager-${System.identityHashCode(it)}" }.orEmpty(),
            ghostIdentity = manager ?: NoGhostIdentity,
            blockingInput = blockingInput(),
            ghostIdentityProvider = { activeSurfaceManager ?: NoGhostIdentity },
            blockingInputProvider = blockingInput,
            routingEpochProvider = {
                HostRoutingEpoch(
                    surfaceManager = surfaceManagerInputEpoch,
                    blocking = blockingInputEpoch(),
                )
            },
            onSurfaceEffect = interactionPort::dispatch,
            onToggleChrome = onSurfaceTap,
            modifier = modifier,
            sakuraSurface = { snapshot ->
                RenderedSurfaceLayer(snapshot, showCollisionOverlay = false)
            },
            keroSurface = { snapshot ->
                RenderedSurfaceLayer(snapshot, showCollisionOverlay = false)
            },
        )
    }

    private data class SpeakerSurface(val plan: SurfaceRenderPlan, val visible: Boolean)
    private data class SpeakerSurfaceKey(val sakura: Boolean, val surfaceId: String)
    private data class RenderedFrameKey(val speaker: SurfaceSpeaker, val surfaceId: String, val frame: SurfaceRenderFrame?)
    private data class ActiveComposedSurface(val key: RenderedFrameKey, val surface: ComposedSurface)
    private data class HostRoutingEpoch(val surfaceManager: Long, val blocking: Long)

    private fun composedSurface(
        compositor: SurfaceCompositor,
        speaker: SurfaceSpeaker,
        surfaceId: String,
        plan: SurfaceRenderPlan,
        frame: SurfaceRenderFrame?,
        explicitlyHidden: Boolean,
    ): ComposedSurface {
        val key = RenderedFrameKey(speaker, surfaceId, frame)
        activeComposedSurfaces[speaker]?.takeIf { it.key == key && it.surface.explicitlyHidden == explicitlyHidden }
            ?.let { return it.surface }
        renderedFrames[key]?.let {
            val cached = if (it.explicitlyHidden == explicitlyHidden) it else it.copy(explicitlyHidden = explicitlyHidden)
            activeComposedSurfaces[speaker] = ActiveComposedSurface(key, cached)
            return cached
        }
        val revision = nextComposedRevision++
        val composed = frame?.let { compositor.composeFrame(plan, it, explicitlyHidden, revision) }
            ?: compositor.composeNormal(plan, explicitlyHidden, revision)
        activeComposedSurfaces[speaker] = ActiveComposedSurface(key, composed)
        val pixels = composed.image.width.toLong() * composed.image.height.toLong()
        if (pixels > MAX_CACHED_FRAME_PIXELS) return composed
        while (renderedFramePixels + pixels > MAX_CACHED_FRAME_PIXELS && renderedFrames.isNotEmpty()) {
            val eldest = renderedFrames.entries.iterator().next()
            renderedFramePixels -= eldest.value.image.width.toLong() * eldest.value.image.height.toLong()
            renderedFrames.remove(eldest.key)
        }
        renderedFrames[key] = composed
        renderedFramePixels += pixels
        return composed
    }

    private fun safeComposedSurface(
        compositor: SurfaceCompositor,
        speaker: SurfaceSpeaker,
        surfaceId: String,
        plan: SurfaceRenderPlan,
        frame: SurfaceRenderFrame?,
        explicitlyHidden: Boolean,
    ): ComposedSurface = try {
        composedSurface(compositor, speaker, surfaceId, plan, frame, explicitlyHidden)
    } catch (_: IllegalArgumentException) {
        // Installed ghosts are data, not trusted program input. A pathological
        // bitmap/canvas must hide that surface instead of crashing the stage.
        ComposedSurface(
            image = SurfacePixelImage.Empty,
            canvasSize = androidx.compose.ui.unit.IntSize.Zero,
            visiblePixelBounds = null,
            effectiveCollisions = emptyList(),
            surfaceKey = SurfaceKey(plan.surfaceId, androidx.compose.ui.unit.IntSize.Zero),
            revision = nextComposedRevision++,
            explicitlyHidden = true,
        )
    }

    private fun clearRenderedFrames() {
        renderedFrames.clear()
        activeComposedSurfaces.clear()
        renderedFramePixels = 0L
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
    private val nextPeriodicTicks = java.util.IdentityHashMap<SurfaceAnimationScheduler, Long>()
    private fun newScheduler(plan: SurfaceRenderPlan): SurfaceAnimationScheduler =
        SurfaceAnimationScheduler(plan, SurfaceRenderClock { SystemClock.uptimeMillis() }, SurfaceRenderEntropy { Math.random() })
            .also {
                schedulerSurfaceIds[it] = plan.surfaceId
                nextPeriodicTicks[it] = SystemClock.uptimeMillis() + PERIODIC_ANIMATION_INTERVAL_MILLIS
            }
    private fun itPlan(scheduler: SurfaceAnimationScheduler): Int? = schedulerSurfaceIds[scheduler]

    private fun tickSchedulers() {
        val now = SystemClock.uptimeMillis()
        sakuraScheduler?.tickForHost(GhostSpeaker.SAKURA, now)
        keroScheduler?.tickForHost(GhostSpeaker.KERO, now)
    }
    private fun SurfaceAnimationScheduler.tickForHost(speaker: GhostSpeaker, nowMillis: Long) {
        val periodicSelectionDue = nowMillis >= (nextPeriodicTicks[this] ?: Long.MAX_VALUE)
        tick(allowPeriodicSelection = periodicSelectionDue).applyFrames(speaker)
        if (periodicSelectionDue) nextPeriodicTicks[this] = nowMillis + PERIODIC_ANIMATION_INTERVAL_MILLIS
    }
    private fun rearmPeriodicTicks() {
        val nextTick = SystemClock.uptimeMillis() + PERIODIC_ANIMATION_INTERVAL_MILLIS
        nextPeriodicTicks.keys.forEach { scheduler -> nextPeriodicTicks[scheduler] = nextTick }
    }
    private fun List<SurfaceAnimationScheduleEffect>?.applyFrames(speaker: GhostSpeaker) {
        this?.filterIsInstance<SurfaceAnimationScheduleEffect.Frame>()?.lastOrNull()?.frame?.let {
            if (speaker == GhostSpeaker.SAKURA) sakuraFrame = it else keroFrame = it
        }
    }

    private fun SurfaceManager?.speakerSurface(id: String, sakura: Boolean): SpeakerSurface {
        if (id == "-1") return SpeakerSurface(SurfaceRenderPlan.Missing, false)
        val key = SpeakerSurfaceKey(sakura, id)
        return speakerSurfaces.getOrPut(key) {
            val shell = if (sakura) this?.getSakuraSurface(id) else this?.getKeroSurface(id)
            val definition = shell?.toSurfaceDefinition()
            val plan = definition.toSurfaceRenderPlan()
            if (!plan.isRenderableSurface()) {
                SpeakerSurface(SurfaceRenderPlan.Missing, false)
            } else {
                SpeakerSurface(plan, true)
            }
        }
    }

    private fun SurfaceRenderPlan.isRenderableSurface(): Boolean =
        width >= 0 && height >= 0 && width.toLong() * height.toLong() <= MAX_RENDERABLE_SURFACE_PIXELS

    private companion object {
        private data object NoGhostIdentity
        /** 32 MiB of ARGB pixels; oversized frames remain usable but uncached. */
        const val MAX_CACHED_FRAME_PIXELS = 8L * 1024L * 1024L
        // Rendering creates several temporary ARGB copies; keep malicious or
        // unusually huge installed surfaces well below a typical app heap.
        const val MAX_RENDERABLE_SURFACE_PIXELS = 1L * 1024L * 1024L
        const val PERIODIC_ANIMATION_INTERVAL_MILLIS = 1_000L
    }
}
