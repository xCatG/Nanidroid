"""Structural contract for the Kotlin Activity migration.

This deliberately verifies seams rather than attempting to unit-test framework
lifecycles with default-return Android stubs.  The device lane owns runtime
rendering; these assertions lock down the compatibility obligations of this PR.
"""

import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class KotlinNanidroidActivityContractTest(unittest.TestCase):
    def setUp(self):
        self.source = (
            ROOT / "src/com/cattailsw/nanidroid/Nanidroid.kt"
        ).read_text(encoding="utf-8")

    def test_gradle_build_uses_kotlin_while_ant_keeps_the_frozen_java_activity(self):
        self.assertFalse((ROOT / "src/com/cattailsw/nanidroid/Nanidroid.java").exists())
        self.assertTrue((ROOT / "legacy/src/com/cattailsw/nanidroid/Nanidroid.java").exists())
        self.assertIn("class Nanidroid : FragmentActivity()", self.source)
        self.assertIn("composeRoot.setContent {", self.source)
        self.assertIn("NanidroidComposeShell(", self.source)

    def test_retained_stage_and_dialog_callback_names_remain_public(self):
        for callback in (
            "fun onNextSurface(v: View)",
            "fun onAnimate(v: View)",
            "fun onShowCollision(v: View)",
            "fun runClick(v: View)",
            "fun narTest(v: View)",
            "fun onUpdate(v: View)",
            "fun onListGhost(v: View)",
            "fun onHelp(v: View)",
            "fun onSetupClick(v: View)",
            "fun frameClick(v: View)",
            "override fun showUserInputBox(id: String)",
            "override fun showUserSelection(textlabel: Array<String>, ids: Array<String>)",
        ):
            self.assertIn(callback, self.source)
        self.assertIn("sv = SakuraView(this).apply { id = R.id.sakura_display }", self.source)
        self.assertIn("kv = KeroView(this).apply { id = R.id.kero_display }", self.source)
        self.assertIn("ghostStage = stage", self.source)
        self.assertIn("runner!!.setUICallback(this@Nanidroid)", self.source)

    def test_incoming_nar_boundary_remains_https_approval_before_service_start(self):
        self.assertIn("if (!IncomingNarIntent.isApprovedDownload(target))", self.source)
        self.assertIn("if (!IncomingNarIntent.isApprovedDownload(incoming))", self.source)
        self.assertIn("Rejected unapproved external install URI", self.source)
        self.assertIn("startModernService(Intent(this, NanidroidService::class.java)", self.source)

    def test_compose_dialog_state_is_saved_and_restored_across_recreation(self):
        self.assertIn("override fun onSaveInstanceState(outState: Bundle)", self.source)
        self.assertIn("saveSimpleDialog(outState)", self.source)
        self.assertIn("restoreSimpleDialog(savedInstanceState)", self.source)
        self.assertIn("DIALOG_HELP_MENU -> createHelpMenuDialog()", self.source)
        self.assertIn("DIALOG_GENERAL_HELP -> createGeneralHelpDialog()", self.source)
        self.assertIn("DIALOG_MORE_GHOST -> createMoreGhostDialog()", self.source)
        self.assertIn("is NanidroidSimpleDialog.DebugMessage -> Unit", self.source)
        self.assertNotIn("DIALOG_DEBUG", self.source)


if __name__ == "__main__":
    unittest.main()
