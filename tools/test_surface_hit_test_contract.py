import pathlib
import unittest


class SurfaceHitTestContractTest(unittest.TestCase):
    def test_hit_test_delegates_to_canonical_shape_geometry_and_rect_boundaries(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        source = (root / "src/main/kotlin/com/cattailsw/nanidroid/SurfaceHitTest.kt").read_text(encoding="utf-8")
        geometry = (
            root / "src/main/kotlin/com/cattailsw/nanidroid/surface/CollisionGeometry.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("fun findCollisionId", source)
        self.assertIn("collision.shape.contains(androidx.compose.ui.unit.IntOffset(x, y))", source)
        self.assertIn("sealed interface CollisionShape", geometry)
        self.assertIn("data class Rectangle(override val bounds: IntRect) : CollisionShape", geometry)
        self.assertIn("override fun contains(point: IntOffset): Boolean = path.contains(point)", geometry)
        self.assertIn("point.x >= bounds.left && point.x < bounds.right", geometry)
        self.assertIn("point.y >= bounds.top && point.y < bounds.bottom", geometry)
        self.assertNotIn("android.graphics", source)
        self.assertNotIn("android.graphics", geometry)


if __name__ == "__main__":
    unittest.main()
