import pathlib
import unittest

class KotlinComposeRendererContractTest(unittest.TestCase):
    def test_modern_adapter_is_kotlin_and_preserves_interaction_fallback(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        source = (root / "modern/src/com/cattailsw/nanidroid/ComposeBackedGhostPresentationRenderer.kt").read_text(encoding="utf-8")
        self.assertIn("class ComposeBackedGhostPresentationRenderer", source)
        self.assertIn("balloon.urls.isNotEmpty()", source)
        self.assertIn("balloon.movementMethod != null", source)
        self.assertIn("composeHost.render", source)
        self.assertFalse((root / "modern/src/com/cattailsw/nanidroid/ComposeBackedGhostPresentationRenderer.java").exists())

if __name__ == "__main__": unittest.main()
