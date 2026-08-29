import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "src/main/kotlin/com/cattailsw/nanidroid/runtime/SakuraScriptPlayer.kt"


class SakuraScriptPlayerLinearContractTest(unittest.TestCase):
    def test_player_has_no_prefix_scope_rescan(self):
        source = PLAYER.read_text(encoding="utf-8")

        self.assertNotIn("scopeAt(", source)

    def test_player_has_no_immutable_per_character_text_concatenation(self):
        source = PLAYER.read_text(encoding="utf-8")

        self.assertNotIn("current.text + character", source)


if __name__ == "__main__":
    unittest.main()
