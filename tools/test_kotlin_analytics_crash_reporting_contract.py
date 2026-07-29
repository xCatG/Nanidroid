import unittest
from pathlib import Path


class KotlinAnalyticsAndCrashReportingContractTest(unittest.TestCase):
    def test_modern_boundaries_are_kotlin_with_java_visible_entrypoints(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse(
            (root / "src/com/cattailsw/nanidroid/util/AnalyticsUtils.java").exists()
        )
        self.assertFalse(
            (root / "src/com/cattailsw/nanidroid/util/CrashReporting.java").exists()
        )

        analytics = (
            root / "src/com/cattailsw/nanidroid/util/AnalyticsUtils.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("open class AnalyticsUtils private constructor(context: Context?)", analytics)
        self.assertIn("fun getInstance(ctx: Context?, uaCode: String?, enableAnalytics: Boolean)", analytics)
        self.assertIn("fun getInstance(context: Context?): AnalyticsUtils", analytics)
        self.assertIn("fun setDeviceValidationNoTelemetry(disabled: Boolean)", analytics)
        self.assertIn(
            "fun trackEvent(category: String?, action: String?, label: String?, value: Int)",
            analytics,
        )
        self.assertIn("fun trackPageView(path: String?)", analytics)
        self.assertIn("object : AnalyticsUtils(null)", analytics)
        self.assertIn("override fun trackEvent", analytics)
        self.assertIn("override fun trackPageView", analytics)
        self.assertIn("override fun dispatch", analytics)
        self.assertIn("analyticsEnabled = !deviceValidationNoTelemetry && enableAnalytics", analytics)
        self.assertGreaterEqual(analytics.count("@JvmStatic"), 3)

        crash_reporting = (
            root / "src/com/cattailsw/nanidroid/util/CrashReporting.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("object CrashReporting", crash_reporting)
        self.assertIn("fun initialize(application: Application)", crash_reporting)
        self.assertIn("FirebaseApp.initializeApp(application) ?: run", crash_reporting)
        self.assertIn("fun setCustomKey(key: String, value: String)", crash_reporting)
        self.assertIn("if (enabled)", crash_reporting)
        self.assertGreaterEqual(crash_reporting.count("@JvmStatic"), 2)

    def test_current_boundaries_have_no_archived_java_counterparts(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "legacy").exists())
        self.assertFalse((root / "src/com/cattailsw/nanidroid/util/AnalyticsUtils.java").exists())
        self.assertFalse((root / "src/com/cattailsw/nanidroid/util/CrashReporting.java").exists())


if __name__ == "__main__":
    unittest.main()
