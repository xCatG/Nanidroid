#!/usr/bin/env python3
"""Executable contract for the build-only narfs_core static-library slice."""

from __future__ import annotations

import copy
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

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
HEADER_PROBE_ARM = HEADER_ARM.replace("REL (Relocatable file)", "EXEC (Executable file)")
HEADER_PROBE_ARM64 = HEADER_ARM64.replace(
    "REL (Relocatable file)", "EXEC (Executable file)"
)
SYMBOLS = """\
  Num: Value Size Type Bind Vis Ndx Name
   20: 0 10 FUNC GLOBAL DEFAULT 1 narfs_default_options
   21: 0 20 FUNC GLOBAL DEFAULT 1 narfs_inspect
   22: 0 0 NOTYPE GLOBAL DEFAULT UND openat
   23: 0 0 NOTYPE GLOBAL DEFAULT UND fstatat
   24: 0 0 NOTYPE GLOBAL DEFAULT UND close
"""
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
            root = Path(directory)
            archive = root / "armeabi" / "libnarfs_core.a"
            probe = root / "armeabi" / "narfs_core_link_probe"
            archive.parent.mkdir()
            archive.write_bytes(b"!<arch>\n")
            probe.write_bytes(b"\x7fELF")
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
            root = Path(directory) / "arm64-v8a"
            root.mkdir()
            archive = root / "libnarfs_core.a"
            probe = root / "narfs_core_link_probe"
            archive.write_bytes(b"!<arch>\n")
            probe.write_bytes(b"\x7fELF")
            report = inspect_artifacts(
                archive, probe, Path("readelf"), abi="arm64-v8a", api="android-21"
            )
        self.assertEqual("AArch64", report["architecture"])

    @mock.patch("inspect_narfs_static.subprocess.run", side_effect=forbidden_readelf)
    def test_forbidden_import_is_rejected(self, _run):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "armeabi"
            root.mkdir()
            archive = root / "libnarfs_core.a"
            probe = root / "narfs_core_link_probe"
            archive.write_bytes(b"!<arch>\n")
            probe.write_bytes(b"\x7fELF")
            with self.assertRaisesRegex(StaticContractError, "forbidden symbol"):
                inspect_artifacts(
                    archive, probe, Path("readelf"), abi="armeabi", api="android-9"
                )

    def test_parity_rejects_material_drift(self):
        reference = {"contract": {"module": "narfs_core", "needed": ["libc.so"]}}
        self.assertEqual("equivalent", compare_contracts(reference, copy.deepcopy(reference))["status"])
        changed = copy.deepcopy(reference)
        changed["contract"]["needed"].append("libcrypto.so")
        with self.assertRaisesRegex(StaticContractError, "differs"):
            compare_contracts(reference, changed)

    def test_build_scripts_execute_all_static_gates(self):
        legacy = (ROOT / "docker/legacy/build.sh").read_text(encoding="utf-8")
        arm64 = (ROOT / "docker/emulator/build-native.sh").read_text(encoding="utf-8")
        for required in (
            "libnarfs_core.a",
            "narfs_core_link_probe",
            "narfs-static-ndk-build.json",
            "narfs-static-cmake.json",
            "narfs-static-parity.json",
        ):
            self.assertIn(required, legacy)
        for required in ("libnarfs_core.a", "narfs_core_link_probe", "narfs-static-contract.json"):
            self.assertIn(required, arm64)
        self.assertNotIn("libnarfs_core.so", legacy + arm64)


if __name__ == "__main__":
    unittest.main()
