import pathlib
import unittest


class ComposeStageLayoutContractTest(unittest.TestCase):
    def test_compose_stage_consumes_the_measured_adaptive_geometry_policy(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        stage = (root / "src/main/kotlin/com/cattailsw/nanidroid/compose/GhostPresentationStage.kt").read_text(
            encoding="utf-8"
        )
        measured_layout = (
            root
            / "src/main/kotlin/com/cattailsw/nanidroid/compose/stage/MeasuredGhostStageLayout.kt"
        ).read_text(encoding="utf-8")
        host = (root / "src/main/kotlin/com/cattailsw/nanidroid/compose/ComposeGhostStageHost.kt").read_text(
            encoding="utf-8"
        )
        measured = (root / "src/main/kotlin/com/cattailsw/nanidroid/compose/stage/MeasuredGhostStageLayout.kt").read_text(
            encoding="utf-8"
        )
        for boundary in ("StageEnvironmentProvider", "StagePointerInput", "MeasuredGhostStageLayout", "toStageEnvironment"):
            self.assertIn(boundary, stage)
        for owner in ("SubcomposeLayout", "GhostStageLayoutPolicy.calculate", "StageLayoutPx.from"):
            self.assertIn(owner, measured)
        for slot in ("KERO_SURFACE", "SAKURA_SURFACE", "KERO_BALLOON", "SAKURA_BALLOON"):
            self.assertIn(slot, measured)
        self.assertIn("SurfaceCompositor", host)
        self.assertIn("onSurfaceEffect = interactionPort::dispatch", host)
        self.assertNotIn("GhostPresentationComposeHost", host)


if __name__ == "__main__":
    unittest.main()
