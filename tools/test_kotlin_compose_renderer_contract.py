import pathlib
import unittest

class KotlinComposeRendererContractTest(unittest.TestCase):
    def test_compose_stage_host_is_the_only_production_renderer_adapter(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        source = (root / "src/main/kotlin/com/cattailsw/nanidroid/compose/ComposeGhostStageHost.kt").read_text(encoding="utf-8")
        runtime = (root / "src/main/kotlin/com/cattailsw/nanidroid/runtime/KotlinGhostPresentationRuntime.kt").read_text(encoding="utf-8")
        self.assertIn("class ComposeGhostStageHost", source)
        self.assertIn("val renderer = KotlinGhostPresentationRuntime", source)
        self.assertIn("SurfaceAnimationScheduler", source)
        for obsolete in ("GhostPresentationRuntimeEvent", "GhostPresentationRuntimeEffect", "GhostPresentationRuntimeTransition"):
            self.assertNotIn(obsolete, runtime)
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/compose/ComposeSurfaceImagePolicy.kt").exists())
        self.assertFalse((root / "modern/src/com/cattailsw/nanidroid/ComposeBackedGhostPresentationRenderer.kt").exists())
        self.assertFalse((root / "modern/src/com/cattailsw/nanidroid/compose/GhostPresentationComposeHost.kt").exists())

if __name__ == "__main__": unittest.main()
