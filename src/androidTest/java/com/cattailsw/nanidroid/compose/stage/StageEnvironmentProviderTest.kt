package com.cattailsw.nanidroid.compose.stage

import android.graphics.Rect
import android.view.InputDevice
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
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
    fun stageAdapterKeepsWindowLocalInsetsAndFeaturesInOneCoordinateSpace() {
        val first = FixedFeature(Rect(40, 100, 80, 260))
        val second = FixedFeature(Rect(300, 20, 320, 400))
        val window = StageWindowEnvironment.forTest(
            windowSizePx = IntSize(500, 900),
            safeBoundsInWindowPx = IntRect(20, 40, 480, 860),
            density = 2f,
            fontScale = 1f,
            displayFeatures = listOf(second, first),
        )

        val environment = window.toStageEnvironment(
            stageBoundsInWindowPx = IntRect(20, 40, 480, 860),
            canonicalAppBarHeight = 64.dp,
            ghostKey = "fixture",
        )

        assertEquals(DpSize(230.dp, 410.dp), environment.safeSize)
        assertEquals(0.dp, environment.safeBounds.left)
        assertEquals(0.dp, environment.safeBounds.top)
        assertEquals(64.dp, environment.canonicalAppBarHeight)
        assertEquals(
            listOf(
                com.cattailsw.nanidroid.runtime.stage.StageDpRect(140.dp, (-10).dp, 150.dp, 180.dp),
                com.cattailsw.nanidroid.runtime.stage.StageDpRect(10.dp, 30.dp, 30.dp, 110.dp),
            ),
            environment.displayFeatures.map { it.bounds },
        )
    }

    @Test
    fun imeInsetsDoNotChangeStageClassificationBounds() {
        val common = StageWindowEnvironment.forTest(
            windowSizePx = IntSize(500, 900),
            safeBoundsInWindowPx = IntRect(20, 40, 480, 860),
            density = 2f,
            fontScale = 1f,
            displayFeatures = emptyList(),
        )
        val withIme = StageWindowEnvironment.forTest(
            windowSizePx = IntSize(500, 900),
            safeBoundsInWindowPx = IntRect(20, 40, 480, 860),
            density = 2f,
            fontScale = 1f,
            displayFeatures = emptyList(),
            imeInsetsPx = IntInsetsPx(0, 0, 0, 500),
        )

        val stageBounds = IntRect(20, 40, 480, 860)
        assertEquals(
            common.toStageEnvironment(stageBounds, 64.dp, "fixture"),
            withIme.toStageEnvironment(stageBounds, 64.dp, "fixture"),
        )
    }

    @Test
    fun multipleFeaturesAreOrderStableAndRemovalIsObservable() {
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
                            stageBoundsInWindowPx = IntRect(0, 0, size.width, size.height),
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
            IntRect(0, 0, foldedWindow.windowSizePx.width, foldedWindow.windowSizePx.height),
            64.dp,
            "fixture",
        )
        assertEquals((17f / foldedWindow.density).dp, converted.safeBounds.left)
        assertEquals((29f / foldedWindow.density).dp, converted.safeBounds.top)
        assertEquals((fold.bounds.left / foldedWindow.density).dp, converted.displayFeatures.single().bounds.left)
        assertEquals((fold.bounds.top / foldedWindow.density).dp, converted.displayFeatures.single().bounds.top)
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
