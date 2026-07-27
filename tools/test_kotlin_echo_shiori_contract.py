import unittest
from pathlib import Path


class KotlinEchoShioriContractTest(unittest.TestCase):
    def test_gradle_base_implementation_is_kotlin(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/com/cattailsw/nanidroid/shiori/EchoShiori.java").exists())
        source = (root / "src/com/cattailsw/nanidroid/shiori/EchoShiori.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("open class EchoShiori : Shiori", source)
        self.assertIn("protected open fun genResponse(): String", source)
        self.assertIn("protected var reqTable", source)

    def test_legacy_ant_base_implementation_is_java(self):
        root = Path(__file__).resolve().parents[1]
        source = (
            root / "legacy/src/com/cattailsw/nanidroid/shiori/EchoShiori.java"
        ).read_text(encoding="utf-8")
        self.assertIn("public class EchoShiori implements Shiori", source)
        self.assertIn("protected String genResponse()", source)


if __name__ == "__main__":
    unittest.main()
