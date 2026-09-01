import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class ComposeStageRetirementContractTest(unittest.TestCase):
    def test_retained_stage_views_and_renderer_are_absent(self):
        for name in (
            "SakuraView.kt",
            "KeroView.kt",
            "Balloon.kt",
            "LayoutManager.kt",
            "LegacyGhostPresentationRenderer.kt",
        ):
            self.assertFalse((ROOT / "src/main/kotlin/com/cattailsw/nanidroid" / name).exists())

    def test_runner_has_only_the_toolkit_neutral_presentation_seam(self):
        source = (ROOT / "src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("fun setPresentationRenderer(renderer: GhostPresentationRenderer?)", source)
        self.assertIn("fun dispatchSurfaceInteraction(effect: SurfaceInteractionEffect): Boolean", source)
        self.assertNotIn("fun dispatchComposeDoubleClick", source)
        self.assertNotIn("fun setViews(", source)
        self.assertNotIn("fun setLayoutMgr(", source)
        self.assertNotIn("LegacyGhostPresentationRenderer", source)
        self.assertNotIn("SakuraView", source)
        self.assertNotIn("KeroView", source)

    def test_compose_stage_stack_owns_geometry_images_pointer_routing_and_balloons(self):
        stage = (ROOT / "src/main/kotlin/com/cattailsw/nanidroid/compose/GhostPresentationStage.kt").read_text(
            encoding="utf-8"
        )
        measured_layout = (
            ROOT
            / "src/main/kotlin/com/cattailsw/nanidroid/compose/stage/MeasuredGhostStageLayout.kt"
        ).read_text(encoding="utf-8")
        bubble = (
            ROOT / "src/main/kotlin/com/cattailsw/nanidroid/compose/stage/GhostBubble.kt"
        ).read_text(encoding="utf-8")
        host = (ROOT / "src/main/kotlin/com/cattailsw/nanidroid/compose/ComposeGhostStageHost.kt").read_text(
            encoding="utf-8"
        )
        scheduler = (ROOT / "src/main/kotlin/com/cattailsw/nanidroid/compose/SurfaceAnimationScheduler.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("MeasuredGhostStageLayout", stage)
        self.assertIn("StagePointerInput", stage)
        self.assertIn("GhostBubble", stage)
        self.assertIn("onSurfaceEffect = interactionPort::dispatch", host)
        self.assertIn("SurfaceAnimationScheduler", host)
        self.assertIn("talk begins on the first update and then every tenth update", scheduler)


if __name__ == "__main__":
    unittest.main()
