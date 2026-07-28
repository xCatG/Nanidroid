package com.cattailsw.nanidroid.compose

import com.cattailsw.nanidroid.ShellSurface

/** Monotonic time source for the thin runtime adapter. */
fun interface SurfaceRenderClock {
    fun nowMillis(): Long
}

/** Injectable entropy makes the legacy probability rules reproducible in tests. */
fun interface SurfaceRenderEntropy {
    /** A value in [0.0, 1.0); values outside that range are normalized defensively. */
    fun nextUnitDouble(): Double
}

/**
 * The renderer-independent timing state for one surface.
 *
 * The old View renderer could show only one AnimationDrawable at a time, so
 * [active] deliberately represents one current animation rather than inventing
 * simultaneous animation composition before the stage owns that policy.
 */
data class SurfaceAnimationScheduleState(
    val active: ActiveAnimation? = null,
    val lastObservedSecond: Long? = null,
    val lastProbabilityRollMillis: Long? = null,
    /** Final child retained by each legacy AltAnimation branch. */
    val alternateBranchResults: Map<String, String> = emptyMap(),
) {
    data class ActiveAnimation(
        val animationId: String,
        /** The animation requested by the script; it can be an alternate container. */
        val requestedAnimationId: String = animationId,
        val startedAtMillis: Long,
        val frameIndex: Int,
    )

    companion object {
        val Idle = SurfaceAnimationScheduleState()
    }
}

/**
 * Shared legacy talk cadence. Its owner advances it once per presentation
 * update and passes the resulting token to every speaker/surface scheduler.
 */
class SurfaceTalkCadence(initialUpdateCount: Int = 0) {
    private var updateCount = Math.floorMod(initialUpdateCount, TALK_UPDATE_PERIOD)

    fun nextPresentationUpdate(): Update {
        val update = Update(talkingAnimationEnabled = updateCount == 0)
        updateCount = (updateCount + 1) % TALK_UPDATE_PERIOD
        return update
    }

    fun snapshotUpdateCount(): Int = updateCount

    data class Update(val talkingAnimationEnabled: Boolean)

    private companion object {
        const val TALK_UPDATE_PERIOD = 10
    }
}

sealed interface SurfaceAnimationScheduleEvent {
    /**
     * One timer observation. The reducer deliberately evaluates one
     * probability roll for any elapsed time, matching SScriptRunner rather
     * than manufacturing missed per-second events after a pause.
     */
    data class Tick(
        val nowMillis: Long,
        val probabilityRoll: Double,
        val selectionRolls: List<Double>,
    ) : SurfaceAnimationScheduleEvent

    /**
     * A presentation update for one speaker. The legacy runner increments its
     * shared talk gate after every update, including explicit animation output.
     */
    data class PresentationUpdated(
        val nowMillis: Long,
        val hasVisibleSpeech: Boolean,
        val oneShotAnimationId: String?,
        val talkingAnimationEnabled: Boolean,
        val selectionRolls: List<Double>,
    ) : SurfaceAnimationScheduleEvent

    /** Explicit SakuraScript `\![animate,...]` dispatch. */
    data class OneShotRequested(
        val nowMillis: Long,
        val animationId: String,
        val selectionRolls: List<Double>,
    ) : SurfaceAnimationScheduleEvent
}

data class SurfaceAnimationScheduleTransition(
    val state: SurfaceAnimationScheduleState,
    val effects: List<SurfaceAnimationScheduleEffect>,
)

sealed interface SurfaceAnimationScheduleEffect {
    /**
     * The compositor should render [frame] for [animationId]. An id names the
     * resolved concrete animation, never an alternate container.
     */
    data class Frame(
        val animationId: String,
        val frameIndex: Int,
        val frame: SurfaceRenderFrame,
    ) : SurfaceAnimationScheduleEffect
}

/**
 * Total pure timing reducer over [SurfaceRenderPlan]. It contains no Android
 * clock, View, coroutine, random, or compositor dependency.
 *
 * Timing parity captured here:
 * - frame durations cycle exactly as AnimationDrawable frames do;
 * - `rarely` is selected for p < .25 and `sometimes` for .25 <= p < .50;
 * - talk begins on the first update and then every tenth update;
 * - a named SakuraScript animation wins over a talk trigger for its update;
 * - alternative targets are selected here, not by the compositor.
 */
object SurfaceAnimationScheduleReducer {
    fun reduce(
        plan: SurfaceRenderPlan,
        state: SurfaceAnimationScheduleState,
        event: SurfaceAnimationScheduleEvent,
    ): SurfaceAnimationScheduleTransition = when (event) {
        is SurfaceAnimationScheduleEvent.Tick -> onTick(plan, state, event)
        is SurfaceAnimationScheduleEvent.PresentationUpdated -> onPresentationUpdated(plan, state, event)
        is SurfaceAnimationScheduleEvent.OneShotRequested -> start(
            plan,
            state,
            event.nowMillis,
            event.animationId,
            SelectionRolls(event.selectionRolls),
        )
    }

    private fun onTick(
        plan: SurfaceRenderPlan,
        state: SurfaceAnimationScheduleState,
        event: SurfaceAnimationScheduleEvent.Tick,
    ): SurfaceAnimationScheduleTransition {
        val advanced = advance(plan, state, event.nowMillis)
        val observedSecond = event.nowMillis.coerceAtLeast(0) / MILLIS_PER_SECOND
        val previousSecond = state.lastObservedSecond
        // The first scheduled tick is one full clock interval after scheduler
        // creation, so it is eligible just like every later elapsed second.
        val shouldRoll = previousSecond == null || observedSecond > previousSecond
        val withClock = advanced.copy(lastObservedSecond = maxOf(previousSecond ?: observedSecond, observedSecond))
        if (!shouldRoll) return SurfaceAnimationScheduleTransition(withClock, advancedEffects(plan, state, withClock))

        val interval = when {
            event.probabilityRoll.normalizedUnitDouble() < RARELY_THRESHOLD -> ShellSurface.A_TYPE_RARELY
            event.probabilityRoll.normalizedUnitDouble() < SOMETIMES_THRESHOLD -> ShellSurface.A_TYPE_SOMETIMES
            else -> null
        } ?: return SurfaceAnimationScheduleTransition(withClock, advancedEffects(plan, state, withClock))
        val selectionRolls = SelectionRolls(event.selectionRolls)
        val candidates = plan.animations.filter { it.interval == interval }
        val selected = candidates.select(selectionRolls)?.id
            ?: return SurfaceAnimationScheduleTransition(withClock, advancedEffects(plan, state, withClock))
        return start(
            plan,
            withClock,
            event.nowMillis,
            selected,
            selectionRolls,
            advancedEffects(plan, state, withClock),
        )
    }

    private fun onPresentationUpdated(
        plan: SurfaceRenderPlan,
        state: SurfaceAnimationScheduleState,
        event: SurfaceAnimationScheduleEvent.PresentationUpdated,
    ): SurfaceAnimationScheduleTransition {
        val advanced = advance(plan, state, event.nowMillis)
        val advancedEffects = advancedEffects(plan, state, advanced)
        val selectionRolls = SelectionRolls(event.selectionRolls)
        val requested = event.oneShotAnimationId ?: if (event.hasVisibleSpeech && event.talkingAnimationEnabled) {
            plan.animations.filter { it.interval == ShellSurface.A_TYPE_TALK }.select(selectionRolls)?.id
        } else {
            null
        }
        return requested?.let { start(plan, advanced, event.nowMillis, it, selectionRolls, advancedEffects) }
            ?: SurfaceAnimationScheduleTransition(advanced, advancedEffects)
    }

    private fun start(
        plan: SurfaceRenderPlan,
        state: SurfaceAnimationScheduleState,
        nowMillis: Long,
        requestedAnimationId: String,
        selectionRolls: SelectionRolls,
        priorEffects: List<SurfaceAnimationScheduleEffect> = emptyList(),
    ): SurfaceAnimationScheduleTransition {
        // SakuraView keeps the resolved child when the script asks for the
        // same alternate container again; it does not roll a new alternative,
        // but it does stop and restart that child's drawable from frame zero.
        if (
            state.active?.requestedAnimationId == requestedAnimationId &&
            plan.animations.firstOrNull { it.id == requestedAnimationId }?.alternatives?.isNotEmpty() == true
        ) {
            val active = requireNotNull(state.active)
            val retained = plan.animations.firstOrNull { it.id == active.animationId }
            val firstFrame = retained?.frames?.firstOrNull()
                ?: return SurfaceAnimationScheduleTransition(state, priorEffects)
            return SurfaceAnimationScheduleTransition(
                state.copy(active = active.copy(startedAtMillis = nowMillis, frameIndex = 0)),
                listOf(SurfaceAnimationScheduleEffect.Frame(active.animationId, 0, firstFrame)),
            )
        }
        val resolved = resolve(plan, requestedAnimationId, selectionRolls, state.alternateBranchResults)
            ?: return SurfaceAnimationScheduleTransition(state, priorEffects)
        val animation = resolved.animation
        val firstFrame = animation.frames.firstOrNull() ?: return SurfaceAnimationScheduleTransition(state, priorEffects)
        val nextState = state.copy(
            alternateBranchResults = resolved.alternateBranchResults,
            active = SurfaceAnimationScheduleState.ActiveAnimation(
                animationId = animation.id,
                requestedAnimationId = requestedAnimationId,
                startedAtMillis = nowMillis,
                frameIndex = 0,
            ),
        )
        return SurfaceAnimationScheduleTransition(
            nextState,
            // A successful start replaces an advanced prior frame. If this
            // method fails above, [priorEffects] remains visible instead.
            listOf(SurfaceAnimationScheduleEffect.Frame(animation.id, 0, firstFrame)),
        )
    }

    private fun advance(
        plan: SurfaceRenderPlan,
        state: SurfaceAnimationScheduleState,
        nowMillis: Long,
    ): SurfaceAnimationScheduleState {
        val active = state.active ?: return state
        val animation = plan.animations.firstOrNull { it.id == active.animationId } ?: return state.copy(active = null)
        val nextIndex = frameIndexAt(animation.frames, active.startedAtMillis, nowMillis)
        return if (nextIndex == active.frameIndex) state else state.copy(active = active.copy(frameIndex = nextIndex))
    }

    private fun advancedEffects(
        plan: SurfaceRenderPlan,
        before: SurfaceAnimationScheduleState,
        after: SurfaceAnimationScheduleState,
    ): List<SurfaceAnimationScheduleEffect> {
        val active = after.active ?: return emptyList()
        if (active == before.active) return emptyList()
        val animation = plan.animations.firstOrNull { it.id == active.animationId } ?: return emptyList()
        val frame = animation.frames.getOrNull(active.frameIndex) ?: return emptyList()
        return listOf(SurfaceAnimationScheduleEffect.Frame(active.animationId, active.frameIndex, frame))
    }

    private data class ResolvedAnimation(
        val animation: SurfaceRenderAnimation,
        val alternateBranchResults: Map<String, String>,
    )

    private fun resolve(
        plan: SurfaceRenderPlan,
        requestedAnimationId: String,
        selectionRolls: SelectionRolls,
        priorResults: Map<String, String>,
        seen: MutableSet<String> = mutableSetOf(),
    ): ResolvedAnimation? {
        var id = requestedAnimationId
        val results = priorResults.toMutableMap()
        while (seen.add(id)) {
            val animation = plan.animations.firstOrNull { it.id == id } ?: return null
            if (animation.alternatives.isEmpty()) return ResolvedAnimation(animation, results)
            val index = (selectionRolls.next() * animation.alternatives.size).toInt().coerceAtMost(animation.alternatives.lastIndex)
            val key = "${animation.id}#$index"
            results[key]?.let { cached ->
                val cachedAnimation = plan.animations.firstOrNull { it.id == cached } ?: return null
                return ResolvedAnimation(cachedAnimation, results)
            }
            val alternate = animation.alternatives[index]
            val resolved = resolve(plan, alternate, selectionRolls, results, seen) ?: return null
            results.putAll(resolved.alternateBranchResults)
            results[key] = resolved.animation.id
            return ResolvedAnimation(resolved.animation, results)
        }
        return null
    }

    private fun frameIndexAt(frames: List<SurfaceRenderFrame>, startedAtMillis: Long, nowMillis: Long): Int {
        if (frames.isEmpty()) return 0
        val durations = frames.map { it.durationMillis.coerceAtLeast(0).toLong() }
        val cycle = durations.sum()
        if (cycle == 0L) return frames.lastIndex
        var position = (nowMillis - startedAtMillis).coerceAtLeast(0) % cycle
        durations.forEachIndexed { index, duration ->
            if (duration == 0L) return@forEachIndexed
            if (position < duration) return index
            position -= duration
        }
        return frames.lastIndex // defensive rounding fallback
    }

    private fun List<SurfaceRenderAnimation>.select(rolls: SelectionRolls): SurfaceRenderAnimation? =
        when (size) {
            0 -> null
            1 -> first()
            else -> getOrNull((rolls.next() * size).toInt().coerceAtMost(lastIndex))
        }

    private fun List<String>.select(rolls: SelectionRolls): String? =
        getOrNull((rolls.next() * size).toInt().coerceAtMost(lastIndex))

    private const val MILLIS_PER_SECOND = 1_000L
    private const val MINIMUM_FRAME_DURATION_MILLIS = 1
    private const val RARELY_THRESHOLD = 0.25
    private const val SOMETIMES_THRESHOLD = 0.50
}

/** Event-owned selection stream: reducer calls are deterministic and total. */
private class SelectionRolls(private val values: List<Double>) {
    private var index = 0
    fun next(): Double = values.getOrElse(index++) { 0.0 }.normalizedUnitDouble()
}

private fun Double.normalizedUnitDouble(): Double = when {
    isNaN() -> 0.0
    this < 0.0 -> 0.0
    this >= 1.0 -> Math.nextDown(1.0)
    else -> this
}

/**
 * Stateful adapter for the production cut-over. It is intentionally tiny:
 * tests can exercise the reducer directly, while a future Compose runtime
 * provides a monotonic Android clock and schedules [tick].
 */
class SurfaceAnimationScheduler(
    private val plan: SurfaceRenderPlan,
    private val clock: SurfaceRenderClock,
    private val entropy: SurfaceRenderEntropy,
) {
    var state: SurfaceAnimationScheduleState = SurfaceAnimationScheduleState.Idle
        private set

    fun tick(): List<SurfaceAnimationScheduleEffect> {
        val nowMillis = clock.nowMillis()
        val observedSecond = nowMillis.coerceAtLeast(0) / 1_000L
        val shouldRoll = state.lastObservedSecond?.let { observedSecond > it } ?: true
        // SScriptRunner only calls Math.random when a new second is observed;
        // consuming entropy for every UI tick would make its next visible
        // choice depend on render-loop frequency.
        val probabilityRoll = if (shouldRoll) entropy.nextUnitDouble().normalizedUnitDouble() else 1.0
        val interval = when {
            probabilityRoll < 0.25 -> ShellSurface.A_TYPE_RARELY
            probabilityRoll < 0.50 -> ShellSurface.A_TYPE_SOMETIMES
            else -> null
        }
        val selectionRolls = interval
            ?.let { plan.animations.filter { animation -> animation.interval == it } }
            ?.let { selectionRollsFor(it, state) }
            ?: emptyList()
        return dispatch(
            SurfaceAnimationScheduleEvent.Tick(
                nowMillis = nowMillis,
                probabilityRoll = probabilityRoll,
                selectionRolls = selectionRolls,
            ),
        )
    }

    fun presentationUpdated(
        hasVisibleSpeech: Boolean,
        oneShotAnimationId: String? = null,
        talkUpdate: SurfaceTalkCadence.Update,
    ): List<SurfaceAnimationScheduleEffect> {
        val requested = oneShotAnimationId ?: if (hasVisibleSpeech && talkUpdate.talkingAnimationEnabled) {
            plan.animations.filter { it.interval == ShellSurface.A_TYPE_TALK }.singleOrNull()?.id
                ?: plan.animations.firstOrNull { it.interval == ShellSurface.A_TYPE_TALK }?.id
        } else {
            null
        }
        val selectionRolls = requested
            ?.let { id ->
                if (oneShotAnimationId == null) {
                    selectionRollsFor(plan.animations.filter { it.interval == ShellSurface.A_TYPE_TALK }, state)
                } else {
                    selectionRollsFor(id, state)
                }
            }
            ?: emptyList()
        return dispatch(
            SurfaceAnimationScheduleEvent.PresentationUpdated(
                nowMillis = clock.nowMillis(),
                hasVisibleSpeech = hasVisibleSpeech,
                oneShotAnimationId = oneShotAnimationId,
                talkingAnimationEnabled = talkUpdate.talkingAnimationEnabled,
                selectionRolls = selectionRolls,
        ),
        )
    }

    fun requestOneShot(animationId: String): List<SurfaceAnimationScheduleEffect> = dispatch(
        SurfaceAnimationScheduleEvent.OneShotRequested(
            nowMillis = clock.nowMillis(),
            animationId = animationId,
            selectionRolls = selectionRollsFor(animationId, state),
        ),
    )

    private fun dispatch(event: SurfaceAnimationScheduleEvent): List<SurfaceAnimationScheduleEffect> =
        SurfaceAnimationScheduleReducer.reduce(plan, state, event).also { state = it.state }.effects

    /** Draw once for a multi-candidate initial choice and once per AltAnimation hop. */
    private fun selectionRollsFor(
        candidates: List<SurfaceRenderAnimation>,
        state: SurfaceAnimationScheduleState = SurfaceAnimationScheduleState.Idle,
    ): List<Double> {
        if (candidates.isEmpty()) return emptyList()
        val rolls = mutableListOf<Double>()
        val selected = if (candidates.size > 1) {
            entropy.nextUnitDouble().normalizedUnitDouble().also(rolls::add)
                .let { roll -> candidates[(roll * candidates.size).toInt().coerceAtMost(candidates.lastIndex)] }
        } else {
            candidates.single()
        }
        return rolls + selectionRollsFor(selected.id, state)
    }

    private fun selectionRollsFor(
        animationId: String,
        state: SurfaceAnimationScheduleState = SurfaceAnimationScheduleState.Idle,
    ): List<Double> {
        if (
            state.active?.requestedAnimationId == animationId &&
            plan.animations.firstOrNull { it.id == animationId }?.alternatives?.isNotEmpty() == true
        ) return emptyList()
        val rolls = mutableListOf<Double>()
        var id = animationId
        val seen = mutableSetOf<String>()
        while (seen.add(id)) {
            val animation = plan.animations.firstOrNull { it.id == id } ?: return rolls
            if (animation.alternatives.isEmpty()) return rolls
            val roll = entropy.nextUnitDouble().normalizedUnitDouble()
            rolls += roll
            val index = (roll * animation.alternatives.size).toInt().coerceAtMost(animation.alternatives.lastIndex)
            state.alternateBranchResults["${animation.id}#$index"]?.let { return rolls }
            id = animation.alternatives[index]
        }
        return rolls
    }
}
