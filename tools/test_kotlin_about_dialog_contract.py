"""Compatibility contract for the Kotlin About dialog migration."""

import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class KotlinAboutDialogContractTest(unittest.TestCase):
    def test_gradle_uses_kotlin_while_frozen_ant_keeps_the_original_java_dialog(self):
        source = ROOT / "src/com/cattailsw/nanidroid/dlgs/AboutDialogFragment.kt"
        legacy = ROOT / "legacy/src/com/cattailsw/nanidroid/dlgs/AboutDialogFragment.java"

        self.assertTrue(source.exists())
        self.assertFalse(
            (ROOT / "src/com/cattailsw/nanidroid/dlgs/AboutDialogFragment.java").exists()
        )
        self.assertTrue(legacy.exists())
        self.assertIn("public class AboutDialogFragment extends DialogFragment", legacy.read_text(encoding="utf-8"))

    def test_kotlin_dialog_preserves_the_legacy_webview_and_close_contract(self):
        source = (
            ROOT / "src/com/cattailsw/nanidroid/dlgs/AboutDialogFragment.kt"
        ).read_text(encoding="utf-8")

        self.assertIn("class AboutDialogFragment : DialogFragment()", source)
        self.assertIn("override fun onCreateDialog(savedInstanceState: Bundle?): Dialog", source)
        self.assertIn("View.inflate(activity, R.layout.installdlg, null)", source)
        self.assertIn("findViewById<WebView>(R.id.readme_view)", source)
        self.assertIn('webView.loadUrl("file:///android_asset/about.html")', source)
        self.assertNotIn("setWebViewClient", source)
        self.assertIn(".setTitle(R.string.about_title)", source)
        self.assertIn(".setPositiveButton(R.string.close_btn_text)", source)
        self.assertIn("dialog.dismiss()", source)


if __name__ == "__main__":
    unittest.main()
