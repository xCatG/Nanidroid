import pathlib
import unittest


class ViewServerRetirementContractTest(unittest.TestCase):
    def test_active_product_has_no_view_server_or_lifecycle_adapter(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/ViewServerLifecycle.kt").exists())
        self.assertFalse((root / "src/main/kotlin/com/android/debug/hv/ViewServer.kt").exists())
        active_sources = (root / "src").rglob("*.kt")
        self.assertFalse(any("ViewServer" in source.read_text(encoding="utf-8") for source in active_sources))


if __name__ == "__main__":
    unittest.main()
