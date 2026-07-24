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

    def test_staging_jni_and_java_boundaries_remain_exact(self):
        root = Path(__file__).resolve().parents[1]
        expected = {
            "jni/narfs/narfs_core.c":
                "7f508f1a24de64cb7156334eca96f30eab9c0b20a367085f1244915bda5b424b",
            "jni/narfs/narfs_jni.c":
                "2198c6549e33c5d9a38045d536526dad67262bab1f35b62174b046a4be84bf56",
            "src/com/cattailsw/nanidroid/GhostMgr.java":
                "65dc3709240aa0bf871f5955b2358da5deb505c6f7f91f13bb4e830abda809f6",
        }
        actual = {
            name: hashlib.sha256(
                (root / name).read_bytes().replace(b"\r\n", b"\n")
            ).hexdigest()
            for name in expected
        }
        self.assertEqual(expected, actual)

    def test_dual_build_declarations_are_exact(self):
        root = Path(__file__).resolve().parents[1]
        cmake = (root / "jni/narfs/sha256/CMakeLists.txt").read_text()
        make = (root / "jni/narfs/sha256/Android.mk").read_text()
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
        self.assertIn(
            'APP_MODULES="narfs narfs_sha256_link_probe"', script)
        self.assertIn(
            "--target narfs narfs_sha256_link_probe", script)
        self.assertIn("expected_sha_api=", script)
        self.assertIn('"${readelf}" --syms "${archive}"', script)
        self.assertIn("libnarfs_sha256.a", script)


if __name__ == "__main__":
    unittest.main()
