import re
import unittest
from pathlib import Path


class KotlinGhostDiscoveryContractTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(__file__).resolve().parents[1]
        self.manager_source = (
            self.root / "src/main/kotlin/com/cattailsw/nanidroid/GhostMgr.kt"
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
        self.assertIn("InstalledGhostCatalog.scan(context)", self.manager_source)
        self.assertIn("internal object InstalledGhostCatalog", preparation_source)
        self.assertNotIn("Shiori", preparation_source)
        self.assertNotRegex(preparation_source, r"\bnative\b")

    def test_dead_manager_install_and_error_bypasses_are_absent(self):
        for obsolete in (
            "hasSameGhostId",
            "getLastInstallError",
            "lastInstallError",
        ):
            self.assertNotIn(obsolete, self.manager_source)

        signatures = re.findall(
            r"\b(?:private\s+)?fun\s+installGhost\s*\((.*?)\)\s*:",
            self.manager_source,
            flags=re.DOTALL,
        )
        self.assertEqual(1, len(signatures))
        parameters = re.findall(r"(?m)^\s*\w+\s*:", signatures[0])
        self.assertEqual(4, len(parameters))
        self.assertIn("private fun installGhost(", self.manager_source)

    def test_bundled_install_and_cancellable_install_core_remain(self):
        self.assertEqual(2, self.manager_source.count("fun installFirstGhost("))
        self.assertIn("isCancelled: () -> Boolean", self.manager_source)
        self.assertIn("NarTransactionalInstaller.install(", self.manager_source)

    def test_discovery_has_no_archived_java_overlay(self):
        self.assertFalse((self.root / "legacy").exists())


if __name__ == "__main__":
    unittest.main()
