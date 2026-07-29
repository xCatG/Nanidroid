import unittest
from pathlib import Path


class KotlinNotSupportedShioriContractTest(unittest.TestCase):
    def test_gradle_implementation_is_kotlin(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse(
            (root / "src/com/cattailsw/nanidroid/shiori/NotSupportedShiori.java").exists()
        )
        source = (
            root / "src/com/cattailsw/nanidroid/shiori/NotSupportedShiori.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("class NotSupportedShiori", source)
        self.assertIn("override fun genResponse(): String", source)
        self.assertIn("R.string.unsupported_shiori", source)

    def test_unsupported_shiori_has_no_archived_java_overlay(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "legacy").exists())


if __name__ == "__main__":
    unittest.main()
