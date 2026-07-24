import hashlib
import unittest
from pathlib import Path


class NarfsStageContractTest(unittest.TestCase):
    def test_flat_blob_stage_uses_the_borrowed_inspector_fd(self):
        project = Path(__file__).resolve().parents[1]
        header = (project / "jni/narfs/narfs_stage.h").read_text()
        source = (project / "jni/narfs/narfs_stage.c").read_text()

        for token in (
            "narfs_stage_existing",
            "narfs_stage_discard",
            "narfs_stage_result_dispose",
            "stage_device",
            "stage_inode",
            "blob_ordinal",
            "sha256[32]",
        ):
            self.assertIn(token, header)
        for token in (
            "narfs_inspect(",
            "O_NOFOLLOW",
            "O_EXCL",
            "openat(",
            "fsync(",
            '"b%06u"',
        ):
            self.assertIn(token, source)
        for forbidden in (
            "GhostMgr",
            "overlay",
            "journal",
            "publish",
            "open(entry->relative_path",
        ):
            self.assertNotIn(forbidden, source)

    def test_java_jni_manager_and_ui_boundaries_remain_exact(self):
        project = Path(__file__).resolve().parents[1]
        expected = {
            "jni/narfs/narfs_jni.c":
                "2198c6549e33c5d9a38045d536526dad67262bab1f35b62174b046a4be84bf56",
            "jni/narfs/narfs_jni.map":
                "02f45b0ae1431df655013d5b707e11602c251d9c0ae5cf74c6237eff78bd5819",
            "src/com/cattailsw/nanidroid/install/NarFilesystemInspector.java":
                "92d9e4a12b57bfa3adc1ef7a0582416202ce5b8b7090bb782bf08818867beaff",
            "src/com/cattailsw/nanidroid/GhostMgr.java":
                "65dc3709240aa0bf871f5955b2358da5deb505c6f7f91f13bb4e830abda809f6",
        }
        actual = {
            relative: hashlib.sha256(
                (project / relative).read_bytes().replace(b"\r\n", b"\n")
            ).hexdigest()
            for relative in expected
        }
        self.assertEqual(expected, actual)

    def test_process_death_orphan_recovery_is_explicitly_deferred(self):
        project = Path(__file__).resolve().parents[1]
        header = (project / "jni/narfs/narfs_stage.h").read_text()
        self.assertIn("D9b3", header)
        self.assertIn("process-death", header)


if __name__ == "__main__":
    unittest.main()
