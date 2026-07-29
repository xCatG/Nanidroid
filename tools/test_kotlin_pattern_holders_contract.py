import unittest
from pathlib import Path


class KotlinPatternHoldersContractTest(unittest.TestCase):
    def test_gradle_patterns_are_kotlin_java_static_fields(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/com/cattailsw/nanidroid/PatternHolders.java").exists())
        source = (root / "src/com/cattailsw/nanidroid/PatternHolders.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("object PatternHolders", source)
        self.assertEqual(source.count("@JvmField"), 25)
        for field in ("element", "animation", "shiori_res_header_ptrn", "open_input"):
            self.assertIn("val " + field, source)

    def test_patterns_have_no_archived_java_overlay(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "legacy").exists())


if __name__ == "__main__":
    unittest.main()
