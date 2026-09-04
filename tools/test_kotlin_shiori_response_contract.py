import unittest
from pathlib import Path


class KotlinShioriResponseContractTest(unittest.TestCase):
    def test_gradle_response_parser_is_kotlin(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/ShioriResponse.java").exists())
        source = (root / "src/main/kotlin/com/cattailsw/nanidroid/ShioriResponse.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("class ShioriResponse", source)
        self.assertIn("constructor(reader: BufferedReader)", source)
        self.assertIn("private var statusCode: Int = 500", source)
        self.assertIn("fun getStatusCode(): Int = statusCode", source)
        for suffix in ("java", "kt"):
            self.assertFalse(
                (root / f"src/main/kotlin/com/cattailsw/nanidroid/ShioriProtocolVersion.{suffix}").exists()
            )

    def test_response_parser_has_no_archived_java_overlay(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "legacy").exists())


if __name__ == "__main__":
    unittest.main()
