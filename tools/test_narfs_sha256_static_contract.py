import copy
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import inspect_narfs_sha256 as sha


ROOT = Path(__file__).resolve().parents[1]
SYMBOLS = """\
File: archive(narfs_sha256.o)
Num: Value Size Type Bind Vis Ndx Name
 1: 0 10 FUNC GLOBAL DEFAULT 1 narfs_sha256_init
 2: 0 10 FUNC GLOBAL DEFAULT 1 narfs_sha256_update
 3: 0 10 FUNC GLOBAL DEFAULT 1 narfs_sha256_final
 4: 0 0 NOTYPE GLOBAL DEFAULT UND memcpy
 5: 0 0 NOTYPE GLOBAL DEFAULT UND memset
"""


def fake_readelf(arguments, **_kwargs):
    artifact = arguments[-1]
    if "--file-header" in arguments:
        arm64 = "arm64" in artifact
        return subprocess.CompletedProcess(arguments, 0, f"""\
 Class:                             {'ELF64' if arm64 else 'ELF32'}
 Type:                              {'REL' if artifact.endswith('.a') else 'DYN' if arm64 else 'EXEC'}
 Machine:                           {'AArch64' if arm64 else 'ARM'}
 Entry point address:               0x100
""", "")
    if "--program-headers" in arguments:
        return subprocess.CompletedProcess(
            arguments, 0, "INTERP /system/bin/linker64\n", "")
    if "--symbols" in arguments:
        value = SYMBOLS.replace("narfs_sha256.o", "narfs_sha256.c.o") \
            if "cmake" in artifact else SYMBOLS
        return subprocess.CompletedProcess(arguments, 0, value, "")
    return subprocess.CompletedProcess(arguments, 0, "", "")


class NarfsSha256StaticContractTest(unittest.TestCase):
    def test_declarations_are_guarded_and_equivalent(self):
        declarations = sha.inspect_declarations(ROOT)
        self.assertEqual(declarations["ndkBuild"], declarations["cmake"])
        self.assertEqual("narfs_sha256", declarations["ndkBuild"]["module"])

    @mock.patch("inspect_narfs_sha256.subprocess.run", side_effect=fake_readelf)
    def test_archive_probe_and_all_globals_are_exact(self, run):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "libnarfs_sha256.a"
            probe = root / "narfs_sha256_link_probe"
            archive.write_bytes(b"!<arch>\n")
            probe.write_bytes(b"\x7fELF")
            report = sha.inspect_artifacts(
                archive, probe, Path("readelf"), "armeabi",
                "android-9", "ndk-build")
            self.assertEqual(
                ["narfs_sha256_final", "narfs_sha256_init",
                 "narfs_sha256_update"], report["exports"])
            for mutation in (
                "\nFile: archive(extra.o)\n",
                "\n 9: 0 10 FUNC GLOBAL DEFAULT 1 extra_global\n",
                "\n 9: 0 0 NOTYPE GLOBAL DEFAULT UND open\n",
            ):
                run.side_effect = lambda arguments, mutation=mutation, **kwargs: (
                    subprocess.CompletedProcess(
                        arguments, 0, SYMBOLS + mutation, "")
                    if "--symbols" in arguments else fake_readelf(
                        arguments, **kwargs))
                with self.assertRaises(sha.Sha256ContractError):
                    sha.inspect_artifacts(
                        archive, probe, Path("readelf"), "armeabi",
                        "android-9", "ndk-build")

    def test_compile_and_link_evidence_normalizes_and_rejects_drift(self):
        prefix = (
            "/opt/android-ndk-r14b/toolchains/arm-linux-androideabi-4.9/"
            "prebuilt/linux-x86_64/bin/arm-linux-androideabi-gcc "
            "--sysroot=/opt/android-ndk-r14b/platforms/android-9/arch-arm "
            "-I/tmp/jni/narfs -std=c99 -Wall -Wextra -Werror")
        commands = [
            prefix + " -c /tmp/jni/narfs/narfs_sha256.c -o sha.o",
            prefix + " -c /tmp/test/native/narfs_sha256_link_probe.c -o probe.o",
            prefix + " probe.o /tmp/libnarfs_sha256.a -Wl,--no-undefined -o probe",
        ]
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / "build.log"
            evidence.write_text("\n".join(commands))
            report = sha.inspect_build_evidence(
                evidence, "ndk-build", "armeabi", "android-9")
            self.assertEqual(["source", "probe"], report["compileOrder"])
            for changed in (
                [commands[0] + " -Wno-error", *commands[1:]],
                [commands[0].replace("narfs_sha256.c", "other.c"),
                 *commands[1:]],
                [*commands[:2], commands[2].replace(
                    "probe.o /tmp/lib", "/tmp/lib") + " probe.o"],
            ):
                evidence.write_text("\n".join(changed))
                with self.assertRaises(sha.Sha256ContractError):
                    sha.inspect_build_evidence(
                        evidence, "ndk-build", "armeabi", "android-9")

    def test_parity_rejects_contract_drift(self):
        contract = {"contract": {"exports": ["a"]}, "provenance": {"lane": "x"}}
        self.assertEqual("equivalent", sha.compare_contracts(
            contract, copy.deepcopy(contract))["status"])
        changed = copy.deepcopy(contract)
        changed["contract"]["exports"].append("b")
        with self.assertRaises(sha.Sha256ContractError):
            sha.compare_contracts(contract, changed)

    def test_off_lane_is_a_real_build_gate(self):
        script = (ROOT / "docker/narfs-jni/build.sh").read_text()
        self.assertIn("candidate-off graph unexpectedly contains sha256", script)
        self.assertIn("NANIDROID_NARFS_SHA256_CANDIDATE", script)


if __name__ == "__main__":
    unittest.main()
