import unittest
from pathlib import Path


class KotlinShioriFactoryContractTest(unittest.TestCase):
    def test_gradle_factory_is_kotlin_and_preserves_java_entrypoints(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/ShioriFactory.java").exists())
        source = (root / "src/main/kotlin/com/cattailsw/nanidroid/ShioriFactory.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("class ShioriFactory private constructor()", source)
        self.assertIn("@JvmStatic", source)
        self.assertIn("fun getInstance(): ShioriFactory", source)
        self.assertIn("fun getShiori(path: String, masterDesc: Map<String, String>?): Shiori", source)
        self.assertIn("fun getShiori(path: String, masterDesc: Map<String, String>?, ctx: Context?): Shiori", source)

    def test_factory_routes_supported_native_engines_and_keeps_ambiguous_dlls_safe(self):
        root = Path(__file__).resolve().parents[1]
        source = (root / "src/main/kotlin/com/cattailsw/nanidroid/ShioriFactory.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn('"Nanidroid" -> NanidroidShiori(path, ctx != null)', source)
        self.assertIn('"satori.dll" -> SatoriShiori(path, ctx)', source)
        self.assertIn('"shiori.dll" -> checkShioriByPath(path, ctx)', source)
        self.assertIn('"yaya.dll" -> YayaShiori(path, ctx)', source)
        self.assertIn("else -> NotSupportedShiori(ctx)", source)
        self.assertIn('File(path, "kawarirc.kis").isFile', source)
        self.assertIn("Kawari(path)", source)
        self.assertNotIn("SatoriPosixShiori(", source)

    def test_mainline_has_no_archived_java_factory(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "legacy").exists())
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/ShioriFactory.java").exists())


if __name__ == "__main__":
    unittest.main()
