package com.cattailsw.nanidroid.compose.stage

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.SurfaceCollision
import com.cattailsw.nanidroid.SurfaceHitTarget
import com.cattailsw.nanidroid.compose.ComposedSurface
import com.cattailsw.nanidroid.compose.SurfaceInteractionPort
import com.cattailsw.nanidroid.compose.SurfacePixelImage
import com.cattailsw.nanidroid.compose.SurfacePointerInteractionMapper
import com.cattailsw.nanidroid.compose.SurfacePointerPosition
import com.cattailsw.nanidroid.compose.SurfacePointerResolution
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.compose.stagePositionFromLocal
import com.cattailsw.nanidroid.compose.NanidroidComposeShell
import com.cattailsw.nanidroid.compose.SurfaceCompositor
import com.cattailsw.nanidroid.compose.SurfacePixelAssets
import com.cattailsw.nanidroid.compose.SurfacePlanRegistry
import com.cattailsw.nanidroid.compose.SurfaceRenderBase
import com.cattailsw.nanidroid.compose.SurfaceRenderFrame
import com.cattailsw.nanidroid.compose.SurfaceRenderLayer
import com.cattailsw.nanidroid.compose.SurfaceRenderPlan
import com.cattailsw.nanidroid.SurfaceTransparencyPolicy
import com.cattailsw.nanidroid.runtime.GhostPresentationReducer
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource
import com.cattailsw.nanidroid.runtime.stage.StageDisplayFeature
import com.cattailsw.nanidroid.runtime.stage.StageDpRect
import com.cattailsw.nanidroid.runtime.stage.StageEnvironment
import com.cattailsw.nanidroid.runtime.stage.StageInputCapabilities
import com.cattailsw.nanidroid.runtime.stage.StagePosture
import com.cattailsw.nanidroid.runtime.stage.SurfaceKey
import com.cattailsw.nanidroid.surface.CollisionShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RenderedTransformContractTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun portraitAndLandscapePublishFreshAtomicSnapshotsWithStablePeerSizing() {
        val landscapeViewport = mutableStateOf(false)
        val (selected, sourceOnlyBase) = sourceOnlyBasePair()
        val sakura = mutableStateOf(selected)
        val kero = surface(5, 7, 1, collisionId = 12)
        val measureState = GhostStageMeasureState()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                val size = if (landscapeViewport.value) IntSize(720, 360) else IntSize(360, 720)
                Box(Modifier.requiredSize(size.width.dp, size.height.dp)) {
                    MeasuredGhostStageLayout(
                        presentation = presentation(),
                        environmentForSize = { measured -> environment(measured) },
                        measureState = measureState,
                        kero = kero,
                        sakura = sakura.value,
                        modifier = Modifier.fillMaxSize(),
                        surfaceContent = { snapshot ->
                            RenderedSurfaceLayer(
                                snapshot = snapshot,
                                interactionPort = SurfaceInteractionPort {},
                                onSurfaceTap = {},
                                showCollisionOverlay = true,
                            )
                        },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val portrait = requireNotNull(measureState.latest)
        val portraitPeer = requireNotNull(portrait.kero)
        val firstSakura = requireNotNull(portrait.sakura)
        assertSame(sakura.value, firstSakura.composedSurface)
        assertSame(firstSakura.transform, firstSakura.rendererTransform)
        assertSame(firstSakura.transform, firstSakura.pointerTransform)
        assertSame(firstSakura.transform, firstSakura.overlayTransform)
        assertSame(firstSakura.transform, firstSakura.semanticsTransform)
        assertSame(firstSakura.transform, firstSakura.debugTransform)
        assertExactSurfaceEdges(firstSakura)

        composeRule.runOnIdle { sakura.value = sourceOnlyBase }
        composeRule.waitForIdle()
        val replaced = requireNotNull(measureState.latest)
        val replacedSakura = requireNotNull(replaced.sakura)
        val replacedKero = requireNotNull(replaced.kero)
        assertEquals(sourceOnlyBase.image.copyPixels().toList(), replacedSakura.composedSurface.image.copyPixels().toList())
        assertEquals(sourceOnlyBase.canvasSize, replacedSakura.transform.intrinsicSize)
        assertEquals(22, replacedSakura.composedSurface.effectiveCollisions.single().id)
        assertEquals(sourceOnlyBase.surfaceKey, replacedSakura.composedSurface.surfaceKey)
        assertEquals(2L, replacedSakura.composedSurface.revision)
        assertEquals(portraitPeer.transform, replacedKero.transform)

        composeRule.runOnIdle { landscapeViewport.value = true }
        composeRule.waitForIdle()

        val landscape = requireNotNull(measureState.latest)
        val landscapeSakura = requireNotNull(landscape.sakura)
        val landscapeKero = requireNotNull(landscape.kero)
        assertNotSame(firstSakura.transform, landscapeSakura.transform)
        assertNotEquals(firstSakura.transform.renderedBounds, landscapeSakura.transform.renderedBounds)
        assertEquals(22, landscapeSakura.composedSurface.effectiveCollisions.single().id)
        assertEquals(2L, landscapeSakura.composedSurface.revision)
        assertEquals(portraitPeer.composedSurface.surfaceKey, landscapeKero.composedSurface.surfaceKey)
        assertTrue(landscapeKero.transform.scale.isFinite())
        assertExactSurfaceEdges(landscapeSakura)
        assertExactSurfaceEdges(landscapeKero)
    }

    @Test
    fun dialogueOnlyRecompositionKeepsTheComposedPixelsAndDecodeCountStable() {
        var decodeCount = 0
        val plan = SurfaceRenderPlan(
            surfaceId = 1,
            width = 7,
            height = 5,
            base = SurfaceRenderBase.Layers(listOf(SurfaceRenderLayer("base", 0, 0, 7, 5))),
            animations = emptyList(),
            collisions = listOf(
                SurfaceCollision(1, "all", CollisionShape.Rectangle(IntRect(0, 0, 7, 5)), 0),
            ),
            transparencyPolicy = SurfaceTransparencyPolicy.AUTHORED_ALPHA,
        )
        val compositor = SurfaceCompositor(
            SurfacePixelAssets {
                decodeCount++
                SurfacePixelImage.of(7, 5, IntArray(35) { Color.Red.toArgb() })
            },
        )
        val composed = compositor.composeNormal(plan, revision = 1)
        val dialogue = mutableStateOf(presentation())
        val state = GhostStageMeasureState()
        composeRule.setContent {
            MeasuredGhostStageLayout(
                presentation = dialogue.value,
                environmentForSize = { environment(it) },
                measureState = state,
                kero = null,
                sakura = composed,
                surfaceContent = { snapshot ->
                    RenderedSurfaceLayer(snapshot, SurfaceInteractionPort {}, {}, false)
                },
            )
        }
        composeRule.waitForIdle()
        val before = requireNotNull(state.latest?.sakura)
        assertEquals(1, decodeCount)

        composeRule.runOnIdle {
            dialogue.value = GhostPresentationReducer.snapshot(
                sakuraText = "A different dialogue line",
                sakuraSurfaceId = "0",
                sakuraAnimationId = null,
                sakuraBalloonId = "0",
                keroText = "Kero changed too",
                keroSurfaceId = "10",
                keroAnimationId = null,
                keroBalloonId = "0",
            )
        }
        composeRule.waitForIdle()
        val after = requireNotNull(state.latest?.sakura)

        assertEquals(1, decodeCount)
        assertSame(before.composedSurface, after.composedSurface)
        assertSame(before.composedSurface.image, after.composedSurface.image)
        assertEquals(before.transform, after.transform)
    }

    @Test
    fun imageFillsTheExactRoundedDestinationAndOverlayDoesNotInterceptInput() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        var taps = 0
        val overlayVisible = mutableStateOf(false)
        val measureState = GhostStageMeasureState()
        val composed = edgeSentinelSurface()
        composeRule.setContent {
            MeasuredGhostStageLayout(
                presentation = presentation(),
                environmentForSize = { environment(it, density = 1.5f) },
                measureState = measureState,
                kero = null,
                sakura = composed,
                modifier = Modifier.fillMaxSize(),
                surfaceContent = { snapshot ->
                    RenderedSurfaceLayer(
                        snapshot = snapshot,
                        interactionPort = SurfaceInteractionPort(effects::add),
                        onSurfaceTap = { taps++ },
                        showCollisionOverlay = overlayVisible.value,
                    )
                },
            )
        }
        composeRule.waitForIdle()

        val snapshot = requireNotNull(measureState.latest?.sakura)
        val image = composeRule.onNodeWithTag("surface-sakura", useUnmergedTree = true).captureToImage()
        val pixels = image.toPixelMap()
        assertEquals(snapshot.transform.renderedBounds.width, image.width)
        assertEquals(snapshot.transform.renderedBounds.height, image.height)
        assertEquals(Color.Red, pixels[0, 0])
        assertEquals(Color.Green, pixels[image.width - 1, 0])
        assertEquals(Color.Blue, pixels[0, image.height - 1])
        assertEquals(Color.Yellow, pixels[image.width - 1, image.height - 1])

        composeRule.runOnIdle { overlayVisible.value = true }
        composeRule.onNodeWithTag("surface-sakura", useUnmergedTree = true).performClick()
        composeRule.runOnIdle {
            assertEquals(1, taps)
            assertEquals(1, effects.size)
        }
    }

    @Test
    fun exactOverlayPixelsAndPointerResolutionAgreeForEveryCollisionShape() {
        val currentShape = mutableStateOf<CollisionShape>(CollisionShape.Rectangle(IntRect(0, 0, 1, 1)))
        val transform = com.cattailsw.nanidroid.runtime.stage.SurfaceTransformPx(
            intrinsicSize = IntSize(7, 5),
            renderedBounds = IntRect(0, 0, 70, 50),
            scale = 10f,
            stageToRoot = IntOffset(19, 23),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(70.dp, 50.dp).background(Color.White)) {
                    CollisionOverlay(
                        collisions = listOf(SurfaceCollision(1, "shape", currentShape.value, 0)),
                        transform = transform,
                        visible = true,
                        showLabels = false,
                    )
                }
            }
        }
        val shapes = listOf(
            CollisionShape.Rectangle(IntRect(-2, 1, 4, 7)),
            CollisionShape.Ellipse.fromAuthored(-1, 0, 6, 4),
            CollisionShape.Circle.fromAuthored(0, 0, 0),
            CollisionShape.Circle.fromAuthored(3, 2, 3),
            CollisionShape.Polygon(
                listOf(IntOffset(-1, 0), IntOffset(7, 4), IntOffset(-1, 4), IntOffset(7, 0)),
            ),
        )

        shapes.forEach { shape ->
            composeRule.runOnIdle { currentShape.value = shape }
            composeRule.waitForIdle()
            val image = composeRule.onNodeWithTag("collision-overlay", useUnmergedTree = true).captureToImage()
            val pixels = image.toPixelMap()
            for (intrinsicY in 0 until transform.intrinsicSize.height) {
                for (intrinsicX in 0 until transform.intrinsicSize.width) {
                    val stage = Offset(intrinsicX * 10f + 5f, intrinsicY * 10f + 5f)
                    val expected = shape.contains(IntOffset(intrinsicX, intrinsicY))
                    assertEquals(
                        "overlay shape=$shape intrinsic=($intrinsicX,$intrinsicY)",
                        expected,
                        pixels[stage.x.toInt(), stage.y.toInt()] != Color.White,
                    )
                    val surface = surfaceWithCollision(shape)
                    val resolution = SurfacePointerInteractionMapper.map(
                        SurfaceSpeaker.SAKURA,
                        surface,
                        transform,
                        SurfacePointerPosition(stage.x, stage.y),
                        PointerSource.TOUCH,
                    )
                    assertEquals(
                        "pointer shape=$shape stage=$stage",
                        expected,
                        resolution is SurfacePointerResolution.Hit &&
                            resolution.target is SurfaceHitTarget.Collision,
                    )
                }
            }
            val overlayEdgeProbes = buildList {
                for (x in 1 until transform.intrinsicSize.width) {
                    val boundary = x * 10
                    for (y in 0 until transform.intrinsicSize.height) {
                        add(IntOffset(boundary - 3, y * 10 + 5))
                        add(IntOffset(boundary + 3, y * 10 + 5))
                    }
                }
                for (y in 1 until transform.intrinsicSize.height) {
                    val boundary = y * 10
                    for (x in 0 until transform.intrinsicSize.width) {
                        add(IntOffset(x * 10 + 5, boundary - 3))
                        add(IntOffset(x * 10 + 5, boundary + 3))
                    }
                }
            }
            overlayEdgeProbes.forEach { pixel ->
                val stage = Offset(pixel.x.toFloat(), pixel.y.toFloat())
                val expected = transform.toIntrinsic(stage)?.let(shape::contains) == true
                assertEquals(
                    "overlay edge shape=$shape pixel=$pixel",
                    expected,
                    pixels[pixel.x, pixel.y] != Color.White,
                )
            }
            edgeProbes(transform).forEach { stage ->
                val expected = transform.toIntrinsic(stage)?.let(shape::contains) == true
                val resolution = SurfacePointerInteractionMapper.map(
                    SurfaceSpeaker.SAKURA,
                    surfaceWithCollision(shape),
                    transform,
                    SurfacePointerPosition(stage.x, stage.y),
                    PointerSource.TOUCH,
                )
                assertEquals(
                    "edge shape=$shape stage=$stage",
                    expected,
                    resolution is SurfacePointerResolution.Hit &&
                        resolution.target is SurfaceHitTarget.Collision,
                )
            }
        }
    }

    @Test
    fun collisionOverlayIsDecorativeAndTinyLabelCanvasCannotThrow() {
        val transform = com.cattailsw.nanidroid.runtime.stage.SurfaceTransformPx(
            intrinsicSize = IntSize(4, 1),
            renderedBounds = IntRect(0, 0, 120, 12),
            scale = 12f,
            stageToRoot = IntOffset.Zero,
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(120.dp, 12.dp)) {
                    CollisionOverlay(
                        collisions = listOf(
                            SurfaceCollision(7, "label", CollisionShape.Rectangle(IntRect(0, 0, 4, 1)), 0),
                        ),
                        transform = transform,
                        visible = true,
                    )
                }
            }
        }

        composeRule.onNodeWithTag("collision-overlay", useUnmergedTree = true)
            .assertExists()
            .assert(hasNoClickAction())
            .captureToImage()
        composeRule.onNodeWithTag("surface-sakura", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun sameCanvasRevisionDuringAnInProgressTapUsesLatestAtomicSurfaceWithoutCancellation() {
        val current = mutableStateOf(surface(10, 10, 1, collisionId = 31))
        val effects = mutableListOf<SurfaceInteractionEffect>()
        var taps = 0
        val state = GhostStageMeasureState()
        composeRule.setContent {
            MeasuredGhostStageLayout(
                presentation = presentation(),
                environmentForSize = { environment(it) },
                measureState = state,
                kero = null,
                sakura = current.value,
                surfaceContent = { snapshot ->
                    RenderedSurfaceLayer(
                        snapshot,
                        SurfaceInteractionPort(effects::add),
                        { taps++ },
                        showCollisionOverlay = false,
                    )
                },
            )
        }
        composeRule.waitForIdle()
        val node = composeRule.onNodeWithTag("surface-sakura", useUnmergedTree = true)

        node.performTouchInput { down(center) }
        composeRule.runOnIdle {
            current.value = current.value.copy(
                effectiveCollisions = listOf(
                    SurfaceCollision(99, "latest", CollisionShape.Rectangle(IntRect(0, 0, 10, 10)), 0),
                ),
                revision = 2,
            )
        }
        composeRule.waitForIdle()
        node.performTouchInput { up() }

        composeRule.runOnIdle {
            assertEquals(1, taps)
            assertEquals(1, effects.size)
            assertEquals(99, effects.single().diagnosticCollisionId)
            assertEquals("latest", effects.single().collisionIdentifier)
        }
    }

    @Test
    fun overlayRootGeometryAndPointerInverseUseTheSameHalfOpenEdges() {
        val state = GhostStageMeasureState()
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
                MeasuredGhostStageLayout(
                    presentation = presentation(),
                    environmentForSize = { environment(it) },
                    measureState = state,
                    kero = null,
                    sakura = surface(10, 10, 4, collisionId = 9),
                    stageToRoot = IntOffset(13, 17),
                    surfaceContent = { snapshot ->
                        RenderedSurfaceLayer(snapshot, SurfaceInteractionPort {}, {}, true)
                    },
                )
            }
        }
        composeRule.waitForIdle()

        val snapshot = requireNotNull(state.latest?.sakura)
        val stageOrigin = snapshot.transform.stagePositionFromLocal(Offset.Zero)
        val localOriginHit = SurfacePointerInteractionMapper.map(
            snapshot.speaker,
            snapshot.composedSurface,
            snapshot.transform,
            stageOrigin,
            PointerSource.TOUCH,
        ) as SurfacePointerResolution.Hit
        assertEquals(IntOffset.Zero, localOriginHit.effect.intrinsic)
        if (snapshot.transform.renderedBounds.left != 0 || snapshot.transform.renderedBounds.top != 0) {
            assertSame(
                SurfacePointerResolution.OutsideSurface,
                SurfacePointerInteractionMapper.map(
                    snapshot.speaker,
                    snapshot.composedSurface,
                    snapshot.transform,
                    SurfacePointerPosition(0f, 0f),
                    PointerSource.TOUCH,
                ),
            )
        }
        val collision = snapshot.composedSurface.effectiveCollisions.single().shape
        val root = snapshot.transform.toRoot(collision).bounds
        val inside = androidx.compose.ui.geometry.Offset(root.left, root.top)
        val outsideRight = androidx.compose.ui.geometry.Offset(root.right, root.top)
        assertTrue(snapshot.composedSurface.effectiveCollisions.single().shape.contains(
            requireNotNull(snapshot.transform.rootToIntrinsic(inside)),
        ))
        val intrinsicAtRight = snapshot.transform.rootToIntrinsic(outsideRight)
        if (intrinsicAtRight != null) {
            assertFalse(snapshot.composedSurface.effectiveCollisions.single().shape.contains(intrinsicAtRight))
        }
    }

    @Test
    fun toolbarAndDebugVisibilityDoNotChangeTheMeasuredStageAtFixedWindowGeometry() {
        val toolbar = mutableStateOf(true)
        val debug = mutableStateOf(false)
        val state = GhostStageMeasureState()
        composeRule.setContent {
            NanidroidComposeShell(
                ghostStage = {
                    MeasuredGhostStageLayout(
                        presentation = presentation(),
                        environmentForSize = { environment(it) },
                        measureState = state,
                        kero = surface(5, 7, 1, collisionId = 1),
                        sakura = surface(7, 5, 1, collisionId = 2),
                    )
                },
                loading = false,
                progressMessage = "",
                toolbarVisible = toolbar.value,
                showDebugControls = debug.value,
                onListGhost = {},
                onUpdate = {},
                onPreferences = {},
                onHelp = {},
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }
        composeRule.waitForIdle()
        val before = requireNotNull(state.latest)
        composeRule.onNodeWithTag("debug-next-surface", useUnmergedTree = true).assertDoesNotExist()

        composeRule.runOnIdle { debug.value = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("debug-next-surface", useUnmergedTree = true).assertExists()
        val withVisibleDebugRow = requireNotNull(state.latest)

        composeRule.runOnIdle { toolbar.value = false }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("debug-next-surface", useUnmergedTree = true).assertDoesNotExist()
        val after = requireNotNull(state.latest)

        assertEquals(before.layoutDp.mode, withVisibleDebugRow.layoutDp.mode)
        assertEquals(before.layoutDp.content, withVisibleDebugRow.layoutDp.content)
        assertEquals(requireNotNull(before.kero).transform.renderedBounds, requireNotNull(withVisibleDebugRow.kero).transform.renderedBounds)
        assertEquals(requireNotNull(before.sakura).transform.renderedBounds, requireNotNull(withVisibleDebugRow.sakura).transform.renderedBounds)
        assertEquals(before.layoutDp.mode, after.layoutDp.mode)
        assertEquals(before.layoutDp.content, after.layoutDp.content)
        assertEquals(requireNotNull(before.kero).transform.renderedBounds, requireNotNull(after.kero).transform.renderedBounds)
        assertEquals(requireNotNull(before.sakura).transform.renderedBounds, requireNotNull(after.sakura).transform.renderedBounds)
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

    private fun environment(size: IntSize, density: Float = 1f) = StageEnvironment(
        safeBounds = StageDpRect(0.dp, 0.dp, (size.width / density).dp, (size.height / density).dp),
        density = density,
        fontScale = 1f,
        canonicalAppBarHeight = 64.dp,
        posture = StagePosture.FLAT,
        displayFeatures = emptyList<StageDisplayFeature>(),
        inputCapabilities = StageInputCapabilities(true, false, false, false),
        ghostKey = "fixture",
    )

    private fun surface(width: Int, height: Int, revision: Long, collisionId: Int): ComposedSurface {
        val pixels = IntArray(width * height) { 0xff202020.toInt() }
        val collision = SurfaceCollision(
            collisionId,
            "collision-$collisionId",
            CollisionShape.Rectangle(IntRect(0, 0, width, height)),
            0,
        )
        return ComposedSurface(
            image = SurfacePixelImage.of(width, height, pixels),
            canvasSize = IntSize(width, height),
            visiblePixelBounds = IntRect(0, 0, width, height),
            effectiveCollisions = listOf(collision),
            surfaceKey = SurfaceKey(collisionId, IntSize(width, height)),
            revision = revision,
            explicitlyHidden = false,
        )
    }

    private fun surfaceWithCollision(shape: CollisionShape): ComposedSurface = ComposedSurface(
        image = SurfacePixelImage.of(7, 5, IntArray(35) { 0xff202020.toInt() }),
        canvasSize = IntSize(7, 5),
        visiblePixelBounds = IntRect(0, 0, 7, 5),
        effectiveCollisions = listOf(SurfaceCollision(1, "shape", shape, 0)),
        surfaceKey = SurfaceKey(1, IntSize(7, 5)),
        revision = 1,
        explicitlyHidden = false,
    )

    private fun edgeProbes(
        transform: com.cattailsw.nanidroid.runtime.stage.SurfaceTransformPx,
    ): List<Offset> {
        val xs = mutableSetOf<Float>()
        val ys = mutableSetOf<Float>()
        for (x in 0..transform.intrinsicSize.width) {
            val edge = transform.renderedBounds.left +
                x * transform.renderedBounds.width.toFloat() / transform.intrinsicSize.width
            xs += edge
            xs += Math.nextAfter(edge, Double.NEGATIVE_INFINITY)
            xs += Math.nextAfter(edge, Double.POSITIVE_INFINITY)
        }
        for (y in 0..transform.intrinsicSize.height) {
            val edge = transform.renderedBounds.top +
                y * transform.renderedBounds.height.toFloat() / transform.intrinsicSize.height
            ys += edge
            ys += Math.nextAfter(edge, Double.NEGATIVE_INFINITY)
            ys += Math.nextAfter(edge, Double.POSITIVE_INFINITY)
        }
        xs += transform.renderedBounds.left - 1f
        xs += transform.renderedBounds.right + 1f
        ys += transform.renderedBounds.top - 1f
        ys += transform.renderedBounds.bottom + 1f
        return xs.flatMap { x -> ys.map { y -> Offset(x, y) } }
    }

    private fun assertExactSurfaceEdges(snapshot: StageSurfaceSnapshot) {
        val bounds = snapshot.transform.renderedBounds
        val rightInside = Math.nextAfter(bounds.right.toFloat(), Double.NEGATIVE_INFINITY)
        val bottomInside = Math.nextAfter(bounds.bottom.toFloat(), Double.NEGATIVE_INFINITY)
        val leftOutside = Math.nextAfter(bounds.left.toFloat(), Double.NEGATIVE_INFINITY)
        val topOutside = Math.nextAfter(bounds.top.toFloat(), Double.NEGATIVE_INFINITY)
        assertEquals(
            IntOffset.Zero,
            snapshot.transform.toIntrinsic(Offset(bounds.left.toFloat(), bounds.top.toFloat())),
        )
        assertEquals(
            IntOffset(snapshot.transform.intrinsicSize.width - 1, snapshot.transform.intrinsicSize.height - 1),
            snapshot.transform.toIntrinsic(Offset(rightInside, bottomInside)),
        )
        assertEquals(null, snapshot.transform.toIntrinsic(Offset(bounds.right.toFloat(), bounds.top.toFloat())))
        assertEquals(null, snapshot.transform.toIntrinsic(Offset(bounds.left.toFloat(), bounds.bottom.toFloat())))
        assertEquals(null, snapshot.transform.toIntrinsic(Offset(leftOutside, bounds.top.toFloat())))
        assertEquals(null, snapshot.transform.toIntrinsic(Offset(bounds.left.toFloat(), topOutside)))
    }

    private fun edgeSentinelSurface(): ComposedSurface {
        val width = 7
        val height = 5
        val pixels = IntArray(width * height) { 0xff000000.toInt() }
        pixels[0] = Color.Red.toArgb()
        pixels[width - 1] = Color.Green.toArgb()
        pixels[(height - 1) * width] = Color.Blue.toArgb()
        pixels[height * width - 1] = Color.Yellow.toArgb()
        return ComposedSurface(
            image = SurfacePixelImage.of(width, height, pixels),
            canvasSize = IntSize(width, height),
            visiblePixelBounds = IntRect(0, 0, width, height),
            effectiveCollisions = listOf(
                SurfaceCollision(1, "all", CollisionShape.Rectangle(IntRect(0, 0, width, height)), 0),
            ),
            surfaceKey = SurfaceKey(1, IntSize(width, height)),
            revision = 1,
            explicitlyHidden = false,
        )
    }

    private fun sourceOnlyBasePair(): Pair<ComposedSurface, ComposedSurface> {
        val selectedCollision = SurfaceCollision(
            11,
            "selected",
            CollisionShape.Rectangle(IntRect(0, 0, 7, 5)),
            0,
        )
        val sourceCollision = SurfaceCollision(
            22,
            "source",
            CollisionShape.Ellipse(IntRect(1, 1, 8, 5)),
            0,
        )
        val selectedPlan = SurfaceRenderPlan(
            surfaceId = 11,
            width = 7,
            height = 5,
            base = SurfaceRenderBase.Layers(listOf(SurfaceRenderLayer("selected", 0, 0, 7, 5))),
            animations = emptyList(),
            collisions = listOf(selectedCollision),
            transparencyPolicy = SurfaceTransparencyPolicy.AUTHORED_ALPHA,
        )
        val sourcePlan = SurfaceRenderPlan(
            surfaceId = 22,
            width = 9,
            height = 6,
            base = SurfaceRenderBase.Layers(listOf(SurfaceRenderLayer("source", 0, 0, 9, 6))),
            animations = emptyList(),
            collisions = listOf(sourceCollision),
            transparencyPolicy = SurfaceTransparencyPolicy.AUTHORED_ALPHA,
        )
        val assets = SurfacePixelAssets { path ->
            when (path) {
                "selected" -> SurfacePixelImage.of(7, 5, IntArray(35) { Color.Red.toArgb() })
                "source" -> SurfacePixelImage.of(9, 6, IntArray(54) { Color.Green.toArgb() })
                else -> null
            }
        }
        val compositor = SurfaceCompositor(assets, SurfacePlanRegistry(listOf(selectedPlan, sourcePlan)))
        return compositor.composeNormal(selectedPlan, revision = 1) to compositor.composeFrame(
            selectedPlan,
            SurfaceRenderFrame.Base("22", null, 0, 0, 16),
            revision = 2,
        )
    }
}
