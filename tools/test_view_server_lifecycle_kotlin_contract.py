import pathlib
import unittest


class ViewServerLifecycleKotlinContractTest(unittest.TestCase):
    def test_characterized_lifecycle_api_remains_java_callable_after_kotlin_migration(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        source = root / "src/com/cattailsw/nanidroid/ViewServerLifecycle.kt"
        self.assertTrue(source.exists())
        content = source.read_text(encoding="utf-8")
        self.assertIn("object ViewServerLifecycle", content)
        self.assertIn("interface Backend", content)
        self.assertGreaterEqual(content.count("@JvmStatic"), 6)
        self.assertIn("sdkInt < Build.VERSION_CODES.HONEYCOMB", content)
        self.assertFalse((root / "src/com/cattailsw/nanidroid/ViewServerLifecycle.java").exists())
        self.assertTrue(
            (root / "legacy/src/com/cattailsw/nanidroid/ViewServerLifecycle.java").exists()
        )


if __name__ == "__main__":
    unittest.main()
