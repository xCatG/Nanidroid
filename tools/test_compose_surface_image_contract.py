import pathlib
import unittest


class ComposeSurfaceImageContractTest(unittest.TestCase):
    def test_compose_image_layer_has_a_conservative_runtime_policy(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        policy = (root / "src/com/cattailsw/nanidroid/compose/ComposeSurfaceImagePolicy.kt").read_text(encoding="utf-8")
        host = (root / "modern/src/com/cattailsw/nanidroid/compose/GhostPresentationComposeHost.kt").read_text(encoding="utf-8")
        self.assertIn("definition.type == ShellSurface.S_TYPE_BASE", policy)
        self.assertIn("animationId == null", policy)
        self.assertIn("!(talkingAnimationEnabled && balloonVisible)", policy)
        self.assertIn("ComposeSurfaceImage", host)
        self.assertIn("decodeLegacyTransparentImage", host)
        self.assertIn("sakuraView.currentSurfaceDefinition", host)
        self.assertIn("Modifier.size(", host)
        self.assertNotIn("modifier = Modifier.fillMaxSize()", host)


if __name__ == "__main__":
    unittest.main()
