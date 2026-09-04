import pathlib
import unittest


class KotlinSurfaceReaderContractTest(unittest.TestCase):
    def test_surface_reader_is_kotlin_and_delegates_typed_decoding_and_discovery(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/SurfaceReader.java").exists())
        self.assertFalse((root / "legacy").exists())
        source = (root / "src/main/kotlin/com/cattailsw/nanidroid/SurfaceReader.kt").read_text(encoding="utf-8")
        self.assertIn("class SurfaceReader", source)
        self.assertIn("SurfaceSourceDecoder.newSession()", source)
        self.assertIn("discoverSourceFiles(rootDirectory)", source)
        self.assertIn("discoverPngFiles(rootDirectory)", source)


if __name__ == "__main__":
    unittest.main()
