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
        with tempfile.TemporaryDirectory() as directory:
            build = Path(directory) / "build"
            evidence = build / "ndk-armeabi/build.log"
            evidence.parent.mkdir(parents=True)
            compiler = (
                "/opt/android-ndk-r14b/toolchains/arm-linux-androideabi-4.9/"
                "prebuilt/linux-x86_64/bin/arm-linux-androideabi-gcc")
            prefix = (
                f"{compiler} "
                "--sysroot=/opt/android-ndk-r14b/platforms/android-9/arch-arm "
                f"-I{build}/jni/narfs -I{build}/jni/narfs/sha256 "
                "-march=armv5te -mtune=xscale -msoft-float -mthumb "
                "-std=c99 -Wall -Wextra -Werror "
                "-Wformat -Werror=format-security")
            lane = build / "ndk-armeabi/obj/local/armeabi"
            probe_object = (
                lane / "objs/narfs_sha256_link_probe/narfs_sha256_link_probe.o")
            sysroot = "/opt/android-ndk-r14b/platforms/android-9/arch-arm"
            linker_flags = (
                "-Wl,--gc-sections -Wl,-z,nocopyreloc "
                f"-Wl,-rpath-link={sysroot}/usr/lib "
                f"-Wl,-rpath-link={lane} ")
            commands = [
                prefix + f" -c {build}/jni/narfs/narfs_sha256.c "
                f"-o {lane}/objs/narfs_sha256/narfs_sha256.o",
                prefix + f" -c {build}/test/native/narfs_sha256_link_probe.c "
                f"-o {probe_object}",
                f"{compiler} --sysroot={sysroot} {linker_flags}"
                f"{probe_object} {lane}/libnarfs_sha256.a -lgcc "
                "-no-canonical-prefixes -Wl,--no-undefined -Wl,--build-id "
                "-Wl,--no-undefined -Wl,-z,noexecstack -Wl,-z,relro "
                "-Wl,-z,now -Wl,--warn-shared-textrel -Wl,--fatal-warnings "
                f"-lc -lm -o {lane}/narfs_sha256_link_probe",
            ]
            evidence.write_text("\n".join(commands))
            report = sha.inspect_build_evidence(
                evidence, "ndk-build", "armeabi", "android-9")
            self.assertEqual(["source", "probe"], report["compileOrder"])
            for changed in (
                [commands[0] + " -Wno-error", *commands[1:]],
                [commands[0] + " -w", *commands[1:]],
                [commands[0] + " --std=c11", *commands[1:]],
                [commands[0] + " -Wall", *commands[1:]],
                [commands[0] + " -O0", *commands[1:]],
                [commands[0] + " @foreign.rsp", *commands[1:]],
                [commands[0].replace(sysroot, "/foreign/sysroot"),
                 *commands[1:]],
                [commands[0].replace("narfs_sha256.c", "other.c"),
                 *commands[1:]],
                [commands[0].replace(str(build), "/foreign"),
                 *commands[1:]],
                [commands[0] + " -I/foreign/jni/narfs", *commands[1:]],
                [commands[0] + " -isystem /foreign/system", *commands[1:]],
                [commands[0].replace(compiler, "/foreign/gcc"),
                 *commands[1:]],
                [*commands[:2], commands[2].replace(
                    f"{probe_object} {lane}/libnarfs_sha256.a",
                    f"{lane}/libnarfs_sha256.a {probe_object}")],
                [*commands[:2], commands[2].replace(
                    "libnarfs_sha256.a", "extra.o libnarfs_sha256.a")],
                [*commands[:2], commands[2] + " -lcrypto"],
                [*commands[:2], commands[2] + " @foreign.rsp"],
                [*commands[:2], commands[2].replace(
                    f"--sysroot={sysroot}", "--sysroot=/foreign/sysroot")],
                [*commands[:2], commands[2].replace(
                    compiler, "/foreign/gcc")],
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
        self.assertIn(
            'APP_BUILD_SCRIPT="${BUILD_ROOT}/jni/Android.mk"', script)
        self.assertIn("APP_MODULES=narfs_sha256_link_probe", script)


if __name__ == "__main__":
    unittest.main()
