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

    def test_frozen_ant_overlay_keeps_java_kero_view(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        source = (root / "legacy/src/com/cattailsw/nanidroid/KeroView.java").read_text(encoding="utf-8")

        self.assertIn("public class KeroView extends SakuraView", source)
        self.assertIn("mgr.getKeroSurface(surfaceid)", source)


if __name__ == "__main__":
    unittest.main()
