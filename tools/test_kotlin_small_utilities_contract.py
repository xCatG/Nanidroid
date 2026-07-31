import unittest
from pathlib import Path


class KotlinSmallUtilitiesContractTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(__file__).resolve().parents[1]

    def test_gradle_utilities_are_kotlin_and_keep_java_static_entry_points(self):
        pref = (self.root / "src/main/kotlin/com/cattailsw/nanidroid/util/PrefUtil.kt").read_text(
            encoding="utf-8"
        )
        ui = (self.root / "src/main/kotlin/com/cattailsw/nanidroid/util/UIUtil.kt").read_text(
            encoding="utf-8"
        )
        self.assertFalse((self.root / "src/main/kotlin/com/cattailsw/nanidroid/util/PrefUtil.java").exists())
        self.assertFalse((self.root / "src/main/kotlin/com/cattailsw/nanidroid/util/UIUtil.java").exists())
        self.assertEqual(pref.count("@JvmStatic"), 9)
        for name in (
            "getSharedPreferences", "hasKey", "setKey", "getKeyValue", "getKeyValueLong"
        ):
            self.assertIn("fun " + name, pref)
        self.assertIn('private const val SHARED_PREFS = "CATTAILSW_NANIDROID_PREFS"', pref)
        self.assertIn(".commit()", pref)
        self.assertIn("fun setKey(ctx: Context?, key: String, value: String?)", pref)
        self.assertEqual(pref.count("fun setKey"), 5)
        self.assertEqual(ui.count("@JvmStatic"), 2)
        self.assertIn("fun isAfterEclair(): Boolean", ui)
        self.assertIn("fun isGingerbread(): Boolean", ui)

    def test_utilities_have_no_archived_java_overlay(self):
        self.assertFalse((self.root / "legacy").exists())


if __name__ == "__main__":
    unittest.main()
