import pathlib
import unittest


class ComposeStageLayoutContractTest(unittest.TestCase):
    def test_compose_stage_consumes_the_characterized_geometry_policy(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        stage = (root / "src/main/kotlin/com/cattailsw/nanidroid/compose/GhostPresentationStage.kt").read_text(
            encoding="utf-8"
        )
        host = (root / "src/main/kotlin/com/cattailsw/nanidroid/compose/ComposeGhostStageHost.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("BoxWithConstraints", stage)
        self.assertIn("GhostStageLayoutPolicy.calculate", stage)
        self.assertIn("StageNode(layout.kero)", stage)
        self.assertIn("StageNode(layout.sakura)", stage)
        self.assertIn("StageNode(layout.keroBalloon)", stage)
        self.assertIn("StageNode(layout.sakuraBalloon)", stage)
        self.assertIn("val density = LocalDensity.current", stage)
        self.assertIn("placement.size.width.toDp()", stage)
        self.assertIn("placement.size.height.toDp()", stage)
        self.assertIn("SurfaceCompositor", host)
        self.assertIn("SurfacePointerInteractionDispatcher", host)
        self.assertNotIn("GhostPresentationComposeHost", host)


if __name__ == "__main__":
    unittest.main()
