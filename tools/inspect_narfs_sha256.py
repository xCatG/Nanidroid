#!/usr/bin/env python3
"""Inspect the guarded narfs SHA-256 static archive and link probe."""

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


EXPECTED = {
    "kind": "static",
    "module": "narfs_sha256",
    "source": "jni/narfs/narfs_sha256.c",
    "probeSource": "test/native/narfs_sha256_link_probe.c",
    "include": "jni/narfs",
    "flags": ["-std=c99", "-Wall", "-Wextra", "-Werror"],
}
EXPORTS = ["narfs_sha256_final", "narfs_sha256_init", "narfs_sha256_update"]
IMPORTS = ["memcpy", "memset"]
TOOLCHAIN_IMPORTS = {
    "_GLOBAL_OFFSET_TABLE_", "__aeabi_unwind_cpp_pr0",
    "__aeabi_unwind_cpp_pr1", "__aeabi_llsr",
    "__stack_chk_fail", "__stack_chk_guard",
}
NEEDED = ["libc.so", "libdl.so", "libm.so", "libstdc++.so"]


class Sha256ContractError(ValueError):
    pass


def _fail(message: str) -> None:
    raise Sha256ContractError(message)


def _compact(path: Path) -> str:
    text = re.sub(r"(?m)#.*$", "", path.read_text(encoding="utf-8"))
    return " ".join(text.split())


def inspect_declarations(root: Path) -> dict[str, object]:
    parent_make = _compact(root / "jni/narfs/Android.mk")
    parent_cmake = _compact(root / "jni/narfs/CMakeLists.txt")
    make = _compact(root / "jni/narfs/sha256/module.mk")
    cmake = _compact(root / "jni/narfs/sha256/CMakeLists.txt")
    if (root / "jni/narfs/sha256/Android.mk").exists():
        _fail("SHA-256 module is independently discoverable")
    required = (
        ("Android parent", parent_make, (
            "ifeq ($(NANIDROID_NARFS_SHA256_CANDIDATE),1) "
            "include $(LOCAL_PATH)/sha256/module.mk endif",)),
        ("CMake parent", parent_cmake, (
            'option(NANIDROID_BUILD_NARFS_SHA256_CANDIDATE "Build SHA-256 candidate" OFF)',
            "if(NANIDROID_BUILD_NARFS_SHA256_CANDIDATE) add_subdirectory(sha256) endif()")),
        ("Android module", make, (
            "LOCAL_MODULE := narfs_sha256 ",
            "LOCAL_SRC_FILES := ../narfs_sha256.c ",
            "LOCAL_MODULE := narfs_sha256_link_probe ",
            "LOCAL_STATIC_LIBRARIES := narfs_sha256 ",
            "LOCAL_LDFLAGS := -Wl,--no-undefined ")),
        ("CMake module", cmake, (
            "add_library(narfs_sha256 STATIC ../narfs_sha256.c)",
            "add_executable( narfs_sha256_link_probe ",
            "target_link_libraries(narfs_sha256_link_probe narfs_sha256)",
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
        _fail("SHA-256 module count changed")
    return {"ndkBuild": dict(EXPECTED), "cmake": dict(EXPECTED)}


def _lane(abi: str, api: str) -> tuple[str, str, str, str, list[str]]:
    value = {
        "armeabi": ("android-9", "ELF32", "ARM", "EXEC",
                    ["-march=armv5te", "-mtune=xscale",
                     "-msoft-float", "-mthumb"]),
        "arm64-v8a": ("android-21", "ELF64", "AArch64", "DYN", []),
    }.get(abi)
    if value is None or value[0] != api:
        _fail(f"unsupported ABI/API: {abi}/{api}")
    return value


def _commands(evidence: Path, build_system: str) -> list[str]:
    if build_system == "ndk-build":
        return evidence.read_text(encoding="utf-8").splitlines()
    try:
        compiled = json.loads((evidence / "compile_commands.json").read_text())
        commands = [item["command"] for item in compiled]
    except (KeyError, TypeError, json.JSONDecodeError) as error:
        raise Sha256ContractError("invalid CMake compile evidence") from error
    commands += [
        line for path in evidence.rglob("link.txt")
        for line in path.read_text(encoding="utf-8").splitlines()
    ]
    return commands


def _norm(value: object, base: Path | None = None) -> str:
    text = str(value).replace("\\", "/")
    if base is not None and not (
            posixpath.isabs(text) or re.match(r"^[A-Za-z]:/", text)):
        text = posixpath.join(base.as_posix(), text)
    return posixpath.normpath(text)


def _option_values(tokens: list[str], option: str) -> list[str]:
    values = []
    for index, value in enumerate(tokens):
        if value == option:
            if index + 1 == len(tokens):
                _fail(f"{option} is missing its value")
            values.append(tokens[index + 1])
        elif value.startswith(option + "="):
            values.append(value.split("=", 1)[1])
        elif option in ("-I", "-isystem") and value.startswith(option) \
                and value != option:
            values.append(value[len(option):])
    return values


def inspect_build_evidence(
        evidence: Path, build_system: str, abi: str, api: str
) -> dict[str, object]:
    lane = _lane(abi, api)
    commands = [shlex.split(value) for value in _commands(evidence, build_system)]
    build_root = evidence.parent.parent if build_system == "ndk-build" \
        else evidence.parent
    build_root = Path(_norm(build_root.resolve()))
    wanted_sources = {
        "source": _norm(build_root / EXPECTED["source"]),
        "probe": _norm(build_root / EXPECTED["probeSource"]),
    }
    records: dict[str, list[str]] = {}
    for tokens in commands:
        if "-c" not in tokens:
            continue
        source = _norm(tokens[tokens.index("-c") + 1])
        keys = [key for key, value in wanted_sources.items()
                if source == value]
        if not keys:
            continue
        key = keys[0]
        if key in records:
            _fail(f"duplicate {key} compile evidence")
        records[key] = tokens
    if set(records) != {"source", "probe"}:
        _fail("missing SHA-256 compile evidence")
    ndk = "/opt/android-ndk-r14b"
    arch = "arm-linux-androideabi" if abi == "armeabi" else "aarch64-linux-android"
    compiler = f"{ndk}/toolchains/{arch}-4.9/prebuilt/linux-x86_64/bin/{arch}-gcc"
    wanted_sysroot = f"{ndk}/platforms/{lane[0]}/arch-" + (
        "arm" if abi == "armeabi" else "arm64")
    policy = (EXPECTED["flags"] + ["-Wformat", "-Werror=format-security"]
              if build_system == "ndk-build" else
              ["-Wformat", "-Werror=format-security"] * 2 + EXPECTED["flags"])
    local_includes = [
        _norm(build_root / "jni/narfs"),
        _norm(build_root / "jni/narfs/sha256"),
    ][:2 if build_system == "ndk-build" else 1]
    system_includes = [] if build_system == "ndk-build" else [
        wanted_sysroot + "/usr/include",
        wanted_sysroot + f"/usr/include/{arch}",
    ]
    allowed = {
        "-MMD", "-MP", "-fpic", "-fPIC", "-fPIE", "-fpie",
        "-ffunction-sections", "-funwind-tables", "-fstack-protector-strong",
        "-no-canonical-prefixes", "-g", "-Os", "-O2", "-DNDEBUG", "-DANDROID",
        "-Wa,--noexecstack", "-MD", *policy, *lane[4],
    }
    valued = {"-MF", "-MT", "-o", "-c", "-I", "-isystem", "--sysroot"}
    for key, tokens in records.items():
        if tokens[0] != compiler:
            _fail(f"{key} compiler changed")
        actual_policy = [value for value in tokens
                         if value.startswith(("-std", "--std", "-W", "-w"))
                         and not value.startswith("-Wa,")]
        if actual_policy != policy:
            _fail(f"{key} flags changed: {actual_policy}")
        if [_norm(value) for value in _option_values(tokens, "--sysroot")] \
                != [wanted_sysroot]:
            _fail(f"{key} sysroot changed")
        includes = [_norm(value) for value in _option_values(tokens, "-I")]
        systems = [_norm(value) for value in _option_values(tokens, "-isystem")]
        if includes != local_includes or systems != system_includes:
            _fail(f"{key} includes changed: {includes}/{systems}")
        abi_flags = [value for value in tokens if value.startswith("-m")]
        repeats = 2 if build_system == "cmake" else 1
        if abi_flags != lane[4] * repeats:
            _fail(f"{key} ABI flags changed")
        skip = False
        for index, value in enumerate(tokens[1:], 1):
            if skip:
                skip = False
            elif value in valued:
                skip = True
            elif value.startswith(("-I", "-isystem", "--sysroot=")):
                continue
            elif value not in allowed:
                _fail(f"{key} unexpected compile input/flag: {value}")
    links = [
        tokens for tokens in commands if "-c" not in tokens
        and any(value.endswith("narfs_sha256_link_probe")
                for value in tokens)
        and any(value.endswith("libnarfs_sha256.a") for value in tokens)
    ]
    if len(links) != 1:
        _fail("missing or duplicate SHA-256 link evidence")
    link = links[0]
    if link[0] != compiler or [_norm(value) for value in
                               _option_values(link, "--sysroot")] \
            != [wanted_sysroot] or any(value.startswith("@") for value in link):
        _fail("SHA-256 probe linker/sysroot/response changed")
    link_base = build_root if build_system == "ndk-build" \
        else build_root / f"cmake-{abi}/sha256"
    paths = [_norm(value, link_base) for value in link
             if value.endswith((".o", ".a", ".so", ".c"))]
    lane_root = build_root / f"ndk-{abi}/obj/local/{abi}" \
        if build_system == "ndk-build" else build_root / f"cmake-{abi}"
    archive = _norm(lane_root / (
        "libnarfs_sha256.a" if build_system == "ndk-build"
        else f"static/{abi}/libnarfs_sha256.a"))
    objects = [value for value in paths if value.endswith(
        ("/narfs_sha256_link_probe.o", "/narfs_sha256_link_probe.c.o"))]
    if len(objects) != 1 or paths != [objects[0], archive] \
            or not objects[0].startswith(_norm(lane_root) + "/"):
        _fail(f"SHA-256 probe link inputs changed: {paths}")
    libraries = [value for value in link if value.startswith("-l")]
    wanted_libraries = ["-lgcc", "-lc", "-lm"] \
        if build_system == "ndk-build" else ["-lm"]
    relevant = [(_norm(value, link_base) if value.endswith((".o", ".a"))
                 else value) for value in link
                if value.endswith((".o", ".a")) or value.startswith("-l")]
    if relevant != [objects[0], archive, *wanted_libraries] \
            or libraries != wanted_libraries:
        _fail(f"SHA-256 probe link order/libraries changed: {relevant}")
    output = _option_values(link, "-o")
    wanted_output = _norm(lane_root / (
        "narfs_sha256_link_probe" if build_system == "ndk-build"
        else "sha256/narfs_sha256_link_probe"))
    if len(output) != 1 or _norm(output[0], link_base) != wanted_output:
        _fail("SHA-256 probe output changed")
    block = [
        "-Wl,--build-id", "-Wl,--warn-shared-textrel", "-Wl,--fatal-warnings",
        "-Wl,--no-undefined", "-Wl,-z,noexecstack", "-Wl,-z,relro",
        "-Wl,-z,now", "-Wl,--gc-sections", "-Wl,-z,nocopyreloc",
    ]
    wanted_wl = (["-Wl,--gc-sections", "-Wl,-z,nocopyreloc",
                  "-Wl,--no-undefined", "-Wl,--build-id",
                  "-Wl,--no-undefined", "-Wl,-z,noexecstack",
                  "-Wl,-z,relro", "-Wl,-z,now",
                  "-Wl,--warn-shared-textrel", "-Wl,--fatal-warnings"]
                 if build_system == "ndk-build" else block * 2 +
                 ["-Wl,--no-undefined"])
    wl = [value for value in link
          if value.startswith("-Wl,") and "-rpath-link=" not in value]
    if wl != wanted_wl:
        _fail(f"SHA-256 probe linker flags changed: {wl}")
    rpaths = [value for value in link if value.startswith("-Wl,-rpath-link=")]
    wanted_rpaths = [] if build_system == "cmake" else [
        f"-Wl,-rpath-link={wanted_sysroot}/usr/lib",
        f"-Wl,-rpath-link={_norm(lane_root)}",
    ]
    if rpaths != wanted_rpaths:
        _fail("SHA-256 probe rpaths changed")
    link_allowed = allowed | set(wanted_wl + wanted_rpaths +
                                 wanted_libraries + ["-pie"])
    skip = False
    for value in link[1:]:
        if skip:
            skip = False
        elif value == "-o":
            skip = True
        elif value.startswith("--sysroot=") or value in paths \
                or _norm(value, link_base) in paths or value in link_allowed:
            continue
        else:
            _fail(f"unexpected SHA-256 link input/flag: {value}")
    return {
        "compileOrder": list(wanted_sources),
        "sources": [posixpath.relpath(value, build_root.as_posix())
                    for value in wanted_sources.values()],
        "flags": [value for value in actual_policy if value in EXPECTED["flags"]],
        "include": posixpath.relpath(local_includes[0], build_root.as_posix()),
        "sysroot": posixpath.relpath(wanted_sysroot, ndk),
        "compiler": posixpath.basename(records["source"][0]),
        "linkOrder": ["probe", "archive"],
    }


def inspect_artifacts(
        archive: Path, probe: Path, readelf: Path, abi: str, api: str,
        build_system: str
) -> dict[str, object]:
    lane = _lane(abi, api)
    if archive.name != "libnarfs_sha256.a" \
            or archive.read_bytes()[:8] != b"!<arch>\n":
        _fail("invalid SHA-256 archive")
    if probe.name != "narfs_sha256_link_probe" \
            or probe.read_bytes()[:4] != b"\x7fELF":
        _fail("invalid SHA-256 probe")
    for artifact, elf_type in ((archive, "REL"), (probe, lane[3])):
        header = _readelf(readelf, "--file-header", artifact=artifact)
        if _headers(header, "Class") != {lane[1]} \
                or _headers(header, "Machine") != {lane[2]} \
                or _headers(header, "Type") != {elf_type}:
            _fail(f"{artifact} ELF identity changed")
    symbols = _readelf(readelf, "--symbols", "--wide", artifact=archive)
    members = re.findall(r"^File: .+\(([^)]+)\)$", symbols, re.M)
    wanted = "narfs_sha256.o" if build_system == "ndk-build" \
        else "narfs_sha256.c.o"
    if members != [wanted]:
        _fail(f"archive members changed: {members}")
    defined, imports = _symbols(symbols)
    expected_defined = sorted(
        ("FUNC", "GLOBAL", "DEFAULT", value) for value in EXPORTS)
    if defined != expected_defined:
        _fail(f"global definitions changed: {defined}")
    normalized_imports = sorted(set(imports) - TOOLCHAIN_IMPORTS)
    if normalized_imports != IMPORTS:
        _fail(f"imports changed: {normalized_imports}")
    probe_defined, probe_imports = _symbols(
        _readelf(readelf, "--symbols", "--wide", artifact=probe))
    if sorted(row for row in probe_defined if row[3].startswith("narfs_sha256_")) \
            != expected_defined \
            or any(value.startswith("narfs_sha256_") for value in probe_imports):
        _fail("probe did not consume exact SHA-256 API")
    return {
        "abi": abi, "api": api,
        "architecture": "ARMv5TE Thumb-1" if abi == "armeabi" else "AArch64",
        "exports": list(EXPORTS), "imports": list(IMPORTS),
        "probeElfType": lane[3], "_members": members,
        "_toolchainImports": sorted(set(imports) & TOOLCHAIN_IMPORTS),
    }


def compare_contracts(
        reference: dict[str, object], candidate: dict[str, object]
) -> dict[str, str]:
    if not isinstance(reference, dict) or not isinstance(candidate, dict) \
            or reference.get("contract") != candidate.get("contract"):
        _fail("SHA-256 build contract differs")
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
            "archiveSha256": hashlib.sha256(
                arguments.archive.read_bytes()).hexdigest(),
            "archiveMembers": artifact.pop("_members"),
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
