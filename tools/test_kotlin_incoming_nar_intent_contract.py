import unittest
from pathlib import Path


class KotlinIncomingNarIntentContractTest(unittest.TestCase):
    def test_retired_intent_gate_is_replaced_by_remote_url_validation(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse(
            (root / "src/main/kotlin/com/cattailsw/nanidroid/IncomingNarIntent.java").exists()
        )
        self.assertFalse((root / "legacy").exists())
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/IncomingNarIntent.kt").exists())
        source = (root / "src/main/kotlin/com/cattailsw/nanidroid/RemoteNarUrl.kt").read_text(encoding="utf-8")
        self.assertIn("object RemoteNarUrl", source)
        self.assertGreaterEqual(source.count("@JvmStatic"), 2)
        self.assertIn('target.scheme.equals("https", ignoreCase = true)', source)
        self.assertIn("Locale.US", source)
        self.assertNotIn('"file"', source)


if __name__ == "__main__":
    unittest.main()
