"""Compose-only contract for About and installed-document presentation."""

import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class KotlinAboutDialogContractTest(unittest.TestCase):
    def test_document_ui_is_compose_only(self):
        dialogs = (ROOT / "src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidSimpleDialogs.kt").read_text(encoding="utf-8")
        activity = (ROOT / "src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt").read_text(encoding="utf-8")
        reader = (ROOT / "src/main/kotlin/com/cattailsw/nanidroid/compose/PlainTextDocument.kt").read_text(encoding="utf-8")
        self.assertIn("data class TextDocument", dialogs)
        self.assertIn("data class SwitchConfirmation", dialogs)
        self.assertIn("PlainTextDocument.linkPattern", dialogs)
        self.assertIn("createAboutDialog", activity)
        self.assertIn("createReadmeDialog", activity)
        self.assertIn("createNoReadmeDialog", activity)
        self.assertIn("Shift_JIS", reader)
        self.assertIn("https?://", reader)
        self.assertFalse(any((ROOT / "src/main/kotlin/com/cattailsw/nanidroid/dlgs").glob("*.kt")))
        self.assertFalse((ROOT / "src/main/res/layout/installdlg.xml").exists())

    def test_document_policy_has_no_embedded_html_or_webview(self):
        for source in (ROOT / "src").rglob("*.kt"):
            text = source.read_text(encoding="utf-8")
            self.assertNotIn("WebView", text)
            self.assertNotIn("loadDataWithBaseURL", text)
            self.assertNotIn("DialogFragment", text)


if __name__ == "__main__":
    unittest.main()
