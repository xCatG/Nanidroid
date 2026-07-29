import pathlib
import unittest


class KotlinKeroViewContractTest(unittest.TestCase):
    def test_gradle_source_uses_open_kotlin_subclass_with_xml_constructors(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        source = (root / "src/com/cattailsw/nanidroid/KeroView.kt").read_text(encoding="utf-8")

        self.assertIn("open class KeroView @JvmOverloads constructor", source)
        self.assertIn("protected override fun loadSurface", source)
        self.assertIn("mgr!!.getKeroSurface(surfaceId)", source)
        self.assertFalse((root / "src/com/cattailsw/nanidroid/KeroView.java").exists())

    def test_kero_surface_resolution_has_no_java_or_archived_overlay(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/com/cattailsw/nanidroid/KeroView.java").exists())
        self.assertFalse((root / "legacy").exists())


if __name__ == "__main__":
    unittest.main()
