import pathlib
import unittest


class KotlinSurfaceCatalogContractTest(unittest.TestCase):
    def test_surface_manager_is_kotlin_and_preserves_ant_overlay_source(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/SurfaceManager.java").exists())
        self.assertFalse((root / "legacy").exists())

        source = (root / "src/main/kotlin/com/cattailsw/nanidroid/SurfaceManager.kt").read_text(encoding="utf-8")
        self.assertIn("class SurfaceManager", source)
        self.assertIn("fun getSakuraSurface", source)
        self.assertIn("fun getKeroSurface", source)
        self.assertIn("surfaces[id] ?: surfaces[SAKURA_DEFAULT_ID] ?: nullSurface", source)
        self.assertIn("surfaces[id] ?: surfaces[KERO_DEFAULT_ID] ?: nullSurface", source)


if __name__ == "__main__":
    unittest.main()
