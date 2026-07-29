import unittest
from pathlib import Path


class KotlinBottleSensorsContractTest(unittest.TestCase):
    def test_gradle_sensors_are_kotlin_with_java_static_entry_points(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/com/cattailsw/nanidroid/SSTPBottleSensor.java").exists())
        self.assertFalse((root / "src/com/cattailsw/nanidroid/BottleLogSensor.java").exists())

        sstp = (root / "src/com/cattailsw/nanidroid/SSTPBottleSensor.kt").read_text(
            encoding="utf-8"
        )
        bottle_log = (root / "src/com/cattailsw/nanidroid/BottleLogSensor.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("open class SSTPBottleSensor", sstp)
        self.assertIn("class ApiException", sstp)
        self.assertIn("class ParseException", sstp)
        self.assertIn("@JvmStatic", sstp)
        self.assertIn("fun getPageContent(ctx: Context): LinkedList<String>", sstp)
        self.assertIn("protected fun parseBuffer", sstp)
        self.assertIn("open class BottleLogSensor : SSTPBottleSensor()", bottle_log)
        self.assertIn("@JvmStatic", bottle_log)
        self.assertIn("fun getPageContent(ctx: Context): LinkedList<String>", bottle_log)

    def test_sensors_have_no_archived_java_overlay(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "legacy").exists())


if __name__ == "__main__":
    unittest.main()
