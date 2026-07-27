import unittest
from pathlib import Path


class KotlinGhostManagerContractTest(unittest.TestCase):
    def test_ghost_manager_is_kotlin_and_preserves_java_call_surface(self):
        root = Path(__file__).resolve().parents[1]
        java = root / "src/com/cattailsw/nanidroid/GhostMgr.java"
        kotlin = root / "src/com/cattailsw/nanidroid/GhostMgr.kt"

        self.assertFalse(java.exists())
        source = kotlin.read_text(encoding="utf-8")
        self.assertIn("class GhostMgr(ctx: Context)", source)
        for signature in (
            "fun getGhostId(name: String): Int",
            "fun installFirstGhost(gid: String, narPath: String): String?",
            "fun installGhost(gid: String, narPath: String): String?",
            "fun installGhost(ghostId: String, narPath: String, usegid: Boolean): String?",
            "fun getLastInstallError(): String?",
            "fun refreshGhost()",
            "fun getGnames(): Array<String>?",
        ):
            self.assertIn(signature, source)
        self.assertIn("NarTransactionalInstaller.install", source)
        self.assertNotIn("NarUtil.readNarArchive", source)


if __name__ == "__main__":
    unittest.main()
