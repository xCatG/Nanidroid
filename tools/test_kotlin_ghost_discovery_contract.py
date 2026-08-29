import re
import unittest
from pathlib import Path


class KotlinGhostDiscoveryContractTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(__file__).resolve().parents[1]
        self.runtime_source = (
            self.root / "src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt"
        ).read_text(encoding="utf-8")

    def test_metadata_inheritance_helpers_are_absent(self):
        self.assertFalse((self.root / "src/main/kotlin/com/cattailsw/nanidroid/InfoOnlyGhost.java").exists())
        self.assertFalse((self.root / "src/main/kotlin/com/cattailsw/nanidroid/InfoOnlyGhost.kt").exists())

    def test_discovery_uses_the_immutable_installed_catalog(self):
        self.assertFalse((self.root / "src/main/kotlin/com/cattailsw/nanidroid/DirList.java").exists())
        self.assertFalse((self.root / "src/main/kotlin/com/cattailsw/nanidroid/DirList.kt").exists())
        preparation_source = (self.root / "src/main/kotlin/com/cattailsw/nanidroid/GhostPreparation.kt").read_text(
            encoding="utf-8"
        )
        self.assertFalse(
            (self.root / "src/main/kotlin/com/cattailsw/nanidroid/GhostMgr.kt").exists()
        )
        self.assertIn("InstalledGhostCatalog.scan", self.runtime_source)
        self.assertIn("RuntimeCatalogState.Ready", self.runtime_source)
        self.assertIn("internal object InstalledGhostCatalog", preparation_source)
        self.assertNotIn("Shiori", preparation_source)
        self.assertNotRegex(preparation_source, r"\bnative\b")

    def test_dead_manager_install_and_error_bypasses_are_absent(self):
        activity = (
            self.root / "src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt"
        ).read_text(encoding="utf-8")
        for obsolete in ("GhostMgr", "hasSameGhostId", "getLastInstallError", "lastInstallError"):
            self.assertNotIn(obsolete, activity)

    def test_bundled_install_and_cancellable_install_core_remain(self):
        application = (
            self.root / "src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("NarTransactionalInstaller", application)
        self.assertIn("CatalogPublicationToken", application)

    def test_discovery_has_no_archived_java_overlay(self):
        self.assertFalse((self.root / "legacy").exists())


if __name__ == "__main__":
    unittest.main()
