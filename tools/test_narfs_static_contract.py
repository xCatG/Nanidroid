#!/usr/bin/env python3
"""Executable contract for the build-only narfs_core static-library slice."""

from __future__ import annotations

import copy
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import inspect_narfs_static as static
from inspect_narfs_static import (
    StaticContractError,
    compare_contracts,
    inspect_artifacts,
    inspect_declarations,
)


ROOT = Path(__file__).resolve().parents[1]
HEADER_ARM = """\
  Class:                             ELF32
  Machine:                           ARM
  Type:                              REL (Relocatable file)
"""
HEADER_ARM64 = """\
  Class:                             ELF64
  Machine:                           AArch64
  Type:                              REL (Relocatable file)
"""
HEADER_PROBE_ARM = HEADER_ARM.replace(
    "REL (Relocatable file)", "EXEC (Executable file)\n  Entry point address:               0x8000"
)
HEADER_PROBE_ARM64 = HEADER_ARM64.replace(
    "REL (Relocatable file)", "DYN (Shared object file)\n  Entry point address:               0xcf0"
)
SYMBOLS = """\
File: archive(narfs_core.o)
  Num: Value Size Type Bind Vis Ndx Name
   20: 0 10 FUNC GLOBAL DEFAULT 1 narfs_default_options
   21: 0 20 FUNC GLOBAL DEFAULT 1 narfs_inspect
   22: 0 0 NOTYPE GLOBAL DEFAULT UND openat
   23: 0 0 NOTYPE GLOBAL DEFAULT UND fstatat
   24: 0 0 NOTYPE GLOBAL DEFAULT UND close
"""
SYMBOLS += "".join(f"  90: 0 0 NOTYPE GLOBAL DEFAULT UND {name}\n" for name in static.EXPECTED_IMPORTS if name not in {"close", "fstatat", "openat"})
PROGRAM_ARM = " INTERP 0 0\n [Requesting program interpreter: /system/bin/linker]\n"
PROGRAM_ARM64 = " INTERP 0 0\n [Requesting program interpreter: /system/bin/linker64]\n"
ATTRIBUTES = """\
  Tag_CPU_name: "5TE"
  Tag_CPU_arch: v5TE
  Tag_ARM_ISA_use: Yes
  Tag_THUMB_ISA_use: Thumb-1
"""
DYNAMIC = """\
 0x00000001 (NEEDED) Shared library: [libstdc++.so]
 0x00000001 (NEEDED) Shared library: [libm.so]
 0x00000001 (NEEDED) Shared library: [libc.so]
 0x00000001 (NEEDED) Shared library: [libdl.so]
"""


def fake_readelf(arguments, **kwargs):
    command = arguments[1:]
    if "--file-header" in command:
        output = HEADER_ARM64 if "arm64" in arguments[-1] else HEADER_ARM
        if arguments[-1].endswith("narfs_core_link_probe"):
            output = (
                HEADER_PROBE_ARM64 if "arm64" in arguments[-1]
                else HEADER_PROBE_ARM
            )
    elif "--arch-specific" in command:
        output = ATTRIBUTES
    elif "--dynamic" in command:
        output = DYNAMIC
    elif "--program-headers" in command:
        output = PROGRAM_ARM64 if "arm64" in arguments[-1] else PROGRAM_ARM
    elif "--dyn-syms" in command:
        output = SYMBOLS
    else:
        output = SYMBOLS
    return subprocess.CompletedProcess(arguments, 0, output, "")


def forbidden_readelf(arguments, **kwargs):
    result = fake_readelf(arguments, **kwargs)
    if "--symbols" in arguments or "--dyn-syms" in arguments:
        result.stdout = result.stdout.replace("openat", "openat2")
    return result


def artifacts(directory, abi):
    root = Path(directory) / abi
    root.mkdir()
    archive, probe = root / "libnarfs_core.a", root / "narfs_core_link_probe"
    archive.write_bytes(b"!<arch>\n")
    probe.write_bytes(b"\x7fELF")
    return archive, probe


class NarfsStaticContractTest(unittest.TestCase):
    def test_build_declarations_are_exact_and_equivalent(self):
        declarations = inspect_declarations(ROOT)
        self.assertEqual(declarations["ndkBuild"], declarations["cmake"])
        self.assertEqual(
            {
                "kind": "static",
                "module": "narfs_core",
                "source": "jni/narfs/narfs_core.c",
                "include": "jni/narfs",
                "flags": ["-std=c99", "-Wall", "-Wextra", "-Werror"],
            },
            declarations["ndkBuild"],
        )

    @mock.patch("inspect_narfs_static.subprocess.run", side_effect=fake_readelf)
    def test_archive_and_link_probe_are_audited(self, _run):
        with tempfile.TemporaryDirectory() as directory:
            archive, probe = artifacts(directory, "armeabi")
            report = inspect_artifacts(
                archive, probe, Path("readelf"), abi="armeabi", api="android-9"
            )
        self.assertEqual(["narfs_default_options", "narfs_inspect"], report["exports"])
        self.assertEqual(
            ["libc.so", "libdl.so", "libm.so", "libstdc++.so"],
            report["needed"],
        )
        self.assertEqual("ARMv5TE Thumb-1", report["architecture"])

    @mock.patch("inspect_narfs_static.subprocess.run", side_effect=fake_readelf)
    def test_arm64_contract_is_supported_without_weakening_armv5(self, _run):
        with tempfile.TemporaryDirectory() as directory:
            archive, probe = artifacts(directory, "arm64-v8a")
            report = inspect_artifacts(
                archive, probe, Path("readelf"), abi="arm64-v8a", api="android-21"
            )
        self.assertEqual("AArch64", report["architecture"])

    @mock.patch("inspect_narfs_static.subprocess.run", side_effect=fake_readelf)
    def test_dyn_probe_requires_positive_android_pie_evidence(self, run):
        run.side_effect = lambda arguments, **kwargs: (
            subprocess.CompletedProcess(arguments, 0, "", "")
            if "--program-headers" in arguments
            else fake_readelf(arguments, **kwargs)
        )
        with tempfile.TemporaryDirectory() as directory:
            archive, probe = artifacts(directory, "arm64-v8a")
            with self.assertRaisesRegex(StaticContractError, "interpreter"):
                inspect_artifacts(
                    archive, probe, Path("readelf"), abi="arm64-v8a", api="android-21"
                )

    @mock.patch("inspect_narfs_static.subprocess.run", side_effect=forbidden_readelf)
    def test_forbidden_import_is_rejected(self, _run):
        with tempfile.TemporaryDirectory() as directory:
            archive, probe = artifacts(directory, "armeabi")
            with self.assertRaisesRegex(StaticContractError, "forbidden symbol"):
                inspect_artifacts(
                    archive, probe, Path("readelf"), abi="armeabi", api="android-9"
                )

    def test_parity_rejects_material_drift(self):
        reference = {
            "contract": {
                "declaration": dict(static.EXPECTED), "abi": "armeabi",
                "api": "android-9", "architecture": "ARMv5TE Thumb-1",
                "exports": list(static.EXPORTS), "globalDefinitions": list(static.EXPORTS),
                "imports": static.EXPECTED_IMPORTS,
                "needed": ["libc.so", "libdl.so", "libm.so", "libstdc++.so"],
                "archiveSources": [static.EXPECTED["source"]],
                "build": {"sources": [static.EXPECTED["source"], "test/native/narfs_link_probe.c"],
                          "flags": static.EXPECTED["flags"], "include": static.EXPECTED["include"],
                          "sysroot": "platforms/android-9/arch-arm", "compiler": "arm-linux-androideabi-gcc"},
                "probe": {"elfType": "EXEC", "interpreter": "/system/bin/linker"},
            },
            "provenance": {"buildSystem": "ndk-build", "archiveSha256": "0" * 64,
                           "archiveMembers": ["narfs_core.o"],
                           "toolchainImports": ["__aeabi_unwind_cpp_pr0", "__aeabi_unwind_cpp_pr1", "__stack_chk_fail", "__stack_chk_guard"]},
        }
        self.assertEqual("equivalent", compare_contracts(reference, copy.deepcopy(reference))["status"])
        changed = copy.deepcopy(reference)
        changed["contract"]["needed"].append("libcrypto.so")
        with self.assertRaisesRegex(StaticContractError, "schema|differs"):
            compare_contracts(reference, changed)

        invalid = ({}, {"contract": None}, [], {"contract": {"abi": 9}})
        for malformed in invalid:
            with self.subTest(malformed=malformed), self.assertRaisesRegex(
                StaticContractError, "report|schema"
            ):
                compare_contracts(malformed, copy.deepcopy(reference))
        invalid_lane = copy.deepcopy(reference)
        invalid_lane["contract"]["api"] = "android-99"
        with self.assertRaisesRegex(StaticContractError, "schema"):
            compare_contracts(invalid_lane, copy.deepcopy(invalid_lane))

    def test_measured_build_evidence_rejects_mutations(self):
        inspect = getattr(static, "inspect_build_evidence")
        base = [
            "/ndk/bin/arm-linux-androideabi-gcc --sysroot=/ndk/platforms/android-9/arch-arm "
            "-I/tmp/jni/narfs -march=armv5te -mtune=xscale -msoft-float -mthumb "
            "-std=c99 -Wall -Wextra -Werror -o CMakeFiles/narfs_core.dir/narfs/narfs_core.c.o "
            "-c /tmp/jni/narfs/narfs_core.c",
            "/ndk/bin/arm-linux-androideabi-gcc --sysroot=/ndk/platforms/android-9/arch-arm "
            "-I/tmp/jni/narfs -march=armv5te -mtune=xscale -msoft-float -mthumb "
            "-std=c99 -Wall -Wextra -Werror -o CMakeFiles/narfs_core_link_probe.dir/probe.o "
            "-c /tmp/test/native/narfs_link_probe.c",
        ]
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / "compile_commands.json"
            def audit(commands):
                evidence.write_text(json.dumps([{"command": value} for value in commands]))
                return inspect(evidence, "cmake", "armeabi", "android-9")
            self.assertEqual(2, len(audit(base)["sources"]))
            mutations = (
                base + [base[0].replace("narfs_core.c", "extra.c")],
                [base[0] + " -Wno-error", base[1]],
                base + [base[0]],
            )
            for commands in mutations:
                with self.subTest(commands=commands), self.assertRaises(StaticContractError):
                    audit(commands)

    @mock.patch("inspect_narfs_static.subprocess.run", side_effect=fake_readelf)
    def test_archive_member_and_all_global_definitions_are_exact(self, run):
        with tempfile.TemporaryDirectory() as directory:
            archive, probe = artifacts(directory, "armeabi")
            for mutation in (
                "\nFile: archive(extra.o)\n",
                "\nFile: archive(narfs_core.o)\n",
                "\n 25: 0 10 FUNC GLOBAL DEFAULT 1 unrelated_global\n",
                "\n 25: 0 10 FUNC WEAK DEFAULT 1 narfs_inspect\n",
                "\n 25: 0 10 OBJECT GLOBAL DEFAULT 1 narfs_inspect\n",
                "\n 25: 0 10 FUNC GLOBAL DEFAULT 1 narfs_inspect\n",
            ):
                run.side_effect = lambda arguments, mutation=mutation, **kwargs: (
                    subprocess.CompletedProcess(arguments, 0, SYMBOLS + mutation, "")
                    if "--symbols" in arguments else fake_readelf(arguments, **kwargs)
                )
                with self.subTest(mutation=mutation), self.assertRaises(StaticContractError):
                    inspect_artifacts(
                        archive, probe, Path("readelf"), abi="armeabi", api="android-9",
                        build_system="ndk-build",
                    )
            run.side_effect = lambda arguments, **kwargs: (
                subprocess.CompletedProcess(arguments, 0, "Num: Value Size Type Bind Vis Ndx Name\n", "")
                if "--symbols" in arguments and arguments[-1].endswith("narfs_core_link_probe")
                else fake_readelf(arguments, **kwargs)
            )
            with self.assertRaisesRegex(StaticContractError, "probe"):
                inspect_artifacts(
                    archive, probe, Path("readelf"), abi="armeabi", api="android-9"
                )

    def test_build_scripts_execute_all_static_gates(self):
        legacy = (ROOT / "docker/legacy/build.sh").read_text(encoding="utf-8")
        arm64 = (ROOT / "docker/emulator/build-native.sh").read_text(encoding="utf-8")
        for required in ("libnarfs_core.a", "narfs_core_link_probe", "narfs-static-ndk-build.json",
                         "narfs-static-cmake.json", "narfs-static-parity.json"):
            self.assertIn(required, legacy)
        for required in ("libnarfs_core.a", "narfs_core_link_probe", "narfs-static-contract.json"):
            self.assertIn(required, arm64)
        self.assertNotIn("libnarfs_core.so", legacy + arm64)


if __name__ == "__main__":
    unittest.main()
