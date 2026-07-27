import unittest
from pathlib import Path


class KotlinShioriProtocolContractTest(unittest.TestCase):
    def test_gradle_protocol_value_is_kotlin(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse(
            (root / "src/com/cattailsw/nanidroid/ShioriProtocolVersion.java").exists()
        )
        source = (
            root / "src/com/cattailsw/nanidroid/ShioriProtocolVersion.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("class ShioriProtocolVersion(", source)
        self.assertIn('"$protocol/$major.$minor"', source)

    def test_legacy_ant_overlay_remains_java(self):
        root = Path(__file__).resolve().parents[1]
        source = (
            root / "legacy/src/com/cattailsw/nanidroid/ShioriProtocolVersion.java"
        ).read_text(encoding="utf-8")
        self.assertIn("public final class ShioriProtocolVersion", source)
        self.assertIn('return protocol + "/" + major + "." + minor;', source)


if __name__ == "__main__":
    unittest.main()
