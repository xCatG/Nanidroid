import unittest
from pathlib import Path


class KotlinGhostDiscoveryContractTest(unittest.TestCase):
    def test_gradle_metadata_ghost_is_kotlin(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/InfoOnlyGhost.java").exists())
        source = (root / "src/main/kotlin/com/cattailsw/nanidroid/InfoOnlyGhost.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("class InfoOnlyGhost(path: String) : Ghost(path)", source)
        self.assertIn("override fun loadGhostInfo()", source)
        self.assertIn("override fun unload()", source)
        self.assertIn("override fun incrementCreateCount()", source)

    def test_gradle_directory_discovery_is_kotlin_and_java_callable(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/main/kotlin/com/cattailsw/nanidroid/DirList.java").exists())
        source = (root / "src/main/kotlin/com/cattailsw/nanidroid/DirList.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("object DirList", source)
        self.assertIn("@JvmStatic", source)
        self.assertIn("fun parseDataDir(ctx: Context): List<InfoOnlyGhost>?", source)
        self.assertIn('File(ctx.getExternalFilesDir(null), "ghost")', source)
        self.assertIn("InfoOnlyGhost", source)

    def test_discovery_has_no_archived_java_overlay(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "legacy").exists())


if __name__ == "__main__":
    unittest.main()
