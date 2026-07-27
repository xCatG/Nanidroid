import unittest
from pathlib import Path


class KotlinShioriFactoryContractTest(unittest.TestCase):
    def test_gradle_factory_is_kotlin_and_preserves_java_entrypoints(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/com/cattailsw/nanidroid/ShioriFactory.java").exists())
        source = (root / "src/com/cattailsw/nanidroid/ShioriFactory.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("class ShioriFactory private constructor()", source)
        self.assertIn("@JvmStatic", source)
        self.assertIn("fun getInstance(): ShioriFactory", source)
        self.assertIn("fun getShiori(path: String, masterDesc: Map<String, String>?): Shiori", source)
        self.assertIn("fun getShiori(path: String, masterDesc: Map<String, String>?, ctx: Context?): Shiori", source)

    def test_factory_preserves_supported_and_fallback_engine_selection(self):
        root = Path(__file__).resolve().parents[1]
        source = (root / "src/com/cattailsw/nanidroid/ShioriFactory.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn('"kawarirc.kis"', source)
        self.assertIn('"kawari.ini"', source)
        self.assertIn('"aya5.txt"', source)
        self.assertIn('"Nanidroid" -> NanidroidShiori(ctx, path)', source)
        self.assertIn('"satori.dll" -> SatoriPosixShiori(path)', source)
        self.assertIn('"shiori.dll" -> checkShioriByPath(path, ctx)', source)
        self.assertIn('"yaya.dll" -> NotSupportedShiori(ctx)', source)
        self.assertIn("else -> NotSupportedShiori(ctx)", source)

    def test_legacy_ant_factory_remains_java(self):
        root = Path(__file__).resolve().parents[1]
        source = (root / "legacy/src/com/cattailsw/nanidroid/ShioriFactory.java").read_text(
            encoding="utf-8"
        )
        self.assertIn("public class ShioriFactory", source)
        self.assertIn("public static final ShioriFactory getInstance()", source)
        self.assertIn("public Shiori getShiori(String path, Map<String, String> masterDesc", source)


if __name__ == "__main__":
    unittest.main()
