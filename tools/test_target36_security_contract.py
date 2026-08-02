"""Source-level regression checks for the target-SDK 36 security lane.

These checks intentionally do not claim to replace device tests.  They lock the
security boundaries that must not silently regress while the legacy UI is still
being decomposed.
"""

from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"


def _active_activity_source():
    kotlin = ROOT / "src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt"
    java = ROOT / "src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.java"
    return (kotlin if kotlin.exists() else java).read_text(encoding="utf-8")


class Target36SecurityContractTest(unittest.TestCase):
    def test_manifest_declares_modern_component_and_service_policy(self):
        root = ET.parse(ROOT / "src/main/AndroidManifest.xml").getroot()
        application = root.find("application")
        activity = application.find("activity")
        service = application.find("service")

        self.assertEqual("true", activity.get(ANDROID + "exported"))
        self.assertEqual("singleTop", activity.get(ANDROID + "launchMode"))
        self.assertEqual("false", service.get(ANDROID + "exported"))
        self.assertEqual("dataSync", service.get(ANDROID + "foregroundServiceType"))
        permissions = {item.get(ANDROID + "name") for item in root.findall("uses-permission")}
        self.assertIn("android.permission.FOREGROUND_SERVICE", permissions)
        self.assertIn("android.permission.FOREGROUND_SERVICE_DATA_SYNC", permissions)
        self.assertNotIn("android.permission.WRITE_EXTERNAL_STORAGE", permissions)

    def test_manifest_has_no_file_or_cleartext_deep_link_surface(self):
        manifest = (ROOT / "src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertNotIn('android:scheme="file"', manifest)
        self.assertNotIn('android:scheme="http"', manifest)
        self.assertNotIn('android:host="*"', manifest)
        self.assertNotIn('android:scheme="https"', manifest)
        self.assertIn('android:scheme="content"', manifest)
        self.assertNotIn('android:mimeType="*/*"', manifest)

    def test_activity_validates_initial_and_warm_intents(self):
        source = _active_activity_source()
        self.assertIn("handleIncomingIntent(intent)", source)
        self.assertIn("setIntent(intent)", source)
        self.assertIn("ArchiveIntentAdapter.contentUri(incoming,", source)
        self.assertNotIn("extractNar(data.getPath())", source)
        self.assertNotIn("getExternalStorageDirectory() + \"/nar/\"", source)

    def test_service_uses_immutable_pending_intent_and_no_file_uri(self):
        source = (ROOT / "src/main/kotlin/com/cattailsw/nanidroid/NanidroidService.kt").read_text(encoding="utf-8")
        self.assertIn("PendingIntent.FLAG_IMMUTABLE", source)
        self.assertIn("PendingIntent.FLAG_UPDATE_CURRENT", source)
        self.assertIn("startForeground", source)
        self.assertIn("private fun finishForegroundWork(startId: Int)", source)
        self.assertIn("activeForegroundStartIds.add(startId)", source)
        self.assertIn("activeForegroundStartIds.remove(startId)", source)
        self.assertIn("activeForegroundStartIds.isEmpty()", source)
        self.assertIn("if (noForegroundWorkRemains)", source)
        self.assertIn("stopForeground(true)", source)
        self.assertIn("finishForegroundWork(svcid)", source)
        self.assertIn("finishForegroundWork(sid)", source)
        self.assertNotIn("Uri.fromFile", source)
        activity = _active_activity_source()
        self.assertTrue(
            'getMethod("startForegroundService", Intent.class)' in activity
            or 'getMethod("startForegroundService", Intent::class.java)' in activity
        )

    def test_network_stack_rejects_cleartext_and_permissive_tls(self):
        source = (ROOT / "src/main/kotlin/com/cattailsw/nanidroid/util/NetworkUtil.kt").read_text(encoding="utf-8")
        self.assertIn("HttpsURLConnection", source)
        self.assertIn("requireHttps", source)
        self.assertNotIn("DefaultHttpClient", source)
        self.assertNotIn("MyVerifier", source)
        self.assertFalse((ROOT / "legacy").exists())

    def test_production_sources_do_not_depend_on_removed_apache_http(self):
        source_root = ROOT / "src"
        apache_users = [
            path.relative_to(ROOT).as_posix()
            for path in source_root.rglob("*.java")
            if "org.apache.http" in path.read_text(encoding="utf-8")
        ]
        self.assertEqual([], apache_users)


if __name__ == "__main__":
    unittest.main()
