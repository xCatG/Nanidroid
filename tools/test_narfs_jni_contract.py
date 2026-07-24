import copy
import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from inspect_narfs_jni import (
    CandidateContractError,
    compare_contracts,
    inspect_candidate,
)


JNI_EXPORT = (
    "Java_com_cattailsw_nanidroid_install_"
    "NarFilesystemInspector_nativeInspect"
)


class NarfsJniContractTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.dso = self.root / "libnarfs.so"
        self.dso.write_bytes(b"\x7fELFcandidate")
        self.evidence = self.root / "evidence"
        self.evidence.mkdir()
        compiler = (
            "/opt/android-ndk-r14b/toolchains/arm-linux-androideabi-4.9/"
            "prebuilt/linux-x86_64/bin/arm-linux-androideabi-gcc"
        )
        prefix = (
            compiler
            + " --sysroot=/opt/android-ndk-r14b/platforms/android-9/arch-arm"
            + " -I/tmp/project/jni/narfs"
            + " -isystem /opt/android-ndk-r14b/platforms/android-9/arch-arm/usr/include"
            + " -isystem /opt/android-ndk-r14b/platforms/android-9/arch-arm/usr/include/arm-linux-androideabi"
            + " -march=armv5te -mtune=xscale"
            + " -msoft-float -mthumb -march=armv5te -mtune=xscale"
            + " -msoft-float -mthumb -Wformat -Werror=format-security"
            + " -Wformat -Werror=format-security -std=c99 -Wall -Wextra -Werror"
            + " -fvisibility=hidden"
        )
        commands = [
            {"command": prefix + " -c /tmp/project/jni/narfs/narfs_jni.c"},
            {"command": prefix + " -c /tmp/project/jni/narfs/narfs_utf.c"},
        ]
        (self.evidence / "compile_commands.json").write_text(json.dumps(commands))
        (self.evidence / "link.txt").write_text(
            compiler + " -shared -Wl,-soname,libnarfs.so -Wl,--as-needed"
            + " -Wl,--no-undefined"
            + " -Wl,--version-script=/tmp/project/jni/narfs/narfs_jni.map"
            + " /tmp/build/libnarfs_core.a -o libnarfs.so"
        )
        self.outputs = {
            "--file-header": (
                "Class: ELF32\nMachine: ARM\nType: DYN (Shared object file)\n"
            ),
            "--arch-specific": 'Tag_CPU_name: "5TE"\nTag_CPU_arch: v5TE\nThumb-1\n',
            "--dynamic": (
                "0x0000000e (SONAME) Library soname: [libnarfs.so]\n"
                "0x00000001 (NEEDED) Shared library: [libc.so]\n"
            ),
            "--dyn-syms": (
                "1: 00001000 20 FUNC GLOBAL DEFAULT 8 " + JNI_EXPORT + "\n"
                "2: 00005000 0 NOTYPE GLOBAL DEFAULT ABS __bss_start\n"
                "3: 00005000 0 NOTYPE GLOBAL DEFAULT ABS _edata\n"
                "4: 00005000 0 NOTYPE GLOBAL DEFAULT ABS _end\n"
            ),
            "--symbols": (
                "1: 00001000 20 FUNC GLOBAL DEFAULT 8 " + JNI_EXPORT + "\n"
                "2: 00001100 20 FUNC LOCAL DEFAULT 8 narfs_default_options\n"
                "3: 00001200 20 FUNC LOCAL DEFAULT 8 narfs_inspect\n"
                "4: 00001300 20 FUNC LOCAL HIDDEN 8 narfs_utf16_to_utf8\n"
                "5: 00001400 20 FUNC LOCAL HIDDEN 8 narfs_utf8_to_utf16\n"
            ),
        }

    def tearDown(self):
        self.directory.cleanup()

    def readelf(self, command, **kwargs):
        key = next(value for value in self.outputs if value in command)
        return SimpleNamespace(stdout=self.outputs[key])

    def inspect(self):
        with mock.patch("inspect_narfs_jni.subprocess.run", side_effect=self.readelf):
            return inspect_candidate(
                self.dso, Path("readelf"), self.evidence,
                abi="armeabi", api="android-9", build_system="cmake",
            )

    def test_exact_candidate_contract_and_build_parity(self):
        report = self.inspect()
        self.assertEqual("libnarfs.so", report["contract"]["soname"])
        self.assertEqual([JNI_EXPORT], report["contract"]["jniExports"])
        self.assertEqual(["libc.so"], report["contract"]["needed"])
        self.assertEqual(
            ["jni/narfs/narfs_jni.c", "jni/narfs/narfs_utf.c"],
            report["contract"]["sources"],
        )
        peer = copy.deepcopy(report)
        peer["provenance"]["buildSystem"] = "ndk-build"
        peer["provenance"]["sha256"] = "f" * 64
        self.assertEqual({"status": "equivalent"}, compare_contracts(report, peer))

    def test_elf_exports_needed_core_extraction_and_evidence_are_exact(self):
        mutations = (
            ("--file-header", "Class: ELF64\nMachine: ARM\nType: DYN\n"),
            ("--dynamic", "0 (SONAME) Library soname: [libother.so]\n"),
            ("--dynamic", "0 (SONAME) Library soname: [libnarfs.so]\n"
                           "0 (NEEDED) Shared library: [liblog.so]\n"),
            ("--dyn-syms", self.outputs["--dyn-syms"] + "2: 0 1 FUNC GLOBAL DEFAULT 8 leak\n"),
            ("--dyn-syms", self.outputs["--dyn-syms"] + "5: 0 1 OBJECT WEAK DEFAULT 8 leak\n"),
            ("--symbols", self.outputs["--symbols"].replace("narfs_inspect", "missing")),
        )
        for key, value in mutations:
            with self.subTest(key=key):
                original = self.outputs[key]
                self.outputs[key] = value
                with self.assertRaises(CandidateContractError):
                    self.inspect()
                self.outputs[key] = original
        original = (self.evidence / "link.txt").read_text()
        (self.evidence / "link.txt").write_text(original.replace("--no-undefined", ""))
        with self.assertRaises(CandidateContractError):
            self.inspect()
        (self.evidence / "link.txt").write_text(original)
        (self.evidence / "link.txt").write_text(original.replace("-gcc -shared", "-g++ -shared"))
        with self.assertRaises(CandidateContractError):
            self.inspect()
        (self.evidence / "link.txt").write_text(original)
        policy = (self.evidence / "compile_commands.json").read_text()
        (self.evidence / "compile_commands.json").write_text(
            policy.replace(" -c ", " -ansi -fvisibility=default -c "))
        with self.assertRaises(CandidateContractError):
            self.inspect()
        (self.evidence / "compile_commands.json").write_text(policy)
        commands = (self.evidence / "compile_commands.json").read_text()
        (self.evidence / "compile_commands.json").write_text(
            commands.replace("android-9", "android-21"))
        with self.assertRaises(CandidateContractError):
            self.inspect()

    def test_source_and_build_script_keep_candidate_private_and_strict(self):
        project = Path(__file__).resolve().parents[1]
        jni = (project / "jni/narfs/narfs_jni.c").read_text()
        self.assertIn("GetStringChars", jni)
        self.assertNotIn("GetStringUTF", jni)
        self.assertNotIn("NewStringUTF", jni)
        self.assertNotIn("ExceptionClear", jni)
        java = (project / "src/com/cattailsw/nanidroid/install/NarFilesystemInspector.java").read_text()
        for forbidden in ("->", "::", "java.util.Objects", "java.nio.file", "java.time.", "try ("):
            self.assertNotIn(forbidden, java)
        script = (project / "docker/narfs-jni/build.sh").read_text()
        self.assertIn("'TARGET_CXX=$(TARGET_CC)'", script)
        self.assertIn("APP_MODULES=narfs", script)
        self.assertNotIn("artifacts/", script)
        self.assertNotRegex(script, r"(?:cp|mv).+libnarfs\.so.+OUTPUT_ROOT")
        for published in (
            "tools/verify_apk_native_payload.py",
            "tools/verify_emulator_apk.py",
            "build.gradle.kts",
        ):
            self.assertIn("libnarfs.so", (project / published).read_text())
        self.assertNotIn(
            "libnarfs.so",
            (project / "tools/inspect_android_test_apk.py").read_text(),
        )

    def test_published_lane_keeps_the_reviewed_candidate_sources_exact(self):
        project = Path(__file__).resolve().parents[1]
        expected = {
            "jni/narfs/narfs_jni.c": "2198c6549e33c5d9a38045d536526dad67262bab1f35b62174b046a4be84bf56",
            "jni/narfs/narfs_utf.c": "6968d471affac1e6e470f5eda37c7d0b814d8060b30908362f964bc7ef6e2800",
            "jni/narfs/narfs_utf.h": "9b99cc6d865358920a02afae1e550a0c21aa49045cb8cf1bbad5287751ad88c4",
            "jni/narfs/narfs_core.c": "7f508f1a24de64cb7156334eca96f30eab9c0b20a367085f1244915bda5b424b",
            "jni/narfs/narfs_core.h": "f9ba4abaa106d7aa85f7fc96a8dfc429c116256ff4dddfbaa718afd9d45ff643",
            "src/com/cattailsw/nanidroid/install/NarFilesystemInspector.java":
                "92d9e4a12b57bfa3adc1ef7a0582416202ce5b8b7090bb782bf08818867beaff",
        }
        actual = {
            relative: hashlib.sha256(
                (project / relative).read_bytes().replace(b"\r\n", b"\n")
            ).hexdigest()
            for relative in expected
        }
        self.assertEqual(expected, actual)


if __name__ == "__main__":
    unittest.main()
