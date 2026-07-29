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
            self.assertFalse((ROOT / "src/com/cattailsw/nanidroid" / name).exists())

    def test_runner_has_only_the_toolkit_neutral_presentation_seam(self):
        source = (ROOT / "src/com/cattailsw/nanidroid/SScriptRunner.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("fun setPresentationRenderer(renderer: GhostPresentationRenderer?)", source)
        self.assertIn("fun dispatchComposeDoubleClick", source)
        self.assertNotIn("fun setViews(", source)
        self.assertNotIn("fun setLayoutMgr(", source)
        self.assertNotIn("LegacyGhostPresentationRenderer", source)
        self.assertNotIn("SakuraView", source)
        self.assertNotIn("KeroView", source)

    def test_compose_stage_owns_geometry_images_pointer_routing_and_balloons(self):
        stage = (ROOT / "src/com/cattailsw/nanidroid/compose/GhostPresentationStage.kt").read_text(
            encoding="utf-8"
        )
        host = (ROOT / "src/com/cattailsw/nanidroid/compose/ComposeGhostStageHost.kt").read_text(
            encoding="utf-8"
        )
        scheduler = (ROOT / "src/com/cattailsw/nanidroid/compose/SurfaceAnimationScheduler.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("GhostStageLayoutPolicy.calculate", stage)
        self.assertIn("StageNode(layout.sakuraBalloon)", stage)
        self.assertIn("linkifyForCompose", stage)
        self.assertIn("never creates a SakuraView, KeroView, Balloon, or FrameLayout", host)
        self.assertIn("SurfacePointerInteractionDispatcher", host)
        self.assertIn("SurfaceAnimationScheduler", host)
        self.assertIn("talk begins on the first update and then every tenth update", scheduler)


if __name__ == "__main__":
    unittest.main()
