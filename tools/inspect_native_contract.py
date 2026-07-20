#!/usr/bin/env python3
"""Inventory and compare the stable Nanidroid native build contract."""

from __future__ import annotations

import argparse
import hashlib
import json
import posixpath
import re
import shlex
import subprocess
import sys
from pathlib import Path
from typing import NoReturn


EXPECTED_LIBRARIES = {
    "armeabi/libkawari8.so": "kawari8",
    "armeabi/libsatoriya.so": "satoriya",
}
EXPECTED_MODULES = sorted(EXPECTED_LIBRARIES.values())
EXPECTED_ELF = {
    "class": "ELF32",
    "data": "2's complement, little endian",
    "machine": "ARM",
    "type": "DYN",
    "eabi": "Version5 EABI",
    "floatAbi": "soft-float ABI",
}


class NativeContractError(ValueError):
    """A native build or artifact does not satisfy the frozen PR C1 contract."""


def _fail(message: str) -> NoReturn:
    raise NativeContractError(message)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _readelf(readelf: Path, *arguments: str, library: Path) -> str:
    try:
        return subprocess.run(
            [str(readelf), *arguments, str(library)],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
    except subprocess.CalledProcessError as error:
        detail = error.stderr.strip() if error.stderr else str(error)
        raise NativeContractError(
            f"readelf failed for {library}: {detail}"
        ) from error


def _header_value(header: str, field: str) -> str:
    match = re.search(rf"^\s*{re.escape(field)}:\s*(.+?)\s*$", header, re.MULTILINE)
    if match is None:
        _fail(f"ELF header does not contain {field}")
    return match.group(1)


def _dynamic_values(dynamic: str, tag: str) -> list[str]:
    return sorted(
        re.findall(
            rf"\({re.escape(tag)}\).*?\[(.+?)\]\s*$",
            dynamic,
            re.MULTILINE,
        )
    )


def _jni_exports(symbol_table: str) -> list[str]:
    symbols: set[str] = set()
    for line in symbol_table.splitlines():
        fields = line.split()
        if len(fields) < 8:
            continue
        symbol = fields[-1].split("@", 1)[0]
        section = fields[-2]
        binding = fields[-4]
        if (
            symbol.startswith("Java_")
            and section != "UND"
            and binding in {"GLOBAL", "WEAK"}
        ):
            symbols.add(symbol)
    return sorted(symbols)


def _normalized_path(base: str, value: str) -> str:
    return posixpath.normpath(posixpath.join(base, value.replace("\\", "/")))


def _expand_make(value: str, variables: dict[str, str]) -> str:
    pattern = re.compile(r"\$\(([A-Za-z0-9_]+)\)")
    for _ in range(20):
        expanded = pattern.sub(lambda match: variables.get(match.group(1), ""), value)
        if expanded == value:
            return expanded
        value = expanded
    _fail(f"Make variable expansion did not converge: {value}")


def _logical_make_lines(text: str) -> list[str]:
    lines: list[str] = []
    current = ""
    for physical in text.splitlines():
        content = physical.split("#", 1)[0].rstrip()
        current = f"{current} {content}".strip()
        if current.endswith("\\"):
            current = current[:-1].rstrip()
            continue
        if current:
            lines.append(current)
        current = ""
    if current:
        lines.append(current)
    return lines


def _module(
    name: str,
    sources: list[str],
    definitions: list[str],
    flags: list[str],
    includes: list[str],
    link_libraries: list[str],
) -> dict[str, object]:
    return {
        "name": name,
        "sourceFiles": sorted(set(sources)),
        "definitions": sorted(set(definitions)),
        "materialFlags": sorted(set(flags)),
        "includeRoots": sorted(set(includes)),
        "linkLibraries": sorted(set(link_libraries)),
    }


def inspect_android_mk(project_root: Path) -> list[dict[str, object]]:
    """Normalize the two Android.mk module declarations."""
    modules: list[dict[str, object]] = []
    global_flags = ["-frtti", "-fexceptions", "-fpermissive"]
    for relative in ("jni/kawari8/Android.mk", "jni/satori/Android.mk"):
        path = project_root / relative
        if not path.is_file():
            _fail(f"Android.mk does not exist: {path}")
        base = path.parent.relative_to(project_root).as_posix()
        variables: dict[str, str] = {"LOCAL_PATH": base}
        for line in _logical_make_lines(path.read_text(encoding="utf-8")):
            if line == "include $(CLEAR_VARS)":
                variables = {
                    key: value
                    for key, value in variables.items()
                    if not key.startswith("LOCAL_") or key == "LOCAL_PATH"
                }
                continue
            if line == "include $(BUILD_SHARED_LIBRARY)":
                name = _expand_make(variables.get("LOCAL_MODULE", ""), variables)
                source_tokens = shlex.split(
                    _expand_make(variables.get("LOCAL_SRC_FILES", ""), variables)
                )
                local_flags = shlex.split(
                    _expand_make(variables.get("LOCAL_CPPFLAGS", ""), variables)
                )
                definitions = ["NDEBUG"] + [
                    value[2:] for value in local_flags if value.startswith("-D")
                ]
                local_material_flags = [
                    value for value in local_flags if not value.startswith("-D")
                ]
                optimization_flags = [
                    value
                    for value in local_material_flags
                    if re.fullmatch(r"-O(?:0|1|2|3|s|fast|g)", value)
                ]
                effective_optimization = (
                    optimization_flags[-1] if optimization_flags else "-Os"
                )
                flags = (
                    global_flags
                    + [
                        value
                        for value in local_material_flags
                        if value not in optimization_flags
                    ]
                    + [effective_optimization]
                )
                includes_value = variables.get(
                    "LOCAL_CPP_INCLUDES",
                    variables.get("LOCAL_C_INCLUDES", ""),
                )
                include_tokens = shlex.split(_expand_make(includes_value, variables))
                link_tokens = shlex.split(
                    _expand_make(variables.get("LOCAL_LDLIBS", ""), variables)
                )
                modules.append(
                    _module(
                        name,
                        [_normalized_path(base, value) for value in source_tokens],
                        definitions,
                        flags,
                        [
                            _normalized_path("", value)
                            for value in include_tokens
                        ],
                        [
                            value[2:]
                            for value in link_tokens
                            if value.startswith("-l")
                        ],
                    )
                )
                continue

            assignment = re.match(
                r"^([A-Za-z0-9_]+)\s*(?::=|=)\s*(.*?)\s*$",
                line,
            )
            if assignment is None:
                continue
            key, value = assignment.groups()
            if key == "LOCAL_PATH" and "$(call my-dir)" in value:
                value = base
            variables[key] = value

    _validate_modules(modules, "Android.mk")
    return sorted(modules, key=lambda module: str(module["name"]))


def _cmake_sets(path: Path) -> dict[str, list[str]]:
    text = re.sub(r"(?m)^\s*#.*$", "", path.read_text(encoding="utf-8"))
    values: dict[str, list[str]] = {}
    for match in re.finditer(
        r"set\(\s*(NANIDROID_[A-Z0-9_]+)\s+(.*?)\)",
        text,
        re.DOTALL,
    ):
        values[match.group(1)] = shlex.split(match.group(2))
    return values


def inspect_cmake(project_root: Path) -> list[dict[str, object]]:
    """Normalize the declarations shared by the CMake targets."""
    path = project_root / "jni/CMakeLists.txt"
    if not path.is_file():
        _fail(f"CMake candidate declaration does not exist: {path}")
    values = _cmake_sets(path)
    modules: list[dict[str, object]] = []
    for key in ("KAWARI8", "SATORIYA"):
        prefix = f"NANIDROID_{key}_"

        def required(field: str) -> list[str]:
            name = prefix + field
            if name not in values or not values[name]:
                _fail(f"CMake declaration is missing {name}")
            return values[name]

        module_name = required("MODULE")
        if len(module_name) != 1:
            _fail(f"{prefix}MODULE must contain exactly one module name")
        modules.append(
            _module(
                module_name[0],
                [_normalized_path("jni", value) for value in required("SOURCES")],
                required("DEFINITIONS"),
                required("FLAGS"),
                [
                    _normalized_path("jni", value)
                    for value in required("INCLUDES")
                ],
                required("LINK_LIBRARIES"),
            )
        )

    compact = re.sub(r"\s+", " ", path.read_text(encoding="utf-8"))
    for key in ("KAWARI8", "SATORIYA"):
        variable = f"NANIDROID_{key}"
        required_wiring = (
            rf"add_library\(\s*\$\{{{variable}_MODULE\}}\s+SHARED\s+"
            rf"\$\{{{variable}_SOURCES\}}\s*\)",
            rf"target_include_directories\(\s*\$\{{{variable}_MODULE\}}\s+"
            rf"PRIVATE\s+\$\{{{variable}_INCLUDES\}}\s*\)",
            rf"target_compile_definitions\(\s*\$\{{{variable}_MODULE\}}\s+"
            rf"PRIVATE\s+\$\{{{variable}_DEFINITIONS\}}\s*\)",
            rf"target_compile_options\(\s*\$\{{{variable}_MODULE\}}\s+"
            rf"PRIVATE\s+\$\{{{variable}_FLAGS\}}\s*\)",
            rf"target_link_libraries\(\s*\$\{{{variable}_MODULE\}}\s+"
            rf"\$\{{{variable}_LINK_LIBRARIES\}}\s*\)",
        )
        for pattern in required_wiring:
            if re.search(pattern, compact) is None:
                _fail(f"CMake target {key.lower()} is not wired through its contract variables")

    _validate_modules(modules, "CMake")
    return sorted(modules, key=lambda module: str(module["name"]))


def _validate_modules(modules: list[dict[str, object]], source: str) -> None:
    names = sorted(str(module.get("name")) for module in modules)
    if names != EXPECTED_MODULES:
        _fail(f"{source} modules changed: expected {EXPECTED_MODULES}, got {names}")
    for module in modules:
        for field in (
            "sourceFiles",
            "definitions",
            "materialFlags",
            "includeRoots",
            "linkLibraries",
        ):
            if not module[field]:
                _fail(f"{source} module {module['name']} has no {field}")


def _cache_values(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith(("//", "#")) or "=" not in line:
            continue
        key_and_type, value = line.split("=", 1)
        key = key_and_type.split(":", 1)[0]
        values[key] = value
    return values


def _verify_cmake_cache(
    path: Path,
    *,
    abi: str,
    api: str,
    compiler: str,
    stl: str,
    arm_mode: str,
) -> str:
    if not path.is_file():
        _fail(f"CMake cache does not exist: {path}")
    values = _cache_values(path)
    expected = {
        "ANDROID_ABI": abi,
        "ANDROID_PLATFORM": api,
        "ANDROID_STL": stl,
        "ANDROID_TOOLCHAIN": "gcc",
        "ANDROID_ARM_MODE": arm_mode,
    }
    for key, required in expected.items():
        actual = values.get(key)
        if actual != required:
            _fail(f"CMake cache {key} changed: expected {required}, got {actual}")
    cxx = values.get("NANIDROID_CXX_COMPILER", "")
    cxx_id = values.get("NANIDROID_CXX_COMPILER_ID", "")
    cxx_version = values.get("NANIDROID_CXX_COMPILER_VERSION", "")
    if compiler == "gcc-4.9" and (
        not cxx.endswith("arm-linux-androideabi-g++")
        or cxx_id != "GNU"
        or not re.fullmatch(r"4\.9(?:\.\d+)?", cxx_version)
    ):
        _fail(
            "CMake compiler changed: expected GCC 4.9 "
            f"arm-linux-androideabi-g++, got {cxx_id} {cxx_version} {cxx}"
        )
    version = ".".join(
        values.get(key, "")
        for key in (
            "CMAKE_CACHE_MAJOR_VERSION",
            "CMAKE_CACHE_MINOR_VERSION",
            "CMAKE_CACHE_PATCH_VERSION",
        )
    )
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        _fail(f"CMake cache does not declare a valid CMake version: {version}")
    return version


def inspect_native_directory(
    root: Path,
    readelf: Path,
    *,
    project_root: Path,
    build_system: str,
    abi: str,
    api: str,
    compiler: str,
    stl: str,
    arm_mode: str,
    ndk: str,
    cmake_cache: Path | None = None,
) -> dict[str, object]:
    """Return normalized build declarations and stable ELF/JNI facts."""
    if not root.is_dir():
        _fail(f"native artifact directory does not exist: {root}")
    observed = sorted(
        path.relative_to(root).as_posix() for path in root.rglob("*.so")
    )
    expected = sorted(EXPECTED_LIBRARIES)
    if observed != expected:
        _fail(f"native library paths changed: expected {expected}, got {observed}")

    if build_system == "ndk-build":
        modules = inspect_android_mk(project_root)
        cmake_version = None
    elif build_system == "cmake":
        modules = inspect_cmake(project_root)
        if cmake_cache is None:
            _fail("CMake inspection requires --cmake-cache")
        cmake_version = _verify_cmake_cache(
            cmake_cache,
            abi=abi,
            api=api,
            compiler=compiler,
            stl=stl,
            arm_mode=arm_mode,
        )
    else:
        _fail(f"unsupported build system: {build_system}")

    libraries: list[dict[str, object]] = []
    hashes: dict[str, str] = {}
    for relative_path, module_name in sorted(EXPECTED_LIBRARIES.items()):
        library = root / relative_path
        if library.read_bytes()[:4] != b"\x7fELF":
            _fail(f"{relative_path} is not an ELF file")
        header = _readelf(readelf, "--file-header", library=library)
        flags = _header_value(header, "Flags")
        elf = {
            "class": _header_value(header, "Class"),
            "data": _header_value(header, "Data"),
            "machine": _header_value(header, "Machine"),
            "type": _header_value(header, "Type").split(" ", 1)[0],
            "eabi": EXPECTED_ELF["eabi"] if EXPECTED_ELF["eabi"] in flags else "",
            "floatAbi": (
                EXPECTED_ELF["floatAbi"]
                if EXPECTED_ELF["floatAbi"] in flags
                else ""
            ),
        }
        if elf != EXPECTED_ELF:
            _fail(
                f"{relative_path} ELF identity changed: "
                f"expected {EXPECTED_ELF}, got {elf}"
            )

        dynamic = _readelf(readelf, "--dynamic", "--wide", library=library)
        sonames = _dynamic_values(dynamic, "SONAME")
        if len(sonames) != 1:
            _fail(f"{relative_path} must have exactly one SONAME, got {sonames}")
        jni_exports = _jni_exports(
            _readelf(readelf, "--dyn-syms", "--wide", library=library)
        )
        if not jni_exports:
            _fail(f"{relative_path} exports no JNI symbols")

        libraries.append(
            {
                "module": module_name,
                "path": relative_path,
                "elf": elf,
                "soname": sonames[0],
                "needed": _dynamic_values(dynamic, "NEEDED"),
                "jniExports": jni_exports,
            }
        )
        hashes[relative_path] = _sha256(library)

    return {
        "provenance": {
            "buildSystem": build_system,
            "ndk": ndk,
            "cmake": cmake_version,
            "librarySha256": hashes,
        },
        "contract": {
            "toolchain": {
                "ndk": ndk,
                "abi": abi,
                "api": api,
                "compiler": compiler,
                "stl": stl,
                "armMode": arm_mode,
            },
            "modules": modules,
            "libraries": libraries,
        },
    }


def _first_difference(expected: object, actual: object, path: str = "contract") -> str | None:
    if type(expected) is not type(actual):
        return path
    if isinstance(expected, dict):
        keys = sorted(set(expected) | set(actual))
        for key in keys:
            if key not in expected or key not in actual:
                return f"{path}.{key}"
            difference = _first_difference(
                expected[key],
                actual[key],
                f"{path}.{key}",
            )
            if difference is not None:
                return difference
        return None
    if isinstance(expected, list):
        if len(expected) != len(actual):
            return path
        for index, (expected_item, actual_item) in enumerate(zip(expected, actual)):
            difference = _first_difference(
                expected_item,
                actual_item,
                f"{path}[{index}]",
            )
            if difference is not None:
                return difference
        return None
    return None if expected == actual else path


def compare_native_contracts(
    reference: dict[str, object], candidate: dict[str, object]
) -> dict[str, object]:
    """Compare behavior-bearing native facts while retaining hash provenance."""
    expected = reference.get("contract")
    actual = candidate.get("contract")
    difference = _first_difference(expected, actual)
    if difference is not None:
        _fail(
            f"candidate native contract differs at {difference}: "
            f"expected {expected!r}, got {actual!r}"
        )
    return {
        "status": "equivalent",
        "comparedFacts": [
            "toolchain NDK/ABI/API/compiler/STL/ARM mode",
            "module names and normalized source sets",
            "definitions, material flags, include roots, and link libraries",
            "ELF class/data/machine/EABI/float ABI",
            "SONAME and DT_NEEDED",
            "exported JNI symbols",
        ],
        "ignoredFacts": [
            "build system name",
            "CMake version",
            "timestamps",
            "debug sections",
            "build IDs",
            "whole-file hashes",
        ],
    }


def _load(path: Path) -> dict[str, object]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise NativeContractError(f"cannot read native contract {path}: {error}") from error
    if not isinstance(value, dict):
        _fail(f"{path} does not contain a JSON object")
    return value


def _write(path: Path, value: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    inspect_parser = subparsers.add_parser("inspect")
    inspect_parser.add_argument("root", type=Path)
    inspect_parser.add_argument("--readelf", type=Path, required=True)
    inspect_parser.add_argument("--project-root", type=Path, required=True)
    inspect_parser.add_argument(
        "--build-system",
        choices=("ndk-build", "cmake"),
        required=True,
    )
    inspect_parser.add_argument("--abi", required=True)
    inspect_parser.add_argument("--api", required=True)
    inspect_parser.add_argument("--compiler", required=True)
    inspect_parser.add_argument("--stl", required=True)
    inspect_parser.add_argument("--arm-mode", required=True)
    inspect_parser.add_argument("--ndk", required=True)
    inspect_parser.add_argument("--cmake-cache", type=Path)
    inspect_parser.add_argument("--output", type=Path, required=True)

    compare_parser = subparsers.add_parser("compare")
    compare_parser.add_argument("reference", type=Path)
    compare_parser.add_argument("candidate", type=Path)
    compare_parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = _arguments()
    try:
        if args.command == "inspect":
            result = inspect_native_directory(
                args.root,
                args.readelf,
                project_root=args.project_root,
                build_system=args.build_system,
                abi=args.abi,
                api=args.api,
                compiler=args.compiler,
                stl=args.stl,
                arm_mode=args.arm_mode,
                ndk=args.ndk,
                cmake_cache=args.cmake_cache,
            )
        else:
            result = compare_native_contracts(
                _load(args.reference),
                _load(args.candidate),
            )
        _write(args.output, result)
    except (NativeContractError, OSError) as error:
        print(f"native contract validation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
