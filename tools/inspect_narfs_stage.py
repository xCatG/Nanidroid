#!/usr/bin/env python3
"""Inspect the guarded narfs staging archive and its C-only link probe."""

from __future__ import annotations

import argparse
import hashlib
import json
import posixpath
import re
import shlex
import subprocess
from pathlib import Path

from inspect_narfs_static import _headers, _readelf, _symbols
from inspect_narfs_sha256 import _commands, _compact, _lane, _norm, _option_values


EXPECTED = {
    "kind": "static",
    "module": "narfs_stage",
    "source": "jni/narfs/narfs_stage.c",
    "probeSource": "test/native/narfs_stage_link_probe.c",
    "include": "jni/narfs",
    "flags": ["-std=c99", "-Wall", "-Wextra", "-Werror"],
    "linkModules": ["narfs_stage", "narfs_core", "narfs_sha256"],
}
EXPORTS = sorted([
    "narfs_default_stage_options", "narfs_stage_clone_retained", "narfs_stage_discard",
    "narfs_stage_existing", "narfs_stage_result_dispose",
])
IMPORTS = sorted([
    "__errno", "close", "closedir", "dup", "fdopendir", "free", "fsync",
    "fstat", "fstatat", "malloc", "memcmp", "memcpy", "memset", "mkdirat",
    "narfs_default_options", "narfs_inspect", "narfs_sha256_final",
    "narfs_sha256_init", "narfs_sha256_update", "open", "openat", "read",
    "readdir", "realloc", "snprintf", "strcmp", "strdup", "strlen",
    "unlinkat", "write",
])
TOOLCHAIN_IMPORTS = {
    "_GLOBAL_OFFSET_TABLE_", "__aeabi_unwind_cpp_pr0",
    "__aeabi_unwind_cpp_pr1", "__stack_chk_fail", "__stack_chk_guard",
}


class StageContractError(ValueError):
    pass


def _fail(message: str) -> None:
    raise StageContractError(message)


def _imports(abi: str, build_system: str) -> list[str]:
    return [value for value in IMPORTS
            if not (abi == "arm64-v8a" and build_system == "ndk-build"
                    and value == "memset")]


def _toolchain_imports(abi: str, build_system: str) -> list[str]:
    if abi == "arm64-v8a":
        return ["__stack_chk_fail", "__stack_chk_guard"]
    if build_system == "ndk-build":
        return [
            "_GLOBAL_OFFSET_TABLE_", "__aeabi_unwind_cpp_pr0", "__aeabi_unwind_cpp_pr1",
            "__stack_chk_fail", "__stack_chk_guard",
        ]
    return [
        "__aeabi_unwind_cpp_pr0", "__aeabi_unwind_cpp_pr1",
        "__stack_chk_fail", "__stack_chk_guard",
    ]


def inspect_declarations(root: Path) -> dict[str, object]:
    parent_make = _compact(root / "jni/narfs/Android.mk")
    parent_cmake = _compact(root / "jni/narfs/CMakeLists.txt")
    make = _compact(root / "jni/narfs/stage/module.mk")
    cmake = _compact(root / "jni/narfs/stage/CMakeLists.txt")
    if (root / "jni/narfs/stage/Android.mk").exists():
        _fail("staging module is independently discoverable")
    required = (
        ("Android parent", parent_make, (
            "ifeq ($(NANIDROID_NARFS_STAGE_CANDIDATE),1) "
            "include $(LOCAL_PATH)/stage/module.mk endif",)),
        ("CMake parent", parent_cmake, (
            'option(NANIDROID_BUILD_NARFS_STAGE_CANDIDATE '
            '"Build staging candidate" OFF)',
            "if(NANIDROID_BUILD_NARFS_STAGE_CANDIDATE)",
            'message(FATAL_ERROR "staging candidate requires SHA-256 candidate")',
            "add_subdirectory(stage) endif()")),
        ("Android module", make, (
            "LOCAL_MODULE := narfs_stage ",
            "LOCAL_SRC_FILES := ../narfs_stage.c ",
            "LOCAL_MODULE := narfs_stage_link_probe ",
            "LOCAL_STATIC_LIBRARIES := narfs_stage narfs_core narfs_sha256 ",
            "LOCAL_LDFLAGS := -Wl,--no-undefined ")),
        ("CMake module", cmake, (
            "add_library(narfs_stage STATIC ../narfs_stage.c)",
            "add_executable( narfs_stage_link_probe ",
            "target_link_libraries( narfs_stage_link_probe "
            "narfs_stage narfs_core narfs_sha256)",
            'LINKER_LANGUAGE C LINK_FLAGS "-Wl,--no-undefined"')),
    )
    for label, text, tokens in required:
        for token in tokens:
            if token not in text + " ":
                _fail(f"{label} declaration changed: {token}")
    if make.count("include $(BUILD_STATIC_LIBRARY)") != 1 \
            or make.count("include $(BUILD_EXECUTABLE)") != 1 \
            or len(re.findall(r"add_library\([^)]* STATIC ", cmake)) != 1 \
            or cmake.count("add_executable(") != 1:
        _fail("staging module count changed")
    return {"ndkBuild": dict(EXPECTED), "cmake": dict(EXPECTED)}


def _compiler(abi: str) -> tuple[str, str, str]:
    ndk = "/opt/android-ndk-r14b"
    arch = "arm-linux-androideabi" if abi == "armeabi" \
        else "aarch64-linux-android"
    return (
        ndk,
        f"{ndk}/toolchains/{arch}-4.9/prebuilt/linux-x86_64/bin/{arch}-gcc",
        arch,
    )


def _compile_records(
        commands: list[list[str]], wanted: dict[str, str]
) -> dict[str, list[str]]:
    records: dict[str, list[str]] = {}
    for tokens in commands:
        if "-c" not in tokens:
            continue
        source = _norm(tokens[tokens.index("-c") + 1])
        matches = [key for key, value in wanted.items() if source == value]
        if not matches:
            continue
        key = matches[0]
        if key in records:
            _fail(f"duplicate {key} compile evidence")
        records[key] = tokens
    if set(records) != set(wanted):
        _fail("missing staging compile evidence")
    return records


def _signature(
        tokens: list[str], dynamic_options: set[str],
        mask_inputs: bool = False
) -> list[str]:
    result: list[str] = []
    index = 1
    while index < len(tokens):
        value = tokens[index]
        matched = next((
            option for option in dynamic_options
            if value.startswith(option + "=")
            or option in ("-I", "-isystem")
            and value.startswith(option) and value != option
        ), None)
        if value in dynamic_options:
            if index + 1 == len(tokens):
                _fail(f"{value} is missing its value")
            result.extend((value, "<value>"))
            index += 2
        elif matched is not None:
            result.extend((matched, "<value>"))
            index += 1
        elif mask_inputs and value.endswith(".o"):
            result.append("<object>")
            index += 1
        elif mask_inputs and value.endswith(".a"):
            result.append("<archive>")
            index += 1
        else:
            result.append(value)
            index += 1
    return result


def _toolchain_flags(lane: tuple, repeats: int = 1) -> list[str]:
    block = [
        "-g", "-DANDROID", "-ffunction-sections", "-funwind-tables",
        "-fstack-protector-strong", "-no-canonical-prefixes", *lane[4],
        "-Wa,--noexecstack", "-Wformat", "-Werror=format-security",
    ]
    return block * repeats


def _compile_signature(
        build_system: str, abi: str, key: str, lane: tuple,
        include_count: int, system_count: int
) -> list[str]:
    includes = ["-I", "<value>"] * include_count
    systems = ["-isystem", "<value>"] * system_count
    if build_system == "ndk-build":
        optimization = "-Os" if abi == "armeabi" else "-O2"
        pie = ["-fpie"] if abi == "arm64-v8a" and key == "probe" else []
        return [
            "-MMD", "-MP", "-MF", "<value>", "-fpic",
            "-ffunction-sections", "-funwind-tables",
            "-fstack-protector-strong", "-no-canonical-prefixes",
            "-g", *lane[4], optimization, "-DNDEBUG", *includes,
            "-DANDROID", *EXPECTED["flags"], "-Wa,--noexecstack",
            "-Wformat", "-Werror=format-security", *pie,
            "--sysroot", "<value>", "-c", "<value>", "-o", "<value>",
        ]
    position = ["-fPIC" if key == "source" else "-fPIE"] \
        if abi == "arm64-v8a" else []
    return [
        "--sysroot", "<value>", *includes, *systems,
        *_toolchain_flags(lane, 2), *position, *EXPECTED["flags"],
        "-o", "<value>", "-c", "<value>",
    ]


def _link_signature(
        build_system: str, abi: str, lane: tuple,
        rpaths: list[str], wl: list[str]
) -> list[str]:
    if build_system == "ndk-build":
        pie = ["-fpie", "-pie"] if abi == "arm64-v8a" else []
        return [
            "-Wl,--gc-sections", "-Wl,-z,nocopyreloc",
            "--sysroot", "<value>", *rpaths, "<object>",
            "<archive>", "<archive>", "<archive>", "-lgcc",
            "-no-canonical-prefixes", *wl[2:], *pie, "-lc", "-lm",
            "-o", "<value>",
        ]
    pie = ["-pie", "-fPIE"] if abi == "arm64-v8a" else []
    block = wl[:-1]
    return [
        "--sysroot", "<value>", *_toolchain_flags(lane, 2),
        *block[:9], *pie, *block[9:], *pie, wl[-1],
        "<object>", "-o", "<value>",
        "<archive>", "<archive>", "<archive>", "-lm",
    ]


def inspect_build_evidence(
        evidence: Path, build_system: str, abi: str, api: str
) -> dict[str, object]:
    lane = _lane(abi, api)
    commands = [shlex.split(value) for value in _commands(
        evidence, build_system)]
    build_root = evidence.parent.parent if build_system == "ndk-build" \
        else evidence.parent
    build_root = Path(_norm(build_root.resolve()))
    wanted_sources = {
        "source": _norm(build_root / EXPECTED["source"]),
        "probe": _norm(build_root / EXPECTED["probeSource"]),
    }
    records = _compile_records(commands, wanted_sources)
    ndk, compiler, arch = _compiler(abi)
    sysroot = f"{ndk}/platforms/{lane[0]}/arch-" + (
        "arm" if abi == "armeabi" else "arm64")
    policy = (EXPECTED["flags"] + ["-Wformat", "-Werror=format-security"]
              if build_system == "ndk-build" else
              ["-Wformat", "-Werror=format-security"] * 2 + EXPECTED["flags"])
    base_include = _norm(build_root / "jni/narfs")
    stage_include = _norm(build_root / "jni/narfs/stage")
    raw_includes = {
        "source": ([base_include + "/stage/..", stage_include]
                   if build_system == "ndk-build"
                   else [base_include + "/stage/.."]),
        "probe": ([base_include + "/stage/..", stage_include]
                  if build_system == "ndk-build" else [
                      base_include + "/stage/..", base_include,
                      base_include + "/sha256/..",
                  ]),
    }
    system_includes = [] if build_system == "ndk-build" else [
        sysroot + "/usr/include", sysroot + f"/usr/include/{arch}",
    ]
    valued = {"-MF", "-MT", "-o", "-c", "-I", "-isystem", "--sysroot"}
    compile_signatures: dict[str, list[str]] = {}
    for key, tokens in records.items():
        if tokens[0] != compiler or "g++" in tokens[0]:
            _fail(f"{key} compiler changed")
        if [_norm(value) for value in _option_values(tokens, "-c")] \
                != [wanted_sources[key]]:
            _fail(f"{key} source inputs changed")
        actual_policy = [
            value for value in tokens
            if value.startswith(("-std", "--std", "-W", "-w"))
            and not value.startswith("-Wa,")
        ]
        if actual_policy != policy:
            _fail(f"{key} flags changed: {actual_policy}")
        if [_norm(value) for value in _option_values(tokens, "--sysroot")] \
                != [sysroot]:
            _fail(f"{key} sysroot changed")
        includes = [
            str(value).replace("\\", "/")
            for value in _option_values(tokens, "-I")
        ]
        systems = [_norm(value) for value in _option_values(tokens, "-isystem")]
        if includes != raw_includes[key] or systems != system_includes:
            _fail(f"{key} includes changed: {includes}/{systems}")
        repeats = 2 if build_system == "cmake" else 1
        if [value for value in tokens if value.startswith("-m")] \
                != lane[4] * repeats:
            _fail(f"{key} ABI flags changed")
        signature = _signature(tokens, valued)
        wanted_signature = _compile_signature(
            build_system, abi, key, lane, len(raw_includes[key]),
            len(system_includes))
        if signature != wanted_signature:
            _fail(f"{key} compile flags/order changed: {signature}")
        compile_signatures[key] = signature

    links = [
        tokens for tokens in commands if "-c" not in tokens
        and any(value.endswith("narfs_stage_link_probe") for value in tokens)
        and any(value.endswith("libnarfs_stage.a") for value in tokens)
    ]
    if len(links) != 1:
        _fail("missing or duplicate staging link evidence")
    link = links[0]
    if link[0] != compiler or "g++" in link[0] \
            or [_norm(value) for value in _option_values(
                link, "--sysroot")] != [sysroot] \
            or any(value.startswith("@") for value in link):
        _fail("staging probe linker/sysroot/response changed")
    link_base = build_root if build_system == "ndk-build" \
        else build_root / f"cmake-{abi}/stage"
    paths = [_norm(value, link_base) for value in link
             if value.endswith((".o", ".a", ".so", ".c"))]
    lane_root = build_root / f"ndk-{abi}/obj/local/{abi}" \
        if build_system == "ndk-build" else build_root / f"cmake-{abi}"
    archives = [_norm(lane_root / value) for value in (
        ["libnarfs_stage.a", "libnarfs_core.a", "libnarfs_sha256.a"]
        if build_system == "ndk-build" else
        [f"static/{abi}/libnarfs_stage.a",
         f"static/{abi}/libnarfs_core.a",
         f"static/{abi}/libnarfs_sha256.a"]
    )]
    objects = [
        value for value in paths if value.endswith(
            ("/narfs_stage_link_probe.o", "/narfs_stage_link_probe.c.o"))
    ]
    if len(objects) != 1 or paths != [objects[0], *archives] \
            or not objects[0].startswith(_norm(lane_root) + "/"):
        _fail(f"staging probe link inputs changed: {paths}")
    libraries = ["-lgcc", "-lc", "-lm"] \
        if build_system == "ndk-build" else ["-lm"]
    relevant = [
        _norm(value, link_base) if value.endswith((".o", ".a")) else value
        for value in link
        if value.endswith((".o", ".a")) or value.startswith("-l")
    ]
    if relevant != [objects[0], *archives, *libraries]:
        _fail(f"staging probe link order/libraries changed: {relevant}")
    output = _option_values(link, "-o")
    wanted_output = _norm(lane_root / (
        "narfs_stage_link_probe" if build_system == "ndk-build"
        else "stage/narfs_stage_link_probe"))
    if len(output) != 1 or _norm(output[0], link_base) != wanted_output:
        _fail("staging probe output changed")
    block = [
        "-Wl,--build-id", "-Wl,--warn-shared-textrel",
        "-Wl,--fatal-warnings", "-Wl,--no-undefined",
        "-Wl,-z,noexecstack", "-Wl,-z,relro", "-Wl,-z,now",
        "-Wl,--gc-sections", "-Wl,-z,nocopyreloc",
    ]
    wanted_wl = (
        ["-Wl,--gc-sections", "-Wl,-z,nocopyreloc",
         "-Wl,--no-undefined", "-Wl,--build-id", "-Wl,--no-undefined",
         "-Wl,-z,noexecstack", "-Wl,-z,relro", "-Wl,-z,now",
         "-Wl,--warn-shared-textrel", "-Wl,--fatal-warnings"]
        if build_system == "ndk-build" else block * 2 + ["-Wl,--no-undefined"]
    )
    wl = [value for value in link
          if value.startswith("-Wl,") and "-rpath-link=" not in value]
    if wl != wanted_wl:
        _fail(f"staging probe linker flags changed: {wl}")
    wanted_rpaths = [] if build_system == "cmake" else [
        f"-Wl,-rpath-link={sysroot}/usr/lib",
        f"-Wl,-rpath-link={_norm(lane_root)}",
    ]
    if [value for value in link
            if value.startswith("-Wl,-rpath-link=")] != wanted_rpaths:
        _fail("staging probe rpaths changed")
    link_signature = _signature(
        link, {"--sysroot", "-o"}, mask_inputs=True)
    wanted_link_signature = _link_signature(
        build_system, abi, lane, wanted_rpaths, wanted_wl)
    if link_signature != wanted_link_signature:
        _fail(f"staging link flags/order changed: {link_signature}")
    return {
        "compileOrder": list(wanted_sources),
        "sources": [
            posixpath.relpath(value, build_root.as_posix())
            for value in wanted_sources.values()
        ],
        "flags": EXPECTED["flags"],
        "include": posixpath.relpath(base_include, build_root.as_posix()),
        "sysroot": posixpath.relpath(sysroot, ndk),
        "compiler": posixpath.basename(compiler),
        "linkOrder": ["probe", "stage", "core", "sha256"],
        "_compileFlags": compile_signatures,
        "_linkFlags": link_signature,
    }


def inspect_artifacts(
        archive: Path, probe: Path, readelf: Path, abi: str, api: str,
        build_system: str
) -> dict[str, object]:
    lane = _lane(abi, api)
    if archive.name != "libnarfs_stage.a" \
            or archive.read_bytes()[:8] != b"!<arch>\n":
        _fail("invalid staging archive")
    if probe.name != "narfs_stage_link_probe" \
            or probe.read_bytes()[:4] != b"\x7fELF":
        _fail("invalid staging probe")
    for artifact, elf_type in ((archive, "REL"), (probe, lane[3])):
        header = _readelf(readelf, "--file-header", artifact=artifact)
        if _headers(header, "Class") != {lane[1]} \
                or _headers(header, "Machine") != {lane[2]} \
                or _headers(header, "Type") != {elf_type}:
            _fail(f"{artifact} ELF identity changed")
    symbols = _readelf(readelf, "--symbols", "--wide", artifact=archive)
    members = re.findall(r"^File: .+\(([^)]+)\)$", symbols, re.M)
    wanted = "narfs_stage.o" if build_system == "ndk-build" \
        else "narfs_stage.c.o"
    if members != [wanted]:
        _fail(f"archive members changed: {members}")
    defined, imports = _symbols(symbols)
    expected_defined = sorted(
        ("FUNC", "GLOBAL", "DEFAULT", value) for value in EXPORTS)
    if defined != expected_defined:
        _fail(f"global definitions changed: {defined}")
    actual_toolchain = sorted(set(imports) & TOOLCHAIN_IMPORTS)
    if abi == "armeabi":
        required = {
            "__aeabi_unwind_cpp_pr0", "__aeabi_unwind_cpp_pr1",
            "__stack_chk_fail", "__stack_chk_guard",
        }
        if set(actual_toolchain) not in (required, required | {"_GLOBAL_OFFSET_TABLE_"}):
            _fail(f"toolchain imports changed: {actual_toolchain}")
    elif actual_toolchain != _toolchain_imports(abi, build_system):
        _fail(f"toolchain imports changed: {actual_toolchain}")
    normalized_imports = sorted(set(imports) - TOOLCHAIN_IMPORTS)
    wanted_imports = _imports(abi, build_system)
    if normalized_imports != wanted_imports:
        _fail(f"imports changed: {normalized_imports}")
    probe_defined, probe_imports = _symbols(
        _readelf(readelf, "--symbols", "--wide", artifact=probe))
    if sorted(row for row in probe_defined if row[3] in EXPORTS) \
            != expected_defined \
            or any(value in EXPORTS for value in probe_imports):
        _fail("probe did not consume exact staging API")
    return {
        "abi": abi, "api": api,
        "architecture": "ARMv5TE Thumb-1" if abi == "armeabi" else "AArch64",
        "exports": list(EXPORTS), "imports": list(IMPORTS),
        "probeElfType": lane[3], "_members": members,
        "_actualImports": wanted_imports,
        "_toolchainImports": actual_toolchain,
    }


def compare_contracts(
        reference: dict[str, object], candidate: dict[str, object]
) -> dict[str, str]:
    if not isinstance(reference, dict) or not isinstance(candidate, dict) \
            or reference.get("contract") != candidate.get("contract"):
        _fail("staging build contract differs")
    return {"status": "equivalent"}


def _write(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    inspect = sub.add_parser("inspect")
    for name in ("project-root", "archive", "probe", "readelf",
                 "build-evidence", "output"):
        inspect.add_argument(f"--{name}", type=Path, required=True)
    inspect.add_argument("--abi", required=True)
    inspect.add_argument("--api", required=True)
    inspect.add_argument(
        "--build-system", choices=("ndk-build", "cmake"), required=True)
    compare = sub.add_parser("compare")
    compare.add_argument("reference", type=Path)
    compare.add_argument("candidate", type=Path)
    compare.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    if arguments.command == "inspect":
        declarations = inspect_declarations(arguments.project_root)
        build = inspect_build_evidence(
            arguments.build_evidence, arguments.build_system,
            arguments.abi, arguments.api)
        artifact = inspect_artifacts(
            arguments.archive, arguments.probe, arguments.readelf,
            arguments.abi, arguments.api, arguments.build_system)
        provenance = {
            "buildSystem": arguments.build_system,
            "compileFlags": build.pop("_compileFlags"),
            "linkFlags": build.pop("_linkFlags"),
            "archiveSha256": hashlib.sha256(
                arguments.archive.read_bytes()).hexdigest(),
            "archiveMembers": artifact.pop("_members"),
            "actualImports": artifact.pop("_actualImports"),
            "toolchainImports": artifact.pop("_toolchainImports"),
        }
        contract = {
            "declaration": declarations[
                "ndkBuild" if arguments.build_system == "ndk-build"
                else "cmake"],
            **artifact, "build": build,
        }
        _write(arguments.output, {
            "contract": contract, "provenance": provenance})
    else:
        reference = json.loads(arguments.reference.read_text())
        candidate = json.loads(arguments.candidate.read_text())
        _write(arguments.output, compare_contracts(reference, candidate))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
