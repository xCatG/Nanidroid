import pathlib
import unittest

class KotlinRendererContractTest(unittest.TestCase):
    def test_gradle_contract_is_kotlin(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        source = (root / "src/com/cattailsw/nanidroid/GhostPresentationRenderer.kt").read_text(encoding="utf-8")
        self.assertIn("fun interface GhostPresentationRenderer", source)
        self.assertIn("fun render(frame: GhostPresentationFrame)", source)
        self.assertFalse((root / "src/com/cattailsw/nanidroid/GhostPresentationRenderer.java").exists())
    def test_ant_overlay_is_java(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        self.assertTrue((root / "legacy/src/com/cattailsw/nanidroid/GhostPresentationRenderer.java").exists())

if __name__ == "__main__": unittest.main()
