import pathlib
import unittest


class SurfaceHitTestContractTest(unittest.TestCase):
    def test_hit_test_is_platform_neutral_and_uses_rect_boundaries(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        source = (root / "src/main/kotlin/com/cattailsw/nanidroid/SurfaceHitTest.kt").read_text(encoding="utf-8")
        self.assertIn("fun findCollisionId", source)
        self.assertIn("collision.shape.contains", source)
        self.assertNotIn("android.graphics", source)


if __name__ == "__main__":
    unittest.main()
