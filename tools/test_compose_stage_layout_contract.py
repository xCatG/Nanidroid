import pathlib
import unittest


class ComposeStageLayoutContractTest(unittest.TestCase):
    def test_stage_ticker_is_started_lifecycle_scoped_and_rearms_on_restart(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        host = (root / "src/main/kotlin/com/cattailsw/nanidroid/compose/ComposeGhostStageHost.kt").read_text(
            encoding="utf-8"
        )
        ticker_start = host.index("LaunchedEffect(sakuraScheduler, keroScheduler")
        ticker_end = host.index("GhostPresentationStage(", ticker_start)
        ticker = host[ticker_start:ticker_end]

        self.assertIn("val lifecycle = LocalLifecycleOwner.current.lifecycle", host)
        self.assertIn("LaunchedEffect(sakuraScheduler, keroScheduler, lifecycle)", ticker)
        self.assertIn("lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED)", ticker)
        self.assertLess(ticker.index("rearmPeriodicTicks()"), ticker.index("while (true)"))
        self.assertLess(ticker.index("while (true)"), ticker.index("tickSchedulers()"))
        self.assertNotIn("foregroundHost", ticker)
        self.assertNotIn("hostLease", ticker)
        self.assertNotIn("snapshot", ticker)

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
        self.assertIn("StageEnvironmentProvider { windowEnvironment ->", stage)
        self.assertIn("StagePointerInput(", stage)
        self.assertIn("MeasuredGhostStageLayout(", stage)
        self.assertIn("GhostStageLayoutPolicy.calculate(", measured_layout)
        self.assertIn("environmentForSize(stageSize)", measured_layout)
        self.assertIn("SubcomposeLayout(modifier = modifier)", measured_layout)
        self.assertIn("SurfaceCompositor(pixelAssets, SurfacePlanRegistry(plans))", host)
        self.assertIn("RuntimeCommand.Pointer(", host)
        self.assertIn("submitCommand(RuntimeCommand.AcknowledgeCues", host)
        self.assertNotIn("GhostPresentationComposeHost", host)


if __name__ == "__main__":
    unittest.main()
