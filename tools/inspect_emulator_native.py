#!/usr/bin/env python3
"""Inspect the exact CMake-only ARM64 native artifact used by the emulator lane."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Callable, NoReturn

from inspect_native_contract import inspect_cmake


EXPECTED_LIBRARIES = {
    "arm64-v8a/libkawari8.so": {
        "soname": "libkawari8.so",
        "jniExports": [
            "Java_com_cattailsw_nanidroid_shiori_Kawari_load",
            "Java_com_cattailsw_nanidroid_shiori_Kawari_requestFromJNI",
            "Java_com_cattailsw_nanidroid_shiori_Kawari_unload",
        ],
    },
    "arm64-v8a/libsatoriya.so": {
        "soname": "libsatoriya.so",
        "jniExports": [
            "Java_com_cattailsw_nanidroid_shiori_JNIShiori_requestFromJNI",
            "Java_com_cattailsw_nanidroid_shiori_SatoriPosixShiori_load",
            "Java_com_cattailsw_nanidroid_shiori_SatoriPosixShiori_requestFromJNI2",
            "Java_com_cattailsw_nanidroid_shiori_SatoriPosixShiori_unload",
        ],
    },
}
EXPECTED_NEEDED = ["libc.so", "libdl.so", "liblog.so", "libm.so", "libstdc++.so"]
EXPECTED_CACHE = {
    "ANDROID_ABI": "arm64-v8a",
    "ANDROID_PLATFORM": "android-21",
    "ANDROID_STL": "gnustl_static",
    "ANDROID_TOOLCHAIN": "gcc",
}


class NativeContractError(ValueError):
    """The ARM64 candidate does not satisfy the frozen emulator contract."""


def _fail(message: str) -> NoReturn:
    raise NativeContractError(message)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _cache_values(path: Path) -> dict[str, str]:
    if not path.is_file():
        _fail(f"CMake cache does not exist: {path}")
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith(("#", "//")) or "=" not in line:
            continue
        name_and_type, value = line.split("=", 1)
        values[name_and_type.split(":", 1)[0]] = value
    return values


def _verify_cache(path: Path) -> dict[str, str]:
    values = _cache_values(path)
    for name, expected in EXPECTED_CACHE.items():
        actual = values.get(name)
        if actual != expected:
            _fail(f"CMake cache {name} changed: expected {expected}, got {actual}")
    compiler = values.get("NANIDROID_CXX_COMPILER", "")
    if not compiler.endswith(
        "/toolchains/aarch64-linux-android-4.9/prebuilt/linux-x86_64/bin/"
        "aarch64-linux-android-g++"
    ):
        _fail(f"CMake compiler changed: {compiler}")
    if values.get("NANIDROID_CXX_COMPILER_ID") != "GNU" or not re.fullmatch(
        r"4\.9(?:\.\d+)?", values.get("NANIDROID_CXX_COMPILER_VERSION", "")
    ):
        _fail(
            "CMake compiler identity changed: expected GNU 4.9, got "
            f"{values.get('NANIDROID_CXX_COMPILER_ID')} "
            f"{values.get('NANIDROID_CXX_COMPILER_VERSION')}"
        )
    return {
        "ndk": "r14b",
        "abi": EXPECTED_CACHE["ANDROID_ABI"],
        "api": EXPECTED_CACHE["ANDROID_PLATFORM"],
        "compiler": "gcc-4.9",
        "stl": EXPECTED_CACHE["ANDROID_STL"],
    }


def _subprocess_readelf(tool: Path, arguments: tuple[str, ...], library: Path) -> str:
    try:
        return subprocess.run(
            [str(tool), *arguments, str(library)],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
    except subprocess.CalledProcessError as error:
        detail = error.stderr.strip() if error.stderr else str(error)
        raise NativeContractError(f"readelf failed for {library}: {detail}") from error


def _header_value(header: str, field: str) -> str:
    match = re.search(rf"^\s*{re.escape(field)}:\s*(.+?)\s*$", header, re.MULTILINE)
    if match is None:
        _fail(f"ELF header does not contain {field}")
    return match.group(1)


def _dynamic_values(dynamic: str, tag: str) -> list[str]:
    return sorted(re.findall(rf"\({tag}\).*?\[(.+?)\]\s*$", dynamic, re.MULTILINE))


def _jni_exports(symbols: str) -> list[str]:
    result: set[str] = set()
    for line in symbols.splitlines():
        fields = line.split()
        if len(fields) >= 8 and fields[4] in {"GLOBAL", "WEAK"} and fields[6] != "UND":
            symbol = fields[7].split("@", 1)[0]
            if symbol.startswith("Java_"):
                result.add(symbol)
    return sorted(result)


def inspect_native_directory(
    root: Path,
    readelf: Path,
    cmake_cache: Path,
    *,
    project_root: Path | None = None,
    readelf_runner: Callable[[Path, tuple[str, ...], Path], str] = _subprocess_readelf,
) -> dict[str, object]:
    """Measure toolchain, exact paths, ELF identity, dependencies and JNI exports."""
    observed = sorted(path.relative_to(root).as_posix() for path in root.rglob("*.so"))
    expected = sorted(EXPECTED_LIBRARIES)
    if observed != expected:
        _fail(f"native library paths changed: expected {expected}, got {observed}")
    toolchain = _verify_cache(cmake_cache)
    modules = inspect_cmake(project_root) if project_root is not None else []

    libraries: list[dict[str, object]] = []
    hashes: dict[str, str] = {}
    for relative in expected:
        library = root / relative
        if library.read_bytes()[:4] != b"\x7fELF":
            _fail(f"{relative} is not an ELF file")
        header = readelf_runner(readelf, ("--file-header",), library)
        elf = {
            "class": _header_value(header, "Class"),
            "data": _header_value(header, "Data"),
            "type": _header_value(header, "Type").split(" ", 1)[0],
            "machine": _header_value(header, "Machine"),
        }
        expected_elf = {
            "class": "ELF64",
            "data": "2's complement, little endian",
            "type": "DYN",
            "machine": "AArch64",
        }
        if elf != expected_elf:
            _fail(f"{relative} ELF identity changed: expected {expected_elf}, got {elf}")
        dynamic = readelf_runner(readelf, ("--dynamic", "--wide"), library)
        sonames = _dynamic_values(dynamic, "SONAME")
        if sonames != [EXPECTED_LIBRARIES[relative]["soname"]]:
            _fail(f"{relative} SONAME changed: {sonames}")
        needed = _dynamic_values(dynamic, "NEEDED")
        if needed != EXPECTED_NEEDED:
            _fail(f"{relative} DT_NEEDED changed: expected {EXPECTED_NEEDED}, got {needed}")
        exports = _jni_exports(
            readelf_runner(readelf, ("--dyn-syms", "--wide"), library)
        )
        expected_exports = EXPECTED_LIBRARIES[relative]["jniExports"]
        if exports != expected_exports:
            _fail(f"{relative} JNI exports changed: expected {expected_exports}, got {exports}")
        libraries.append(
            {
                "path": relative,
                "elf": elf,
                "soname": sonames[0],
                "needed": needed,
                "jniExports": exports,
            }
        )
        hashes[relative] = _sha256(library)
    return {
        "toolchain": toolchain,
        "modules": modules,
        "libraries": libraries,
        "sha256": hashes,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--readelf", type=Path, required=True)
    parser.add_argument("--cmake-cache", type=Path, required=True)
    parser.add_argument("--project-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = inspect_native_directory(
            args.root,
            args.readelf,
            args.cmake_cache,
            project_root=args.project_root,
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
    except (NativeContractError, OSError) as error:
        print(f"emulator native validation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
