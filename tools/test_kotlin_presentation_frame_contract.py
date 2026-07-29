import unittest
from pathlib import Path


class KotlinPresentationFrameContractTest(unittest.TestCase):
    def test_frame_is_kotlin_with_java_field_compatibility(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse(
            (root / "src/com/cattailsw/nanidroid/GhostPresentationFrame.java").exists()
        )
        self.assertFalse((root / "legacy").exists())
        source = (
            root / "src/com/cattailsw/nanidroid/GhostPresentationFrame.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("data class GhostPresentationFrame", source)
        self.assertIn("class Speaker", source)
        self.assertGreaterEqual(source.count("@JvmField"), 7)
        self.assertIn('!balloonId.equals("-1", ignoreCase = true)', source)


if __name__ == "__main__":
    unittest.main()
