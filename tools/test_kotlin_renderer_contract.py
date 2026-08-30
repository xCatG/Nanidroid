import pathlib
import unittest

class KotlinRendererContractTest(unittest.TestCase):
    def test_callback_renderer_contract_is_absent(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/GhostPresentationRenderer.kt").exists())
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/GhostPresentationRenderer.java").exists())
        source = (root / "src/main/kotlin/com/cattailsw/nanidroid/compose/ComposeGhostStageHost.kt").read_text(encoding="utf-8")
        self.assertIn("snapshot: RuntimeSnapshot", source)
        self.assertNotIn("GhostPresentationRenderer", source)
    def test_renderer_has_no_archived_java_overlay(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        self.assertFalse((root / "legacy").exists())

if __name__ == "__main__": unittest.main()
