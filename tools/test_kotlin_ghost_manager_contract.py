import unittest
from pathlib import Path


class KotlinGhostManagerContractTest(unittest.TestCase):
    def test_ghost_manager_is_kotlin_and_preserves_java_call_surface(self):
        root = Path(__file__).resolve().parents[1]
        java = root / "src/com/cattailsw/nanidroid/GhostMgr.java"
        kotlin = root / "src/com/cattailsw/nanidroid/GhostMgr.kt"
        legacy = root / "legacy/src/com/cattailsw/nanidroid/GhostMgr.java"

        self.assertFalse(java.exists())
        self.assertTrue(legacy.exists())
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

    def test_legacy_ant_copy_uses_the_complete_frozen_project(self):
        root = Path(__file__).resolve().parents[1]
        build = (root / "docker/legacy/build.sh").read_text(encoding="utf-8")
        self.assertIn('REFERENCE_PROJECT_ROOT="${SOURCE_ROOT}/legacy/reference-project"', build)
        self.assertIn('REFERENCE_THIRD_PARTY_ROOT="${SOURCE_ROOT}/legacy/reference-third-party"', build)
        self.assertIn('REFERENCE_VALIDATOR="${SOURCE_ROOT}/tools/verify_legacy_reference_snapshot.py"', build)
        self.assertIn('"${REFERENCE_PROJECT_ROOT}" "${REFERENCE_THIRD_PARTY_ROOT}"', build)
        self.assertIn(
            'rsync -a "${REFERENCE_PROJECT_ROOT}/" "${REFERENCE_BUILD_ROOT}/"',
            build,
        )


if __name__ == "__main__":
    unittest.main()
