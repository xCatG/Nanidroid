#!/usr/bin/env python3
"""Contract tests for PR C1 native build and ELF parity."""

from __future__ import annotations

import copy
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Callable
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

from inspect_native_contract import (
    NativeContractError,
    _verify_cmake_cache,
    compare_native_contracts,
    inspect_application_mk,
    inspect_android_mk,
    inspect_build_evidence,
    inspect_cmake,
    inspect_native_directory,
)


PROJECT_ROOT = Path(__file__).resolve().parents[1]
ELF_HEADER = """\
ELF Header:
  Class:                             ELF32
  Data:                              2's complement, little endian
  Type:                              DYN (Shared object file)
  Machine:                           ARM
  Flags:                             0x5000200, Version5 EABI, soft-float ABI
"""
KAWARI_DYNAMIC = """\
 0x00000001 (NEEDED)                     Shared library: [liblog.so]
 0x00000001 (NEEDED)                     Shared library: [libdl.so]
 0x0000000e (SONAME)                     Library soname: [libkawari8.so]
"""
SATORI_DYNAMIC = """\
 0x00000001 (NEEDED)                     Shared library: [liblog.so]
 0x0000000e (SONAME)                     Library soname: [libsatoriya.so]
"""
KAWARI_SYMBOLS = """\
Symbol table '.dynsym' contains 3 entries:
   Num:    Value  Size Type    Bind   Vis      Ndx Name
     1: 00001000    24 FUNC    GLOBAL DEFAULT   10 Java_com_cattailsw_nanidroid_shiori_Kawari_load
     2: 00001018    24 FUNC    GLOBAL DEFAULT   10 Java_com_cattailsw_nanidroid_shiori_Kawari_unload
"""
SATORI_SYMBOLS = """\
Symbol table '.dynsym' contains 2 entries:
   Num:    Value  Size Type    Bind   Vis      Ndx Name
     1: 00002000    24 FUNC    GLOBAL DEFAULT   10 Java_com_cattailsw_nanidroid_shiori_SatoriPosixShiori_load
"""
ARM_ATTRIBUTES = """\
Attribute Section: aeabi
File Attributes
  Tag_CPU_name: "arm1022e"
  Tag_CPU_arch: v5TE
  Tag_ARM_ISA_use: Yes
  Tag_THUMB_ISA_use: Thumb-1
"""


def module(name: str) -> dict[str, object]:
    return {
        "name": name,
        "sourceFiles": [f"jni/{name}/source.cpp"],
        "definitions": ["POSIX"],
        "materialFlags": ["-fexceptions"],
        "includeRoots": [f"jni/{name}"],
        "linkLibraries": ["dl", "log"],
    }


def library(module_name: str) -> dict[str, object]:
    filename = "libkawari8.so" if module_name == "kawari8" else "libsatoriya.so"
    return {
        "module": module_name,
        "path": f"armeabi/{filename}",
        "elf": {
            "class": "ELF32",
            "data": "2's complement, little endian",
            "machine": "ARM",
            "type": "DYN",
            "eabi": "Version5 EABI",
            "floatAbi": "soft-float ABI",
        },
        "soname": filename,
        "needed": ["libc.so", "libdl.so", "liblog.so"],
        "jniExports": [f"Java_example_{module_name}"],
    }


def report() -> dict[str, object]:
    return {
        "provenance": {
            "buildSystem": "ndk-build",
            "ndk": "r14b",
            "cmake": None,
            "librarySha256": {
                "armeabi/libkawari8.so": "a" * 64,
                "armeabi/libsatoriya.so": "b" * 64,
            },
        },
        "contract": {
            "toolchain": {
                "ndk": "r14b",
                "abi": "armeabi",
                "api": "android-9",
                "compiler": "gcc-4.9",
                "stl": "gnustl_static",
                "armMode": "thumb",
            },
            "modules": [module("kawari8"), module("satoriya")],
            "libraries": [library("kawari8"), library("satoriya")],
        },
    }


class NativeContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.root = Path(self.temp_dir.name)

    def _write_libraries(self) -> None:
        abi = self.root / "armeabi"
        abi.mkdir()
        (abi / "libkawari8.so").write_bytes(b"\x7fELF-kawari")
        (abi / "libsatoriya.so").write_bytes(b"\x7fELF-satori")

    def test_actual_android_mk_and_cmake_declarations_normalize_identically(self) -> None:
        self.assertEqual(
            inspect_android_mk(PROJECT_ROOT),
            inspect_cmake(PROJECT_ROOT),
        )

    def test_rejects_a_cmake_source_escape(self) -> None:
        project = self.root / "project"
        cmake = project / "jni" / "CMakeLists.txt"
        cmake.parent.mkdir(parents=True)
        cmake.write_text(
            (PROJECT_ROOT / "jni" / "CMakeLists.txt")
            .read_text(encoding="utf-8")
            .replace(
                "kawari8/libkawari/kawari_engine.cpp",
                "../outside.cpp",
                1,
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(NativeContractError, "escapes"):
            inspect_cmake(project)

    def test_rejects_an_undeclared_cmake_target_mutation(self) -> None:
        project = self.root / "project"
        cmake = project / "jni" / "CMakeLists.txt"
        cmake.parent.mkdir(parents=True)
        cmake.write_text(
            (PROJECT_ROOT / "jni" / "CMakeLists.txt").read_text(encoding="utf-8")
            + "\ntarget_compile_options(kawari8 PRIVATE -fno-exceptions)\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(NativeContractError, "undeclared CMake mutation"):
            inspect_cmake(project)

    def test_rejects_application_mk_stl_drift(self) -> None:
        project = self.root / "project"
        application_mk = project / "jni" / "Application.mk"
        application_mk.parent.mkdir(parents=True)
        application_mk.write_text(
            "APP_CPPFLAGS := -frtti -fexceptions\nAPP_STL := c++_static\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(NativeContractError, "APP_STL"):
            inspect_application_mk(project)

    def test_rejects_actual_compile_flag_drift(self) -> None:
        project = self.root / "project"
        (project / "jni").mkdir(parents=True)
        evidence = self.root / "ndk-build.log"
        evidence.write_text(
            "/opt/android-ndk-r14b/toolchains/arm-linux-androideabi-4.9/"
            "prebuilt/linux-x86_64/bin/arm-linux-androideabi-g++ "
            "-DANDROID -DPOSIX -DNDEBUG -fno-exceptions -frtti -Os "
            "-mthumb -march=armv5te -mtune=xscale -msoft-float "
            "--sysroot /opt/android-ndk-r14b/platforms/android-9/arch-arm "
            "-I/opt/android-ndk-r14b/sources/cxx-stl/gnu-libstdc++/4.9/"
            "libs/armeabi/include "
            f"-I{project}/jni/kawari8 -c "
            f"{project}/jni/kawari8/libkawari/kawari_engine.cpp -o one.o\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(NativeContractError, "compile flags"):
            inspect_build_evidence(
                build_system="ndk-build",
                evidence=evidence,
                project_root=project,
                ndk_root=Path("/opt/android-ndk-r14b"),
                modules=inspect_android_mk(PROJECT_ROOT),
                expected_abi="armeabi",
                expected_api="android-9",
            )

    def test_rejects_actual_compile_include_escape(self) -> None:
        project = self.root / "project"
        (project / "jni").mkdir(parents=True)
        evidence = self.root / "ndk-build.log"
        evidence.write_text(
            "/opt/android-ndk-r14b/toolchains/arm-linux-androideabi-4.9/"
            "prebuilt/linux-x86_64/bin/arm-linux-androideabi-g++ "
            "-DANDROID -DPOSIX -DNDEBUG -fexceptions -frtti -Os "
            "-mthumb -march=armv5te -mtune=xscale -msoft-float "
            "--sysroot /opt/android-ndk-r14b/platforms/android-9/arch-arm "
            "-I/opt/android-ndk-r14b/sources/cxx-stl/gnu-libstdc++/4.9/"
            "libs/armeabi/include "
            "-I/tmp/undeclared-native-header "
            f"-I{project}/jni/kawari8 -c "
            f"{project}/jni/kawari8/libkawari/kawari_engine.cpp -o one.o\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(NativeContractError, "include path escapes"):
            inspect_build_evidence(
                build_system="ndk-build",
                evidence=evidence,
                project_root=project,
                ndk_root=Path("/opt/android-ndk-r14b"),
                modules=inspect_android_mk(PROJECT_ROOT),
                expected_abi="armeabi",
                expected_api="android-9",
            )

    @mock.patch(
        "inspect_native_contract.inspect_build_evidence",
        return_value={
            "ndk": "r14b",
            "ndkRevision": "14.1.3816874",
            "abi": "armeabi",
            "api": "android-9",
            "modules": [
                {"targetFlags": {"armMode": "thumb"}},
                {"targetFlags": {"armMode": "thumb"}},
            ],
        },
    )
    @mock.patch("inspect_native_contract._verify_cmake_cache", return_value="3.22.1")
    @mock.patch("inspect_native_contract.inspect_cmake")
    @mock.patch("inspect_native_contract.subprocess.run")
    def test_captures_elf_dependencies_jni_and_hash_provenance(
        self,
        run: mock.Mock,
        cmake_modules: mock.Mock,
        _cache: mock.Mock,
        _evidence: mock.Mock,
    ) -> None:
        self._write_libraries()
        cmake_modules.return_value = [module("kawari8"), module("satoriya")]

        def result(arguments: list[str], **_: object) -> subprocess.CompletedProcess[str]:
            library = arguments[-1]
            if "--file-header" in arguments:
                stdout = ELF_HEADER
            elif "--dynamic" in arguments:
                stdout = (
                    KAWARI_DYNAMIC
                    if library.endswith("libkawari8.so")
                    else SATORI_DYNAMIC
                )
            elif "--arch-specific" in arguments:
                stdout = ARM_ATTRIBUTES
            else:
                stdout = (
                    KAWARI_SYMBOLS
                    if library.endswith("libkawari8.so")
                    else SATORI_SYMBOLS
                )
            return subprocess.CompletedProcess(arguments, 0, stdout, "")

        run.side_effect = result
        contract = inspect_native_directory(
            self.root,
            Path("readelf"),
            project_root=PROJECT_ROOT,
            build_system="cmake",
            abi="armeabi",
            api="android-9",
            compiler="gcc-4.9",
            stl="gnustl_static",
            arm_mode="thumb",
            ndk="r14b",
            ndk_root=Path("/opt/android-ndk-r14b"),
            build_evidence=self.root / "cmake",
            cmake_cache=self.root / "CMakeCache.txt",
        )

        self.assertEqual(contract["contract"]["libraries"][0]["soname"], "libkawari8.so")
        self.assertEqual(
            contract["contract"]["libraries"][0]["needed"],
            ["libdl.so", "liblog.so"],
        )
        self.assertEqual(
            contract["contract"]["libraries"][0]["jniExports"],
            [
                "Java_com_cattailsw_nanidroid_shiori_Kawari_load",
                "Java_com_cattailsw_nanidroid_shiori_Kawari_unload",
            ],
        )
        self.assertEqual(contract["provenance"]["buildSystem"], "cmake")
        self.assertEqual(contract["provenance"]["cmake"], "3.22.1")
        self.assertEqual(
            len(contract["provenance"]["librarySha256"]["armeabi/libkawari8.so"]),
            64,
        )

    def test_rejects_absent_candidate_artifacts(self) -> None:
        with self.assertRaisesRegex(
            NativeContractError, "native artifact directory does not exist"
        ):
            inspect_native_directory(
                self.root / "candidate",
                Path("readelf"),
                project_root=PROJECT_ROOT,
                build_system="cmake",
                abi="armeabi",
                api="android-9",
                compiler="gcc-4.9",
                stl="gnustl_static",
                arm_mode="thumb",
                ndk="r14b",
                ndk_root=Path("/opt/android-ndk-r14b"),
                build_evidence=self.root / "cmake",
                cmake_cache=self.root / "CMakeCache.txt",
            )

    def test_verifies_frozen_cmake_cache_toolchain(self) -> None:
        cache = self.root / "CMakeCache.txt"
        cache.write_text(
            "\n".join(
                (
                    "ANDROID_ABI:STRING=armeabi",
                    "ANDROID_PLATFORM:STRING=android-9",
                    "ANDROID_STL:STRING=gnustl_static",
                    "ANDROID_TOOLCHAIN:STRING=gcc",
                    "ANDROID_ARM_MODE:STRING=thumb",
                    "CMAKE_BUILD_TYPE:STRING=",
                    "CMAKE_EXPORT_COMPILE_COMMANDS:BOOL=ON",
                    "NANIDROID_CXX_COMPILER:INTERNAL=/ndk/bin/arm-linux-androideabi-g++",
                    "NANIDROID_CXX_COMPILER_ID:INTERNAL=GNU",
                    "NANIDROID_CXX_COMPILER_VERSION:INTERNAL=4.9.0",
                    "CMAKE_CACHE_MAJOR_VERSION:INTERNAL=3",
                    "CMAKE_CACHE_MINOR_VERSION:INTERNAL=22",
                    "CMAKE_CACHE_PATCH_VERSION:INTERNAL=1",
                )
            ),
            encoding="utf-8",
        )
        self.assertEqual(
            _verify_cmake_cache(
                cache,
                abi="armeabi",
                api="android-9",
                compiler="gcc-4.9",
                stl="gnustl_static",
                arm_mode="thumb",
            ),
            "3.22.1",
        )

    def _assert_drift(
        self,
        mutate: Callable[[dict[str, object]], None],
        expected_path: str,
    ) -> None:
        reference = report()
        candidate = copy.deepcopy(reference)
        candidate["provenance"]["buildSystem"] = "cmake"
        candidate["provenance"]["librarySha256"]["armeabi/libkawari8.so"] = "c" * 64
        mutate(candidate)
        with self.assertRaisesRegex(NativeContractError, re.escape(expected_path)):
            compare_native_contracts(reference, candidate)

    def test_rejects_module_drift(self) -> None:
        self._assert_drift(
            lambda value: value["contract"]["modules"][0].update(name="different"),
            "contract.modules[0].name",
        )

    def test_rejects_source_drift(self) -> None:
        self._assert_drift(
            lambda value: value["contract"]["modules"][0]["sourceFiles"].append(
                "jni/extra.cpp"
            ),
            "contract.modules[0].sourceFiles",
        )

    def test_rejects_jni_export_drift(self) -> None:
        self._assert_drift(
            lambda value: value["contract"]["libraries"][0]["jniExports"].append(
                "Java_extra"
            ),
            "contract.libraries[0].jniExports",
        )

    def test_rejects_abi_drift(self) -> None:
        self._assert_drift(
            lambda value: value["contract"]["toolchain"].update(abi="arm64-v8a"),
            "contract.toolchain.abi",
        )

    def test_rejects_ndk_drift(self) -> None:
        self._assert_drift(
            lambda value: value["contract"]["toolchain"].update(ndk="r15c"),
            "contract.toolchain.ndk",
        )

    def test_rejects_stl_drift(self) -> None:
        self._assert_drift(
            lambda value: value["contract"]["toolchain"].update(stl="c++_static"),
            "contract.toolchain.stl",
        )

    def test_rejects_dependency_drift(self) -> None:
        self._assert_drift(
            lambda value: value["contract"]["libraries"][0]["needed"].append(
                "libunexpected.so"
            ),
            "contract.libraries[0].needed",
        )

    def test_rejects_definition_drift(self) -> None:
        self._assert_drift(
            lambda value: value["contract"]["modules"][0]["definitions"].append(
                "UNEXPECTED"
            ),
            "contract.modules[0].definitions",
        )

    def test_rejects_link_library_drift(self) -> None:
        self._assert_drift(
            lambda value: value["contract"]["modules"][0]["linkLibraries"].append(
                "unexpected"
            ),
            "contract.modules[0].linkLibraries",
        )

    def test_ignores_only_declared_provenance_facts(self) -> None:
        reference = report()
        candidate = copy.deepcopy(reference)
        candidate["provenance"] = {
            "buildSystem": "cmake",
            "ndk": "r14b",
            "cmake": "3.22.1",
            "librarySha256": {
                "armeabi/libkawari8.so": "c" * 64,
                "armeabi/libsatoriya.so": "d" * 64,
            },
        }

        comparison = compare_native_contracts(reference, candidate)

        self.assertEqual(comparison["status"], "equivalent")
        self.assertEqual(
            comparison["ignoredFacts"],
            [
                "build system name",
                "CMake version",
                "timestamps",
                "debug sections",
                "build IDs",
                "whole-file hashes",
            ],
        )


if __name__ == "__main__":
    unittest.main()
