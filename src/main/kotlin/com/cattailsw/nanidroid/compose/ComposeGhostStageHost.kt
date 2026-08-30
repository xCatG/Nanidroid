package com.cattailsw.nanidroid.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import android.os.SystemClock
import com.cattailsw.nanidroid.SurfaceCatalog
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeCueKind
import com.cattailsw.nanidroid.runtime.RuntimeHostLease
import com.cattailsw.nanidroid.runtime.RuntimeSnapshot
import com.cattailsw.nanidroid.runtime.RuntimeSpeakerPresentation
import com.cattailsw.nanidroid.runtime.RuntimeSurfaceIdentity
import com.cattailsw.nanidroid.compose.stage.GhostStageMeasureState
import com.cattailsw.nanidroid.compose.stage.RenderedSurfaceLayer
import com.cattailsw.nanidroid.runtime.stage.SurfaceKey
import com.cattailsw.nanidroid.runtime.stage.bubbleScrollSessionIdentity
import com.cattailsw.nanidroid.runtime.dialogue.AnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueContent
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSpeakerOwnership
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeAnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeChoiceAction
import java.util.IdentityHashMap
import kotlinx.coroutines.delay

/**
 * View-local surface and animation adapter for one immutable runtime snapshot.
 */
internal class ComposeGhostStageHost private constructor(
    private val pixelAssets: SurfacePixelAssets,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) {
    constructor() : this(AndroidSurfacePixelAssets, Unit)

    internal constructor(pixelAssets: SurfacePixelAssets) : this(pixelAssets, Unit)

    private var sakuraFrame: SurfaceRenderFrame? by mutableStateOf(null)
    private var keroFrame: SurfaceRenderFrame? by mutableStateOf(null)
    private var sakuraActiveAnimationId: String? by mutableStateOf(null)
    private var keroActiveAnimationId: String? by mutableStateOf(null)
    private var sakuraScheduler: SurfaceAnimationScheduler? = null
    private var keroScheduler: SurfaceAnimationScheduler? = null
    /* A SurfaceCatalog describes immutable ghost assets. Cache both the parsed
       plans and their rasterized frames for that catalog; recomposition must
       never reopen/decode assets merely because an animation clock ticked. */
    private val speakerSurfaces = mutableMapOf<SpeakerSurfaceKey, SpeakerSurface>()
    private val renderedFrames = LinkedHashMap<RenderedFrameKey, ComposedSurface>(16, 0.75f, true)
    // The two visible speakers are not historical cache entries. Keeping them
    // independently avoids re-decoding a valid large surface on every script
    // character while the bounded LRU protects the rest of the app heap.
    private val activeComposedSurfaces = mutableMapOf<SurfaceSpeaker, ActiveComposedSurface>()
    private var renderedFramePixels = 0L
    private var nextComposedRevision = 1L
    private var lastAppliedCueLease: RuntimeHostLease? = null
    private var lastAppliedCueId = 0L
    private val stageMeasureState = GhostStageMeasureState()
    internal val latestMeasuredSnapshot get() = stageMeasureState.latest

    @Composable
    internal fun Stage(
        snapshot: RuntimeSnapshot,
        hostLease: RuntimeHostLease,
        submitCommand: (RuntimeCommand) -> Unit,
        modifier: Modifier = Modifier,
        blockingInput: () -> Boolean = { false },
        onSurfaceTap: () -> Unit = {},
        onDialogueExternalUrl: (String) -> Unit = {},
        onDialogueInputDraft: (com.cattailsw.nanidroid.runtime.dialogue.RuntimeInputAction) -> Unit = {},
        collisionOverlaySpeaker: SurfaceSpeaker? = null,
    ) {
        val catalog = snapshot.activeSurfaces
        val ghostKey = snapshot.activeGhostId.orEmpty()
        val presentation = snapshot.presentation
        LaunchedEffect(catalog, ghostKey) {
            resetSurfaceCaches(catalog)
        }
        val plans = remember(catalog) { catalog?.keys
            ?.mapNotNull { id -> catalog.definition(id)?.toSurfaceRenderPlan() }
            ?.filter { it.isRenderableSurface() }
            .orEmpty() }
        val compositor = remember(catalog, pixelAssets) { SurfaceCompositor(pixelAssets, SurfacePlanRegistry(plans)) }
        val sakura = catalog.speakerSurface(presentation.sakura.surfaceId, true)
        val kero = catalog.speakerSurface(presentation.kero.surfaceId, false)
        val sakuraComposed = safeComposedSurface(
            compositor,
            SurfaceSpeaker.SAKURA,
            presentation.sakura.surfaceId,
            sakura.plan,
            sakuraFrame,
            explicitlyHidden = !sakura.visible,
        )
        val keroComposed = safeComposedSurface(
            compositor,
            SurfaceSpeaker.KERO,
            presentation.kero.surfaceId,
            kero.plan,
            keroFrame,
            explicitlyHidden = !kero.visible,
        )
        val dialogue = snapshot.dialogue.state
        val dialogueOwnership = remember(dialogue) { DialogueSpeakerOwnership.from(dialogue) }
        val choiceBindings = remember(snapshot.dialogue) {
            identityActionBindings(
                dialogue.pendingChoices,
                snapshot.dialogue.choices,
                RuntimeChoiceAction::action,
            )
        }
        val anchorSources = remember(dialogue) {
            dialogue.contents.flatMap { content ->
                content.segments.mapNotNull { (it as? DialogueSegment.Anchor)?.action }
            }
        }
        val anchorBindings = remember(snapshot.dialogue) {
            identityActionBindings(
                anchorSources,
                snapshot.dialogue.anchors,
                RuntimeAnchorAction::action,
            )
        }
        val sakuraDialogue = dialogueOwnership.content(GhostSpeaker.SAKURA)
            .bindAnchorActions(anchorBindings)
            .withFallback(
            fallbackText = presentation.sakura.text,
            authored = dialogue.contents.any { it.speaker == GhostSpeaker.SAKURA },
        )
        val keroDialogue = dialogueOwnership.content(GhostSpeaker.KERO)
            .bindAnchorActions(anchorBindings)
            .withFallback(
            fallbackText = presentation.kero.text,
            authored = dialogue.contents.any { it.speaker == GhostSpeaker.KERO },
        )
        val actionBindings = remember(snapshot.dialogue) { snapshot.dialogue.choices.toList() }
        val sakuraChoices = bindChoiceActions(
            dialogueOwnership.pendingChoices(GhostSpeaker.SAKURA),
            choiceBindings,
        )
        val keroChoices = bindChoiceActions(
            dialogueOwnership.pendingChoices(GhostSpeaker.KERO),
            choiceBindings,
        )
        LaunchedEffect(catalog, presentation) {
            scheduleNormalized(GhostSpeaker.SAKURA, catalog, presentation.sakura)
            scheduleNormalized(GhostSpeaker.KERO, catalog, presentation.kero)
        }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(snapshot.revision, hostLease) {
            if (snapshot.foregroundHost != hostLease) return@LaunchedEffect
            if (lastAppliedCueLease != hostLease) {
                lastAppliedCueLease = hostLease
                lastAppliedCueId = 0L
            }
            var through = lastAppliedCueId
            val pendingCues = snapshot.cues
                .asSequence()
                .filter { it.hostLease == hostLease && it.cueId > lastAppliedCueId }
                .sortedBy { it.cueId }
            for (cue in pendingCues) {
                val speakerPresentation = snapshot.currentPresentation(cue.target)
                if (speakerPresentation == null) {
                    through = cue.cueId
                    continue
                }
                val activeCatalog = catalog ?: break
                val plan = activeCatalog.speakerSurface(
                    cue.target.surfaceId,
                    cue.target.speaker == GhostSpeaker.SAKURA,
                ).plan
                applyCue(cue.target.speaker, plan, speakerPresentation, cue.kind, cue.animationId)
                through = cue.cueId
            }
            if (through > lastAppliedCueId) {
                lastAppliedCueId = through
                submitCommand(RuntimeCommand.AcknowledgeCues(hostLease, through))
            }
        }
        LaunchedEffect(sakuraScheduler, keroScheduler, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                rearmPeriodicTicks()
                while (true) { delay(16); tickSchedulers() }
            }
        }
        GhostPresentationStage(
            presentation = presentation,
            sakuraComposedSurface = sakuraComposed,
            keroComposedSurface = keroComposed,
            measureState = stageMeasureState,
            ghostKey = ghostKey,
            bubbleScrollSessionKey = bubbleScrollSessionIdentity(dialogue.incarnation),
            ghostIdentity = catalog ?: NoGhostIdentity,
            blockingInput = blockingInput(),
            ghostIdentityProvider = { catalog ?: NoGhostIdentity },
            blockingInputProvider = blockingInput,
            routingEpochProvider = { snapshot.generation ?: 0L },
            onSurfaceEffect = { effect ->
                val generation = snapshot.generation
                if (generation != null && snapshot.foregroundHost == hostLease) {
                    val speakerPresentation = when (effect.speaker) {
                        SurfaceSpeaker.SAKURA -> presentation.sakura
                        SurfaceSpeaker.KERO -> presentation.kero
                    }
                    submitCommand(
                        RuntimeCommand.Pointer(
                            generation = generation,
                            host = hostLease,
                            surface = RuntimeSurfaceIdentity(
                                generation,
                                when (effect.speaker) {
                                    SurfaceSpeaker.SAKURA -> GhostSpeaker.SAKURA
                                    SurfaceSpeaker.KERO -> GhostSpeaker.KERO
                                },
                                speakerPresentation.surfaceId,
                                speakerPresentation.surfaceEpoch,
                            ),
                            effect = effect,
                        ),
                    )
                }
            },
            onToggleChrome = onSurfaceTap,
            modifier = modifier,
            sakuraDialogue = sakuraDialogue,
            keroDialogue = keroDialogue,
            sakuraPendingChoices = sakuraChoices,
            keroPendingChoices = keroChoices,
            sakuraPendingInput = dialogueOwnership.pendingInput(GhostSpeaker.SAKURA),
            keroPendingInput = dialogueOwnership.pendingInput(GhostSpeaker.KERO),
            dialogueTalkId = dialogue.talkId,
            dialogueRevision = dialogue.revision,
            sakuraActiveAnimationId = sakuraActiveAnimationId,
            keroActiveAnimationId = keroActiveAnimationId,
            onDialogueChoice = { action ->
                if (snapshot.foregroundHost == hostLease) {
                    actionBindings.firstOrNull { it.action === action }
                        ?.let { submitCommand(RuntimeCommand.ActivateChoice(it.key, hostLease)) }
                }
            },
            onDialogueAnchor = { action ->
                if (snapshot.foregroundHost == hostLease) {
                    snapshot.dialogue.anchors.firstOrNull { it.action === action }
                        ?.let { submitCommand(RuntimeCommand.ActivateAnchor(it.key, hostLease)) }
                }
            },
            onDialogueExternalUrl = onDialogueExternalUrl,
            onDialogueInput = { segment ->
                snapshot.dialogue.input
                    ?.takeIf { it.pending.spec === segment.spec }
                    ?.let(onDialogueInputDraft)
            },
            sakuraSurface = { snapshot ->
                RenderedSurfaceLayer(
                    snapshot,
                    showCollisionOverlay = collisionOverlaySpeaker == SurfaceSpeaker.SAKURA,
                )
            },
            keroSurface = { snapshot ->
                RenderedSurfaceLayer(
                    snapshot,
                    showCollisionOverlay = collisionOverlaySpeaker == SurfaceSpeaker.KERO,
                )
            },
        )
    }

    private data class SpeakerSurface(val plan: SurfaceRenderPlan, val visible: Boolean)
    private data class SpeakerSurfaceKey(val sakura: Boolean, val surfaceId: String)
    private data class RenderedFrameKey(val speaker: SurfaceSpeaker, val surfaceId: String, val frame: SurfaceRenderFrame?)
    private data class ActiveComposedSurface(val key: RenderedFrameKey, val surface: ComposedSurface)
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

    private fun resetSurfaceCaches(catalog: SurfaceCatalog?) {
        sakuraScheduler = null
        keroScheduler = null
        sakuraFrame = null
        keroFrame = null
        sakuraActiveAnimationId = null
        keroActiveAnimationId = null
        schedulerSurfaceIds.clear()
        nextPeriodicTicks.clear()
        speakerSurfaces.clear()
        clearRenderedFrames()
        stageMeasureState.resetFor(catalog)
    }

    private fun bindChoiceActions(
        actions: List<DialogueAction>,
        bindings: IdentityHashMap<DialogueAction, RuntimeChoiceAction>,
    ): List<DialogueAction> = actions.mapNotNull { bindings[it]?.action }

    private fun scheduleNormalized(
        speaker: GhostSpeaker,
        catalog: SurfaceCatalog?,
        presentation: RuntimeSpeakerPresentation,
    ) {
        val plan = catalog.speakerSurface(
            presentation.surfaceId,
            speaker == GhostSpeaker.SAKURA,
        ).plan
        schedule(speaker, plan, presentation, null, talking = false)
    }

    private fun applyCue(
        speaker: GhostSpeaker,
        plan: SurfaceRenderPlan,
        presentation: RuntimeSpeakerPresentation,
        kind: RuntimeCueKind,
        animationId: String?,
    ) {
        schedule(
            speaker,
            plan,
            presentation,
            oneShotAnimationId = animationId.takeIf { kind == RuntimeCueKind.ONE_SHOT },
            talking = kind == RuntimeCueKind.TALKING,
        )
    }

    private fun schedule(
        speaker: GhostSpeaker,
        plan: SurfaceRenderPlan,
        presentation: RuntimeSpeakerPresentation,
        oneShotAnimationId: String?,
        talking: Boolean,
    ) {
        val scheduler: SurfaceAnimationScheduler = when (speaker) {
            GhostSpeaker.SAKURA -> sakuraScheduler?.takeIf { itPlan(it) == plan.surfaceId }
                ?: newScheduler(plan).also { sakuraScheduler = it; sakuraFrame = null }
            GhostSpeaker.KERO -> keroScheduler?.takeIf { itPlan(it) == plan.surfaceId }
                ?: newScheduler(plan).also { keroScheduler = it; keroFrame = null }
        }
        scheduler.presentationUpdated(
            presentation.balloonVisible,
            oneShotAnimationId,
            SurfaceTalkCadence.Update(talking),
        )
            .filterIsInstance<SurfaceAnimationScheduleEffect.Frame>()
            .lastOrNull()
            ?.frame
            ?.also { frame -> if (speaker == GhostSpeaker.SAKURA) sakuraFrame = frame else keroFrame = frame }
        publishActiveAnimationId(speaker, scheduler.activeAnimationId)
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
        publishActiveAnimationId(speaker, activeAnimationId)
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

    private fun publishActiveAnimationId(speaker: GhostSpeaker, animationId: String?) {
        if (speaker == GhostSpeaker.SAKURA) sakuraActiveAnimationId = animationId else keroActiveAnimationId = animationId
    }

    private fun SurfaceCatalog?.speakerSurface(id: String, sakura: Boolean): SpeakerSurface {
        if (id == "-1") return SpeakerSurface(SurfaceRenderPlan.Missing, false)
        val key = SpeakerSurfaceKey(sakura, id)
        return speakerSurfaces.getOrPut(key) {
            val definition = if (sakura) this?.sakuraDefinition(id) else this?.keroDefinition(id)
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

    private fun DialogueContent.withFallback(fallbackText: String, authored: Boolean): DialogueContent =
        if (authored) this else copy(segments = listOf(DialogueSegment.Text(fallbackText)))

    private fun DialogueContent.bindAnchorActions(
        bindings: IdentityHashMap<AnchorAction, RuntimeAnchorAction>,
    ): DialogueContent = copy(
        segments = segments.map { segment ->
            val anchor = segment as? DialogueSegment.Anchor ?: return@map segment
            DialogueSegment.Anchor(bindings[anchor.action]?.action ?: anchor.action)
        },
    )

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

internal fun RuntimeSnapshot.currentPresentation(
    target: RuntimeSurfaceIdentity,
): RuntimeSpeakerPresentation? {
    if (generation != target.generation) return null
    val current = when (target.speaker) {
        GhostSpeaker.SAKURA -> presentation.sakura
        GhostSpeaker.KERO -> presentation.kero
    }
    return current.takeIf {
        it.surfaceId == target.surfaceId && it.surfaceEpoch == target.surfaceEpoch
    }
}

internal fun <A : Any, B : Any> identityActionBindings(
    sourceActions: List<A>,
    bindings: List<B>,
    bindingAction: (B) -> A,
): IdentityHashMap<A, B> = IdentityHashMap<A, B>().apply {
    sourceActions.zip(bindings).forEach { (source, binding) ->
        if (source === bindingAction(binding)) put(source, binding)
    }
}
