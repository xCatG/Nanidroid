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

    def test_protocol_value_has_no_archived_java_overlay(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "legacy").exists())


if __name__ == "__main__":
    unittest.main()
