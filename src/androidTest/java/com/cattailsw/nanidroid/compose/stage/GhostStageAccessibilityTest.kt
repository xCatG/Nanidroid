package com.cattailsw.nanidroid.compose.stage

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.SurfaceCollision
import com.cattailsw.nanidroid.compose.ComposedSurface
import com.cattailsw.nanidroid.compose.SurfacePixelImage
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import com.cattailsw.nanidroid.runtime.stage.BubbleHitRegionRegistry
import com.cattailsw.nanidroid.runtime.stage.StageInputRouter
import com.cattailsw.nanidroid.runtime.stage.SurfaceKey
import com.cattailsw.nanidroid.runtime.stage.SurfaceTransformPx
import com.cattailsw.nanidroid.surface.CollisionShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class GhostStageAccessibilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun enableAccessibilityValidation() {
        composeRule.enableAccessibilityChecks()
    }

    @Test
    fun generic_surface_action_never_inherits_a_collision_at_visual_center() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        val surface = surface(
            listOf(collision(7, "Center", IntRect(40, 40, 60, 60))),
        )
        setStage(surface, effects)

        surfaceNode()
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals(1, effects.size)
            assertNull(effects.single().collisionIdentifier)
        }
    }

    @Test
    fun named_collision_actions_keep_authored_order_and_dispatch_exact_identity() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        val surface = surface(
            listOf(
                collision(1, "Face", IntRect(0, 0, 10, 10)),
                collision(2, "Button", IntRect(10, 0, 20, 10)),
                collision(3, "Face", IntRect(20, 0, 30, 10)),
            ),
        )
        setStage(surface, effects)
        val node = surfaceNode()

        val actions = node.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(
            listOf(
                "Operate “Face” on Sakura character (1)",
                "Operate “Button” on Sakura character",
                "Operate “Face” on Sakura character (2)",
            ),
            actions.map { it.label },
        )
        composeRule.runOnIdle { actions[2].action() }

        composeRule.runOnIdle {
            assertEquals(1, effects.size)
            assertEquals("Face", effects.single().collisionIdentifier)
            assertEquals(3, effects.single().diagnosticCollisionId)
        }
    }

    @Test
    fun focused_surface_enter_uses_the_same_collision_null_generic_action() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        val surface = surface(
            listOf(collision(7, "Center", IntRect(40, 40, 60, 60))),
        )
        setStage(surface, effects)
        val node = surfaceNode()

        node.performSemanticsAction(SemanticsActions.RequestFocus)
        node.assertIsFocused()
        node.performKeyInput { pressKey(Key.Enter) }

        composeRule.runOnIdle {
            assertEquals(1, effects.size)
            assertNull(effects.single().collisionIdentifier)
        }
    }

    @Test
    fun small_visual_surface_has_a_48dp_semantic_target_without_changing_effect_coordinates() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        val surface = surface(
            collisions = emptyList(),
            renderedBounds = IntRect(0, 0, 20, 20),
        )
        setStage(surface, effects, surfaceSize = 20.dp)
        val node = surfaceNode()

        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue("semantic width was ${bounds.width}dp", bounds.width >= 48f)
        assertTrue("semantic height was ${bounds.height}dp", bounds.height >= 48f)
        node.performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals(IntOffset(10, 10), effects.single().viewportPosition)
        }
    }

    private fun setStage(
        surface: StageSurfaceSnapshot,
        effects: MutableList<SurfaceInteractionEffect>,
        surfaceSize: Dp = 100.dp,
    ) {
        val input = StageInputRouter.snapshot(
            blocking = false,
            bubbleRegistry = BubbleHitRegionRegistry.Empty,
            bubbleGeneration = 0,
            ghostKey = "accessibility",
            surfaces = listOf(surface),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                StagePointerInput(
                    snapshotProvider = { input },
                    onSurfaceEffect = effects::add,
                    onToggleChrome = {},
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(Modifier.requiredSize(surfaceSize)) {
                        RenderedSurfaceLayer(
                            snapshot = surface,
                            showCollisionOverlay = false,
                        )
                    }
                }
            }
        }
    }

    private fun surface(
        collisions: List<SurfaceCollision>,
        renderedBounds: IntRect = IntRect(0, 0, 100, 100),
    ): StageSurfaceSnapshot {
        val size = IntSize(100, 100)
        return StageSurfaceSnapshot(
            speaker = SurfaceSpeaker.SAKURA,
            composedSurface = ComposedSurface(
                image = SurfacePixelImage.of(size.width, size.height, IntArray(size.width * size.height)),
                canvasSize = size,
                visiblePixelBounds = IntRect(0, 0, size.width, size.height),
                effectiveCollisions = collisions,
                surfaceKey = SurfaceKey(0, size),
                revision = 9,
                explicitlyHidden = false,
            ),
            transform = SurfaceTransformPx(
                intrinsicSize = size,
                renderedBounds = renderedBounds,
                scale = renderedBounds.width / size.width.toFloat(),
                stageToRoot = IntOffset.Zero,
            ),
        )
    }

    private fun collision(id: Int, identifier: String, bounds: IntRect) = SurfaceCollision(
        id = id,
        identifier = identifier,
        shape = CollisionShape.Rectangle(bounds),
        authoredOrder = id,
    )

    private fun surfaceNode() = composeRule.onNode(
        hasContentDescription("Sakura character") and hasClickAction(),
        useUnmergedTree = true,
    )
}
