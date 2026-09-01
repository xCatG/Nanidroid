package com.cattailsw.nanidroid.compose.stage

import android.graphics.Rect
import android.view.InputDevice
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.window.layout.DisplayFeature
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import androidx.window.testing.layout.FoldingFeature as TestFoldingFeature
import com.cattailsw.nanidroid.runtime.GhostPresentationReducer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import com.cattailsw.nanidroid.runtime.stage.StagePointingDeviceCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class StageEnvironmentProviderTest {
    private val publisher = WindowLayoutInfoPublisherRule()
    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(publisher).around(composeRule)

    @Test
    fun publisherUpdatesFlatBookAndTabletopPosturesWithoutActivityRecreation() {
        val observed = mutableStateOf<StageWindowEnvironment?>(null)
        val originalActivity = composeRule.activity
        composeRule.setContent {
            StageEnvironmentProvider { environment ->
                SideEffect { observed.value = environment }
            }
        }

        publish(emptyList())
        composeRule.waitUntil { observed.value?.displayFeatures?.isEmpty() == true }
        assertEquals(com.cattailsw.nanidroid.runtime.stage.StagePosture.FLAT, observed.value!!.posture)

        val bounds = Rect(0, 0, observed.value!!.windowSizePx.width, observed.value!!.windowSizePx.height)
        val book = TestFoldingFeature(
            windowBounds = bounds,
            center = bounds.centerX(),
            size = 10,
            state = FoldingFeature.State.FLAT,
            orientation = FoldingFeature.Orientation.VERTICAL,
        )
        publish(listOf(book))
        composeRule.waitUntil { observed.value?.posture == com.cattailsw.nanidroid.runtime.stage.StagePosture.BOOK }
        assertSame(originalActivity, composeRule.activity)

        val tabletop = TestFoldingFeature(
            windowBounds = bounds,
            center = bounds.centerY(),
            size = 12,
            state = FoldingFeature.State.HALF_OPENED,
            orientation = FoldingFeature.Orientation.HORIZONTAL,
        )
        publish(listOf(tabletop))
        composeRule.waitUntil { observed.value?.posture == com.cattailsw.nanidroid.runtime.stage.StagePosture.TABLETOP }
        assertSame(originalActivity, composeRule.activity)

        publish(emptyList())
        composeRule.waitUntil { observed.value?.displayFeatures?.isEmpty() == true }
        assertEquals(com.cattailsw.nanidroid.runtime.stage.StagePosture.FLAT, observed.value!!.posture)
    }

    @Test
    fun multipleFeaturesAreOrderStableAndGenericFallbackIsObservable() {
        val observed = mutableStateOf<StageWindowEnvironment?>(null)
        composeRule.setContent {
            StageEnvironmentProvider { environment -> SideEffect { observed.value = environment } }
        }
        publish(emptyList())
        composeRule.waitUntil { observed.value != null }
        val width = observed.value!!.windowSizePx.width
        val height = observed.value!!.windowSizePx.height
        val vertical = TestFoldingFeature(
            Rect(0, 0, width, height), width / 3, 8,
            FoldingFeature.State.FLAT, FoldingFeature.Orientation.VERTICAL,
        )
        val horizontal = TestFoldingFeature(
            Rect(0, 0, width, height), height / 2, 0,
            FoldingFeature.State.HALF_OPENED, FoldingFeature.Orientation.HORIZONTAL,
        )

        publish(listOf(vertical, horizontal))
        composeRule.waitUntil { observed.value?.displayFeatures?.size == 2 }
        val forward = observed.value!!.displayFeatures
        publish(listOf(horizontal, vertical))
        composeRule.waitUntil { observed.value?.displayFeatures?.size == 2 }
        assertEquals(forward, observed.value!!.displayFeatures)

        publish(listOf(vertical))
        composeRule.waitUntil { observed.value?.displayFeatures?.size == 1 }
        assertTrue(observed.value!!.displayFeatures.single().separating)

        val genericTop = FixedFeature(Rect(5, 5, 15, 15))
        val genericBottom = FixedFeature(Rect(5, 20, 15, 30))
        publish(listOf(genericBottom, genericTop))
        composeRule.waitUntil { observed.value?.displayFeatures?.size == 2 }
        val genericFeatures = observed.value!!.displayFeatures
        assertEquals(
            listOf(IntRect(5, 5, 15, 15), IntRect(5, 20, 15, 30)),
            genericFeatures.map { it.bounds },
        )
        genericFeatures.forEach { feature ->
            assertFalse(feature.separating)
            assertTrue(feature.occluding)
            assertEquals(FeatureOrientation.UNKNOWN, feature.orientation)
            assertFalse(feature.halfOpened)
        }
    }

    @Test
    fun lifecycleCancelsSubscriptionAndReplayingSourceSuppliesLatestLayoutOnRestart() {
        val observed = mutableStateOf<StageWindowEnvironment?>(null)
        val latest = MutableStateFlow(WindowLayoutInfo(emptyList()))
        val activeSubscriptions = AtomicInteger(0)
        val subscriptionStarts = AtomicInteger(0)
        val mounted = mutableStateOf(true)
        val source = WindowLayoutInfoSource {
            flow {
                subscriptionStarts.incrementAndGet()
                activeSubscriptions.incrementAndGet()
                try {
                    emitAll(latest)
                } finally {
                    activeSubscriptions.decrementAndGet()
                }
            }
        }
        composeRule.setContent {
            if (mounted.value) {
                StageEnvironmentProvider(windowLayoutInfoSource = source) { environment ->
                    SideEffect { observed.value = environment }
                }
            }
        }
        composeRule.waitUntil {
            observed.value != null && activeSubscriptions.get() == 1 && subscriptionStarts.get() == 1
        }
        val before = observed.value

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.waitUntil { activeSubscriptions.get() == 0 }
        val width = before!!.windowSizePx.width
        val height = before.windowSizePx.height
        val feature = TestFoldingFeature(
            Rect(0, 0, width, height), width / 2, 10,
            FoldingFeature.State.FLAT, FoldingFeature.Orientation.VERTICAL,
        )
        // WindowLayoutInfoPublisherRule deliberately uses a non-replaying test
        // flow, so it cannot prove restart-with-current without lying via a
        // second publish. The replaying seam models WindowManager's current
        // state contract while PublisherRule remains used by all raw-feature tests.
        latest.value = WindowLayoutInfo(listOf(feature))
        composeRule.runOnIdle { assertEquals(before, observed.value) }

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            activeSubscriptions.get() == 1 && subscriptionStarts.get() == 2 &&
                observed.value?.displayFeatures?.size == 1
        }
        assertFalse(observed.value!!.displayFeatures.isEmpty())

        composeRule.runOnIdle { mounted.value = false }
        composeRule.waitUntil { activeSubscriptions.get() == 0 }
    }

    @Test
    fun inputDeviceAddChangeRemoveAndRestartPublishCurrentCapabilities() {
        val observed = mutableStateOf<StageWindowEnvironment?>(null)
        val latest = MutableStateFlow(StagePointingDeviceCapabilities(mouse = false, stylus = false))
        val activeSubscriptions = AtomicInteger(0)
        val subscriptionStarts = AtomicInteger(0)
        val source = InputCapabilitySource {
            flow {
                subscriptionStarts.incrementAndGet()
                activeSubscriptions.incrementAndGet()
                try {
                    emitAll(latest)
                } finally {
                    activeSubscriptions.decrementAndGet()
                }
            }
        }
        composeRule.setContent {
            StageEnvironmentProvider(
                windowLayoutInfoSource = WindowLayoutInfoSource { MutableStateFlow(WindowLayoutInfo(emptyList())) },
                inputCapabilitySource = source,
            ) { environment -> SideEffect { observed.value = environment } }
        }
        composeRule.waitUntil { activeSubscriptions.get() == 1 && observed.value != null }
        assertFalse(observed.value!!.inputCapabilities.mouse)
        assertFalse(observed.value!!.inputCapabilities.stylus)

        // These three emissions model InputManager's add, change, and remove
        // callbacks; each callback must trigger a complete fresh enumeration.
        latest.value = StagePointingDeviceCapabilities(mouse = true, stylus = false)
        composeRule.waitUntil { observed.value?.inputCapabilities?.mouse == true }
        latest.value = StagePointingDeviceCapabilities(mouse = true, stylus = true)
        composeRule.waitUntil { observed.value?.inputCapabilities?.stylus == true }
        latest.value = StagePointingDeviceCapabilities(mouse = false, stylus = false)
        composeRule.waitUntil { observed.value?.inputCapabilities?.mouse == false }

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.waitUntil { activeSubscriptions.get() == 0 }
        latest.value = StagePointingDeviceCapabilities(mouse = false, stylus = true)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitUntil {
            subscriptionStarts.get() == 2 && activeSubscriptions.get() == 1 &&
                observed.value?.inputCapabilities?.stylus == true
        }
    }

    @Test
    fun productionCapabilityAdapterEnumeratesEveryCallbackAndUnregistersBelowStarted() {
        val observed = mutableStateOf<StageWindowEnvironment?>(null)
        val registry = FakeInputDeviceRegistry()
        val source = RegisteredInputCapabilitySource(registry)
        val mounted = mutableStateOf(true)
        composeRule.setContent {
            if (mounted.value) {
                StageEnvironmentProvider(
                    windowLayoutInfoSource = WindowLayoutInfoSource { MutableStateFlow(WindowLayoutInfo(emptyList())) },
                    inputCapabilitySource = source,
                ) { environment -> SideEffect { observed.value = environment } }
            }
        }
        composeRule.waitUntil { registry.activeListeners == 1 && observed.value != null }

        registry.publishAdd(listOf(InputDevice.SOURCE_MOUSE))
        composeRule.waitUntil { observed.value?.inputCapabilities?.mouse == true }
        registry.publishChange(listOf(InputDevice.SOURCE_STYLUS))
        composeRule.waitUntil {
            observed.value?.inputCapabilities?.mouse == false && observed.value?.inputCapabilities?.stylus == true
        }
        registry.publishRemove(emptyList())
        composeRule.waitUntil { observed.value?.inputCapabilities?.stylus == false }
        assertEquals(4, registry.enumerations)

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.waitUntil { registry.activeListeners == 0 }
        registry.sources = listOf(InputDevice.SOURCE_MOUSE, InputDevice.SOURCE_STYLUS)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitUntil {
            registry.activeListeners == 1 && observed.value?.inputCapabilities?.mouse == true &&
                observed.value?.inputCapabilities?.stylus == true
        }
        assertEquals(2, registry.registrationStarts)

        composeRule.runOnIdle { mounted.value = false }
        composeRule.waitUntil { registry.activeListeners == 0 }
    }

    @Test
    fun publisherFoldWithNonzeroInsetsAndPartialOcclusionTriggersMeasuredRelayout() {
        val stageLeft = 11
        val stageTop = 13
        val observed = mutableStateOf<StageWindowEnvironment?>(null)
        val measureState = GhostStageMeasureState()
        composeRule.setContent {
            StageEnvironmentProvider { raw ->
                val inset = raw.copy(
                    safeBoundsInWindowPx = IntRect(
                        17,
                        29,
                        (raw.windowSizePx.width - 23).coerceAtLeast(17),
                        (raw.windowSizePx.height - 31).coerceAtLeast(29),
                    ),
                )
                SideEffect { observed.value = inset }
                MeasuredGhostStageLayout(
                    presentation = presentation(),
                    environmentForSize = { size ->
                        inset.toStageEnvironment(
                            stageBoundsInWindowPx = IntRect(
                                stageLeft,
                                stageTop,
                                stageLeft + size.width,
                                stageTop + size.height,
                            ),
                            canonicalAppBarHeight = 64.dp,
                            ghostKey = "fixture",
                        )
                    },
                    measureState = measureState,
                    kero = null,
                    sakura = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        publish(emptyList())
        composeRule.waitUntil { measureState.latest != null && observed.value?.displayFeatures?.isEmpty() == true }
        val before = requireNotNull(measureState.latest)
        val window = requireNotNull(observed.value)
        assertEquals(17, window.safeBoundsInWindowPx.left)
        assertEquals(29, window.safeBoundsInWindowPx.top)

        val fold = PartialFoldingFeature(
            rect = Rect(
                window.windowSizePx.width / 2 - 18,
                140,
                window.windowSizePx.width / 2 + 18,
                (window.windowSizePx.height - 160).coerceAtLeast(141),
            ),
        )
        publish(listOf(fold))
        composeRule.waitUntil {
            observed.value?.displayFeatures?.singleOrNull()?.occluding == true &&
                measureState.latest?.layoutDp?.content != before.layoutDp.content
        }

        val after = requireNotNull(measureState.latest)
        val foldedWindow = requireNotNull(observed.value)
        val feature = foldedWindow.displayFeatures.single()
        assertTrue(feature.occluding)
        assertFalse(feature.separating)
        assertEquals(FeatureOrientation.VERTICAL, feature.orientation)
        val converted = foldedWindow.toStageEnvironment(
            IntRect(
                stageLeft,
                stageTop,
                stageLeft + foldedWindow.windowSizePx.width,
                stageTop + foldedWindow.windowSizePx.height,
            ),
            64.dp,
            "fixture",
        )
        assertEquals(((17f - stageLeft) / foldedWindow.density).dp, converted.safeBounds.left)
        assertEquals(((29f - stageTop) / foldedWindow.density).dp, converted.safeBounds.top)
        assertEquals(
            ((foldedWindow.safeBoundsInWindowPx.right - stageLeft) / foldedWindow.density).dp,
            converted.safeBounds.right,
        )
        assertEquals(
            ((foldedWindow.safeBoundsInWindowPx.bottom - stageTop) / foldedWindow.density).dp,
            converted.safeBounds.bottom,
        )
        val convertedFeature = converted.displayFeatures.single().bounds
        assertEquals(((fold.bounds.left - stageLeft) / foldedWindow.density).dp, convertedFeature.left)
        assertEquals(((fold.bounds.top - stageTop) / foldedWindow.density).dp, convertedFeature.top)
        assertEquals(((fold.bounds.right - stageLeft) / foldedWindow.density).dp, convertedFeature.right)
        assertEquals(((fold.bounds.bottom - stageTop) / foldedWindow.density).dp, convertedFeature.bottom)
        assertEquals(64.dp, converted.canonicalAppBarHeight)
        assertNotEquals(before.layoutDp.content, after.layoutDp.content)
        assertNotEquals(before.layoutPx.content, after.layoutPx.content)
    }

    private fun publish(features: List<DisplayFeature>) {
        publisher.overrideWindowLayoutInfo(WindowLayoutInfo(features))
    }

    private fun presentation() = GhostPresentationReducer.snapshot(
        sakuraText = "Sakura",
        sakuraSurfaceId = "0",
        sakuraAnimationId = null,
        sakuraBalloonId = "0",
        keroText = "Kero",
        keroSurfaceId = "10",
        keroAnimationId = null,
        keroBalloonId = "0",
    )

    private data class FixedFeature(private val rect: Rect) : DisplayFeature {
        override val bounds: Rect get() = Rect(rect)
    }

    private data class PartialFoldingFeature(private val rect: Rect) : FoldingFeature {
        override val bounds: Rect get() = Rect(rect)
        override val isSeparating: Boolean get() = false
        override val occlusionType: FoldingFeature.OcclusionType get() = FoldingFeature.OcclusionType.FULL
        override val orientation: FoldingFeature.Orientation get() = FoldingFeature.Orientation.VERTICAL
        override val state: FoldingFeature.State get() = FoldingFeature.State.FLAT
    }

    private class FakeInputDeviceRegistry : InputDeviceRegistry {
        private var listener: InputDeviceRegistry.Listener? = null
        var sources: List<Int> = emptyList()
        var enumerations = 0
        var registrationStarts = 0
        val activeListeners get() = if (listener == null) 0 else 1

        override fun currentSources(): List<Int> {
            enumerations++
            return sources
        }

        override fun register(listener: InputDeviceRegistry.Listener) {
            check(this.listener == null)
            this.listener = listener
            registrationStarts++
        }

        override fun unregister(listener: InputDeviceRegistry.Listener) {
            if (this.listener === listener) this.listener = null
        }

        fun publishAdd(current: List<Int>) {
            sources = current
            listener?.onAdded()
        }

        fun publishChange(current: List<Int>) {
            sources = current
            listener?.onChanged()
        }

        fun publishRemove(current: List<Int>) {
            sources = current
            listener?.onRemoved()
        }
    }
}
