import pathlib
import unittest


class KotlinSurfaceReaderContractTest(unittest.TestCase):
    def test_surface_reader_is_kotlin_and_preserves_ant_overlay_source(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/SurfaceReader.java").exists())
        self.assertFalse((root / "legacy").exists())
        source = (root / "src/main/kotlin/com/cattailsw/nanidroid/SurfaceReader.kt").read_text(encoding="utf-8")
        self.assertIn("class SurfaceReader", source)
        self.assertIn("Charset.forName(\"SJIS\")", source)
        self.assertIn("scanFolderForPng", source)
        self.assertIn("PatternHolders.surface_desc_ptrn", source)


if __name__ == "__main__":
    unittest.main()
