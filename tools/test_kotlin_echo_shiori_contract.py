import unittest
from pathlib import Path


class KotlinEchoShioriContractTest(unittest.TestCase):
    def test_gradle_base_implementation_is_kotlin(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/shiori/EchoShiori.java").exists())
        source = (root / "src/main/kotlin/com/cattailsw/nanidroid/shiori/EchoShiori.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("open class EchoShiori : Shiori", source)
        self.assertIn("protected open fun genResponse(): String", source)
        self.assertIn("protected var reqTable", source)

    def test_echo_shiori_has_no_archived_java_overlay(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "legacy").exists())


if __name__ == "__main__":
    unittest.main()
