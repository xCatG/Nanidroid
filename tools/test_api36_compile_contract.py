#!/usr/bin/env python3
"""Static guardrails for the API-37 target-SDK preview lane."""

from __future__ import annotations

import unittest
from pathlib import Path


class Api37CompileContractTest(unittest.TestCase):
    def test_compile_surface_targets_android_37_without_legacy_http_bridge(self) -> None:
        root = Path(__file__).resolve().parents[1]
        gradle = (root / "build.gradle.kts").read_text(encoding="utf-8")

        self.assertIn("compileSdk = 37", gradle)
        self.assertIn("minSdk = 31", gradle)
        self.assertNotIn("minSdk = 9", gradle)
        self.assertIn("targetSdk = 37", gradle)
        self.assertNotIn('useLibrary("org.apache.http.legacy", false)', gradle)
        self.assertIn('platforms/android-15/android.jar', gradle)
        self.assertNotIn("compileOnly(legacyTestApi)", gradle)
        self.assertIn("testCompileOnly(legacyTestApi)", gradle)
        self.assertIn("androidTestCompileOnly(legacyTestApi)", gradle)

    def test_removed_notification_setter_is_behind_the_min_sdk_bridge(self) -> None:
        root = Path(__file__).resolve().parents[1]
        service = (root / "src/com/cattailsw/nanidroid/NanidroidService.kt").read_text(
            encoding="utf-8"
        )
        bridge = (root / "src/com/cattailsw/nanidroid/LegacyNotificationBridge.java").read_text(
            encoding="utf-8"
        )

        self.assertNotIn("setLatestEventInfo", service)
        self.assertIn("LegacyNotificationBridge.create", service)
        self.assertIn('"setLatestEventInfo"', bridge)
        self.assertIn("Build.VERSION.SDK_INT >= 11", bridge)


if __name__ == "__main__":
    unittest.main()
