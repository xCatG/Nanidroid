import unittest
from pathlib import Path


class KotlinIncomingNarIntentContractTest(unittest.TestCase):
    def test_external_archive_gate_accepts_only_granted_content_uris(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse(
            (root / "src/main/kotlin/com/cattailsw/nanidroid/IncomingNarIntent.java").exists()
        )
        self.assertFalse((root / "legacy").exists())
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/IncomingNarIntent.kt").exists())
        source = (root / "src/main/kotlin/com/cattailsw/nanidroid/ArchiveIntentAdapter.kt").read_text(encoding="utf-8")
        self.assertIn("object ArchiveIntentAdapter", source)
        self.assertIn('"application/zip"', source)
        self.assertIn('"application/x-nar"', source)
        self.assertIn('scheme.equals("content", ignoreCase = true)', source)
        self.assertIn("FLAG_GRANT_READ_URI_PERMISSION", source)
        self.assertNotIn('"https"', source)


if __name__ == "__main__":
    unittest.main()
