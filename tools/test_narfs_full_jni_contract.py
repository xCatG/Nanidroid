import hashlib
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class NarfsFullJniContractTest(unittest.TestCase):
    def test_full_profile_is_private_mutually_exclusive_and_unpublished(self):
        make = (ROOT / "jni/narfs/Android.mk").read_text()
        cmake = (ROOT / "jni/narfs/CMakeLists.txt").read_text()
        script = (ROOT / "docker/narfs-jni/build.sh").read_text()
        for value in (
            "NANIDROID_NARFS_FULL_JNI_CANDIDATE",
            "narfs_full", "narfs_full_jni.map",
            "narfs_stage narfs_core narfs_sha256",
        ):
            self.assertIn(value, make + cmake + script)
        self.assertIn("full and inspector JNI candidates are exclusive",
                      make + cmake)
        self.assertIn("narfs_stage_token_test", script)
        self.assertNotRegex(script, r"(?:cp|mv).+libnarfs\.so.+OUTPUT_ROOT")
        self.assertNotIn("NANIDROID_NARFS_FULL_JNI_CANDIDATE",
                         (ROOT / "build.gradle.kts").read_text())

    def test_bridge_uses_standard_utf_factory_and_self_discard(self):
        source = (ROOT / "jni/narfs/narfs_stage_jni.c").read_text()
        descriptor = (
            "(IIIJJ[B[Ljava/lang/String;[I[J[I[B)"
            "Lcom/cattailsw/nanidroid/install/"
            "NarStagedTree$BeginResult;"
        )
        self.assertIn(descriptor, source)
        for value in (
            "GetStringChars", "ReleaseStringChars",
            "narfs_utf16_to_utf8", "narfs_utf8_to_utf16",
            "FindClass", "GetStaticMethodID", "CallStaticObjectMethod",
            "NarStagedTree", "fromNativeBegin",
            "narfs_stage_existing", "narfs_stage_discard",
            "narfs_stage_token_encode", "narfs_stage_token_decode",
        ):
            self.assertIn(value, source)
        for forbidden in (
            "GetStringUTF", "NewStringUTF", "ExceptionClear",
            "destination", "registry", "nativeDescribe",
        ):
            self.assertNotIn(forbidden, source)
        self.assertRegex(
            source,
            r"if \(output == NULL \|\| \(\*env\)->ExceptionCheck\(env\)\)"
            r"[\s\S]+narfs_stage_discard")
        for operation in (
            "FindClass", "GetStaticMethodID", "NewObjectArray",
            "NewIntArray", "NewLongArray", "NewByteArray", "NewString",
            "SetObjectArrayElement", "SetIntArrayRegion",
            "SetLongArrayRegion", "SetByteArrayRegion",
            "CallStaticObjectMethod",
        ):
            self.assertRegex(
                source,
                operation + r"[\s\S]{0,500}"
                r"(?:goto done|return NULL)")
        for variable in ("paths", "types", "sizes", "ordinals", "digests"):
            self.assertRegex(
                source,
                rf"{variable} = \(\*env\)->New[\s\S]{{0,200}}"
                rf"if \({variable} == NULL \|\| "
                r"\(\*env\)->ExceptionCheck\(env\)\) goto done;")

    def test_token_is_fixed_versioned_reserved_and_inode_bound(self):
        header = (ROOT / "jni/narfs/narfs_stage_token.h").read_text()
        for value in (
            "NARFS_STAGE_WIRE_BYTES 88U",
            "NARFS_STAGE_WIRE_VERSION 1U",
            "root_device", "root_inode", "stage_device", "stage_inode",
            "narfs_stage_token_encode", "narfs_stage_token_decode",
        ):
            self.assertIn(value, header)
        self.assertIn("reserved", header)

    def test_existing_inspector_profile_sources_and_map_are_unchanged(self):
        expected = {
            "jni/narfs/narfs_jni.c":
                "2198c6549e33c5d9a38045d536526dad67262bab1f35b62174b046a4be84bf56",
            "jni/narfs/narfs_jni.map":
                "02f45b0ae1431df655013d5b707e11602c251d9c0ae5cf74c6237eff78bd5819",
            "jni/narfs/narfs_utf.c":
                "6968d471affac1e6e470f5eda37c7d0b814d8060b30908362f964bc7ef6e2800",
        }
        actual = {
            name: hashlib.sha256(
                (ROOT / name).read_bytes().replace(b"\r\n", b"\n")
            ).hexdigest()
            for name in expected
        }
        self.assertEqual(expected, actual)


if __name__ == "__main__":
    unittest.main()
