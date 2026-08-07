package com.cattailsw.nanidroid.compose.stage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class DialogueActionSurfaceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun compactSurfaceIsFullWidthAndRowsWrapAtEveryRequiredFontScale() {
        val fontScale = mutableFloatStateOf(1f)
        val long = DialogueAction.Normal(
            "A deliberately long localized choice label that must wrap without clipping at large font scales",
            "long",
            emptyList(),
        )
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)) then
                    DeviceConfigurationOverride.FontScale(fontScale.floatValue),
            ) {
                Box(Modifier.requiredSize(360.dp, 640.dp)) {
                    DialogueActionSurfaceContent(
                        actions = listOf(long),
                        speaker = SurfaceSpeaker.KERO,
                        compact = true,
                        onDismiss = {},
                        onAction = {},
                    )
                }
            }
        }

        var largeFontHeight = 0f
        listOf(1f, 1.5f, 2f).forEach { scale ->
            composeRule.runOnIdle { fontScale.floatValue = scale }
            composeRule.waitForIdle()
            val surface = composeRule.onNodeWithTag("dialogue-action-surface-kero").fetchSemanticsNode().boundsInRoot
            val row = composeRule.onNodeWithTag("dialogue-action-0").fetchSemanticsNode().boundsInRoot
            composeRule.runOnIdle {
                val density = composeRule.activity.resources.displayMetrics.density
                assertTrue(surface.width / density >= 352f)
                assertTrue(row.height / density >= 48f)
                if (scale == 2f) largeFontHeight = row.height / density
            }
        }
        assertTrue(largeFontHeight > 48f)
    }

    @Test
    fun expandedSurfaceIsCenteredAndCapped() {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(1000.dp, 800.dp)),
            ) {
                Box(Modifier.requiredSize(1000.dp, 800.dp)) {
                    DialogueActionSurfaceContent(
                        actions = listOf(DialogueAction.Normal("Choose", "id", emptyList())),
                        speaker = SurfaceSpeaker.SAKURA,
                        compact = false,
                        onDismiss = {},
                        onAction = {},
                    )
                }
            }
        }

        val surface = composeRule.onNodeWithTag("dialogue-action-surface-sakura").fetchSemanticsNode().boundsInRoot
        val root = composeRule.onNodeWithTag("dialogue-action-root").fetchSemanticsNode().boundsInRoot
        composeRule.runOnIdle {
            val density = composeRule.activity.resources.displayMetrics.density
            assertTrue(surface.width / density <= 560f)
            assertTrue(surface.left > root.left)
            assertTrue(surface.right < root.right)
            assertTrue(abs(surface.center.x - root.center.x) <= 1f)
        }
    }

    @Test
    fun compactLandscapeAtFontTwoKeepsSheetInRootAndLastChoiceReachable() {
        val actions = (0 until 20).map { index ->
            DialogueAction.Normal(
                "Long localized choice $index wraps without clipping at font scale two",
                "id-$index",
                emptyList(),
            )
        }
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(720.dp, 360.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                Box(Modifier.requiredSize(720.dp, 360.dp)) {
                    DialogueActionSurfaceContent(
                        actions = actions,
                        speaker = SurfaceSpeaker.SAKURA,
                        compact = true,
                        onDismiss = {},
                        onAction = {},
                    )
                }
            }
        }

        val surface = composeRule.onNodeWithTag("dialogue-action-surface-sakura")
            .fetchSemanticsNode().boundsInRoot
        val root = composeRule.onNodeWithTag("dialogue-action-root").fetchSemanticsNode().boundsInRoot
        composeRule.runOnIdle {
            assertTrue(surface.top >= root.top)
            assertTrue(surface.bottom <= root.bottom)
        }
        composeRule.onNodeWithTag("dialogue-action-19").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun keyboardTraversalActivatesExactRowsOnceAndEscapeOnlyDismissesPresentation() {
        val open = mutableStateOf(true)
        val actions = (0..5).map { index ->
            DialogueAction.Normal("Choice $index", "id-$index", listOf(index.toString()))
        }
        val selected = mutableListOf<DialogueAction>()
        val focusRequests = mutableListOf<DialogueActionFocusRequest>()
        var dismissals = 0
        composeRule.setContent {
            DialogueActionSurface(
                actions = actions,
                speaker = SurfaceSpeaker.SAKURA,
                open = open.value,
                compact = true,
                onDismiss = {
                    dismissals++
                    open.value = false
                },
                onAction = selected::add,
                onInitialFocusRequest = focusRequests::add,
            )
        }

        composeRule.waitUntil(5_000) { focusRequests.any { it.accepted } }
        composeRule.runOnIdle {
            assertTrue(focusRequests.isNotEmpty())
            assertTrue(focusRequests.all { it.windowFocused && it.firstRowAttached })
            assertTrue(focusRequests.last().accepted)
        }
        composeRule.onNodeWithTag("dialogue-action-0").assertIsFocused()
        composeRule.onNodeWithTag("dialogue-action-0").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("dialogue-action-1").assertIsFocused()
        composeRule.onNodeWithTag("dialogue-action-1").performKeyInput { pressKey(Key.Tab) }
        composeRule.onNodeWithTag("dialogue-action-2").assertIsFocused()
        composeRule.onNodeWithTag("dialogue-action-2").performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        composeRule.onNodeWithTag("dialogue-action-1").assertIsFocused()
        composeRule.onNodeWithTag("dialogue-action-1").performKeyInput { pressKey(Key.Enter) }
        composeRule.onNodeWithTag("dialogue-action-1").performKeyInput { pressKey(Key.Spacebar) }
        composeRule.runOnIdle {
            assertEquals(2, selected.size)
            assertSame(actions[1], selected[0])
            assertSame(actions[1], selected[1])
        }

        composeRule.onNodeWithTag("dialogue-action-1").performKeyInput { pressKey(Key.Escape) }
        composeRule.runOnIdle {
            assertEquals(1, dismissals)
            assertEquals(6, actions.size)
        }
        composeRule.runOnIdle { open.value = true }
        composeRule.onNodeWithTag("dialogue-action-0").assertIsFocused()
    }

    @Test
    fun replacingAnEqualActionBetweenDownAndUpCannotDispatchEitherInstance() {
        val first = DialogueAction.Normal("Same", "same", listOf("ref"))
        val replacement = first.copy()
        val actions = mutableStateOf(listOf<DialogueAction>(first), neverEqualPolicy())
        var recompositions = 0
        var selected: DialogueAction? = null
        composeRule.setContent {
            SideEffect { recompositions++ }
            DialogueActionSurface(
                actions = actions.value,
                speaker = SurfaceSpeaker.KERO,
                open = true,
                compact = true,
                onDismiss = {},
                onAction = { selected = it },
            )
        }

        composeRule.onNodeWithTag("dialogue-action-0").performTouchInput { down(center) }
        val beforeReplacement = recompositions
        composeRule.runOnIdle { actions.value = listOf(replacement) }
        composeRule.waitUntil(5_000) { recompositions > beforeReplacement }
        val cancelledGesture = runCatching {
            composeRule.onNodeWithTag("dialogue-action-0").performTouchInput { up() }
        }.exceptionOrNull()

        composeRule.runOnIdle {
            assertTrue(cancelledGesture is IllegalStateException)
            assertEquals(null, selected)
        }
    }

}
