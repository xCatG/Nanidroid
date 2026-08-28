import unittest
from pathlib import Path


class KotlinGhostDomainContractTest(unittest.TestCase):
    def test_ghost_is_immutable_prepared_display_data_without_native_authority(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/Ghost.java").exists())
        self.assertFalse((root / "legacy").exists())
        source = (root / "src/main/kotlin/com/cattailsw/nanidroid/Ghost.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("internal class Ghost internal constructor(prepared: PreparedGhost)", source)
        for value in (
            "val id: String = prepared.id",
            "val canonicalRoot: File = prepared.canonicalRoot",
            "val surfaces: SurfaceCatalog = prepared.surfaces",
            "val engine: GhostEngine = prepared.engine",
        ):
            self.assertIn(value, source)
        for retired_authority in (
            "Shiori",
            "fun loadGhostInfo(",
            "fun unload(",
            "fun requestRaw(",
            "fun doShioriEvent(",
        ):
            self.assertNotIn(retired_authority, source)


if __name__ == "__main__":
    unittest.main()
