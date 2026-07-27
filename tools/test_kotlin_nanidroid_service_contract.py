#!/usr/bin/env python3
"""Regression contract for the Kotlin-owned foreground download service."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "src/com/cattailsw/nanidroid/NanidroidService.kt"
LEGACY = ROOT / "legacy/src/com/cattailsw/nanidroid/NanidroidService.java"


class KotlinNanidroidServiceContractTest(unittest.TestCase):
    def test_kotlin_service_preserves_manifest_and_java_command_api(self):
        source = SOURCE.read_text(encoding="utf-8")
        manifest = (ROOT / "AndroidManifest.xml").read_text(encoding="utf-8")

        self.assertIn("class NanidroidService : Service()", source)
        self.assertIn('android:name=".NanidroidService"', manifest)
        self.assertIn("const val ACTION_CAN_STOP = \"canstopsensing\"", source)
        self.assertIn("const val EXT_GID = \"ghost_id_to_update\"", source)
        self.assertIn("const val EXT_GROOT = \"ghost_root_to_update\"", source)
        self.assertIn("@JvmStatic", source)
        self.assertIn("fun createUpdateIntent(", source)
        self.assertIn("Intent.ACTION_SYNC", source)

    def test_rejects_untrusted_download_and_update_commands_before_work(self):
        source = SOURCE.read_text(encoding="utf-8")

        self.assertIn("IncomingNarIntent.isApprovedDownload(data)", source)
        self.assertIn('"Rejected non-HTTPS archive download request"', source)
        self.assertIn("isHttpsUri(homeurl) && gid != null", source)
        self.assertIn('"Rejected update request without an HTTPS URL and ghost id"', source)
        self.assertIn('"https".equals(uri.scheme, ignoreCase = true)', source)
        self.assertNotIn("Uri.fromFile", source)

    def test_foreground_lifetime_and_pending_intent_hardening_survive_port(self):
        source = SOURCE.read_text(encoding="utf-8")

        self.assertIn("activeForegroundStartIds.add(startId)", source)
        self.assertIn("activeForegroundStartIds.remove(startId)", source)
        self.assertIn("activeForegroundStartIds.isEmpty()", source)
        self.assertIn("stopForeground(true)", source)
        self.assertIn("flags = flags or FLAG_IMMUTABLE", source)
        self.assertIn("PendingIntent.FLAG_UPDATE_CURRENT", source)
        self.assertIn("startForeground(FOREGROUND_NOTIFICATION_ID", source)

    def test_frozen_java_overlay_remains_for_the_ant_compatibility_build(self):
        self.assertTrue(LEGACY.is_file())
        self.assertIn("public class NanidroidService extends Service", LEGACY.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
