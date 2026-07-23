#!/usr/bin/env python3
"""Validate the build-only narfs_core static archive and link probe."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import NoReturn


EXPECTED = {
    "kind": "static",
    "module": "narfs_core",
    "source": "jni/narfs/narfs_core.c",
    "include": "jni/narfs",
    "flags": ["-std=c99", "-Wall", "-Wextra", "-Werror"],
}
EXPORTS = ["narfs_default_options", "narfs_inspect"]
ALLOWED_IMPORTS = {
    "_GLOBAL_OFFSET_TABLE_", "__aeabi_unwind_cpp_pr0", "__aeabi_unwind_cpp_pr1", "__errno",
    "__errno_location", "__stack_chk_fail", "__stack_chk_guard", "close",
    "closedir", "dup", "fdopendir", "free", "fstat", "fstatat", "memcpy",
    "memset", "open", "openat", "qsort", "readdir", "realloc", "strcmp",
    "strdup", "strlen",
}
FORBIDDEN = re.compile(
    r"^(?:getdents64?|openat2|statx|EVP_|SHA|MD5|OPENSSL_|CRYPTO_)", re.I
)
TOOLCHAIN_IMPORTS = {
    "_GLOBAL_OFFSET_TABLE_", "__aeabi_unwind_cpp_pr0",
    "__aeabi_unwind_cpp_pr1", "__stack_chk_fail", "__stack_chk_guard",
}


class StaticContractError(ValueError):
    pass


def _fail(message: str) -> NoReturn:
    raise StaticContractError(message)


def _compact(path: Path) -> str:
    text = re.sub(r"(?m)#.*$", "", path.read_text(encoding="utf-8"))
    return " ".join(text.split())


def _require(text: str, pattern: str, source: str) -> None:
    if re.search(pattern, text) is None:
        _fail(f"{source} narfs static declaration changed: {pattern}")


def inspect_declarations(root: Path) -> dict[str, object]:
    make = _compact(root / "jni/narfs/Android.mk")
    cmake = _compact(root / "jni/CMakeLists.txt")
    if make.count("include $(BUILD_STATIC_LIBRARY)") != 1 or make.count(
        "include $(BUILD_EXECUTABLE)"
    ) != 1:
        _fail("Android.mk narfs module count changed")
    if len(re.findall(r"add_library\([^)]* STATIC ", cmake)) != 1 or cmake.count(
        "add_executable("
    ) != 1:
        _fail("CMake narfs module count changed")
    for value in (
        r"LOCAL_MODULE := narfs_core ",
        r"LOCAL_SRC_FILES := narfs_core\.c ",
        r"LOCAL_C_INCLUDES := \$\(LOCAL_PATH\) ",
        r"LOCAL_CFLAGS := -std=c99 -Wall -Wextra -Werror ",
        r"include \$\(BUILD_STATIC_LIBRARY\)",
        r"LOCAL_MODULE := narfs_core_link_probe ",
        r"LOCAL_SRC_FILES := \.\./\.\./test/native/narfs_link_probe\.c ",
        r"LOCAL_STATIC_LIBRARIES := narfs_core ",
        r"LOCAL_LDFLAGS := -Wl,--no-undefined ",
        r"include \$\(BUILD_EXECUTABLE\)",
    ):
        _require(make + " ", value, "Android.mk")
    for value in (
        r"set\(NANIDROID_NARFS_MODULE narfs_core\)",
        r"set\(NANIDROID_NARFS_SOURCES narfs/narfs_core\.c\)",
        r"set\(NANIDROID_NARFS_FLAGS -std=c99 -Wall -Wextra -Werror\)",
        r"set\(NANIDROID_NARFS_INCLUDES narfs\)",
        r"add_library\(\$\{NANIDROID_NARFS_MODULE\} STATIC "
        r"\$\{NANIDROID_NARFS_SOURCES\}\)",
        r"target_include_directories\( \$\{NANIDROID_NARFS_MODULE\} PUBLIC "
        r"\$\{NANIDROID_NARFS_INCLUDES\}\)",
        r"target_compile_options\( \$\{NANIDROID_NARFS_MODULE\} PRIVATE "
        r"\$\{NANIDROID_NARFS_FLAGS\}\)",
        r"add_executable\( \$\{NANIDROID_NARFS_PROBE_MODULE\} "
        r"\$\{NANIDROID_NARFS_PROBE_SOURCE\}\)",
        r"target_link_libraries\( \$\{NANIDROID_NARFS_PROBE_MODULE\} "
        r"\$\{NANIDROID_NARFS_MODULE\}\)",
        r'PROPERTIES LINKER_LANGUAGE CXX LINK_FLAGS "-Wl,--no-undefined"',
    ):
        _require(cmake, value, "CMake")
    source = (root / EXPECTED["source"]).read_text(encoding="utf-8")
    source_forbidden = re.search(
        r"\b(?:getdents64?|openat2|statx)\b|O_PATH|"
        r"\b(?:EVP_|SHA|MD5|OPENSSL_|CRYPTO_)",
        source,
        re.I,
    )
    if source_forbidden:
        _fail(f"narfs_core uses forbidden token: {source_forbidden.group()}")
    return {"ndkBuild": dict(EXPECTED), "cmake": dict(EXPECTED)}


def _readelf(readelf: Path, *args: str, artifact: Path) -> str:
    try:
        return subprocess.run(
            [str(readelf), *args, str(artifact)],
            check=True, capture_output=True, text=True,
        ).stdout
    except subprocess.CalledProcessError as error:
        raise StaticContractError(f"readelf failed for {artifact}") from error


def _headers(output: str, field: str) -> set[str]:
    return set(re.findall(rf"^\s*{field}:\s*(.+?)(?:\s+\(|$)", output, re.M))


def _symbols(output: str) -> tuple[list[str], list[str]]:
    defined: set[str] = set()
    undefined: set[str] = set()
    for line in output.splitlines():
        fields = line.split()
        if len(fields) < 8 or fields[4] not in {"GLOBAL", "WEAK"}:
            continue
        symbol = fields[7].split("@", 1)[0]
        (undefined if fields[6] == "UND" else defined).add(symbol)
    return sorted(defined), sorted(undefined)


def inspect_artifacts(
    archive: Path, probe: Path, readelf: Path, *, abi: str, api: str
) -> dict[str, object]:
    if archive.name != "libnarfs_core.a" or archive.read_bytes()[:8] != b"!<arch>\n":
        _fail(f"invalid static archive: {archive}")
    if probe.name != "narfs_core_link_probe" or probe.read_bytes()[:4] != b"\x7fELF":
        _fail(f"invalid link probe: {probe}")
    expected = {
        "armeabi": ("ELF32", "ARM", "ARMv5TE Thumb-1", "android-9"),
        "arm64-v8a": ("ELF64", "AArch64", "AArch64", "android-21"),
    }.get(abi)
    if expected is None or api != expected[3]:
        _fail(f"unsupported ABI/API: {abi}/{api}")
    for artifact, elf_type in ((archive, "REL"), (probe, "EXEC")):
        header = _readelf(readelf, "--file-header", artifact=artifact)
        if _headers(header, "Class") != {expected[0]}:
            _fail(f"{artifact} ELF class changed")
        if _headers(header, "Machine") != {expected[1]}:
            _fail(f"{artifact} ELF machine changed")
        if _headers(header, "Type") != {elf_type}:
            _fail(f"{artifact} ELF type changed")
    if abi == "armeabi":
        attributes = _readelf(readelf, "--arch-specific", "--wide", artifact=archive)
        for token in ('Tag_CPU_name: "5TE"', "v5TE", "Thumb-1"):
            if token not in attributes:
                _fail(f"legacy archive ARM attribute changed: {token}")
    defined, imports = _symbols(
        _readelf(readelf, "--symbols", "--wide", artifact=archive)
    )
    public = sorted(symbol for symbol in defined if symbol.startswith("narfs_"))
    if public != EXPORTS:
        _fail(f"public symbols changed: expected {EXPORTS}, got {public}")
    forbidden = sorted(symbol for symbol in defined + imports if FORBIDDEN.search(symbol))
    if forbidden:
        _fail(f"forbidden symbol: {forbidden}")
    unexpected = sorted(set(imports) - ALLOWED_IMPORTS)
    if unexpected:
        _fail(f"unexpected imports: {unexpected}")
    dynamic = _readelf(readelf, "--dynamic", "--wide", artifact=probe)
    needed = sorted(re.findall(r"\(NEEDED\).*?\[(.+?)\]", dynamic))
    expected_needed = ["libc.so", "libdl.so", "libm.so", "libstdc++.so"]
    if needed != expected_needed:
        _fail(f"link probe libraries changed: {needed}")
    return {
        "abi": abi, "api": api, "architecture": expected[2],
        "exports": public,
        "imports": sorted(set(imports) - TOOLCHAIN_IMPORTS),
        "needed": needed,
    }


def compare_contracts(reference: dict[str, object], candidate: dict[str, object]):
    if reference.get("contract") != candidate.get("contract"):
        _fail("static build contract differs")
    return {"status": "equivalent"}


def _write(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    inspect = sub.add_parser("inspect")
    for name in ("project-root", "archive", "probe", "readelf", "output"):
        inspect.add_argument(f"--{name}", type=Path, required=True)
    inspect.add_argument("--abi", required=True)
    inspect.add_argument("--api", required=True)
    inspect.add_argument(
        "--build-system", choices=("ndk-build", "cmake"), required=True
    )
    compare = sub.add_parser("compare")
    compare.add_argument("reference", type=Path)
    compare.add_argument("candidate", type=Path)
    compare.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "inspect":
            declarations = inspect_declarations(args.project_root)
            artifact = inspect_artifacts(
                args.archive, args.probe, args.readelf, abi=args.abi, api=args.api
            )
            result = {
                "provenance": {
                    "buildSystem": args.build_system,
                    "archiveSha256": hashlib.sha256(args.archive.read_bytes()).hexdigest(),
                },
                "contract": {
                    "declaration": declarations[
                        "ndkBuild" if args.build_system == "ndk-build" else "cmake"
                    ],
                    **artifact,
                },
            }
        else:
            result = compare_contracts(
                json.loads(args.reference.read_text(encoding="utf-8")),
                json.loads(args.candidate.read_text(encoding="utf-8")),
            )
        _write(args.output, result)
    except (OSError, StaticContractError, json.JSONDecodeError) as error:
        print(f"narfs static validation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
