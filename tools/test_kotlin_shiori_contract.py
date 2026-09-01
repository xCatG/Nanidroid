import unittest
from pathlib import Path


class KotlinShioriContractTest(unittest.TestCase):
    def test_gradle_shiori_contract_is_kotlin(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/shiori/Shiori.java").exists())
        source = (root / "src/main/kotlin/com/cattailsw/nanidroid/shiori/Shiori.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("interface Shiori", source)
        self.assertIn("fun request(request: String): String", source)
        self.assertIn("fun unloadShiori()", source)

    def test_shiori_contract_has_no_archived_java_overlay(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "legacy").exists())

    def test_native_adapters_do_not_unload_an_owner_they_never_acquired(self):
        root = Path(__file__).resolve().parents[1]
        guard = "if (!loaded && !loadCleanupRequired) return ShioriUnloadResult.Unloaded"
        for relative in ("SatoriShiori.kt", "YayaShiori.kt", "Kawari.kt"):
            source = (root / "src/main/kotlin/com/cattailsw/nanidroid/shiori" / relative).read_text(
                encoding="utf-8"
            )
            self.assertIn(guard, source, relative)


if __name__ == "__main__":
    unittest.main()
