import unittest
from pathlib import Path


class KotlinShioriResponseContractTest(unittest.TestCase):
    def test_gradle_response_parser_is_kotlin(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/com/cattailsw/nanidroid/ShioriResponse.java").exists())
        source = (root / "src/com/cattailsw/nanidroid/ShioriResponse.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("class ShioriResponse", source)
        self.assertIn("constructor(reader: BufferedReader)", source)
        self.assertIn("var stat_code: Int = 500", source)
        self.assertIn("fun getStatusCode(): Int = stat_code", source)
        ghost_source = (root / "src/com/cattailsw/nanidroid/Ghost.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("response.getStatusCode() == 200", ghost_source)

    def test_frozen_ant_build_uses_java_overlay(self):
        root = Path(__file__).resolve().parents[1]
        source = (
            root / "legacy/src/com/cattailsw/nanidroid/ShioriResponse.java"
        ).read_text(encoding="utf-8")
        self.assertIn("public class ShioriResponse", source)
        self.assertIn("public int getStatusCode()", source)


if __name__ == "__main__":
    unittest.main()
