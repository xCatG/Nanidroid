package com.cattailsw.nanidroid.compose.stage

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.SurfaceCollision
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.GhostPresentationFrame
import com.cattailsw.nanidroid.ShellSurface
import com.cattailsw.nanidroid.SurfaceHitTarget
import com.cattailsw.nanidroid.SurfaceManager
import com.cattailsw.nanidroid.NO_COLLISION
import com.cattailsw.nanidroid.compose.ComposedSurface
import com.cattailsw.nanidroid.compose.ComposeGhostStageHost
import com.cattailsw.nanidroid.compose.SurfaceInteractionPort
import com.cattailsw.nanidroid.compose.SurfacePixelImage
import com.cattailsw.nanidroid.compose.SurfacePointerInteractionMapper
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.compose.NanidroidComposeShell
import com.cattailsw.nanidroid.compose.GhostPresentationStage
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
import com.cattailsw.nanidroid.runtime.stage.BubbleHitRegionRegistry
import com.cattailsw.nanidroid.runtime.stage.StageInputRouter
import com.cattailsw.nanidroid.runtime.stage.StagePosture
import com.cattailsw.nanidroid.runtime.stage.SurfaceKey
import com.cattailsw.nanidroid.runtime.stage.positiveIntersection
import com.cattailsw.nanidroid.surface.CollisionShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
    fun onePixelKeroRemainsPublishedWithoutShrinkingSakuraAcrossPortraitAndLandscape() {
        val landscapeViewport = mutableStateOf(false)
        val measureState = GhostStageMeasureState()
        val kero = surface(1, 1, revision = 1, collisionId = 12)
        val sakura = surface(427, 640, revision = 1, collisionId = 13)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                val size = if (landscapeViewport.value) IntSize(720, 360) else IntSize(360, 720)
                Box(Modifier.requiredSize(size.width.dp, size.height.dp)) {
                    MeasuredGhostStageLayout(
                        presentation = presentation(),
                        environmentForSize = { measured -> environment(measured) },
                        measureState = measureState,
                        kero = kero,
                        sakura = sakura,
                        modifier = Modifier.fillMaxSize(),
                        surfaceContent = { snapshot ->
                            RenderedSurfaceLayer(snapshot, showCollisionOverlay = false)
                        },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val portrait = requireNotNull(measureState.latest)
        val portraitKero = requireNotNull(portrait.kero)
        val portraitSakura = requireNotNull(portrait.sakura)
        assertEquals(IntSize(1, 1), portraitKero.transform.intrinsicSize)
        assertEquals(IntRect(90, 719, 91, 720), portraitKero.transform.renderedBounds)
        assertEquals(IntSize(427, 640), portraitSakura.transform.intrinsicSize)
        assertEquals(IntRect(180, 450, 360, 720), portraitSakura.transform.renderedBounds)
        assertExactSurfaceEdges(portraitKero)

        composeRule.runOnIdle { landscapeViewport.value = true }
        composeRule.waitForIdle()

        val landscape = requireNotNull(measureState.latest)
        val landscapeKero = requireNotNull(landscape.kero)
        val landscapeSakura = requireNotNull(landscape.sakura)
        assertEquals(IntSize(1, 1), landscapeKero.transform.intrinsicSize)
        assertEquals(IntRect(120, 359, 121, 360), landscapeKero.transform.renderedBounds)
        assertEquals(IntSize(427, 640), landscapeSakura.transform.intrinsicSize)
        assertEquals(IntRect(501, 64, 699, 360), landscapeSakura.transform.renderedBounds)
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
                    RenderedSurfaceLayer(snapshot, false)
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
            val measured = measureState.latest
            val input = StageInputRouter.snapshot(
                blocking = false,
                bubbleRegistry = BubbleHitRegionRegistry.from(emptyList()),
                bubbleGeneration = 0,
                ghostKey = "overlay-input",
                surfaces = listOfNotNull(measured?.sakura),
            )
            StagePointerInput({ input }, effects::add, { taps++ }, Modifier.fillMaxSize()) { _ ->
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
                            showCollisionOverlay = overlayVisible.value,
                        )
                    },
                )
            }
        }
        composeRule.waitForIdle()

        val snapshot = requireNotNull(measureState.latest?.sakura)
        val image = composeRule.onNodeWithTag("surface-sakura", useUnmergedTree = true).captureToImage()
        composeRule.onNodeWithTag("surface-sakura", useUnmergedTree = true)
            .assertContentDescriptionEquals(composeRule.activity.getString(R.string.sakura_character_description))
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
            assertEquals(0, taps)
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

        var observedCyanGuide = false
        fun isMagentaFootprint(color: Color): Boolean =
            color.red > 0.9f && color.blue > 0.9f && color.green < 0.85f
        shapes.forEach { shape ->
            composeRule.runOnIdle { currentShape.value = shape }
            composeRule.waitForIdle()
            val image = composeRule.onNodeWithTag("collision-overlay", useUnmergedTree = true).captureToImage()
            val pixels = image.toPixelMap()
            for (intrinsicY in 0 until transform.intrinsicSize.height) {
                for (intrinsicX in 0 until transform.intrinsicSize.width) {
                    val stage = Offset(intrinsicX * 10f + 5f, intrinsicY * 10f + 5f)
                    val expected = shape.contains(IntOffset(intrinsicX, intrinsicY))
                    val magentaFootprintPixels = (1..8).sumOf { dx ->
                        (1..8).count { dy ->
                            val color = pixels[intrinsicX * 10 + dx, intrinsicY * 10 + dy]
                            isMagentaFootprint(color)
                        }
                    }
                    assertEquals(
                        "overlay shape=$shape intrinsic=($intrinsicX,$intrinsicY)",
                        expected,
                        magentaFootprintPixels > 20,
                    )
                    val surface = surfaceWithCollision(shape)
                    val resolution = SurfacePointerInteractionMapper.map(
                        SurfaceSpeaker.SAKURA,
                        surface,
                        transform,
                        stage,
                        PointerSource.TOUCH,
                    )
                    assertEquals(
                        "pointer shape=$shape stage=$stage",
                        expected,
                        resolution?.target is SurfaceHitTarget.Collision,
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
                    (-1..1).sumOf { dx ->
                        (-1..1).count { dy -> isMagentaFootprint(pixels[pixel.x + dx, pixel.y + dy]) }
                    } > 2,
                )
            }
            edgeProbes(transform).forEach { stage ->
                val expected = transform.toIntrinsic(stage)?.let(shape::contains) == true
                val resolution = SurfacePointerInteractionMapper.map(
                    SurfaceSpeaker.SAKURA,
                    surfaceWithCollision(shape),
                    transform,
                    stage,
                    PointerSource.TOUCH,
                )
                assertEquals(
                    "edge shape=$shape stage=$stage",
                    expected,
                    resolution?.target is SurfaceHitTarget.Collision,
                )
            }
            observedCyanGuide = observedCyanGuide || (0 until image.width).any { x ->
                (0 until image.height).any { y ->
                    val color = pixels[x, y]
                    color.blue > 0.9f && color.green > 0.75f && color.red < 0.9f
                }
            }
        }
        assertTrue("authored cyan guide should be independently visible", observedCyanGuide)
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
    fun realRootCanvasPlacementAndTouchResolveTheSameCollisionCell() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        val state = GhostStageMeasureState()
        val shape = CollisionShape.Rectangle(IntRect(1, 1, 3, 3))
        val composed = coloredSurface(7, 5, Color.White, shape)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(500.dp, 820.dp).background(Color.White)) {
                    Box(Modifier.offset(37.dp, 41.dp).requiredSize(360.dp, 720.dp)) {
                        GhostPresentationStage(
                            presentation = presentation(),
                            sakuraComposedSurface = composed,
                            keroComposedSurface = null,
                            measureState = state,
                            ghostKey = "root-fixture",
                            onSurfaceEffect = effects::add,
                            modifier = Modifier.fillMaxSize(),
                            sakuraSurface = { snapshot ->
                                RenderedSurfaceLayer(snapshot, showCollisionOverlay = true)
                            },
                        )
                    }
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { state.latest?.sakura != null }
        val snapshot = requireNotNull(state.latest?.sakura)
        val node = composeRule.onNodeWithTag("surface-sakura", useUnmergedTree = true)
        val nodeBounds = node.fetchSemanticsNode().boundsInRoot
        assertEquals(snapshot.transform.rootBounds.left.toFloat(), nodeBounds.left, 0.01f)
        assertEquals(snapshot.transform.rootBounds.top.toFloat(), nodeBounds.top, 0.01f)
        assertEquals(snapshot.transform.rootBounds.right.toFloat(), nodeBounds.right, 0.01f)
        assertEquals(snapshot.transform.rootBounds.bottom.toFloat(), nodeBounds.bottom, 0.01f)

        val rootBounds = snapshot.transform.rootBounds
        val rootPoint = Offset(
            rootBounds.left + 1.5f * rootBounds.width / snapshot.transform.intrinsicSize.width,
            rootBounds.top + 1.5f * rootBounds.height / snapshot.transform.intrinsicSize.height,
        )
        val expectedIntrinsic = requireNotNull(snapshot.transform.rootToIntrinsic(rootPoint))
        assertTrue(shape.contains(expectedIntrinsic))
        val rootImage = composeRule.onRoot(useUnmergedTree = true).captureToImage().toPixelMap()
        assertTrue(rootImage[rootPoint.x.toInt(), rootPoint.y.toInt()] != Color.White)

        node.performTouchInput {
            down(Offset(rootPoint.x - nodeBounds.left, rootPoint.y - nodeBounds.top))
            up()
        }
        composeRule.runOnIdle {
            assertEquals(1, effects.size)
            assertEquals(expectedIntrinsic, effects.single().intrinsic)
            assertEquals(1, effects.single().diagnosticCollisionId)
        }

        val outsideRootPoint = Offset(
            rootBounds.left + 6.5f * rootBounds.width / snapshot.transform.intrinsicSize.width,
            rootBounds.top + 0.5f * rootBounds.height / snapshot.transform.intrinsicSize.height,
        )
        val expectedOutsideIntrinsic = requireNotNull(snapshot.transform.rootToIntrinsic(outsideRootPoint))
        assertFalse(shape.contains(expectedOutsideIntrinsic))
        assertEquals(Color.White, rootImage[outsideRootPoint.x.toInt(), outsideRootPoint.y.toInt()])

        node.performTouchInput {
            down(Offset(outsideRootPoint.x - nodeBounds.left, outsideRootPoint.y - nodeBounds.top))
            up()
        }
        composeRule.runOnIdle {
            assertEquals(2, effects.size)
            assertEquals(expectedOutsideIntrinsic, effects.last().intrinsic)
            assertEquals(NO_COLLISION, effects.last().diagnosticCollisionId)
            assertEquals(null, effects.last().collisionIdentifier)
        }
    }

    @Test
    fun measuredBubbleIsVisibleAndSeparatedFromPeerSurfaceAndCollisionFootprint() {
        val state = GhostStageMeasureState()
        val peer = coloredSurface(
            width = 7,
            height = 5,
            color = Color.Red,
            shape = CollisionShape.Rectangle(IntRect(0, 0, 7, 5)),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.offset(19.dp, 23.dp).requiredSize(360.dp, 720.dp)) {
                    MeasuredGhostStageLayout(
                        presentation = presentation(),
                        environmentForSize = { environment(it) },
                        measureState = state,
                        kero = null,
                        sakura = peer,
                        stageToRoot = IntOffset(19, 23),
                        showKeroBalloon = true,
                        keroBalloon = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Green)
                                    .testTag("measured-kero-balloon"),
                            )
                        },
                        surfaceContent = { snapshot ->
                            RenderedSurfaceLayer(snapshot, false)
                        },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val bubble = composeRule.onNodeWithTag("measured-kero-balloon", useUnmergedTree = true)
        val surfaceNode = composeRule.onNodeWithTag("surface-sakura", useUnmergedTree = true)
        val bubbleBounds = bubble.fetchSemanticsNode().boundsInRoot
        val surfaceBounds = surfaceNode.fetchSemanticsNode().boundsInRoot
        assertFalse(bubbleBounds.overlaps(surfaceBounds))
        assertEquals(Color.Green, bubble.captureToImage().toPixelMap()[1, 1])
        assertEquals(Color.Red, surfaceNode.captureToImage().toPixelMap()[1, 1])

        val snapshot = requireNotNull(state.latest?.sakura)
        val bubblePx = IntRect(
            bubbleBounds.left.toInt(),
            bubbleBounds.top.toInt(),
            bubbleBounds.right.toInt(),
            bubbleBounds.bottom.toInt(),
        )
        snapshot.transform.toRootRegion(peer.effectiveCollisions.single().shape).rects.forEach { collision ->
            val collisionPx = IntRect(
                collision.left.toInt(),
                collision.top.toInt(),
                collision.right.toInt(),
                collision.bottom.toInt(),
            )
            assertFalse(bubblePx.positiveIntersection(collisionPx))
        }
    }

    @Test
    fun productionHostSourceOnlyBaseFrameChangesCanvasAndCollisionAndReusesDecodedPixels() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        val loads = ConcurrentHashMap<String, AtomicInteger>()
        val assets = SurfacePixelAssets { path ->
            loads.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            when (path) {
                "selected" -> SurfacePixelImage.of(7, 5, IntArray(35) { Color.Red.toArgb() })
                "source" -> SurfacePixelImage.of(9, 6, IntArray(54) { Color.Green.toArgb() })
                "kero" -> SurfacePixelImage.of(1, 1, intArrayOf(Color.Blue.toArgb()))
                else -> null
            }
        }
        val selected = shellSurface(0, 7, 5, "selected", collisionId = 1)
        val source = shellSurface(22, 9, 6, "source", collisionId = 22)
        val kero = shellSurface(10, 1, 1, "kero", collisionId = 10)
        val frame = selected.AnimationFrame().apply {
            sid = "22"
            frameType = ShellSurface.TYPE_BASE
            time = 10_000
            W = 9
            H = 6
        }
        selected.animationTable = mutableMapOf(
            "7" to selected.Animation("7", ShellSurface.A_TYPE_RUNONCE).apply {
                frames = mutableListOf(frame)
            },
        )
        val manager = SurfaceManager("host-fixture").apply {
            addSurface("0", selected)
            addSurface("10", kero)
            addSurface("22", source)
        }
        val host = ComposeGhostStageHost(SurfaceInteractionPort(effects::add), assets).apply {
            setSurfaceManager(manager, "rendered-transform-fixture")
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(360.dp, 720.dp)) {
                    host.Stage(Modifier.fillMaxSize())
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            host.latestMeasuredSnapshot?.sakura?.composedSurface?.canvasSize == IntSize(7, 5)
        }
        val selectedSnapshot = requireNotNull(host.latestMeasuredSnapshot?.sakura)
        val selectedSurface = selectedSnapshot.composedSurface
        assertEquals(Color.Red.toArgb(), selectedSurface.image.pixelAt(3, 2))
        composeRule.runOnIdle {
            assertEquals(1, loads.getValue("selected").get())
            assertEquals(1, loads.getValue("kero").get())
        }

        fun render(text: String) {
            host.renderer.render(
                GhostPresentationFrame(
                    GhostPresentationFrame.Speaker(text, "0", "7", "-1"),
                    GhostPresentationFrame.Speaker("", "10", null, "-1"),
                    false,
                ),
            )
        }
        composeRule.runOnIdle { render("first") }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            host.latestMeasuredSnapshot?.sakura?.composedSurface?.canvasSize == IntSize(9, 6)
        }
        val sourceSnapshot = requireNotNull(host.latestMeasuredSnapshot?.sakura)
        val sourceSurface = sourceSnapshot.composedSurface
        val sourceImage = sourceSurface.image
        assertNotSame(selectedSurface, sourceSurface)
        assertEquals(Color.Green.toArgb(), sourceImage.pixelAt(4, 3))
        assertEquals(IntSize(9, 6), IntSize(sourceImage.width, sourceImage.height))
        assertEquals(IntSize(9, 6), sourceSurface.canvasSize)
        assertEquals(IntSize(9, 6), sourceSnapshot.transform.intrinsicSize)
        assertEquals(22, sourceSurface.surfaceKey.surfaceId)
        assertEquals(IntSize(9, 6), sourceSurface.surfaceKey.canvasSize)
        assertEquals(listOf(22), sourceSurface.effectiveCollisions.map { it.id })
        assertTrue(sourceSurface.revision > selectedSurface.revision)
        composeRule.runOnIdle { assertEquals(1, loads.getValue("source").get()) }

        val node = composeRule.onNodeWithTag("surface-sakura", useUnmergedTree = true)
        val nodeBounds = node.fetchSemanticsNode().boundsInRoot
        val collision = sourceSnapshot.transform.toRootRegion(
            sourceSnapshot.composedSurface.effectiveCollisions.single().shape,
        ).rects.single()
        val collisionRootPoint = Offset(
            ((collision.left + collision.right) / 2.0).toFloat(),
            ((collision.top + collision.bottom) / 2.0).toFloat(),
        )
        node.performTouchInput {
            down(Offset(collisionRootPoint.x - nodeBounds.left, collisionRootPoint.y - nodeBounds.top))
            up()
        }
        composeRule.runOnIdle {
            assertEquals(22, effects.single().diagnosticCollisionId)
            render("dialogue changed")
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val afterDialogue = requireNotNull(host.latestMeasuredSnapshot?.sakura?.composedSurface)
            assertSame(sourceSurface, afterDialogue)
            assertSame(sourceImage, afterDialogue.image)
            assertEquals(1, loads.getValue("source").get())
        }
    }

    @Test
    fun productionHostReusesComposedSurfaceForEqualResetFramesAcrossAnimationRequests() {
        val assets = SurfacePixelAssets { path ->
            when (path) {
                "sakura" -> SurfacePixelImage.of(7, 5, IntArray(35) { Color.Red.toArgb() })
                "kero" -> SurfacePixelImage.of(1, 1, intArrayOf(Color.Blue.toArgb()))
                else -> null
            }
        }
        val sakura = shellSurface(0, 7, 5, "sakura", collisionId = 1).apply {
            animationTable = mutableMapOf(
                "7" to Animation("7", ShellSurface.A_TYPE_RUNONCE).apply {
                    frames = mutableListOf(AnimationFrame().apply {
                        frameType = ShellSurface.TYPE_RESET
                        time = 10_000
                    })
                },
                "8" to Animation("8", ShellSurface.A_TYPE_RUNONCE).apply {
                    frames = mutableListOf(AnimationFrame().apply {
                        frameType = ShellSurface.TYPE_RESET
                        time = 10_000
                    })
                },
            )
        }
        val manager = SurfaceManager("animation-switch-fixture").apply {
            addSurface("0", sakura)
            addSurface("10", shellSurface(10, 1, 1, "kero", collisionId = 10))
        }
        val host = ComposeGhostStageHost(SurfaceInteractionPort { }, assets).apply {
            setSurfaceManager(manager, "animation-switch-fixture")
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(360.dp, 720.dp)) {
                    host.Stage(Modifier.fillMaxSize())
                }
            }
        }

        fun render(animationId: String) {
            host.renderer.render(
                GhostPresentationFrame(
                    GhostPresentationFrame.Speaker("", "0", animationId, "-1"),
                    GhostPresentationFrame.Speaker("", "10", null, "-1"),
                    false,
                ),
            )
        }

        composeRule.runOnIdle { render("7") }
        composeRule.waitForIdle()
        val firstSurface = requireNotNull(host.latestMeasuredSnapshot?.sakura).composedSurface
        assertEquals("7", host.renderer.state.presentation.sakura.animationId)

        composeRule.runOnIdle { render("8") }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals("8", host.renderer.state.presentation.sakura.animationId)
            assertSame(firstSurface, requireNotNull(host.latestMeasuredSnapshot?.sakura).composedSurface)
        }
    }

    @Test
    fun collisionGeometryChangeDuringAnInProgressTapCancelsTheGesture() {
        val current = mutableStateOf(surface(10, 10, 1, collisionId = 31))
        val effects = mutableListOf<SurfaceInteractionEffect>()
        var taps = 0
        val state = GhostStageMeasureState()
        composeRule.setContent {
            GhostPresentationStage(
                presentation = presentation(),
                measureState = state,
                keroComposedSurface = null,
                sakuraComposedSurface = current.value,
                ghostKey = "geometry-change",
                onSurfaceEffect = effects::add,
                onToggleChrome = { taps++ },
                modifier = Modifier.fillMaxSize(),
                sakuraSurface = { snapshot -> RenderedSurfaceLayer(snapshot, false) },
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
            assertEquals(0, taps)
            assertTrue(effects.isEmpty())
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
                        RenderedSurfaceLayer(snapshot, true)
                    },
                )
            }
        }
        composeRule.waitForIdle()

        val snapshot = requireNotNull(state.latest?.sakura)
        val renderedBounds = snapshot.transform.renderedBounds
        val stageOrigin = Offset(renderedBounds.left.toFloat(), renderedBounds.top.toFloat())
        val localOriginHit = requireNotNull(SurfacePointerInteractionMapper.map(
            snapshot.speaker,
            snapshot.composedSurface,
            snapshot.transform,
            stageOrigin,
            PointerSource.TOUCH,
        ))
        assertEquals(IntOffset.Zero, localOriginHit.effect.intrinsic)
        if (renderedBounds.left != 0 || renderedBounds.top != 0) {
            assertNull(
                SurfacePointerInteractionMapper.map(
                    snapshot.speaker,
                    snapshot.composedSurface,
                    snapshot.transform,
                    Offset.Zero,
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
    fun toolbarVisibilityDoesNotChangeTheMeasuredStageAtFixedWindowGeometry() {
        val toolbar = mutableStateOf(true)
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
                onListGhost = {},
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }
        composeRule.waitForIdle()
        val before = requireNotNull(state.latest)
        composeRule.runOnIdle { toolbar.value = false }
        composeRule.waitForIdle()
        val after = requireNotNull(state.latest)

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

    private fun coloredSurface(
        width: Int,
        height: Int,
        color: Color,
        shape: CollisionShape,
    ): ComposedSurface = ComposedSurface(
        image = SurfacePixelImage.of(width, height, IntArray(width * height) { color.toArgb() }),
        canvasSize = IntSize(width, height),
        visiblePixelBounds = IntRect(0, 0, width, height),
        effectiveCollisions = listOf(SurfaceCollision(1, "shape", shape, 0)),
        surfaceKey = SurfaceKey(1, IntSize(width, height)),
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

    private fun shellSurface(
        id: Int,
        width: Int,
        height: Int,
        path: String,
        collisionId: Int,
    ) = ShellSurface().apply {
        surfaceId = id
        surfaceType = ShellSurface.S_TYPE_BASE
        origW = width
        origH = height
        selfFilename = path
        transparencyPolicy = SurfaceTransparencyPolicy.AUTHORED_ALPHA
        setCanonicalCollisions(
            listOf(
                SurfaceCollision(
                    collisionId,
                    "collision-$collisionId",
                    CollisionShape.Rectangle(IntRect(0, 0, minOf(2, width), minOf(2, height))),
                    0,
                ),
            ),
        )
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
