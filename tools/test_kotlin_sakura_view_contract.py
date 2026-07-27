import pathlib
import unittest

class KotlinSakuraViewContractTest(unittest.TestCase):
    def test_gradle_source_preserves_java_visible_renderer_contract(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        source = (root / "src/com/cattailsw/nanidroid/SakuraView.kt").read_text(encoding="utf-8")
        self.assertIn("open class SakuraView @JvmOverloads constructor", source)
        self.assertIn("@JvmField var currentSurface", source)
        self.assertIn("protected open fun loadSurface", source)
        self.assertIn("open fun changeSurface", source)
        self.assertIn("open fun loadAnimation", source)
        self.assertIn("open fun startAnimation()", source)
        self.assertIn("open fun startTalkingAnimation", source)
        self.assertIn("fun testColDect", source)
        self.assertFalse((root / "src/com/cattailsw/nanidroid/SakuraView.java").exists())

if __name__ == "__main__": unittest.main()
