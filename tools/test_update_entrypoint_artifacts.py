from __future__ import annotations

import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
MANIFEST = (
    ROOT
    / "build"
    / "intermediates"
    / "packaged_manifests"
    / "debug"
    / "processDebugManifestForPackage"
    / "AndroidManifest.xml"
)
LINT_MODULE = (
    ROOT
    / "build"
    / "intermediates"
    / "lint_report_lint_model"
    / "debug"
    / "generateDebugLintReportModel"
    / "module.xml"
)


class UpdateEntrypointArtifactTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        for artifact in (MANIFEST, LINT_MODULE):
            if not artifact.is_file():
                raise AssertionError(
                    f"missing generated artifact {artifact.relative_to(ROOT)}; "
                    "run assembleDebug and lint first"
                )
        cls.manifest = ET.parse(MANIFEST).getroot()
        cls.application = cls.manifest.find("application")
        assert cls.application is not None

    def test_sdk_floor_and_compile_target_are_generated_as_31_and_37(self) -> None:
        uses_sdk = self.manifest.find("uses-sdk")
        self.assertIsNotNone(uses_sdk)
        self.assertEqual("31", uses_sdk.get(f"{ANDROID}minSdkVersion"))
        self.assertEqual("37", uses_sdk.get(f"{ANDROID}targetSdkVersion"))

        lint_module = ET.parse(LINT_MODULE).getroot()
        self.assertEqual("android-37.0", lint_module.get("compileTarget"))

    def test_activity_is_exported_single_top_with_only_content_archive_filters(self) -> None:
        activity = next(
            item
            for item in self.application.findall("activity")
            if item.get(f"{ANDROID}name") == "com.cattailsw.nanidroid.Nanidroid"
        )
        self.assertEqual("true", activity.get(f"{ANDROID}exported"))
        self.assertEqual("singleTop", activity.get(f"{ANDROID}launchMode"))

        view_filters = [
            intent_filter
            for intent_filter in activity.findall("intent-filter")
            if any(
                action.get(f"{ANDROID}name") == "android.intent.action.VIEW"
                for action in intent_filter.findall("action")
            )
        ]
        self.assertEqual(1, len(view_filters))
        data = {
            (
                item.get(f"{ANDROID}scheme"),
                item.get(f"{ANDROID}mimeType"),
            )
            for item in view_filters[0].findall("data")
        }
        self.assertEqual(
            {
                ("content", "application/zip"),
                ("content", "application/x-nar"),
            },
            data,
        )

    def test_removed_service_and_foreground_permissions_are_absent(self) -> None:
        permissions = {
            item.get(f"{ANDROID}name")
            for item in self.manifest.findall("uses-permission")
        }
        self.assertNotIn("android.permission.FOREGROUND_SERVICE", permissions)
        self.assertNotIn("android.permission.FOREGROUND_SERVICE_DATA_SYNC", permissions)
        service_names = {
            item.get(f"{ANDROID}name")
            for item in self.application.findall("service")
        }
        self.assertNotIn("com.cattailsw.nanidroid.NanidroidService", service_names)
        self.assertIn(
            "androidx.work.impl.foreground.SystemForegroundService",
            service_names,
        )

    def test_archive_components_and_permissions_remain_narrow(self) -> None:
        permissions = {
            item.get(f"{ANDROID}name")
            for item in self.manifest.findall("uses-permission")
        }
        self.assertTrue(
            {
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.INTERNET",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.RECEIVE_BOOT_COMPLETED",
            }.issubset(permissions)
        )
        self.assertTrue(
            permissions.isdisjoint(
                {
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE",
                    "android.permission.MANAGE_EXTERNAL_STORAGE",
                }
            )
        )
        receiver_names = {
            item.get(f"{ANDROID}name")
            for item in self.application.findall("receiver")
        }
        self.assertTrue(
            {
                "com.cattailsw.nanidroid.install.NarDownloadReceiver",
                "com.cattailsw.nanidroid.install.NarDownloadRecoveryReceiver",
                "com.cattailsw.nanidroid.durable.DurableOperationAttentionReceiver",
            }.issubset(receiver_names)
        )


if __name__ == "__main__":
    unittest.main()
