import unittest
from pathlib import Path


class FirebaseCrashlyticsContractTest(unittest.TestCase):
    def test_acra_is_replaced_by_firebase_crashlytics_boundary(self):
        root = Path(__file__).resolve().parents[1]
        build = (root / "build.gradle.kts").read_text(encoding="utf-8")
        app = (root / "src/com/cattailsw/nanidroid/CatTailApplication.java").read_text(
            encoding="utf-8"
        )
        activity = (root / "src/com/cattailsw/nanidroid/Nanidroid.java").read_text(
            encoding="utf-8"
        )
        boundary = (
            root / "src/com/cattailsw/nanidroid/util/CrashReporting.kt"
        ).read_text(encoding="utf-8")
        legacy_boundary = (
            root / "legacy/src/com/cattailsw/nanidroid/util/CrashReporting.java"
        ).read_text(encoding="utf-8")

        self.assertNotIn("acra-4.2.3", build)
        self.assertIn("firebase-bom:34.16.0", build)
        self.assertIn("firebase-crashlytics", build)
        self.assertNotIn("org.acra", app)
        self.assertNotIn("org.acra", activity)
        self.assertIn("CrashReporting.initialize(this)", app)
        self.assertIn("FirebaseApp.initializeApp(application)", boundary)
        self.assertIn("FirebaseCrashlytics.getInstance()", boundary)
        self.assertIn("setCustomKey", boundary)
        self.assertIn('CrashReporting.setCustomKey("current_ghost"', activity)
        self.assertIn("Frozen Ant-build compatibility shim", legacy_boundary)
        self.assertNotIn("org.acra", legacy_boundary)

    def test_setup_documentation_preserves_no_credentials_policy(self):
        root = Path(__file__).resolve().parents[1]
        documentation = (root / "docs/firebase-crashlytics.md").read_text(
            encoding="utf-8"
        )
        self.assertIn("google-services.json", documentation)
        self.assertIn("contains no Firebase credentials", documentation)


if __name__ == "__main__":
    unittest.main()
