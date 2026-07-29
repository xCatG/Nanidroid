import unittest
from pathlib import Path


class KotlinSetupContractTest(unittest.TestCase):
    def test_gradle_constants_are_kotlin_compile_time_values(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/com/cattailsw/nanidroid/Setup.java").exists())
        source = (root / "src/com/cattailsw/nanidroid/Setup.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("object Setup", source)
        for name in ("NANIDROID", "UA_CODE", "DLG_README", "ANA_PERF", "PREF_KEY_USE_ANALYTICS"):
            self.assertIn("const val " + name, source)

    def test_constants_have_no_archived_java_overlay(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "legacy").exists())


if __name__ == "__main__":
    unittest.main()
