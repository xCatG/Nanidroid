import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
TOOLS = "{http://schemas.android.com/tools}"
ACTIVITY = ROOT / "src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt"
BACKEND = (
    ROOT
    / "src/main/kotlin/com/cattailsw/nanidroid/install/ForegroundNarImportBackend.kt"
)
SOURCE_MANIFEST = ROOT / "src/main/AndroidManifest.xml"


class KotlinForegroundNarImportContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.activity = ACTIVITY.read_text(encoding="utf-8")
        cls.backend = BACKEND.read_text(encoding="utf-8")
        cls.manifest = ET.parse(SOURCE_MANIFEST).getroot()
        cls.application = cls.manifest.find("application")
        assert cls.application is not None

    def test_document_picker_is_the_only_live_archive_ingress(self) -> None:
        activity = self.activity
        backend = self.backend

        self.assertIn("ActivityResultContracts.OpenDocument", activity)
        self.assertIn('arrayOf("*/*")', activity)
        self.assertIn("ForegroundNarImportCoordinator.get", activity)
        self.assertNotIn("NarDownloadRepository", activity)
        self.assertNotIn("NarLiveGrantHandoff", activity)
        self.assertNotIn("handleIncomingIntent", activity)
        self.assertNotIn("enqueuePendingArchiveIntent", activity)
        self.assertNotIn("override fun onNewIntent", activity)
        self.assertNotIn("ArchiveIntent", activity)
        self.assertNotIn("takePersistableUriPermission", activity)
        self.assertIn("NarContentUriImport.importContent", backend)
        self.assertIn("NarTransactionalInstaller.install", backend)

    def test_source_manifest_has_no_archive_or_durable_legacy_root(self) -> None:
        view_filters = [
            intent_filter
            for intent_filter in self.manifest.iter("intent-filter")
            if any(
                action.get(f"{ANDROID}name") == "android.intent.action.VIEW"
                for action in intent_filter.findall("action")
            )
        ]
        self.assertEqual([], view_filters)
        self.assertEqual([], self.application.findall("receiver"))

        permissions = {
            item.get(f"{ANDROID}name")
            for item in self.manifest.findall("uses-permission")
        }
        self.assertTrue(
            permissions.isdisjoint(
                {
                    "android.permission.ACCESS_NETWORK_STATE",
                    "android.permission.INTERNET",
                    "android.permission.POST_NOTIFICATIONS",
                    "android.permission.RECEIVE_BOOT_COMPLETED",
                }
            )
        )

    def test_launcher_and_manifest_merge_controls_are_preserved(self) -> None:
        activities = self.application.findall("activity")
        self.assertEqual(1, len(activities))
        launcher = activities[0]
        self.assertEqual("Nanidroid", launcher.get(f"{ANDROID}name"))
        self.assertEqual("true", launcher.get(f"{ANDROID}exported"))
        self.assertEqual("singleTop", launcher.get(f"{ANDROID}launchMode"))
        launcher_filters = [
            intent_filter
            for intent_filter in launcher.findall("intent-filter")
            if any(
                action.get(f"{ANDROID}name") == "android.intent.action.MAIN"
                for action in intent_filter.findall("action")
            )
            and any(
                category.get(f"{ANDROID}name") == "android.intent.category.LAUNCHER"
                for category in intent_filter.findall("category")
            )
        ]
        self.assertEqual(1, len(launcher_filters))

        initializer_removals = [
            item
            for provider in self.application.findall("provider")
            for item in provider.findall("meta-data")
            if item.get(f"{ANDROID}name") == "androidx.work.WorkManagerInitializer"
            and item.get(f"{TOOLS}node") == "remove"
        ]
        self.assertEqual(1, len(initializer_removals))
        foreground_tombstones = [
            item
            for item in self.manifest.findall("uses-permission")
            if item.get(f"{ANDROID}name") == "android.permission.FOREGROUND_SERVICE"
            and item.get(f"{TOOLS}node") == "remove"
        ]
        self.assertEqual(1, len(foreground_tombstones))

    def test_trusted_bundled_install_and_outgoing_browser_intents_remain(self) -> None:
        self.assertIn('assets.open("nanidroid.zip")', self.activity)
        self.assertIn("Intent.ACTION_VIEW", self.activity)


if __name__ == "__main__":
    unittest.main()
