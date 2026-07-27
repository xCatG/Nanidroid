import unittest
from pathlib import Path


class KotlinShioriContractTest(unittest.TestCase):
    def test_gradle_shiori_contract_is_kotlin(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/com/cattailsw/nanidroid/shiori/Shiori.java").exists())
        source = (root / "src/com/cattailsw/nanidroid/shiori/Shiori.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("interface Shiori", source)
        self.assertIn("fun request(request: String): String", source)
        self.assertIn("fun unloadShiori()", source)

    def test_legacy_ant_contract_is_java(self):
        root = Path(__file__).resolve().parents[1]
        source = (
            root / "legacy/src/com/cattailsw/nanidroid/shiori/Shiori.java"
        ).read_text(encoding="utf-8")
        self.assertIn("public interface Shiori", source)
        self.assertIn("String request(String req);", source)


if __name__ == "__main__":
    unittest.main()
