from __future__ import annotations

import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
ARCHIVE_MIME_TYPES = {"application/zip", "application/x-nar"}
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


def archive_view_filters(manifest: ET.Element) -> list[ET.Element]:
    return [
        intent_filter
        for intent_filter in manifest.iter("intent-filter")
        if any(
            action.get(f"{ANDROID}name") == "android.intent.action.VIEW"
            for action in intent_filter.findall("action")
        )
        and any(
            any(
                mime_pattern_matches(
                    data.get(f"{ANDROID}mimeType"),
                    archive_mime_type,
                )
                for archive_mime_type in ARCHIVE_MIME_TYPES
            )
            for data in intent_filter.findall("data")
        )
    ]


def mime_pattern_matches(pattern: str | None, target: str) -> bool:
    if pattern is None or pattern.count("/") != 1:
        return False
    pattern_type, pattern_subtype = pattern.split("/", 1)
    target_type, target_subtype = target.split("/", 1)
    if not pattern_type or not pattern_subtype:
        return False
    if "*" in pattern_type and pattern_type != "*":
        return False
    if "*" in pattern_subtype and pattern_subtype != "*":
        return False
    if pattern_type == "*" and pattern_subtype != "*":
        return False
    return (pattern_type == "*" or pattern_type == target_type) and (
        pattern_subtype == "*" or pattern_subtype == target_subtype
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

    def test_activity_is_exported_single_top_with_only_the_launcher_filter(self) -> None:
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
        self.assertEqual([], view_filters)
        launcher_filters = [
            intent_filter
            for intent_filter in activity.findall("intent-filter")
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

    def test_archive_view_filter_scan_covers_other_activities_and_aliases(self) -> None:
        manifest = ET.fromstring(
            f"""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <application>
                <activity android:name=".Nanidroid" />
                <activity android:name=".DependencyActivity">
                  <intent-filter>
                    <action android:name="android.intent.action.VIEW" />
                    <data android:mimeType="application/zip" />
                  </intent-filter>
                  <intent-filter>
                    <action android:name="android.intent.action.VIEW" />
                    <data android:mimeType="application/*" />
                  </intent-filter>
                  <intent-filter>
                    <action android:name="android.intent.action.VIEW" />
                    <data android:mimeType="Application/*" />
                  </intent-filter>
                  <intent-filter>
                    <action android:name="android.intent.action.VIEW" />
                    <data android:mimeType="Application/Zip" />
                  </intent-filter>
                  <intent-filter>
                    <action android:name="android.intent.action.VIEW" />
                    <data android:mimeType="Application/X-Nar" />
                  </intent-filter>
                  <intent-filter>
                    <action android:name="android.intent.action.VIEW" />
                    <data android:mimeType="text/plain" />
                  </intent-filter>
                  <intent-filter>
                    <action android:name="android.intent.action.VIEW" />
                    <data android:mimeType="image/*" />
                  </intent-filter>
                </activity>
                <activity-alias
                    android:name=".ArchiveAlias"
                    android:targetActivity=".Nanidroid">
                  <intent-filter>
                    <action android:name="android.intent.action.VIEW" />
                    <data android:mimeType="application/x-nar" />
                  </intent-filter>
                  <intent-filter>
                    <action android:name="android.intent.action.VIEW" />
                    <data android:mimeType="*/*" />
                  </intent-filter>
                </activity-alias>
              </application>
            </manifest>
            """
        )

        filters = archive_view_filters(manifest)

        self.assertEqual(4, len(filters))
        self.assertEqual(
            {
                "application/zip",
                "application/x-nar",
                "application/*",
                "*/*",
            },
            {
                data.get(f"{ANDROID}mimeType")
                for intent_filter in filters
                for data in intent_filter.findall("data")
            },
        )

    def test_packaged_manifest_has_no_archive_view_filters(self) -> None:
        self.assertEqual([], archive_view_filters(self.manifest))

    def test_removed_services_and_workmanager_components_are_absent(self) -> None:
        permissions = {
            item.get(f"{ANDROID}name")
            for item in self.manifest.findall("uses-permission")
        }
        self.assertTrue(
            permissions.isdisjoint(
                {
                    "android.permission.FOREGROUND_SERVICE",
                    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                    "android.permission.INTERNET",
                    "android.permission.POST_NOTIFICATIONS",
                    "android.permission.ACCESS_NETWORK_STATE",
                    "android.permission.RECEIVE_BOOT_COMPLETED",
                    "android.permission.WAKE_LOCK",
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE",
                    "android.permission.MANAGE_EXTERNAL_STORAGE",
                }
            )
        )
        service_names = {
            item.get(f"{ANDROID}name")
            for item in self.application.findall("service")
        }
        self.assertNotIn("com.cattailsw.nanidroid.NanidroidService", service_names)
        self.assertFalse(
            any(name.startswith("androidx.work.") for name in service_names)
        )

    def test_no_workmanager_receivers_remain(self) -> None:
        receiver_names = {
            item.get(f"{ANDROID}name")
            for item in self.application.findall("receiver")
        }
        self.assertFalse(
            any(name.startswith("androidx.work.") for name in receiver_names)
        )
        self.assertIn(
            "androidx.profileinstaller.ProfileInstallReceiver",
            receiver_names,
        )

    def test_workmanager_initializer_metadata_is_absent(self) -> None:
        initializer_names = {
            metadata.get(f"{ANDROID}name")
            for provider in self.application.findall("provider")
            for metadata in provider.findall("meta-data")
        }
        self.assertNotIn(
            "androidx.work.WorkManagerInitializer",
            initializer_names,
        )


if __name__ == "__main__":
    unittest.main()
