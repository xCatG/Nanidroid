import unittest
from pathlib import Path


class KotlinPresentationFrameContractTest(unittest.TestCase):
    def test_legacy_frame_is_absent_and_snapshot_presentation_is_canonical(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse(
            (root / "src/main/kotlin/com/cattailsw/nanidroid/GhostPresentationFrame.java").exists()
        )
        self.assertFalse((root / "legacy").exists())
        self.assertFalse(
            (root / "src/main/kotlin/com/cattailsw/nanidroid/GhostPresentationFrame.kt").exists()
        )
        source = (
            root / "src/main/kotlin/com/cattailsw/nanidroid/runtime/RuntimeSnapshot.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("data class RuntimePresentation", source)
        self.assertIn("data class RuntimeSpeakerPresentation", source)


if __name__ == "__main__":
    unittest.main()
