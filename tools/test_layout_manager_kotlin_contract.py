import pathlib
import unittest


class LayoutManagerKotlinContractTest(unittest.TestCase):
    def test_legacy_view_adapter_consumes_the_shared_stage_geometry_policy(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        source = root / "src/com/cattailsw/nanidroid/LayoutManager.kt"
        self.assertTrue(source.exists())
        content = source.read_text(encoding="utf-8")
        self.assertIn("class LayoutManager private constructor", content)
        self.assertIn("@JvmStatic", content)
        self.assertIn("GhostStageLayoutPolicy.calculate", content)
        self.assertIn("GhostStageSize(frameLayout.width, frameLayout.height)", content)
        self.assertIn("layout.sakura.toLayoutParams()", content)
        self.assertIn("layout.keroBalloon.toLayoutParams()", content)
        self.assertIn("Gravity.RIGHT", content)
        self.assertIn("Gravity.LEFT", content)
        self.assertIn("params.bottomMargin = bottomMargin", content)
        self.assertFalse((root / "src/com/cattailsw/nanidroid/LayoutManager.java").exists())
        self.assertTrue(
            (root / "legacy/src/com/cattailsw/nanidroid/LayoutManager.java").exists()
        )


if __name__ == "__main__":
    unittest.main()
