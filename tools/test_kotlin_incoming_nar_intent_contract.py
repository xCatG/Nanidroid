import unittest
from pathlib import Path


class KotlinIncomingNarIntentContractTest(unittest.TestCase):
    def test_security_gate_is_kotlin_and_keeps_java_static_entry_points(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse(
            (root / "src/main/kotlin/com/cattailsw/nanidroid/IncomingNarIntent.java").exists()
        )
        self.assertFalse((root / "legacy").exists())
        source = (
            root / "src/main/kotlin/com/cattailsw/nanidroid/IncomingNarIntent.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("object IncomingNarIntent", source)
        self.assertEqual(2, source.count("@JvmStatic"))
        self.assertIn("Intent.ACTION_VIEW == intent?.action", source)
        self.assertIn('download.scheme.equals("https", ignoreCase = true)', source)
        self.assertIn("Locale.US", source)
        self.assertNotIn('"file"', source)


if __name__ == "__main__":
    unittest.main()
