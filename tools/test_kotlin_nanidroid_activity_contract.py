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
        self.assertIn("setContentView(R.layout.main)", self.source)

    def test_xml_stage_and_dialog_callback_names_remain_public(self):
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
        self.assertIn("sv = findViewById(R.id.sakura_display)", self.source)
        self.assertIn("kv = findViewById(R.id.kero_display)", self.source)
        self.assertIn("runner!!.setUICallback(this@Nanidroid)", self.source)

    def test_incoming_nar_boundary_remains_https_approval_before_service_start(self):
        self.assertIn("if (!IncomingNarIntent.isApprovedDownload(target))", self.source)
        self.assertIn("if (!IncomingNarIntent.isApprovedDownload(incoming))", self.source)
        self.assertIn("Rejected unapproved external install URI", self.source)
        self.assertIn("startModernService(Intent(this, NanidroidService::class.java)", self.source)


if __name__ == "__main__":
    unittest.main()
