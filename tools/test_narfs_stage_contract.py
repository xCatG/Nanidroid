import hashlib
import unittest
from pathlib import Path


class NarfsStageContractTest(unittest.TestCase):
    def test_flat_blob_stage_uses_the_borrowed_inspector_fd(self):
        project = Path(__file__).resolve().parents[1]
        header = (project / "jni/narfs/narfs_stage.h").read_text()
        source = (project / "jni/narfs/narfs_stage.c").read_text()

        for token in (
            "narfs_stage_existing", "narfs_stage_discard",
            "narfs_stage_result_dispose", "stage_device", "stage_inode",
            "blob_ordinal", "sha256[32]"):
            self.assertIn(token, header)
        for token in (
            "narfs_inspect(", '#include "narfs_sha256.h"', "O_NOFOLLOW",
            "O_EXCL", "openat(", "fsync(", '"b%06u"'):
            self.assertIn(token, source)
        for forbidden in (
            "GhostMgr", "overlay", "journal", "publish", "sha_transform",
            "sha_k[", "open(entry->relative_path"):
            self.assertNotIn(forbidden, source)

        core = (project / "jni/narfs/narfs_core.c").read_text()
        self.assertIn("state.visitor(", core)
        self.assertIn("same_snapshot(&opened, &after)", core)

    def test_java_jni_boundary_remains_exact_and_manager_uses_transaction(self):
        project = Path(__file__).resolve().parents[1]
        expected = {
            "jni/narfs/narfs_jni.c": "2198c6549e33c5d9a38045d536526dad67262bab1f35b62174b046a4be84bf56",
            "jni/narfs/narfs_jni.map": "02f45b0ae1431df655013d5b707e11602c251d9c0ae5cf74c6237eff78bd5819",
            "src/com/cattailsw/nanidroid/install/NarFilesystemInspector.java": "e2da6a2d3a6e4c25bb37b6cf52e5e3a8de440b3eade5fd2df37261a09322bf47",
        }
        actual = {
            relative: hashlib.sha256(
                (project / relative).read_bytes().replace(b"\r\n", b"\n")
            ).hexdigest()
            for relative in expected
        }
        self.assertEqual(expected, actual)
        manager = (project / "src/com/cattailsw/nanidroid/GhostMgr.kt").read_text()
        self.assertIn("NarTransactionalInstaller.install", manager)
        self.assertNotIn("NarUtil.readNarArchive", manager)

    def test_process_death_orphan_recovery_is_explicitly_deferred(self):
        project = Path(__file__).resolve().parents[1]
        header = (project / "jni/narfs/narfs_stage.h").read_text()
        self.assertIn("D9b3", header)
        self.assertIn("process-death", header)


if __name__ == "__main__":
    unittest.main()
