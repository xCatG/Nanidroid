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
BUILD_ONLY_SOURCES = {
    "jni/narfs/narfs_core.c",
    "test/native/narfs_link_probe.c",
}
EXPECTED_ELF = {
    "class": "ELF32",
    "data": "2's complement, little endian",
    "machine": "ARM",
    "type": "DYN",
    "eabi": "Version5 EABI",
    "floatAbi": "soft-float ABI",
}
EXPECTED_ARM_ATTRIBUTES = {
    "Tag_CPU_name": "arm1022e",
    "Tag_CPU_arch": "v5TE",
    "Tag_ARM_ISA_use": "Yes",
    "Tag_THUMB_ISA_use": "Thumb-1",
}
EXPECTED_NDK_REVISION = "14.1.3816874"


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


def _arm_attributes(output: str) -> dict[str, str]:
    attributes: dict[str, str] = {}
    for match in re.finditer(
        r"^\s*(Tag_[A-Za-z0-9_]+):\s*(.+?)\s*$",
        output,
        re.MULTILINE,
    ):
        attributes[match.group(1)] = match.group(2).strip('"')
    for key, expected in EXPECTED_ARM_ATTRIBUTES.items():
        actual = attributes.get(key)
        if actual != expected:
            _fail(
                f"ARM attribute {key} changed: expected {expected}, got {actual}"
            )
    return dict(sorted(attributes.items()))


def _jni_exports(symbol_table: str) -> list[str]:
    symbols: set[str] = set()
    for line in symbol_table.splitlines():
        fields = line.split()
        if len(fields) < 8:
            continue
        symbol = fields[7].split("@", 1)[0]
        section = fields[6]
        binding = fields[4]
        if (
            symbol.startswith("Java_")
            and section != "UND"
            and binding in {"GLOBAL", "WEAK"}
        ):
            symbols.add(symbol)
    return sorted(symbols)


def _normalized_path(base: str, value: str) -> str:
    portable = value.replace("\\", "/")
    if posixpath.isabs(portable):
        _fail(f"path must be project-relative, got absolute path: {value}")
    normalized = posixpath.normpath(posixpath.join(base, portable))
    if normalized == ".." or normalized.startswith("../"):
        _fail(f"path escapes the project root: {value}")
    if (base == "jni" or base.startswith("jni/")) and not (
        normalized == "jni" or normalized.startswith("jni/")
    ):
        _fail(f"path escapes the native source root: {value}")
    return normalized


def _ordered_unique(values: list[str], field: str) -> list[str]:
    observed: set[str] = set()
    result: list[str] = []
    for value in values:
        if value in observed:
            continue
        observed.add(value)
        result.append(value)
    if not result:
        _fail(f"{field} must not be empty")
    return result


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
        "sourceFiles": _ordered_unique(sources, f"{name} source files"),
        "definitions": sorted(set(definitions)),
        "materialFlags": _ordered_unique(flags, f"{name} material flags"),
        "includeRoots": _ordered_unique(includes, f"{name} include roots"),
        "linkLibraries": _ordered_unique(
            link_libraries, f"{name} link libraries"
        ),
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
                # NDK r14b adds LOCAL_PATH for every module and applies
                # LOCAL_C_INCLUDES. The historical LOCAL_CPP_INCLUDES spelling
                # in Kawari is not present in its real V=1 compiler commands.
                # Normalize the effective search roots, while the evidence
                # parser below independently requires the same observed order.
                includes_value = variables.get("LOCAL_C_INCLUDES", base)
                include_tokens = shlex.split(
                    _expand_make(includes_value, variables)
                )
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
    text = path.read_text(encoding="utf-8")
    values = _cmake_sets(path)
    commands = re.findall(r"(?mi)^\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(", text)
    allowed_commands = {
        "cmake_minimum_required",
        "project",
        "set",
        "add_library",
        "add_executable",
        "target_include_directories",
        "target_compile_definitions",
        "target_compile_options",
        "target_link_libraries",
        "set_target_properties",
    }
    unexpected_commands = [
        command for command in commands if command.lower() not in allowed_commands
    ]
    expected_counts = {
        "add_library": 3,
        "add_executable": 1,
        "target_include_directories": 3,
        "target_compile_definitions": 2,
        "target_compile_options": 4,
        "target_link_libraries": 3,
        "set_target_properties": 3,
    }
    invalid_counts = {
        command: commands.count(command)
        for command, expected_count in expected_counts.items()
        if commands.count(command) != expected_count
    }
    set_names = re.findall(r"(?mi)^\s*set\(\s*([A-Za-z_][A-Za-z0-9_]*)", text)
    invalid_sets = [
        name for name in set_names if not name.startswith("NANIDROID_")
    ]
    duplicate_sets = sorted(
        {name for name in set_names if set_names.count(name) > 1}
    )
    if unexpected_commands or invalid_counts or invalid_sets or duplicate_sets:
        _fail(
            "undeclared CMake mutation detected: "
            f"commands={unexpected_commands}, counts={invalid_counts}, "
            f"sets={invalid_sets}, duplicateSets={duplicate_sets}"
        )
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

    compact = re.sub(r"\s+", " ", text)
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


def inspect_application_mk(project_root: Path) -> dict[str, object]:
    """Verify the tracked Application.mk values used by the reference."""
    path = project_root / "jni/Application.mk"
    if not path.is_file():
        _fail(f"Application.mk does not exist: {path}")
    values: dict[str, str] = {}
    for line in _logical_make_lines(path.read_text(encoding="utf-8")):
        assignment = re.match(
            r"^([A-Za-z0-9_]+)\s*(?::=|=)\s*(.*?)\s*$",
            line,
        )
        if assignment is not None:
            values[assignment.group(1)] = assignment.group(2)
    stl = values.get("APP_STL")
    if stl != "gnustl_static":
        _fail(f"Application.mk APP_STL changed: expected gnustl_static, got {stl}")
    cpp_flags = shlex.split(values.get("APP_CPPFLAGS", ""))
    if cpp_flags != ["-frtti", "-fexceptions"]:
        _fail(
            "Application.mk APP_CPPFLAGS changed: expected "
            f"['-frtti', '-fexceptions'], got {cpp_flags}"
        )
    return {"stl": stl, "cppFlags": cpp_flags}


def _project_relative_path(
    value: str,
    *,
    project_root: Path,
    directory: Path,
) -> str:
    path = Path(value)
    if not path.is_absolute():
        path = directory / path
    resolved = path.resolve(strict=False)
    root = project_root.resolve(strict=False)
    try:
        return resolved.relative_to(root).as_posix()
    except ValueError:
        _fail(f"build evidence path escapes the project root: {value}")


def _option_values(tokens: list[str], option: str) -> list[str]:
    values: list[str] = []
    index = 0
    while index < len(tokens):
        token = tokens[index]
        if token == option and index + 1 < len(tokens):
            values.append(tokens[index + 1])
            index += 2
            continue
        if token.startswith(option) and token != option:
            values.append(token[len(option) :])
        index += 1
    return values


def _definitions(tokens: list[str]) -> list[str]:
    definitions = [
        value for value in _option_values(tokens, "-D") if value != "ANDROID"
    ]
    by_name: dict[str, str] = {}
    for definition in definitions:
        name = definition.split("=", 1)[0]
        previous = by_name.get(name)
        if previous is not None and previous != definition:
            _fail(
                f"conflicting compile definitions for {name}: "
                f"{previous}, {definition}"
            )
        by_name[name] = definition
    return sorted(by_name.values())


def _last_matching(tokens: list[str], pattern: str) -> str:
    matches = [token for token in tokens if re.fullmatch(pattern, token)]
    return matches[-1] if matches else ""


def _compile_flags(tokens: list[str]) -> dict[str, object]:
    return {
        "optimization": _last_matching(tokens, r"-O(?:0|1|2|3|s|g|fast)"),
        "exceptions": _last_matching(tokens, r"-f(?:no-)?exceptions"),
        "rtti": _last_matching(tokens, r"-f(?:no-)?rtti"),
        "permissive": "-fpermissive" in tokens,
        "writeStringsWarning": (
            "-Wno-write-strings" if "-Wno-write-strings" in tokens else ""
        ),
    }


def _target_flags(tokens: list[str]) -> dict[str, str]:
    arm_flag = _last_matching(tokens, r"-m(?:thumb|arm)")
    float_flag = _last_matching(tokens, r"-m(?:soft|hard)-float")
    return {
        "armMode": arm_flag.removeprefix("-m"),
        "cpuArch": _last_matching(tokens, r"-march=.+").partition("=")[2],
        "cpuTune": _last_matching(tokens, r"-mtune=.+").partition("=")[2],
        "floatAbi": (
            float_flag.removeprefix("-m").removesuffix("-float")
        ),
    }


def _expected_compile_flags(module: dict[str, object]) -> dict[str, object]:
    return _compile_flags([str(value) for value in module["materialFlags"]])


def _compile_record(
    tokens: list[str],
    *,
    directory: Path,
    project_root: Path,
    ndk_root: Path,
    modules_by_source: dict[str, dict[str, object]],
    expected_abi: str,
    expected_api: str,
) -> tuple[str, dict[str, object], str]:
    if "-c" not in tokens:
        _fail("compile evidence does not contain -c")
    source_index = tokens.index("-c") + 1
    if source_index >= len(tokens):
        _fail("compile evidence has no source after -c")
    source = _project_relative_path(
        tokens[source_index],
        project_root=project_root,
        directory=directory,
    )
    module = modules_by_source.get(source)
    if module is None:
        _fail(f"compile evidence contains undeclared source: {source}")

    compiler = Path(tokens[0]).resolve(strict=False)
    expected_compiler = (
        ndk_root
        / "toolchains/arm-linux-androideabi-4.9/prebuilt/linux-x86_64/bin"
        / "arm-linux-androideabi-g++"
    ).resolve(strict=False)
    if compiler != expected_compiler:
        _fail(
            f"compiler path changed: expected {expected_compiler}, got {compiler}"
        )

    sysroots = _option_values(tokens, "--sysroot")
    if len(sysroots) != 1 or f"/platforms/{expected_api}/arch-arm" not in sysroots[0]:
        _fail(
            f"compile sysroot/API changed: expected {expected_api}, got {sysroots}"
        )
    if not any(f"/libs/{expected_abi}/" in token for token in tokens):
        _fail(
            f"compile STL ABI evidence changed: expected {expected_abi}"
        )

    includes: list[str] = []
    for include in _option_values(tokens, "-I"):
        try:
            relative = _project_relative_path(
                include,
                project_root=project_root,
                directory=directory,
            )
        except NativeContractError:
            include_path = Path(include)
            if not include_path.is_absolute():
                include_path = directory / include_path
            resolved_include = include_path.resolve(strict=False)
            try:
                resolved_include.relative_to(ndk_root.resolve(strict=False))
            except ValueError:
                _fail(
                    "compile include path escapes both the project and frozen "
                    f"NDK roots: {resolved_include}"
                )
            continue
        if relative not in includes:
            includes.append(relative)

    definitions = _definitions(tokens)
    expected_definitions = sorted(str(value) for value in module["definitions"])
    if definitions != expected_definitions:
        _fail(
            f"{module['name']} compile definitions changed for {source}: "
            f"expected {expected_definitions}, got {definitions}"
        )
    flags = _compile_flags(tokens)
    expected_flags = _expected_compile_flags(module)
    if flags != expected_flags:
        _fail(
            f"{module['name']} compile flags changed for {source}: "
            f"expected {expected_flags}, got {flags}"
        )
    expected_includes = [str(value) for value in module["includeRoots"]]
    if includes != expected_includes:
        _fail(
            f"{module['name']} include ordering changed for {source}: "
            f"expected {expected_includes}, got {includes}"
        )
    target_flags = _target_flags(tokens)
    expected_target = {
        "armMode": "thumb",
        "cpuArch": "armv5te",
        "cpuTune": "xscale",
        "floatAbi": "soft",
    }
    if target_flags != expected_target:
        _fail(
            f"{module['name']} target compile flags changed for {source}: "
            f"expected {expected_target}, got {target_flags}"
        )
    return str(module["name"]), {
        "source": source,
        "definitions": definitions,
        "materialFlags": flags,
        "includeRoots": includes,
        "targetFlags": target_flags,
    }, expected_compiler.as_posix()


def _command_tokens(command: object) -> list[str]:
    if isinstance(command, list) and all(
        isinstance(value, str) for value in command
    ):
        return command
    if isinstance(command, str):
        return shlex.split(command)
    _fail(f"build evidence command has unsupported shape: {command!r}")


def _compile_commands(
    *,
    build_system: str,
    evidence: Path,
) -> list[tuple[list[str], Path]]:
    if build_system == "cmake":
        path = evidence / "compile_commands.json"
        try:
            entries = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise NativeContractError(
                f"cannot read CMake compile commands {path}: {error}"
            ) from error
        if not isinstance(entries, list):
            _fail(f"{path} does not contain a JSON array")
        commands: list[tuple[list[str], Path]] = []
        for entry in entries:
            if not isinstance(entry, dict):
                _fail(f"invalid CMake compile entry: {entry!r}")
            raw_command = entry.get("arguments", entry.get("command"))
            commands.append(
                (
                    _command_tokens(raw_command),
                    Path(str(entry.get("directory", evidence))),
                )
            )
        return commands

    try:
        lines = evidence.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise NativeContractError(
            f"cannot read ndk-build command log {evidence}: {error}"
        ) from error
    commands = []
    for line in lines:
        if "arm-linux-androideabi-g++" not in line:
            continue
        tokens = shlex.split(line)
        if "-c" in tokens:
            commands.append((tokens, evidence.parent))
    return commands


def _object_source_order(
    tokens: list[str],
    expected_sources: list[str],
) -> list[str]:
    source_by_stem: dict[str, str] = {}
    for source in expected_sources:
        stem = Path(source).stem
        if stem in source_by_stem:
            _fail(f"cannot map duplicate source stem in link evidence: {stem}")
        source_by_stem[stem] = source
    observed: list[str] = []
    for token in tokens:
        if not token.endswith(".o"):
            continue
        name = Path(token).name
        stem = Path(name[:-2]).stem
        source = source_by_stem.get(stem)
        if source is not None:
            observed.append(source)
    return observed


def _link_command(
    *,
    build_system: str,
    evidence: Path,
    module_name: str,
) -> list[str]:
    library = (
        "libkawari8.so" if module_name == "kawari8" else "libsatoriya.so"
    )
    if build_system == "cmake":
        path = evidence / f"CMakeFiles/{module_name}.dir/link.txt"
        try:
            return shlex.split(path.read_text(encoding="utf-8"))
        except OSError as error:
            raise NativeContractError(
                f"cannot read CMake link evidence {path}: {error}"
            ) from error
    try:
        lines = evidence.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise NativeContractError(
            f"cannot read ndk-build link log {evidence}: {error}"
        ) from error
    matches = [
        shlex.split(line)
        for line in lines
        if "arm-linux-androideabi-g++" in line
        and "-shared" in line
        and library in line
    ]
    if len(matches) != 1:
        _fail(
            f"expected one ndk-build link command for {library}, got {len(matches)}"
        )
    return matches[0]


def _verify_ndk_identity(ndk_root: Path) -> str:
    properties = ndk_root / "source.properties"
    try:
        text = properties.read_text(encoding="utf-8")
    except OSError as error:
        raise NativeContractError(
            f"cannot read NDK identity {properties}: {error}"
        ) from error
    match = re.search(r"(?m)^Pkg\.Revision\s*=\s*(\S+)\s*$", text)
    revision = match.group(1) if match is not None else ""
    if revision != EXPECTED_NDK_REVISION or ndk_root.name != "android-ndk-r14b":
        _fail(
            "NDK identity changed: expected android-ndk-r14b "
            f"{EXPECTED_NDK_REVISION}, got {ndk_root.name} {revision}"
        )
    return "r14b"


def inspect_build_evidence(
    *,
    build_system: str,
    evidence: Path,
    project_root: Path,
    ndk_root: Path,
    modules: list[dict[str, object]],
    expected_abi: str,
    expected_api: str,
) -> dict[str, object]:
    """Normalize real compile and link commands from each native engine."""
    measured_ndk = _verify_ndk_identity(ndk_root)
    commands = _compile_commands(build_system=build_system, evidence=evidence)
    if not commands:
        _fail(f"{build_system} produced no compile command evidence")
    modules_by_source = {
        str(source): module
        for module in modules
        for source in module["sourceFiles"]
    }
    records: dict[str, list[dict[str, object]]] = {
        str(module["name"]): [] for module in modules
    }
    compiler_paths: set[str] = set()
    for tokens, directory in commands:
        source_index = tokens.index("-c") + 1
        source = _project_relative_path(
            tokens[source_index],
            project_root=project_root,
            directory=directory,
        )
        if source in BUILD_ONLY_SOURCES:
            continue
        module_name, record, compiler_path = _compile_record(
            tokens,
            directory=directory,
            project_root=project_root,
            ndk_root=ndk_root,
            modules_by_source=modules_by_source,
            expected_abi=expected_abi,
            expected_api=expected_api,
        )
        records[module_name].append(record)
        compiler_paths.add(compiler_path)
    if len(compiler_paths) != 1:
        _fail(f"compiler paths changed within {build_system}: {compiler_paths}")
    compiler_path = next(iter(compiler_paths))
    try:
        compiler_dump_version = subprocess.run(
            [compiler_path, "-dumpversion"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        compiler_banner = subprocess.run(
            [compiler_path, "--version"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.splitlines()[0]
    except (OSError, subprocess.CalledProcessError) as error:
        raise NativeContractError(
            f"cannot measure compiler version from {compiler_path}: {error}"
        ) from error
    expected_banner = (
        "arm-linux-androideabi-g++ (GCC) 4.9.x 20150123 (prerelease)"
    )
    if compiler_dump_version != "4.9.x" or compiler_banner != expected_banner:
        _fail(
            "compiler version changed: expected r14b GCC "
            f"4.9.x/20150123, got {compiler_dump_version!r}, "
            f"{compiler_banner!r}"
        )

    normalized_modules: list[dict[str, object]] = []
    for module in modules:
        name = str(module["name"])
        module_records = records[name]
        observed_sources = [str(record["source"]) for record in module_records]
        expected_sources = [str(value) for value in module["sourceFiles"]]
        if observed_sources != expected_sources:
            _fail(
                f"{name} compile source ordering changed: "
                f"expected {expected_sources}, got {observed_sources}"
            )
        configurations = [
            {
                key: value
                for key, value in record.items()
                if key != "source"
            }
            for record in module_records
        ]
        if not configurations or any(
            configuration != configurations[0]
            for configuration in configurations[1:]
        ):
            _fail(f"{name} compile configuration differs between sources")

        link_tokens = _link_command(
            build_system=build_system,
            evidence=evidence,
            module_name=name,
        )
        link_sources = _object_source_order(link_tokens, expected_sources)
        if link_sources != expected_sources:
            _fail(
                f"{name} link object ordering changed: "
                f"expected {expected_sources}, got {link_sources}"
            )
        expected_libraries = [str(value) for value in module["linkLibraries"]]
        observed_libraries = [
            token[2:]
            for token in link_tokens
            if token.startswith("-l") and token[2:] in expected_libraries
        ]
        if observed_libraries != expected_libraries:
            _fail(
                f"{name} link library ordering changed: "
                f"expected {expected_libraries}, got {observed_libraries}"
            )
        stl_archives = [
            token for token in link_tokens if token.endswith("libgnustl_static.a")
        ]
        if len(stl_archives) != 1 or (
            f"/gnu-libstdc++/4.9/libs/{expected_abi}/libgnustl_static.a"
            not in stl_archives[0]
        ):
            _fail(
                f"{name} static STL link evidence changed: {stl_archives}"
            )
        normalized_modules.append(
            {
                "name": name,
                "compiler": (
                    "toolchains/arm-linux-androideabi-4.9/"
                    "prebuilt/linux-x86_64/bin/arm-linux-androideabi-g++"
                ),
                "compilerVersion": compiler_banner,
                "sourceFiles": observed_sources,
                **configurations[0],
                "linkSourceFiles": link_sources,
                "linkLibraries": observed_libraries,
                "stl": "gnustl_static",
            }
        )

    return {
        "ndk": measured_ndk,
        "ndkRevision": EXPECTED_NDK_REVISION,
        "abi": expected_abi,
        "api": expected_api,
        "modules": normalized_modules,
    }


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
        "CMAKE_BUILD_TYPE": "",
        "CMAKE_EXPORT_COMPILE_COMMANDS": "ON",
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
    ndk_root: Path,
    build_evidence: Path,
    cmake_cache: Path | None = None,
) -> dict[str, object]:
    """Return normalized build declarations and stable ELF/JNI facts."""
    if compiler != "gcc-4.9":
        _fail(f"requested compiler changed: expected gcc-4.9, got {compiler}")
    if not root.is_dir():
        _fail(f"native artifact directory does not exist: {root}")
    observed = sorted(
        path.relative_to(root).as_posix() for path in root.rglob("*.so")
    )
    expected = sorted(EXPECTED_LIBRARIES)
    if observed != expected:
        _fail(f"native library paths changed: expected {expected}, got {observed}")

    application_mk = inspect_application_mk(project_root)
    if stl != application_mk["stl"]:
        _fail(
            f"requested STL differs from Application.mk: {stl}, "
            f"{application_mk['stl']}"
        )

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

    measured_build = inspect_build_evidence(
        build_system=build_system,
        evidence=build_evidence,
        project_root=project_root,
        ndk_root=ndk_root,
        modules=modules,
        expected_abi=abi,
        expected_api=api,
    )
    if measured_build["ndk"] != ndk:
        _fail(
            f"measured NDK differs from requested NDK: "
            f"{measured_build['ndk']}, {ndk}"
        )
    measured_arm_modes = {
        str(module["targetFlags"]["armMode"])
        for module in measured_build["modules"]
    }
    if measured_arm_modes != {arm_mode}:
        _fail(
            f"measured ARM mode differs from requested mode: "
            f"{measured_arm_modes}, {arm_mode}"
        )

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
                "armAttributes": _arm_attributes(
                    _readelf(
                        readelf,
                        "--arch-specific",
                        "--wide",
                        library=library,
                    )
                ),
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
                "ndk": measured_build["ndk"],
                "ndkRevision": measured_build["ndkRevision"],
                "abi": measured_build["abi"],
                "api": measured_build["api"],
                "compiler": "gcc-4.9",
                "stl": application_mk["stl"],
                "armMode": next(iter(measured_arm_modes)),
            },
            "applicationMake": application_mk,
            "modules": modules,
            "buildEvidence": measured_build["modules"],
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
            "ELF ARM/Thumb/CPU attributes",
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
    inspect_parser.add_argument("--ndk-root", type=Path, required=True)
    inspect_parser.add_argument("--build-evidence", type=Path, required=True)
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
                ndk_root=args.ndk_root,
                build_evidence=args.build_evidence,
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
