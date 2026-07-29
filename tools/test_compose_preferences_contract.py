import pathlib
import unittest


class ComposePreferencesContractTest(unittest.TestCase):
    def test_preferences_are_a_compose_activity_with_the_legacy_shared_preference_contract(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        screen = root / "src/com/cattailsw/nanidroid/Preferences.kt"
        self.assertTrue(screen.exists())
        source = screen.read_text(encoding="utf-8")
        self.assertIn("class Preferences : ComponentActivity()", source)
        self.assertIn("setContent", source)
        self.assertIn("PreferenceManager.getDefaultSharedPreferences", source)
        self.assertIn("Setup.PREF_KEY_USE_ANALYTICS", source)
        self.assertIn("getBoolean(Setup.PREF_KEY_USE_ANALYTICS, true)", source)
        self.assertIn("putBoolean(Setup.PREF_KEY_USE_ANALYTICS, enabled)", source)
        self.assertIn("@Preview", source)
        self.assertTrue(
            (root / "test/device/com/cattailsw/nanidroid/PreferencesScreenTest.kt").exists()
        )
        self.assertFalse((root / "src/com/cattailsw/nanidroid/Preferences.java").exists())
        self.assertFalse((root / "res/xml/main_pref.xml").exists())
        self.assertFalse((root / "legacy").exists())


if __name__ == "__main__":
    unittest.main()
