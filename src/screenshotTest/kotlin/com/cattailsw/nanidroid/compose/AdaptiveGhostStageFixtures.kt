package com.cattailsw.nanidroid.compose

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.SurfaceCollision
import com.cattailsw.nanidroid.SurfaceDefinition
import com.cattailsw.nanidroid.install.ArchiveInstallFailure
import com.cattailsw.nanidroid.install.ForegroundNarImportState
import com.cattailsw.nanidroid.install.NarImportAttemptToken
import com.cattailsw.nanidroid.runtime.GhostPresentationReducer
import com.cattailsw.nanidroid.runtime.GhostPresentationState
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.stage.StageDisplayFeature
import com.cattailsw.nanidroid.runtime.stage.StageDpRect
import com.cattailsw.nanidroid.runtime.stage.StageMode
import com.cattailsw.nanidroid.runtime.stage.StagePosture
import com.cattailsw.nanidroid.surface.CollisionShape

internal data class StageScreenshotCase(
    val name: String,
    val windowSizeDp: DpSize,
    val expectedSafeStageDp: DpSize,
    val fontScale: Float,
    val densityDpi: Int,
    val theme: ScreenshotTheme,
    val layoutDirection: LayoutDirection,
    val posture: StagePosture,
    val expectedInvariants: Set<ScreenshotInvariant>,
    val state: StageFixtureState,
)

enum class ScreenshotTheme {
    LIGHT,
    DARK,
}

enum class ScreenshotInvariant {
    KERO_LEFT,
    SAKURA_RIGHT,
    CENTER_SPLIT,
    NO_CLIP,
    TINY_ONLY,
}

internal data class StageFixtureState(
    val presentation: GhostPresentationState,
    val sakura: ScreenshotSurfaceFixture,
    val kero: ScreenshotSurfaceFixture,
    val collisionOverlaySpeaker: SurfaceSpeaker? = null,
    val narImportState: ForegroundNarImportState,
    val displayFeatures: List<StageDisplayFeature> = emptyList(),
)

data class ScreenshotSurfaceFixture(
    val definition: SurfaceDefinition,
    val image: SurfacePixelImage,
)

private const val CANONICAL_APP_BAR_HEIGHT_DP = 64f

internal val ADAPTIVE_GHOST_STAGE_SCREENSHOT_CASES: List<StageScreenshotCase> = run {
    val sakuraStandard = surfaceFixture(
        id = 0,
        width = 180,
        height = 220,
        color = 0xFF5D86DB,
        collisions = emptyList(),
    )
    val keroStandard = surfaceFixture(
        id = 10,
        width = 140,
        height = 220,
        color = 0xFFFFA94A,
        collisions = emptyList(),
    )
    val sakuraOverlay = surfaceFixture(
        id = 0,
        width = 240,
        height = 260,
        color = 0xFF7AA8D8,
        collisions = listOf(
            SurfaceCollision(
                id = 0,
                identifier = "overlay-rect",
                shape = CollisionShape.Rectangle.fromAuthored(20, 20, 150, 120),
                authoredOrder = 0,
            ),
            SurfaceCollision(
                id = 1,
                identifier = "overlay-ellipse",
                shape = CollisionShape.Ellipse.fromAuthored(40, 140, 190, 220),
                authoredOrder = 1,
            ),
            SurfaceCollision(
                id = 2,
                identifier = "overlay-poly",
                shape = CollisionShape.Polygon(
                    listOf(
                        IntOffset(20, 180),
                        IntOffset(110, 190),
                        IntOffset(200, 210),
                        IntOffset(175, 250),
                        IntOffset(40, 250),
                    ),
                ),
                authoredOrder = 2,
            ),
        ),
    )

    val importToken = NarImportAttemptToken(
        processNonce = "screenshot-fixture",
        sequence = 1L,
    )
    val importInstalling = ForegroundNarImportState.Installing(
        token = importToken,
        phase = "extracting",
        completed = 24L,
    )
    val importFailed = ForegroundNarImportState.Failed(
        token = importToken,
        message = "The selected document is not a valid ghost archive.",
        failure = ArchiveInstallFailure.InvalidArchive,
    )

    val gridCases = listOf(400f, 610f, 900f).flatMap { width ->
        listOf(400f, 500f, 1000f).map { height ->
            stageCase(
                name = "grid_${width.toInt()}x${height.toInt()}",
                window = DpSize(width.dp, height.dp),
                fontScale = 1f,
                densityDpi = 320,
                theme = ScreenshotTheme.LIGHT,
                layoutDirection = LayoutDirection.Ltr,
                posture = StagePosture.FLAT,
                invariants = setOf(ScreenshotInvariant.KERO_LEFT, ScreenshotInvariant.SAKURA_RIGHT),
                state = StageFixtureState(
                    presentation = presentationState(
                        sakuraText = "Grid ${width.toInt()}x${height.toInt()}",
                        keroText = "",
                        sakuraBalloon = true,
                        keroBalloon = false,
                        sakuraSurfaceId = sakuraStandard.definition.id,
                        keroSurfaceId = keroStandard.definition.id,
                    ),
                    sakura = sakuraStandard,
                    kero = keroStandard,
                    narImportState = ForegroundNarImportState.Idle,
                ),
            )
        }
    }

    val foldFeatures = listOf(
        StageDisplayFeature(
            bounds = StageDpRect(303.dp, 0.dp, 307.dp, 500.dp),
            separating = true,
            occluding = false,
        ),
    )

    val productCases = listOf(
        stageCase(
            name = "phone_portrait_one_bubble",
            window = DpSize(360.dp, 720.dp),
            posture = StagePosture.FLAT,
            invariants = setOf(ScreenshotInvariant.KERO_LEFT, ScreenshotInvariant.SAKURA_RIGHT),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "One bubble only.",
                    keroText = "",
                    sakuraBalloon = true,
                    keroBalloon = false,
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = ForegroundNarImportState.Idle,
            ),
        ),
        stageCase(
            name = "phone_portrait_two_bubbles",
            window = DpSize(360.dp, 720.dp),
            posture = StagePosture.FLAT,
            invariants = setOf(
                ScreenshotInvariant.KERO_LEFT,
                ScreenshotInvariant.SAKURA_RIGHT,
            ),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "Sakura text.",
                    keroText = "Kero text.",
                    sakuraBalloon = true,
                    keroBalloon = true,
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = ForegroundNarImportState.Idle,
            ),
        ),
        stageCase(
            name = "compact_landscape_empty",
            window = DpSize(720.dp, 360.dp),
            posture = StagePosture.FLAT,
            invariants = setOf(ScreenshotInvariant.NO_CLIP),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "",
                    keroText = "",
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = ForegroundNarImportState.Idle,
            ),
        ),
        stageCase(
            name = "compact_landscape_one",
            window = DpSize(720.dp, 360.dp),
            posture = StagePosture.FLAT,
            invariants = setOf(ScreenshotInvariant.NO_CLIP),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "Single line.",
                    keroText = "",
                    sakuraBalloon = true,
                    keroBalloon = false,
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = ForegroundNarImportState.Idle,
            ),
        ),
        stageCase(
            name = "compact_landscape_two",
            window = DpSize(720.dp, 360.dp),
            posture = StagePosture.FLAT,
            invariants = setOf(
                ScreenshotInvariant.KERO_LEFT,
                ScreenshotInvariant.SAKURA_RIGHT,
                ScreenshotInvariant.CENTER_SPLIT,
            ),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "Left side.",
                    keroText = "Right side.",
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = ForegroundNarImportState.Idle,
            ),
        ),
        stageCase(
            name = "compact_landscape_long",
            window = DpSize(720.dp, 360.dp),
            posture = StagePosture.FLAT,
            invariants = setOf(
                ScreenshotInvariant.KERO_LEFT,
                ScreenshotInvariant.SAKURA_RIGHT,
                ScreenshotInvariant.CENTER_SPLIT,
            ),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "Sakura long bubble text to stress-wrap and verify compact constraints in compact landscape while preserving readability and lane width.",
                    keroText = "Kero also has a long bubble, testing lane geometry and clipping behavior under compact constraints.",
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = ForegroundNarImportState.Idle,
            ),
        ),
        stageCase(
            name = "tall_phone_two",
            window = DpSize(400.dp, 1000.dp),
            posture = StagePosture.FLAT,
            invariants = setOf(ScreenshotInvariant.KERO_LEFT, ScreenshotInvariant.SAKURA_RIGHT),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "Sakura tall layout.",
                    keroText = "Kero response.",
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = ForegroundNarImportState.Idle,
            ),
        ),
        stageCase(
            name = "tablet_portrait",
            window = DpSize(800.dp, 1280.dp),
            posture = StagePosture.FLAT,
            invariants = setOf(ScreenshotInvariant.KERO_LEFT, ScreenshotInvariant.SAKURA_RIGHT),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "Tablet portrait.",
                    keroText = "Tablet second bubble.",
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = ForegroundNarImportState.Idle,
            ),
        ),
        stageCase(
            name = "tablet_landscape",
            window = DpSize(1280.dp, 800.dp),
            posture = StagePosture.FLAT,
            invariants = setOf(ScreenshotInvariant.KERO_LEFT, ScreenshotInvariant.SAKURA_RIGHT),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "Tablet landscape.",
                    keroText = "Tablet side-by-side.",
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = ForegroundNarImportState.Idle,
            ),
        ),
        stageCase(
            name = "foldable_flat",
            window = DpSize(610.dp, 500.dp),
            posture = StagePosture.FLAT,
            invariants = setOf(ScreenshotInvariant.KERO_LEFT, ScreenshotInvariant.SAKURA_RIGHT),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "Foldable flat baseline.",
                    keroText = "Foldable flat counterpart.",
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = ForegroundNarImportState.Idle,
            ),
        ),
        stageCase(
            name = "foldable_vertical_separating",
            window = DpSize(610.dp, 500.dp),
            posture = StagePosture.BOOK,
            invariants = setOf(
                ScreenshotInvariant.KERO_LEFT,
                ScreenshotInvariant.SAKURA_RIGHT,
                ScreenshotInvariant.CENTER_SPLIT,
            ),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "Folding posture baseline.",
                    keroText = "Separated by fold.",
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = ForegroundNarImportState.Idle,
                displayFeatures = foldFeatures,
            ),
        ),
        stageCase(
            name = "tiny_wide",
            window = DpSize(480.dp, 230.dp),
            posture = StagePosture.FLAT,
            invariants = setOf(ScreenshotInvariant.TINY_ONLY),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "",
                    keroText = "",
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = ForegroundNarImportState.Idle,
            ),
        ),
        stageCase(
            name = "tiny_tall",
            window = DpSize(230.dp, 400.dp),
            posture = StagePosture.FLAT,
            invariants = setOf(ScreenshotInvariant.TINY_ONLY),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "",
                    keroText = "",
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = ForegroundNarImportState.Idle,
            ),
        ),
        stageCase(
            name = "import_installing",
            window = DpSize(360.dp, 720.dp),
            posture = StagePosture.FLAT,
            invariants = setOf(
                ScreenshotInvariant.KERO_LEFT,
                ScreenshotInvariant.SAKURA_RIGHT,
            ),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "Sakura importing.",
                    keroText = "",
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = importInstalling,
            ),
        ),
        stageCase(
            name = "import_failed",
            window = DpSize(400.dp, 1000.dp),
            posture = StagePosture.FLAT,
            invariants = setOf(
                ScreenshotInvariant.KERO_LEFT,
                ScreenshotInvariant.SAKURA_RIGHT,
            ),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "Sakura import failed.",
                    keroText = "",
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = importFailed,
            ),
        ),
        stageCase(
            name = "collision_shapes_combined",
            window = DpSize(610.dp, 500.dp),
            posture = StagePosture.FLAT,
            invariants = setOf(ScreenshotInvariant.KERO_LEFT, ScreenshotInvariant.SAKURA_RIGHT),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "Collision geometry check.",
                    keroText = "With many collisions.",
                    sakuraSurfaceId = sakuraOverlay.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraOverlay,
                kero = keroStandard,
                collisionOverlaySpeaker = SurfaceSpeaker.SAKURA,
                narImportState = ForegroundNarImportState.Idle,
            ),
        ),
    )

    val pairwiseCases = listOf(
        stageCase(
            name = "pair_ltr_light_f100_d160",
            window = DpSize(900.dp, 500.dp),
            fontScale = 1.0f,
            densityDpi = 160,
            theme = ScreenshotTheme.LIGHT,
            layoutDirection = LayoutDirection.Ltr,
            posture = StagePosture.FLAT,
            invariants = setOf(ScreenshotInvariant.KERO_LEFT, ScreenshotInvariant.SAKURA_RIGHT),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "Pairwise text.",
                    keroText = "",
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = ForegroundNarImportState.Idle,
            ),
        ),
        stageCase(
            name = "pair_rtl_dark_f100_d320",
            window = DpSize(900.dp, 500.dp),
            fontScale = 1.0f,
            densityDpi = 320,
            theme = ScreenshotTheme.DARK,
            layoutDirection = LayoutDirection.Rtl,
            posture = StagePosture.FLAT,
            invariants = setOf(ScreenshotInvariant.KERO_LEFT, ScreenshotInvariant.SAKURA_RIGHT),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "Pairwise rtl text.",
                    keroText = "",
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = ForegroundNarImportState.Idle,
            ),
        ),
        stageCase(
            name = "pair_ltr_dark_f150_d320",
            window = DpSize(900.dp, 500.dp),
            fontScale = 1.5f,
            densityDpi = 320,
            theme = ScreenshotTheme.DARK,
            layoutDirection = LayoutDirection.Ltr,
            posture = StagePosture.FLAT,
            invariants = setOf(ScreenshotInvariant.KERO_LEFT, ScreenshotInvariant.SAKURA_RIGHT),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "Pairwise larger text.",
                    keroText = "",
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = ForegroundNarImportState.Idle,
            ),
        ),
        stageCase(
            name = "pair_rtl_light_f150_d160",
            window = DpSize(900.dp, 500.dp),
            fontScale = 1.5f,
            densityDpi = 160,
            theme = ScreenshotTheme.LIGHT,
            layoutDirection = LayoutDirection.Rtl,
            posture = StagePosture.FLAT,
            invariants = setOf(ScreenshotInvariant.KERO_LEFT, ScreenshotInvariant.SAKURA_RIGHT),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "RTL medium text.",
                    keroText = "",
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = ForegroundNarImportState.Idle,
            ),
        ),
        stageCase(
            name = "pair_ltr_light_f200_d320",
            window = DpSize(900.dp, 500.dp),
            fontScale = 2.0f,
            densityDpi = 320,
            theme = ScreenshotTheme.LIGHT,
            layoutDirection = LayoutDirection.Ltr,
            posture = StagePosture.FLAT,
            invariants = setOf(ScreenshotInvariant.KERO_LEFT, ScreenshotInvariant.SAKURA_RIGHT),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "Very large text.",
                    keroText = "",
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = ForegroundNarImportState.Idle,
            ),
        ),
        stageCase(
            name = "pair_rtl_dark_f200_d160",
            window = DpSize(900.dp, 500.dp),
            fontScale = 2.0f,
            densityDpi = 160,
            theme = ScreenshotTheme.DARK,
            layoutDirection = LayoutDirection.Rtl,
            posture = StagePosture.FLAT,
            invariants = setOf(ScreenshotInvariant.KERO_LEFT, ScreenshotInvariant.SAKURA_RIGHT),
            state = StageFixtureState(
                presentation = presentationState(
                    sakuraText = "Very large RTL text.",
                    keroText = "",
                    sakuraSurfaceId = sakuraStandard.definition.id,
                    keroSurfaceId = keroStandard.definition.id,
                ),
                sakura = sakuraStandard,
                kero = keroStandard,
                narImportState = ForegroundNarImportState.Idle,
            ),
        ),
    )

    gridCases + productCases + pairwiseCases
}.also { cases ->
    val errors = validateAdaptiveGhostStageFixtures(cases)
    require(errors.isEmpty()) {
        "Adaptive ghost fixture catalog failed validation:\n${errors.joinToString("\n")}"
    }
}

internal fun validateAdaptiveGhostStageFixtures(
    cases: List<StageScreenshotCase> = ADAPTIVE_GHOST_STAGE_SCREENSHOT_CASES,
): List<String> = buildList {
    if (cases.size != 31) add("expected 31 cases, got ${cases.size}")

    val names = cases.map { it.name }
    val duplicateNames = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (duplicateNames.isNotEmpty()) {
        add("duplicate fixture names: ${duplicateNames.joinToString()}")
    }

    val expectedGrid = setOf(
        "grid_400x400",
        "grid_400x500",
        "grid_400x1000",
        "grid_610x400",
        "grid_610x500",
        "grid_610x1000",
        "grid_900x400",
        "grid_900x500",
        "grid_900x1000",
    )
    val expectedState = setOf(
        "phone_portrait_one_bubble",
        "phone_portrait_two_bubbles",
        "compact_landscape_empty",
        "compact_landscape_one",
        "compact_landscape_two",
        "compact_landscape_long",
        "tall_phone_two",
        "tablet_portrait",
        "tablet_landscape",
        "foldable_flat",
        "foldable_vertical_separating",
        "tiny_wide",
        "tiny_tall",
        "import_installing",
        "import_failed",
        "collision_shapes_combined",
    )
    val expectedPairwise = setOf(
        "pair_ltr_light_f100_d160",
        "pair_rtl_dark_f100_d320",
        "pair_ltr_dark_f150_d320",
        "pair_rtl_light_f150_d160",
        "pair_ltr_light_f200_d320",
        "pair_rtl_dark_f200_d160",
    )

    val gridCases = cases.filter { it.name.startsWith("grid_") }
    if (gridCases.size != 9) add("expected 9 grid cases, got ${gridCases.size}")
    if (gridCases.size == 9 && gridCases.any { it.name !in expectedGrid }) {
        add("grid fixture names must match fixed 9-size matrix")
    }

    val stateCases = cases.filter { it.name.startsWith("phone_") ||
            it.name.startsWith("compact_") ||
            it.name.startsWith("tall_") ||
            it.name.startsWith("tablet_") ||
            it.name.startsWith("foldable_") ||
            it.name.startsWith("tiny_") ||
            it.name.startsWith("import_") ||
            it.name == "collision_shapes_combined"
    }
    if (stateCases.size != 16) add("expected 16 state cases, got ${stateCases.size}")
    if (stateCases.map { it.name }.toSet() != expectedState) {
        val missing = expectedState - stateCases.map { it.name }.toSet()
        val extra = stateCases.map { it.name }.toSet() - expectedState
        if (missing.isNotEmpty()) add("missing state fixture names: ${missing.joinToString()}")
        if (extra.isNotEmpty()) add("unexpected state fixture names: ${extra.joinToString()}")
    }

    val pairwise = cases.filter { it.name.startsWith("pair_") }
    if (pairwise.size != 6) add("expected 6 pairwise cases, got ${pairwise.size}")
    if (pairwise.map { it.name }.toSet() != expectedPairwise) {
        val missing = expectedPairwise - pairwise.map { it.name }.toSet()
        val extra = pairwise.map { it.name }.toSet() - expectedPairwise
        if (missing.isNotEmpty()) add("missing pairwise fixture names: ${missing.joinToString()}")
        if (extra.isNotEmpty()) add("unexpected pairwise fixture names: ${extra.joinToString()}")
    }

    for (case in cases) {
        if (case.windowSizeDp.width <= Dp.Hairline) add("non-positive width in ${case.name}")
        if (case.windowSizeDp.height <= Dp.Hairline) add("non-positive height in ${case.name}")
        val expectedSafeStage = DpSize(
            case.windowSizeDp.width,
            safeStageHeightDp(case.windowSizeDp.height),
        )
        if (case.expectedSafeStageDp != expectedSafeStage) {
            add("unexpected safe stage in ${case.name}: expected $expectedSafeStage, got ${case.expectedSafeStageDp}")
        }

        val mode = ghostStageMode(case.expectedSafeStageDp)
        if (ScreenshotInvariant.TINY_ONLY in case.expectedInvariants && mode != StageMode.TINY) {
            add("case ${case.name} expected TINY_ONLY but predicted mode is $mode")
        }
        if (ScreenshotInvariant.CENTER_SPLIT in case.expectedInvariants &&
            mode != StageMode.COMPACT_LANDSCAPE &&
            case.state.displayFeatures.none { it.separating }
        ) {
            add("case ${case.name} expects center split with non-compact mode and no separating feature")
        }
        val hasVerticalFold = case.state.displayFeatures.any { it.separating }
        if (case.name == "foldable_vertical_separating" && !hasVerticalFold) {
            add("foldable_vertical_separating requires explicit vertical separating display feature")
        }
        if (case.posture == StagePosture.FLAT && hasVerticalFold) {
            add("only foldable_vertical_separating may use separating display feature")
        }
    }
}

private fun safeStageHeightDp(windowHeight: Dp): Dp =
    (windowHeight.value - CANONICAL_APP_BAR_HEIGHT_DP).coerceAtLeast(0f).dp

private fun stageCase(
    name: String,
    window: DpSize,
    fontScale: Float = 1f,
    densityDpi: Int = 320,
    theme: ScreenshotTheme = ScreenshotTheme.LIGHT,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    posture: StagePosture,
    invariants: Set<ScreenshotInvariant>,
    state: StageFixtureState,
): StageScreenshotCase = StageScreenshotCase(
    name = name,
    windowSizeDp = window,
    expectedSafeStageDp = DpSize(window.width, safeStageHeightDp(window.height)),
    fontScale = fontScale,
    densityDpi = densityDpi,
    theme = theme,
    layoutDirection = layoutDirection,
    posture = posture,
    expectedInvariants = invariants,
    state = state,
)

private fun presentationState(
    sakuraText: String,
    keroText: String,
    sakuraBalloon: Boolean = true,
    keroBalloon: Boolean = true,
    sakuraSurfaceId: Int,
    keroSurfaceId: Int,
): GhostPresentationState = GhostPresentationReducer.snapshot(
    sakuraText = sakuraText,
    sakuraSurfaceId = sakuraSurfaceId.toString(),
    sakuraAnimationId = null,
    sakuraBalloonId = sakuraBalloonId(sakuraText, sakuraBalloon),
    keroText = keroText,
    keroSurfaceId = keroSurfaceId.toString(),
    keroAnimationId = null,
    keroBalloonId = keroBalloonId(keroText, keroBalloon),
)

private fun sakuraBalloonId(text: String, visibleOverride: Boolean): String =
    if (text.isNotEmpty() && visibleOverride) "0" else "-1"

private fun keroBalloonId(text: String, visibleOverride: Boolean): String =
    if (text.isNotEmpty() && visibleOverride) "0" else "-1"

private fun ghostStageMode(size: DpSize): StageMode {
    val width = size.width.value
    val height = size.height.value
    val wide = width >= height * WIDE_RATIO
    return when {
        wide && (width < MIN_WIDE_WIDTH_DP || height < MIN_WIDE_HEIGHT_DP) -> StageMode.TINY
        !wide && (width < MIN_TALL_WIDTH_DP || height < MIN_TALL_HEIGHT_DP) -> StageMode.TINY
        wide && width >= MIN_WIDE_WIDTH_DP && height >= MIN_WIDE_HEIGHT_DP && height < COMPACT_HEIGHT_LIMIT_DP ->
            StageMode.COMPACT_LANDSCAPE
        else -> StageMode.STANDARD
    }
}

private fun surfaceFixture(
    id: Int,
    width: Int,
    height: Int,
    color: Long,
    collisions: List<SurfaceCollision>,
): ScreenshotSurfaceFixture = ScreenshotSurfaceFixture(
    definition = SurfaceDefinition(
        id = id,
        type = 0,
        imagePath = null,
        fallbackImagePath = null,
        width = width,
        height = height,
        collisions = collisions,
        animations = emptyList(),
        elements = emptyList(),
    ),
    image = SurfacePixelImage.of(
        width = width,
        height = height,
        pixels = fixtureCharacterPixels(
            width = width,
            height = height,
            primary = color.toInt(),
            kero = id == 10,
        ),
    ),
)

private fun fixtureCharacterPixels(
    width: Int,
    height: Int,
    primary: Int,
    kero: Boolean,
): IntArray {
    val pixels = IntArray(width * height)
    fun put(x: Int, y: Int, color: Int) {
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] = color
    }
    fun ellipse(centerX: Int, centerY: Int, radiusX: Int, radiusY: Int, color: Int) {
        for (y in (centerY - radiusY).coerceAtLeast(0)..(centerY + radiusY).coerceAtMost(height - 1)) {
            for (x in (centerX - radiusX).coerceAtLeast(0)..(centerX + radiusX).coerceAtMost(width - 1)) {
                val dx = (x - centerX).toDouble() / radiusX.coerceAtLeast(1)
                val dy = (y - centerY).toDouble() / radiusY.coerceAtLeast(1)
                if (dx * dx + dy * dy <= 1.0) put(x, y, color)
            }
        }
    }
    fun rectangle(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(height)) {
            for (x in left.coerceAtLeast(0) until right.coerceAtMost(width)) put(x, y, color)
        }
    }

    val ink = 0xFF24212A.toInt()
    val highlight = 0xFFFFF4D8.toInt()
    if (kero) {
        ellipse(width / 2, height * 2 / 5, width * 2 / 5, height / 4, primary)
        ellipse(width / 2, height * 3 / 4, width / 3, height / 4, primary)
        ellipse(width / 3, height / 3, width / 12, height / 14, highlight)
        ellipse(width * 2 / 3, height / 3, width / 12, height / 14, highlight)
        ellipse(width / 3, height / 3, width / 28, height / 28, ink)
        ellipse(width * 2 / 3, height / 3, width / 28, height / 28, ink)
        rectangle(width * 2 / 5, height / 2, width * 3 / 5, height / 2 + 4, ink)
    } else {
        ellipse(width / 2, height / 4, width / 4, height / 5, primary)
        ellipse(width / 2, height * 11 / 20, width * 3 / 10, height * 7 / 20, primary)
        rectangle(width / 3, height * 3 / 4, width * 2 / 3, height, primary)
        ellipse(width * 5 / 12, height / 4, width / 24, height / 32, highlight)
        ellipse(width * 7 / 12, height / 4, width / 24, height / 32, highlight)
        ellipse(width * 5 / 12, height / 4, width / 60, height / 60, ink)
        ellipse(width * 7 / 12, height / 4, width / 60, height / 60, ink)
        rectangle(width * 9 / 20, height / 3, width * 11 / 20, height / 3 + 4, ink)
    }
    return pixels
}

private const val MIN_WIDE_WIDTH_DP = 420f
private const val MIN_WIDE_HEIGHT_DP = 240f
private const val MIN_TALL_WIDTH_DP = 240f
private const val MIN_TALL_HEIGHT_DP = 320f
private const val COMPACT_HEIGHT_LIMIT_DP = 480f
private const val WIDE_RATIO = 1.2f
