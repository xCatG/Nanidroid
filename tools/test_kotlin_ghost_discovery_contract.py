import unittest
from pathlib import Path


class KotlinGhostDiscoveryContractTest(unittest.TestCase):
    def test_metadata_inheritance_helpers_are_absent(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/InfoOnlyGhost.java").exists())
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/InfoOnlyGhost.kt").exists())

    def test_discovery_uses_the_immutable_installed_catalog(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/DirList.java").exists())
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/DirList.kt").exists())
        manager_source = (root / "src/main/kotlin/com/cattailsw/nanidroid/GhostMgr.kt").read_text(
            encoding="utf-8"
        )
        preparation_source = (root / "src/main/kotlin/com/cattailsw/nanidroid/GhostPreparation.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("InstalledGhostCatalog.scan(context)", manager_source)
        self.assertIn("internal object InstalledGhostCatalog", preparation_source)
        self.assertNotIn("Shiori", preparation_source)
        self.assertNotRegex(preparation_source, r"\bnative\b")

    def test_discovery_has_no_archived_java_overlay(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "legacy").exists())


if __name__ == "__main__":
    unittest.main()
