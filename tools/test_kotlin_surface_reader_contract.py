import pathlib
import unittest


class KotlinSurfaceReaderContractTest(unittest.TestCase):
    def test_surface_reader_uses_bounded_strict_surface_source_decoding(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/SurfaceReader.java").exists())
        self.assertFalse((root / "legacy").exists())
        source = (root / "src/main/kotlin/com/cattailsw/nanidroid/SurfaceReader.kt").read_text(encoding="utf-8")
        decoder = (
            root / "src/main/kotlin/com/cattailsw/nanidroid/surface/SurfaceSourceFile.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("class SurfaceReader", source)
        self.assertIn("SurfaceSourceDecoder.newSession()", source)
        self.assertIn("file.length() > SurfaceSourceDecoder.MAX_SOURCE_BYTES", source)
        self.assertIn("readBounded(file, decodeSession.maxReadBytes())", source)
        self.assertIn("SurfaceParser().parse", source)
        self.assertIn('Charset.forName("Windows-31J")', decoder)
        self.assertIn('"shift_jis", "shift-jis", "sjis", "windows-31j", "cp932"', decoder)
        self.assertIn(".onMalformedInput(CodingErrorAction.REPORT)", decoder)
        self.assertIn(".onUnmappableCharacter(CodingErrorAction.REPORT)", decoder)


if __name__ == "__main__":
    unittest.main()
