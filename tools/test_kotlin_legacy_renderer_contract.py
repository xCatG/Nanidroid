import pathlib
import unittest

class KotlinLegacyRendererContractTest(unittest.TestCase):
    def test_gradle_renderer_is_kotlin_and_preserves_order(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        source = (root / "src/com/cattailsw/nanidroid/LegacyGhostPresentationRenderer.kt").read_text(encoding="utf-8")
        self.assertIn("class LegacyGhostPresentationRenderer", source)
        self.assertIn("sakura.changeSurface", source)
        self.assertIn("layoutManager?.checkAndUpdateLayoutParam()", source)
        self.assertIn("view.startTalkingAnimation()", source)
        self.assertFalse((root / "src/com/cattailsw/nanidroid/LegacyGhostPresentationRenderer.java").exists())
    def test_renderer_has_no_archived_java_overlay(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        self.assertFalse((root / "legacy").exists())

if __name__ == "__main__": unittest.main()
