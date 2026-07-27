import hashlib
import unittest
from pathlib import Path


class NarfsSha256ContractTest(unittest.TestCase):
    def test_portable_streaming_api_is_narrow(self):
        root = Path(__file__).resolve().parents[1]
        header = (root / "jni/narfs/narfs_sha256.h").read_text()
        source = (root / "jni/narfs/narfs_sha256.c").read_text()
        for token in (
            "NARFS_SHA256_BYTES", "narfs_sha256_init",
            "narfs_sha256_update", "narfs_sha256_final",
        ):
            self.assertIn(token, header)
        for forbidden in (
            "open(", "openat(", "read(", "write(", "JNI", "GhostMgr",
        ):
            self.assertNotIn(forbidden, source)

    def test_staging_jni_remains_exact_and_manager_uses_transaction(self):
        root = Path(__file__).resolve().parents[1]
        expected = {
            "jni/narfs/narfs_core.c":
                "6160699d0a2a3fdc2ffdddc3e7b225110a3749258e51159a75c2db1ae931692d",
            "jni/narfs/narfs_jni.c":
                "2198c6549e33c5d9a38045d536526dad67262bab1f35b62174b046a4be84bf56",
        }
        actual = {
            name: hashlib.sha256(
                (root / name).read_bytes().replace(b"\r\n", b"\n")
            ).hexdigest()
            for name in expected
        }
        self.assertEqual(expected, actual)
        manager = (root / "src/com/cattailsw/nanidroid/GhostMgr.kt").read_text()
        self.assertIn("NarTransactionalInstaller.install", manager)
        self.assertNotIn("NarUtil.readNarArchive", manager)

    def test_dual_build_declarations_are_exact(self):
        root = Path(__file__).resolve().parents[1]
        cmake = (root / "jni/narfs/sha256/CMakeLists.txt").read_text()
        make = (root / "jni/narfs/sha256/module.mk").read_text()
        parent_make = (root / "jni/narfs/Android.mk").read_text()
        script = (root / "docker/narfs-jni/build.sh").read_text()
        for token in (
            "add_library(narfs_sha256 STATIC ../narfs_sha256.c)",
            "add_executable(", "narfs_sha256_link_probe",
            'LINKER_LANGUAGE C LINK_FLAGS "-Wl,--no-undefined"',
        ):
            self.assertIn(token, cmake)
        for token in (
            "LOCAL_MODULE := narfs_sha256",
            "LOCAL_SRC_FILES := ../narfs_sha256.c",
            "LOCAL_MODULE := narfs_sha256_link_probe",
            "LOCAL_STATIC_LIBRARIES := narfs_sha256",
            "LOCAL_LDFLAGS := -Wl,--no-undefined",
        ):
            self.assertIn(token, make)
        self.assertFalse((root / "jni/narfs/sha256/Android.mk").exists())
        self.assertIn("NANIDROID_NARFS_SHA256_CANDIDATE", parent_make)
        self.assertIn("include $(LOCAL_PATH)/sha256/module.mk", parent_make)
        self.assertIn(
            'APP_MODULES="narfs narfs_sha256_link_probe '
            'narfs_stage_link_probe"', script)
        self.assertIn(
            "--target narfs narfs_sha256_link_probe", script)
        self.assertIn("inspect_narfs_sha256.py", script)
        self.assertIn("--build-system ndk-build", script)
        self.assertIn("--build-system cmake", script)
        self.assertIn("libnarfs_sha256.a", script)


if __name__ == "__main__":
    unittest.main()
