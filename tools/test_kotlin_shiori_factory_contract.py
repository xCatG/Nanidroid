import unittest
from pathlib import Path


class KotlinShioriFactoryContractTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(__file__).resolve().parents[1]
        self.runtime = (
            self.root / "src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt"
        ).read_text(encoding="utf-8")

    def test_static_factory_is_absent_and_runtime_owns_adapter_construction(self):
        factory_root = self.root / "src/main/kotlin/com/cattailsw/nanidroid"
        self.assertFalse((factory_root / "ShioriFactory.java").exists())
        self.assertFalse((factory_root / "ShioriFactory.kt").exists())
        self.assertIn("private fun createAdapter(prepared: PreparedGhost): Shiori", self.runtime)

    def test_runtime_routes_every_prepared_engine_to_its_exact_adapter(self):
        self.assertIn(
            "GhostEngine.Satori -> SatoriShiori(master, applicationContext)",
            self.runtime,
        )
        self.assertIn(
            "GhostEngine.Yaya -> YayaShiori(master, applicationContext)",
            self.runtime,
        )
        self.assertIn("GhostEngine.Kawari -> Kawari(master)", self.runtime)
        self.assertIn(
            "GhostEngine.Nanidroid -> NanidroidShiori(applicationContext, prepared.nanidroidContent)",
            self.runtime,
        )
        self.assertIn(
            "GhostEngine.Unsupported -> NotSupportedShiori(applicationContext)",
            self.runtime,
        )
        self.assertNotIn("SatoriPosixShiori(", self.runtime)

    def test_mainline_has_no_archived_factory(self):
        self.assertFalse((self.root / "legacy").exists())


if __name__ == "__main__":
    unittest.main()
