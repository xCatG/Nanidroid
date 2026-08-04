package com.cattailsw.nanidroid.compose.stage

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.compose.ComposedSurface
import com.cattailsw.nanidroid.compose.GhostPresentationStage
import com.cattailsw.nanidroid.compose.SurfacePixelImage
import com.cattailsw.nanidroid.compose.currentStageInputSnapshot
import com.cattailsw.nanidroid.runtime.GhostPresentationReducer
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.DialogueContent
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.stage.StageMode
import com.cattailsw.nanidroid.runtime.stage.SurfaceKey
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class GhostStageRestorationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tiny_mode_removes_live_stage_input_and_restores_the_exact_frame() {
        val windowSize = mutableStateOf(DpSize(360.dp, 720.dp))
        val measureState = GhostStageMeasureState().also { it.resetFor("tiny-restoration") }
        val surface = surface()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(windowSize.value.width, windowSize.value.height)) {
                    GhostPresentationStage(
                        presentation = presentation(),
                        sakuraComposedSurface = surface,
                        keroComposedSurface = null,
                        measureState = measureState,
                        ghostKey = "tiny-restoration",
                        modifier = Modifier.fillMaxSize(),
                        sakuraSurface = { snapshot -> RenderedSurfaceLayer(snapshot, false) },
                    )
                }
            }
        }

        composeRule.waitUntil(5_000) {
            measureState.latest?.layoutDp?.mode == StageMode.STANDARD &&
                measureState.latest?.sakura != null
        }
        val before = requireNotNull(measureState.latest?.sakura?.composedSurface)
        composeRule.onNodeWithTag("surface-sakura", useUnmergedTree = true).assertExists()

        composeRule.runOnIdle { windowSize.value = DpSize(230.dp, 400.dp) }

        composeRule.waitUntil(5_000) { measureState.latest?.layoutDp?.mode == StageMode.TINY }
        composeRule.onNodeWithText(TINY_MESSAGE).assertExists()
        composeRule.onNodeWithTag("surface-sakura", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(0)
        composeRule.runOnIdle {
            check(
                currentStageInputSnapshot(
                    measured = measureState.latest,
                    blocking = false,
                    ghostKey = "tiny-restoration",
                    ghostIdentity = "tiny-restoration",
                ).surfaces.isEmpty(),
            )
        }

        composeRule.runOnIdle { windowSize.value = DpSize(360.dp, 720.dp) }

        composeRule.waitUntil(5_000) {
            measureState.latest?.layoutDp?.mode == StageMode.STANDARD &&
                measureState.latest?.sakura != null
        }
        composeRule.onNodeWithText(TINY_MESSAGE).assertDoesNotExist()
        composeRule.onNodeWithTag("surface-sakura", useUnmergedTree = true).assertExists()
        composeRule.runOnIdle {
            assertSame(before, requireNotNull(measureState.latest?.sakura?.composedSurface))
        }
    }

    @Test
    fun tiny_message_and_physical_roles_have_exact_localized_copy() {
        val japanese = composeRule.activity.localized(Locale.JAPAN)
        assertEquals(
            "このウィンドウは Nanidroid を表示するには小さすぎます。もう少し大きくしてください💦",
            japanese.getString(R.string.stage_tiny_window_message),
        )
        assertEquals("本体側", japanese.getString(R.string.sakura_character_description))
        assertEquals("相方側", japanese.getString(R.string.kero_character_description))
        assertEquals("本体側を操作する", japanese.getString(R.string.stage_surface_activate_action, "本体側"))
        assertEquals(
            "本体側の「Face」を操作する",
            japanese.getString(R.string.stage_collision_activate_action, "本体側", "Face"),
        )
        assertEquals(
            "本体側の「Face」を操作する（2件目）",
            japanese.getString(R.string.stage_collision_activate_repeated_action, "本体側", "Face", 2),
        )
        assertEquals("名称のない領域", japanese.getString(R.string.stage_collision_unnamed_region))

        val traditionalChinese = composeRule.activity.localized(Locale.forLanguageTag("zh-TW"))
        assertEquals(
            "這個視窗太小，無法顯示 Nanidroid。請把它放大一點 💦",
            traditionalChinese.getString(R.string.stage_tiny_window_message),
        )
        assertEquals("本體側", traditionalChinese.getString(R.string.sakura_character_description))
        assertEquals("搭檔側", traditionalChinese.getString(R.string.kero_character_description))
        assertEquals("操作本體側", traditionalChinese.getString(R.string.stage_surface_activate_action, "本體側"))
        assertEquals("未命名區域", traditionalChinese.getString(R.string.stage_collision_unnamed_region))
    }

    @Test
    fun recreation_restores_manual_scroll_after_blank_host_binding_and_new_talk_resets_both() {
        val talkId = mutableLongStateOf(41L)
        val restoredGhostKey = mutableStateOf("")
        val bubbleScrollSessionKey = mutableStateOf("session-a")
        var compositionGeneration = 0
        val restoration = StateRestorationTester(composeRule)
        val longText = (0 until 80).joinToString("\n") { "restoration line $it" }
        restoration.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                val measureState = remember { GhostStageMeasureState() }
                val generation = remember { ++compositionGeneration }
                GhostPresentationStage(
                    presentation = GhostPresentationReducer.snapshot(
                        sakuraText = "Sakura",
                        sakuraSurfaceId = "0",
                        sakuraAnimationId = null,
                        sakuraBalloonId = "0",
                        keroText = "Kero",
                        keroSurfaceId = "10",
                        keroAnimationId = null,
                        keroBalloonId = "0",
                    ),
                    sakuraComposedSurface = null,
                    keroComposedSurface = null,
                    measureState = measureState,
                    ghostKey = if (generation == 1) {
                        "bubble-scroll-restoration"
                    } else {
                        restoredGhostKey.value
                    },
                    bubbleScrollSessionKey = bubbleScrollSessionKey.value,
                    showSakuraBalloon = true,
                    showKeroBalloon = true,
                    sakuraDialogue = DialogueContent(
                        GhostSpeaker.SAKURA,
                        listOf(DialogueSegment.Text(longText)),
                    ),
                    keroDialogue = DialogueContent(
                        GhostSpeaker.KERO,
                        listOf(DialogueSegment.Text(longText)),
                    ),
                    dialogueTalkId = talkId.longValue,
                    modifier = Modifier.requiredSize(360.dp, 720.dp),
                )
            }
        }
        waitForScrollableBubble("sakura")
        waitForScrollableBubble("kero")
        composeRule.onNodeWithTag("ghost-bubble-scroll-sakura").performTouchInput { swipeDown() }
        composeRule.onNodeWithTag("ghost-bubble-scroll-kero").performTouchInput { swipeDown() }
        val sakuraBefore = waitForManualPosition("sakura")
        val keroBefore = waitForManualPosition("kero")

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { restoredGhostKey.value = "bubble-scroll-restoration" }

        composeRule.waitUntil(5_000) { scrollValue("sakura") == sakuraBefore }
        composeRule.waitUntil(5_000) { scrollValue("kero") == keroBefore }
        composeRule.runOnIdle { bubbleScrollSessionKey.value = "session-b" }
        composeRule.waitUntil(5_000) { scrollValue("sakura") == scrollMax("sakura") }
        composeRule.waitUntil(5_000) { scrollValue("kero") == scrollMax("kero") }
        composeRule.runOnIdle { talkId.longValue++ }
        composeRule.waitUntil(5_000) { scrollValue("sakura") == scrollMax("sakura") }
        composeRule.waitUntil(5_000) { scrollValue("kero") == scrollMax("kero") }
    }

    @Test
    fun repeated_process_sessions_do_not_accumulate_nested_scroll_saved_state() {
        var compositionGeneration = 0
        var savedRegistrySize = 0
        val restoration = StateRestorationTester(composeRule)
        val longText = (0 until 80).joinToString("\n") { "session line $it" }
        restoration.setContent {
            val registry = LocalSaveableStateRegistry.current
            val generation = remember { ++compositionGeneration }
            SideEffect { savedRegistrySize = registry?.performSave()?.size ?: 0 }
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                GhostPresentationStage(
                    presentation = GhostPresentationReducer.snapshot(
                        sakuraText = "Sakura",
                        sakuraSurfaceId = "0",
                        sakuraAnimationId = null,
                        sakuraBalloonId = "0",
                        keroText = "Kero",
                        keroSurfaceId = "10",
                        keroAnimationId = null,
                        keroBalloonId = "0",
                    ),
                    sakuraComposedSurface = null,
                    keroComposedSurface = null,
                    measureState = remember { GhostStageMeasureState() },
                    ghostKey = "bubble-scroll-restoration",
                    bubbleScrollSessionKey = "session-$generation",
                    showSakuraBalloon = true,
                    showKeroBalloon = true,
                    sakuraDialogue = DialogueContent(
                        GhostSpeaker.SAKURA,
                        listOf(DialogueSegment.Text(longText)),
                    ),
                    keroDialogue = DialogueContent(
                        GhostSpeaker.KERO,
                        listOf(DialogueSegment.Text(longText)),
                    ),
                    dialogueTalkId = 1L,
                    modifier = Modifier.requiredSize(360.dp, 720.dp),
                )
            }
        }
        waitForScrollableBubble("sakura")
        waitForScrollableBubble("kero")
        composeRule.onNodeWithTag("ghost-bubble-scroll-sakura").performTouchInput { swipeDown() }
        composeRule.onNodeWithTag("ghost-bubble-scroll-kero").performTouchInput { swipeDown() }

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        val afterSessionB = savedRegistrySize
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        val afterSessionC = savedRegistrySize

        assertEquals(afterSessionB, afterSessionC)
    }

    private fun waitForScrollableBubble(speaker: String) {
        composeRule.waitUntil(5_000) { scrollMax(speaker) > 0f }
    }

    private fun waitForManualPosition(speaker: String): Float {
        composeRule.waitUntil(5_000) { scrollValue(speaker) < scrollMax(speaker) }
        return scrollValue(speaker)
    }

    private fun scrollValue(speaker: String): Float = scrollAxis(speaker).value()

    private fun scrollMax(speaker: String): Float = scrollAxis(speaker).maxValue()

    private fun scrollAxis(speaker: String) = composeRule
        .onNodeWithTag("ghost-bubble-scroll-$speaker")
        .fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]

    private fun surface(): ComposedSurface {
        val size = IntSize(20, 30)
        return ComposedSurface(
            image = SurfacePixelImage.of(size.width, size.height, IntArray(size.width * size.height)),
            canvasSize = size,
            visiblePixelBounds = IntRect(0, 0, size.width, size.height),
            effectiveCollisions = emptyList(),
            surfaceKey = SurfaceKey(0, size),
            revision = 1,
            explicitlyHidden = false,
        )
    }

    private fun presentation() = GhostPresentationReducer.snapshot(
        sakuraText = "Sakura",
        sakuraSurfaceId = "0",
        sakuraAnimationId = null,
        sakuraBalloonId = "-1",
        keroText = "",
        keroSurfaceId = "10",
        keroAnimationId = null,
        keroBalloonId = "-1",
    )

    private companion object {
        const val TINY_MESSAGE = "This window is too small for Nanidroid. Make it a little bigger 💦"
    }
}

private fun Context.localized(locale: Locale): Context {
    val localized = Configuration(resources.configuration).apply { setLocale(locale) }
    return createConfigurationContext(localized)
}
