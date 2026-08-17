package com.cattailsw.nanidroid.compose

import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.cattailsw.nanidroid.compose.stage.GhostStageMeasureState
import com.cattailsw.nanidroid.runtime.GhostPresentationReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NanidroidComposeShellUiAutomatorTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shell_exposes_stage_test_tag_as_accessibility_resource_id() {
        composeRule.setContent {
            NanidroidComposeShell(
                ghostStage = {},
                loading = false,
                progressMessage = "",
                toolbarVisible = false,
                onListGhost = {},
                onUpdate = {},
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }
        composeRule.waitForIdle()

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(
            "Expected UI Automator to discover the ghost-stage test tag as a resource ID",
            device.wait(Until.hasObject(By.res("ghost-stage")), RESOURCE_ID_TIMEOUT_MILLIS),
        )
    }

    @Test
    fun real_stage_exposes_exact_non_full_safe_content_bounds_to_ui_automator() {
        val measureState = GhostStageMeasureState().also { it.resetFor(this) }
        composeRule.setContent {
            NanidroidComposeShell(
                ghostStage = {
                    GhostPresentationStage(
                        presentation = GhostPresentationReducer.snapshot(
                            sakuraText = "",
                            sakuraSurfaceId = "0",
                            sakuraAnimationId = null,
                            sakuraBalloonId = "-1",
                            keroText = "",
                            keroSurfaceId = "10",
                            keroAnimationId = null,
                            keroBalloonId = "-1",
                        ),
                        sakuraComposedSurface = null,
                        keroComposedSurface = null,
                        measureState = measureState,
                        ghostKey = "safe-stage-ui-automator",
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                loading = false,
                progressMessage = "",
                toolbarVisible = false,
                onListGhost = {},
                onUpdate = {},
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }
        composeRule.waitUntil(RESOURCE_ID_TIMEOUT_MILLIS) { measureState.latest != null }
        composeRule.waitForIdle()

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(
            "Expected UI Automator to discover the measured ghost-safe-stage resource ID",
            device.wait(Until.hasObject(By.res("ghost-safe-stage")), RESOURCE_ID_TIMEOUT_MILLIS),
        )
        val safeStageNodes = device.findObjects(By.res("ghost-safe-stage"))
        assertEquals("Expected exactly one ghost-safe-stage semantics node", 1, safeStageNodes.size)

        val shellBounds = device.findObject(By.res("ghost-stage")).visibleBounds
        val content = requireNotNull(measureState.latest).layoutPx.content
        val expectedSafeBounds = Rect(
            shellBounds.left + content.left,
            shellBounds.top + content.top,
            shellBounds.left + content.right,
            shellBounds.top + content.bottom,
        )
        val safeStageNode = safeStageNodes.single()
        val safeBounds = safeStageNode.visibleBounds

        assertEquals("Safe-stage resource bounds must equal StageLayoutPx.content", expectedSafeBounds, safeBounds)
        assertTrue("Safe-stage evidence must not report the full ghost-stage root", safeBounds != shellBounds)
        assertTrue("Safe-stage evidence must not be clickable", !safeStageNode.isClickable)
        assertTrue("Safe-stage evidence must not be long-clickable", !safeStageNode.isLongClickable)
        assertTrue("Safe-stage evidence must not be scrollable", !safeStageNode.isScrollable)
        assertTrue("Safe-stage evidence must not be checkable", !safeStageNode.isCheckable)
    }

    private companion object {
        const val RESOURCE_ID_TIMEOUT_MILLIS = 5_000L
    }
}
