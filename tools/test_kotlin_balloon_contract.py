import pathlib
import unittest


class KotlinBalloonContractTest(unittest.TestCase):
    def test_gradle_source_uses_kotlin_with_legacy_text_behavior(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        source = (root / "src/com/cattailsw/nanidroid/Balloon.kt").read_text(encoding="utf-8")

        self.assertIn("open class Balloon @JvmOverloads constructor", source)
        self.assertIn("open fun setText(text: String)", source)
        self.assertIn("Linkify.addLinks(this, Linkify.ALL)", source)
        self.assertIn("movementMethod = scrollingMovementMethod", source)
        self.assertIn("movementMethod = null", source)
        self.assertFalse((root / "src/com/cattailsw/nanidroid/Balloon.java").exists())

    def test_balloon_has_no_java_or_archived_overlay(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/com/cattailsw/nanidroid/Balloon.java").exists())
        self.assertFalse((root / "legacy").exists())


if __name__ == "__main__":
    unittest.main()
