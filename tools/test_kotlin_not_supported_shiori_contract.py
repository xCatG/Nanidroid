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

    def test_legacy_ant_implementation_is_java(self):
        root = Path(__file__).resolve().parents[1]
        source = (
            root / "legacy/src/com/cattailsw/nanidroid/shiori/NotSupportedShiori.java"
        ).read_text(encoding="utf-8")
        self.assertIn("public class NotSupportedShiori extends EchoShiori", source)


if __name__ == "__main__":
    unittest.main()
