package com.cattailsw.nanidroid.compose.debug

import androidx.activity.ComponentActivity
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.runtime.BoundedShioriLog
import com.cattailsw.nanidroid.runtime.BoundedShioriLog.Entry
import java.util.Locale

class GhostDebugSurfaceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun assertNoNodeWithTag(tag: String, useUnmergedTree: Boolean = false) {
        assertFalse(
            "Expected no node with tag=$tag",
            composeRule.onAllNodesWithTag(tag, useUnmergedTree).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun debug_surface_not_rendered_when_not_visible() {
        var dismissed = 0
        composeRule.setContent {
            GhostDebugSurface(
                presentation = DebugPresentation.FULL_STAGE_MODAL,
                state = DebugPanelState(visible = false),
                selection = null,
                lastInput = null,
                logs = emptyList(),
                onSelectSpeaker = {},
                onCollisionOverlayChange = {},
                onNarTest = {},
                onDismiss = { dismissed++ },
            )
        }

        composeRule.waitForIdle()
        assertNoNodeWithTag(GHOST_DEBUG_SURFACE_FULL_STAGE_MODAL_TAG)
        assertNoNodeWithTag(GHOST_DEBUG_SURFACE_BOTTOM_SHEET_TAG)
        assertNoNodeWithTag(GHOST_DEBUG_SURFACE_SIDE_PANEL_TAG)
        assertEquals(0, dismissed)
    }

    @Test
    fun debug_surface_uses_expected_container_for_each_presentation() {
        val activePresentation = mutableStateOf(DebugPresentation.FULL_STAGE_MODAL)
        val presentations = listOf(
            DebugPresentation.FULL_STAGE_MODAL to GHOST_DEBUG_SURFACE_FULL_STAGE_MODAL_TAG,
            DebugPresentation.BOTTOM_SHEET to GHOST_DEBUG_SURFACE_BOTTOM_SHEET_TAG,
            DebugPresentation.SIDE_PANEL to GHOST_DEBUG_SURFACE_SIDE_PANEL_TAG,
        )

        composeRule.setContent {
            GhostDebugSurface(
                presentation = activePresentation.value,
                state = DebugPanelState(visible = true, selectedSpeaker = SurfaceSpeaker.SAKURA),
                selection = null,
                lastInput = null,
                logs = emptyList(),
                onSelectSpeaker = {},
                onCollisionOverlayChange = {},
                onNarTest = {},
                onDismiss = {},
            )
        }

        presentations.forEach { (presentation, tag) ->
            composeRule.runOnIdle { activePresentation.value = presentation }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
            assertNoNodeWithTag(
                if (tag == GHOST_DEBUG_SURFACE_FULL_STAGE_MODAL_TAG) {
                    GHOST_DEBUG_SURFACE_BOTTOM_SHEET_TAG
                } else {
                    GHOST_DEBUG_SURFACE_FULL_STAGE_MODAL_TAG
                },
            )
        }
    }

    @Test
    fun debug_surface_calls_speaker_callback_for_sakura_default_and_kero_selection() {
        val speaker = mutableStateOf(SurfaceSpeaker.SAKURA)

        composeRule.setContent {
            GhostDebugSurface(
                presentation = DebugPresentation.FULL_STAGE_MODAL,
                state = DebugPanelState(visible = true, selectedSpeaker = speaker.value),
                selection = null,
                lastInput = null,
                logs = emptyList(),
                onSelectSpeaker = { speaker.value = it },
                onCollisionOverlayChange = {},
                onNarTest = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithTag(GHOST_DEBUG_SURFACE_KERO_TAG).performClick()
        composeRule.runOnIdle { assertEquals(SurfaceSpeaker.KERO, speaker.value) }
        composeRule.onNodeWithTag(GHOST_DEBUG_SURFACE_SAKURA_TAG).performClick()
        composeRule.runOnIdle { assertEquals(SurfaceSpeaker.SAKURA, speaker.value) }
    }

    @Test
    fun side_panel_consumes_back_to_dismiss_debug_instead_of_finishing_activity() {
        val visible = mutableStateOf(true)
        var dismissed = 0
        composeRule.setContent {
            GhostDebugSurface(
                presentation = DebugPresentation.SIDE_PANEL,
                state = DebugPanelState(visible = visible.value),
                selection = null,
                lastInput = null,
                logs = emptyList(),
                onSelectSpeaker = {},
                onCollisionOverlayChange = {},
                onNarTest = {},
                onDismiss = {
                    dismissed++
                    visible.value = false
                },
            )
        }

        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

        composeRule.runOnIdle {
            assertEquals(1, dismissed)
            assertFalse(composeRule.activity.isFinishing)
        }
        assertNoNodeWithTag(GHOST_DEBUG_SURFACE_SIDE_PANEL_TAG)
    }

    @Test
    fun debug_surface_callbacks_exposed_for_collision_overlay_nar_and_dismiss() {
        val collision = mutableStateOf(false)
        val collisionChanges = mutableListOf<Boolean>()
        var nar = false
        var dismissed = false

        composeRule.setContent {
            GhostDebugSurface(
                presentation = DebugPresentation.FULL_STAGE_MODAL,
                state = DebugPanelState(
                    visible = true,
                    selectedSpeaker = SurfaceSpeaker.SAKURA,
                    showCollisionOverlay = collision.value,
                ),
                selection = null,
                lastInput = null,
                logs = emptyList(),
                onSelectSpeaker = {},
                onCollisionOverlayChange = {
                    collisionChanges += it
                    collision.value = it
                },
                onNarTest = { nar = true },
                onDismiss = { dismissed = true },
            )
        }

        composeRule.onNodeWithTag(GHOST_DEBUG_SURFACE_COLLISION_SWITCH_TAG).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(listOf(true), collisionChanges.toList()) }
        composeRule.onNodeWithTag(GHOST_DEBUG_SURFACE_NAR_TEST_TAG).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(true, nar) }
        composeRule.onNodeWithTag(GHOST_DEBUG_SURFACE_DISMISS_TAG).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(true, dismissed) }
    }

    @Test
    fun compact_scrollable_debug_surface_renews_sample_feedback_for_each_press() {
        val state = mutableStateOf(DebugPanelState(visible = true))
        var samplesRun = 0

        composeRule.setContent {
            GhostDebugSurface(
                presentation = DebugPresentation.FULL_STAGE_MODAL,
                state = state.value,
                selection = null,
                lastInput = null,
                logs = emptyList(),
                onSelectSpeaker = {},
                onCollisionOverlayChange = {},
                onNarTest = {
                    samplesRun++
                    state.value = state.value.recordSampleFeedback()
                },
                onDismiss = {},
            )
        }

        composeRule.onNodeWithTag(GHOST_DEBUG_SURFACE_NAR_TEST_TAG).performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, samplesRun)
            assertEquals(1L, state.value.sampleFeedbackToken)
        }
        composeRule.onNodeWithTag(GHOST_DEBUG_SURFACE_SAMPLE_FEEDBACK_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(GHOST_DEBUG_SURFACE_NAR_TEST_TAG).performClick()
        composeRule.runOnIdle {
            assertEquals(2, samplesRun)
            assertEquals(2L, state.value.sampleFeedbackToken)
        }
        composeRule.onNodeWithTag(GHOST_DEBUG_SURFACE_SAMPLE_FEEDBACK_TAG).assertIsDisplayed()
    }

    @Test
    fun debug_surface_shows_resolved_candidate_and_rejected_dispatch_outcome() {
        val selection = SurfaceDebugSelection(
            speaker = SurfaceSpeaker.SAKURA,
            scope = "sakura_scope",
            surfaceId = "surface-01",
            intrinsicWidth = 420,
            intrinsicHeight = 280,
            composedLeft = 1,
            composedTop = 2,
            composedRight = 421,
            composedBottom = 281,
            composedWidth = 420,
            composedHeight = 279,
            visibleLeft = 9,
            visibleTop = 10,
            visibleRight = 409,
            visibleBottom = 270,
            animationId = "animation-01",
            visible = true,
            animationRunning = false,
            revision = 77L,
        )
        val input = SurfacePointerDebugEvent(
            speaker = SurfaceSpeaker.KERO,
            viewportX = 13,
            viewportY = 14,
            sourceX = 15,
            sourceY = 16,
            collisionId = 123,
            collisionName = "bubble",
            buttonId = 1,
            candidateEvent = "OnMouseClick",
            dispatchOutcome = PointerDispatchOutcome.REJECTED,
            source = "mouse",
        )
        val logs = listOf(
            Entry(
                id = 0L,
                event = "OnTest",
                request = "Reference0:payload",
                responseStatus = 200,
                responseValue = "OK",
            ),
        )

        composeRule.setContent {
            GhostDebugSurface(
                presentation = DebugPresentation.FULL_STAGE_MODAL,
                state = DebugPanelState(visible = true, selectedSpeaker = SurfaceSpeaker.SAKURA),
                selection = selection,
                lastInput = input,
                logs = logs,
                onSelectSpeaker = {},
                onCollisionOverlayChange = {},
                onNarTest = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("surface-01").assertIsDisplayed()
        composeRule.onNodeWithText("9,10 to 409,270").assertExists()
        composeRule.onNodeWithText("X=13, Y=14").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Recent SHIORI log").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(GHOST_DEBUG_SURFACE_SHIORI_LOG_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ghost-debug-surface-shiori-log-0").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Event: OnTest").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Status: 200").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Response: OK").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Collision ID / name").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("123 / bubble").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("OnMouseClick").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Rejected").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun debug_surface_keeps_the_same_expanded_log_entry_after_bounded_eviction() {
        val log = BoundedShioriLog(maxEvents = 3)
        val firstRequest = "first".repeat(600)
        val expandedRequest = "expanded-original".repeat(300)
        val thirdRequest = "third".repeat(600)
        log.append("First", firstRequest, 200, "OK", "OK")
        log.append("Second", expandedRequest, 200, "OK", "OK")
        log.append("Third", thirdRequest, 200, "OK", "OK")
        val entries = mutableStateOf(log.snapshot())

        composeRule.setContent {
            GhostDebugSurface(
                presentation = DebugPresentation.FULL_STAGE_MODAL,
                state = DebugPanelState(visible = true),
                selection = null,
                lastInput = null,
                logs = entries.value,
                onSelectSpeaker = {},
                onCollisionOverlayChange = {},
                onNarTest = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithTag(GHOST_DEBUG_SURFACE_SHIORI_LOG_TAG).performScrollTo()
        composeRule.onNodeWithTag(GHOST_DEBUG_SURFACE_SHIORI_LOG_LIST_TAG).performScrollToIndex(1)
        composeRule.onNodeWithTag("ghost-debug-surface-shiori-log-1-toggle")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ghost-debug-surface-shiori-log-1-toggle")
            .assertTextEquals("Collapse payload")

        composeRule.runOnIdle {
            log.append("Fourth", "fourth".repeat(600), 200, "OK", "OK")
            entries.value = log.snapshot()
        }

        composeRule.onNodeWithTag(GHOST_DEBUG_SURFACE_SHIORI_LOG_LIST_TAG).performScrollToIndex(0)
        composeRule.onNodeWithTag("ghost-debug-surface-shiori-log-1-toggle")
            .assertTextEquals("Collapse payload")
    }

    @Test
    fun debug_surface_reports_missing_selection_as_unknown() {
        composeRule.setContent {
            GhostDebugSurface(
                presentation = DebugPresentation.FULL_STAGE_MODAL,
                state = DebugPanelState(visible = true),
                selection = null,
                lastInput = null,
                logs = emptyList(),
                onSelectSpeaker = {},
                onCollisionOverlayChange = {},
                onNarTest = {},
                onDismiss = {},
            )
        }

        assertTrue(composeRule.onAllNodesWithText("—").fetchSemanticsNodes().isNotEmpty())
        composeRule.onAllNodesWithText("No").assertCountEquals(0)
    }

    @Test
    fun debug_surface_japanese_copy_uses_ghost_terms_and_recent_shiori_history_at_large_font_scale() {
        val english = composeRule.activity.localized(Locale.US)
        assertEquals("Not dispatched", english.getString(R.string.debug_surface_pointer_dispatch_not_resolved))
        assertEquals("Recent SHIORI log", english.getString(R.string.debug_surface_shiori_section_title))

        val japanese = composeRule.activity.localized(Locale.JAPAN)

        assertEquals("当たり判定ID / 名前", japanese.getString(R.string.debug_surface_collision_id_label))
        assertEquals("入力元", japanese.getString(R.string.debug_surface_pointer_source_label))
        assertEquals("未送信", japanese.getString(R.string.debug_surface_pointer_dispatch_not_resolved))
        assertEquals("当たり判定オーバーレイを表示", japanese.getString(R.string.debug_surface_collision_overlay_toggle))
        assertEquals("直近の SHIORI ログ", japanese.getString(R.string.debug_surface_shiori_section_title))
        assertEquals("さくら側", japanese.getString(R.string.debug_surface_sakura_speaker_label))
        assertEquals("ケロ側", japanese.getString(R.string.debug_surface_kero_speaker_label))

        val traditionalChinese = composeRule.activity.localized(Locale.forLanguageTag("zh-TW"))
        assertEquals("未派送", traditionalChinese.getString(R.string.debug_surface_pointer_dispatch_not_resolved))
        assertEquals("近期 SHIORI 紀錄", traditionalChinese.getString(R.string.debug_surface_shiori_section_title))

        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides japanese,
                LocalConfiguration provides japanese.resources.configuration,
                LocalResources provides japanese.resources,
            ) {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.FontScale(1.5f),
                ) {
                    GhostDebugSurface(
                        presentation = DebugPresentation.FULL_STAGE_MODAL,
                        state = DebugPanelState(visible = true, selectedSpeaker = SurfaceSpeaker.SAKURA),
                        selection = null,
                        lastInput = SurfacePointerDebugEvent(
                            speaker = SurfaceSpeaker.KERO,
                            viewportX = 0,
                            viewportY = 0,
                            sourceX = 0,
                            sourceY = 0,
                            collisionId = 0,
                            collisionName = null,
                            buttonId = 0,
                            candidateEvent = null,
                            dispatchOutcome = PointerDispatchOutcome.NOT_RESOLVED,
                            source = "test",
                        ),
                        logs = emptyList(),
                        onSelectSpeaker = {},
                        onCollisionOverlayChange = {},
                        onNarTest = {},
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(GHOST_DEBUG_SURFACE_SAKURA_TAG)
            .assertContentDescriptionEquals("さくら側")
        composeRule.onNodeWithTag(GHOST_DEBUG_SURFACE_KERO_TAG)
            .assertContentDescriptionEquals("ケロ側")
        composeRule.onAllNodesWithText("ケロ側").assertCountEquals(2)
        composeRule.onAllNodesWithText("本体側").assertCountEquals(0)
        composeRule.onAllNodesWithText("相方側").assertCountEquals(0)
        composeRule.onNodeWithText("直近の SHIORI ログ").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun debug_surface_old_legacy_tags_are_absent() {
        composeRule.setContent {
            GhostDebugSurface(
                presentation = DebugPresentation.SIDE_PANEL,
                state = DebugPanelState(visible = true, selectedSpeaker = SurfaceSpeaker.SAKURA),
                selection = null,
                lastInput = null,
                logs = emptyList(),
                onSelectSpeaker = {},
                onCollisionOverlayChange = {},
                onNarTest = {},
                onDismiss = {},
            )
        }

        assertNoNodeWithTag("debug-next-surface", true)
        assertNoNodeWithTag("debug-draw-cbox", true)
        assertNoNodeWithTag("debug-dump-surfaces", true)
        assertNoNodeWithTag("debug-run", true)
        assertNoNodeWithTag("debug-nar", true)
    }
}

private fun Context.localized(locale: Locale): Context {
    val localized = Configuration(resources.configuration).apply { setLocale(locale) }
    return createConfigurationContext(localized)
}
