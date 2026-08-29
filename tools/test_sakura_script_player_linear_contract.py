import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "src/main/kotlin/com/cattailsw/nanidroid/runtime/SakuraScriptPlayer.kt"
TOKENIZER = ROOT / "src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptTokenizer.kt"


class SakuraScriptPlayerLinearContractTest(unittest.TestCase):
    def test_player_has_no_prefix_scope_rescan(self):
        source = PLAYER.read_text(encoding="utf-8")

        self.assertNotIn("scopeAt(", source)

    def test_player_has_no_immutable_per_character_text_concatenation(self):
        source = PLAYER.read_text(encoding="utf-8")

        self.assertNotIn("current.text + character", source)

    def test_runtime_dialogue_has_no_growing_prefix_retokenization(self):
        source = PLAYER.read_text(encoding="utf-8")

        self.assertNotIn("tokenizeRevealed(", source)
        self.assertNotIn("script.take(", source)
        self.assertNotIn("projectDialogue(", source)

    def test_authored_tokenizer_has_no_immutable_growing_text_copy(self):
        source = TOKENIZER.read_text(encoding="utf-8")

        self.assertNotIn("previous.value + value", source)
        self.assertNotIn("DialogueSegment.Text(previous.value +", source)


if __name__ == "__main__":
    unittest.main()
