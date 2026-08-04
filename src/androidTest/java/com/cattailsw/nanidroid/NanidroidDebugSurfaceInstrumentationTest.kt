package com.cattailsw.nanidroid

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsOn
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cattailsw.nanidroid.compose.debug.GHOST_DEBUG_SURFACE_BOTTOM_SHEET_TAG
import com.cattailsw.nanidroid.compose.debug.GHOST_DEBUG_SURFACE_FULL_STAGE_MODAL_TAG
import com.cattailsw.nanidroid.compose.debug.GHOST_DEBUG_SURFACE_COLLISION_SWITCH_TAG
import com.cattailsw.nanidroid.compose.debug.GHOST_DEBUG_SURFACE_NAR_TEST_TAG
import com.cattailsw.nanidroid.compose.debug.GHOST_DEBUG_SURFACE_DISMISS_TAG
import com.cattailsw.nanidroid.compose.debug.GHOST_DEBUG_SURFACE_SIDE_PANEL_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NanidroidDebugSurfaceInstrumentationTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<Nanidroid>()

    @Test
    fun productionDebugIconOpensExactlyOneAdaptiveDebugContainer() {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag("debug").fetchSemanticsNodes().size == 1
        }

        composeRule.onNodeWithTag("debug").performClick()
        composeRule.waitForIdle()

        val containerCount = listOf(
            GHOST_DEBUG_SURFACE_BOTTOM_SHEET_TAG,
            GHOST_DEBUG_SURFACE_FULL_STAGE_MODAL_TAG,
            GHOST_DEBUG_SURFACE_SIDE_PANEL_TAG,
        ).sumOf { tag -> composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().size }
        assertEquals(1, containerCount)

        composeRule.onNodeWithTag(GHOST_DEBUG_SURFACE_COLLISION_SWITCH_TAG)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(GHOST_DEBUG_SURFACE_COLLISION_SWITCH_TAG).assertIsOn()

        composeRule.onNodeWithTag(GHOST_DEBUG_SURFACE_NAR_TEST_TAG)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Sample queued").assertIsDisplayed()
        composeRule.onNodeWithTag(GHOST_DEBUG_SURFACE_DISMISS_TAG)
            .performScrollTo()
            .performClick()
    }
}
