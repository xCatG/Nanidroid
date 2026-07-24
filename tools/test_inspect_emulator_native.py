#!/usr/bin/env python3
"""Contract tests for the CMake-only ARM64 emulator native artifact."""

from __future__ import annotations

import tempfile
import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from inspect_emulator_native import NativeContractError, inspect_native_directory


HEADER = """\
  Class:                             ELF64
  Data:                              2's complement, little endian
  Type:                              DYN (Shared object file)
  Machine:                           AArch64
"""
DYNAMIC = """\
 0x0000000000000001 (NEEDED)             Shared library: [liblog.so]
 0x0000000000000001 (NEEDED)             Shared library: [libdl.so]
 0x0000000000000001 (NEEDED)             Shared library: [libstdc++.so]
 0x0000000000000001 (NEEDED)             Shared library: [libm.so]
 0x0000000000000001 (NEEDED)             Shared library: [libc.so]
 0x000000000000000e (SONAME)             Library soname: [{soname}]
"""
SYMBOLS = {
    "libkawari8.so": """\
  12: 0000000000000100 8 FUNC GLOBAL DEFAULT 11 Java_com_cattailsw_nanidroid_shiori_Kawari_load
  13: 0000000000000100 8 FUNC GLOBAL DEFAULT 11 Java_com_cattailsw_nanidroid_shiori_Kawari_requestFromJNI
  14: 0000000000000100 8 FUNC GLOBAL DEFAULT 11 Java_com_cattailsw_nanidroid_shiori_Kawari_unload
""",
    "libsatoriya.so": """\
  12: 0000000000000100 8 FUNC GLOBAL DEFAULT 11 Java_com_cattailsw_nanidroid_shiori_JNIShiori_requestFromJNI
  13: 0000000000000100 8 FUNC GLOBAL DEFAULT 11 Java_com_cattailsw_nanidroid_shiori_SatoriPosixShiori_load
  14: 0000000000000100 8 FUNC GLOBAL DEFAULT 11 Java_com_cattailsw_nanidroid_shiori_SatoriPosixShiori_requestFromJNI2
  15: 0000000000000100 8 FUNC GLOBAL DEFAULT 11 Java_com_cattailsw_nanidroid_shiori_SatoriPosixShiori_unload
""",
    "libnarfs.so": """\
  12: 0000000000000100 8 FUNC GLOBAL DEFAULT 11 Java_com_cattailsw_nanidroid_install_NarFilesystemInspector_nativeInspect
  13: 0000000000000100 8 FUNC GLOBAL DEFAULT 11 Java_com_cattailsw_nanidroid_install_NarStagedTree_nativeBegin
  14: 0000000000000100 8 FUNC GLOBAL DEFAULT 11 Java_com_cattailsw_nanidroid_install_NarStagedTree_nativeDiscard
""",
}


class InspectEmulatorNativeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.root = Path(self.temp_dir.name)
        self.native = self.root / "native" / "arm64-v8a"
        self.native.mkdir(parents=True)
        for name in ("libkawari8.so", "libnarfs.so", "libsatoriya.so"):
            (self.native / name).write_bytes(b"\x7fELF" + name.encode())
        self.ndk = self.root / "android-ndk-r14b"
        self.ndk.mkdir()
        (self.ndk / "source.properties").write_text(
            "Pkg.Desc = Android NDK\nPkg.Revision = 14.1.3816874\n",
            encoding="utf-8",
        )
        self.cache = self.root / "CMakeCache.txt"
        self.cache.write_text(
            "ANDROID_ABI:STRING=arm64-v8a\n"
            "ANDROID_PLATFORM:STRING=android-21\n"
            "ANDROID_STL:STRING=gnustl_static\n"
            "ANDROID_TOOLCHAIN:STRING=gcc\n"
            f"NANIDROID_CXX_COMPILER:INTERNAL={self.ndk.as_posix()}/toolchains/"
            "aarch64-linux-android-4.9/prebuilt/linux-x86_64/bin/"
            "aarch64-linux-android-g++\n"
            "NANIDROID_CXX_COMPILER_ID:INTERNAL=GNU\n"
            "NANIDROID_CXX_COMPILER_VERSION:INTERNAL=4.9\n",
            encoding="utf-8",
        )

    def _readelf(self, _tool: Path, arguments: tuple[str, ...], library: Path) -> str:
        if "--file-header" in arguments:
            return HEADER
        if "--dynamic" in arguments:
            if library.name == "libnarfs.so":
                return (
                    " 0x0000000000000001 (NEEDED) Shared library: [libc.so]\n"
                    " 0x000000000000000e (SONAME) Library soname: [libnarfs.so]\n"
                )
            return DYNAMIC.format(soname=library.name)
        if "--dyn-syms" in arguments:
            return SYMBOLS[library.name]
        raise AssertionError(arguments)

    def test_accepts_exact_arm64_toolchain_and_elf_contract(self) -> None:
        report = inspect_native_directory(
            self.root / "native",
            Path("readelf"),
            self.cache,
            ndk_root=self.ndk,
            readelf_runner=self._readelf,
        )

        self.assertEqual(report["toolchain"]["abi"], "arm64-v8a")
        self.assertEqual(report["toolchain"]["api"], "android-21")
        self.assertEqual(report["toolchain"]["stl"], "gnustl_static")
        self.assertEqual(len(report["libraries"]), 3)
        narfs = next(value for value in report["libraries"]
                     if value["path"].endswith("/libnarfs.so"))
        self.assertEqual(len(narfs["jniExports"]), 3)

    def test_rejects_an_extra_library(self) -> None:
        (self.native / "libextra.so").write_bytes(b"\x7fELF-extra")
        with self.assertRaisesRegex(NativeContractError, "paths changed"):
            inspect_native_directory(
                self.root / "native",
                Path("readelf"),
                self.cache,
                ndk_root=self.ndk,
                readelf_runner=self._readelf,
            )

    def test_rejects_toolchain_cache_drift(self) -> None:
        self.cache.write_text(
            self.cache.read_text(encoding="utf-8").replace(
                "ANDROID_PLATFORM:STRING=android-21",
                "ANDROID_PLATFORM:STRING=android-24",
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(NativeContractError, "ANDROID_PLATFORM changed"):
            inspect_native_directory(
                self.root / "native",
                Path("readelf"),
                self.cache,
                ndk_root=self.ndk,
                readelf_runner=self._readelf,
            )

    def test_rejects_ndk_revision_drift(self) -> None:
        (self.ndk / "source.properties").write_text(
            "Pkg.Desc = Android NDK\nPkg.Revision = 14.1.9999999\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(NativeContractError, "NDK revision changed"):
            inspect_native_directory(
                self.root / "native",
                Path("readelf"),
                self.cache,
                ndk_root=self.ndk,
                readelf_runner=self._readelf,
            )


if __name__ == "__main__":
    unittest.main()
