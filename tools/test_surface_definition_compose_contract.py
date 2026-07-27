import pathlib
import unittest


class SurfaceDefinitionComposeContractTest(unittest.TestCase):
    def test_platform_neutral_surface_model_and_mapper_exist(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        source = (root / "src/com/cattailsw/nanidroid/SurfaceDefinition.kt").read_text(encoding="utf-8")
        self.assertIn("data class SurfaceDefinition", source)
        self.assertIn("data class SurfaceAnimation", source)
        self.assertIn("data class SurfaceCollision", source)
        self.assertIn("fun ShellSurface.toSurfaceDefinition()", source)
        self.assertNotIn("android.graphics", source)
        self.assertNotIn("android.graphics.drawable", source)


if __name__ == "__main__":
    unittest.main()
