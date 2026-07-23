#!/usr/bin/env python3
"""Inspect the private build-only narfs JNI candidate."""

import argparse
import hashlib
import json
import re
import shlex
import subprocess
import sys
from pathlib import Path

EXPORT = ("Java_com_cattailsw_nanidroid_install_"
          "NarFilesystemInspector_nativeInspect")
SOURCES = ["jni/narfs/narfs_jni.c", "jni/narfs/narfs_utf.c"]
FLAGS = ["-std=c99", "-Wall", "-Wextra", "-Werror", "-fvisibility=hidden"]


class CandidateContractError(ValueError):
    pass


def _fail(message):
    raise CandidateContractError(message)


def _readelf(tool, option, artifact):
    try:
        return subprocess.run(
            [str(tool), option, "--wide", str(artifact)],
            check=True, capture_output=True, text=True,
        ).stdout
    except subprocess.CalledProcessError as error:
        raise CandidateContractError(f"readelf failed: {option}") from error


def _field(text, name):
    return set(re.findall(rf"^\s*{name}:\s*(.+?)(?:\s+\(|$)", text, re.M))


def _symbols(text, binding):
    found = []
    for line in text.splitlines():
        fields = line.split()
        if (len(fields) >= 8 and fields[3] == "FUNC" and fields[4] == binding
                and fields[6] != "UND"):
            found.append(fields[7].split("@", 1)[0])
    return sorted(found)


def _definitions(text):
    found = []
    for line in text.splitlines():
        fields = line.split()
        if len(fields) >= 8 and fields[4] in ("GLOBAL", "WEAK") and fields[6] != "UND":
            found.append((fields[3], fields[4], fields[5],
                          fields[7].split("@", 1)[0]))
    return sorted(found)


def _evidence(path, build_system, abi):
    if build_system == "cmake":
        rows = json.loads((path / "compile_commands.json").read_text())
        commands = [row["command"] for row in rows]
        link_path = path / "link.txt"
        if not link_path.is_file():
            link_path = path / "CMakeFiles/narfs.dir/link.txt"
        link = link_path.read_text()
    else:
        commands = path.read_text().splitlines()
        link = next((line for line in commands if "libnarfs.so" in line
                     and (" -shared " in line or " -o " in line)), "")
    compiled = []
    lane = {
        "armeabi": ("arm-linux-androideabi-gcc",
                    ["-march=armv5te", "-mtune=xscale", "-msoft-float", "-mthumb"],
                    "/platforms/android-9/arch-arm", "arm-linux-androideabi"),
        "arm64-v8a": ("aarch64-linux-android-gcc", [],
                      "/platforms/android-21/arch-arm64", "aarch64-linux-android"),
    }[abi]
    for source in SOURCES:
        matches = [shlex.split(line) for line in commands
                   if line.replace("\\", "/").endswith(source)
                   or f"/{source} " in line.replace("\\", "/")]
        if len(matches) != 1:
            _fail(f"compile evidence changed: {source}")
        tokens = matches[0]
        policy = [token for token in tokens if token in ("-w", "-ansi", "--ansi")
                  or token.startswith("-fvisibility=")
                  or token.lstrip("-").startswith(("std=", "pedantic"))
                  or token.startswith("-W") and not token.startswith("-Wa,")]
        toolchain = ["-Wformat", "-Werror=format-security"]
        expected_policy = toolchain * 2 + FLAGS if build_system == "cmake" else FLAGS + toolchain
        if policy != expected_policy or any(token.startswith(("-Wno", "@")) for token in tokens):
            _fail(f"compile policy changed: {source}")
        if (Path(tokens[0]).name != lane[0]
                or not tokens[0].startswith("/opt/android-ndk-r14b/toolchains/")):
            _fail(f"compiler changed: {source}")
        abi_flags = [token for token in tokens if token.startswith("-m")]
        if abi_flags != lane[1] * (2 if build_system == "cmake" else 1):
            _fail(f"ABI flags changed: {source}")
        includes = []
        for index, token in enumerate(tokens):
            if token in ("-I", "-isystem"):
                includes.append((token, tokens[index + 1]))
            elif token.startswith("-I"):
                includes.append(("-I", token[2:]))
        normalized = [(kind, "jni/narfs" if value.endswith("/jni/narfs")
                       else value.removeprefix("/opt/android-ndk-r14b/"))
                      for kind, value in includes]
        expected_includes = [("-I", "jni/narfs")] * (
            1 if build_system == "cmake" else 2)
        if build_system == "cmake":
            base = lane[2][1:] + "/usr/include"
            expected_includes += [("-isystem", base), ("-isystem", base + "/" + lane[3])]
        if normalized != expected_includes:
            _fail(f"include evidence changed: {source}")
        sysroots = [token.split("=", 1)[1] if "=" in token else tokens[index + 1]
                    for index, token in enumerate(tokens) if token.startswith("--sysroot")]
        if len(sysroots) != 1 or not sysroots[0].endswith(lane[2]):
            _fail(f"sysroot changed: {source}")
        compiled.append(source)
    link_tokens = shlex.split(link)
    if Path(link_tokens[0]).name != lane[0] or not all(
            any(wanted in token for token in link_tokens) for wanted in (
                "--as-needed", "--no-undefined", "--version-script",
                "libnarfs_core.a")):
        _fail("static-core link evidence changed")
    return {"sources": compiled, "flags": FLAGS, "compiler": lane[0],
            "sysroot": lane[2][1:], "include": "jni/narfs"}


def inspect_candidate(dso, readelf, evidence, *, abi, api, build_system):
    lane = {
        ("armeabi", "android-9"): ("ELF32", "ARM", "ARMv5TE Thumb-1"),
        ("arm64-v8a", "android-21"): ("ELF64", "AArch64", "AArch64"),
    }.get((abi, api))
    if lane is None or dso.name != "libnarfs.so" or dso.read_bytes()[:4] != b"\x7fELF":
        _fail("invalid candidate lane or artifact")
    header = _readelf(readelf, "--file-header", dso)
    if _field(header, "Class") != {lane[0]} or _field(header, "Machine") != {lane[1]}:
        _fail("candidate ELF identity changed")
    if _field(header, "Type") != {"DYN"}:
        _fail("candidate ELF type changed")
    if abi == "armeabi":
        attributes = _readelf(readelf, "--arch-specific", dso)
        if not all(token in attributes for token in ("v5TE", "Thumb-1")):
            _fail("candidate ARM attributes changed")
    dynamic = _readelf(readelf, "--dynamic", dso)
    soname = re.findall(r"\(SONAME\).*?\[(.+?)\]", dynamic)
    needed = sorted(re.findall(r"\(NEEDED\).*?\[(.+?)\]", dynamic))
    dynamic_symbols = _readelf(readelf, "--dyn-syms", dso)
    exports = _symbols(dynamic_symbols, "GLOBAL")
    defined = _definitions(dynamic_symbols)
    locals_ = _symbols(_readelf(readelf, "--symbols", dso), "LOCAL")
    expected_defined = [("FUNC", "GLOBAL", "DEFAULT", EXPORT)]
    if abi == "armeabi":
        expected_defined += [
            ("NOTYPE", "GLOBAL", "DEFAULT", "__bss_start"),
            ("NOTYPE", "GLOBAL", "DEFAULT", "_edata"),
            ("NOTYPE", "GLOBAL", "DEFAULT", "_end"),
        ]
    expected_defined.sort()
    if (soname != ["libnarfs.so"] or needed != ["libc.so"]
            or exports != [EXPORT] or defined != expected_defined):
        _fail("candidate dynamic contract changed")
    if [name for name in locals_ if name.startswith("narfs_")] != [
            "narfs_default_options", "narfs_inspect",
            "narfs_utf16_to_utf8", "narfs_utf8_to_utf16"]:
        _fail("candidate internals were not extracted and hidden exactly")
    build = _evidence(evidence, build_system, abi)
    return {
        "contract": {
            "abi": abi, "api": api, "architecture": lane[2],
            "soname": soname[0], "jniExports": exports, "needed": needed,
            "coreDefinitions": ["narfs_default_options", "narfs_inspect"], **build,
        },
        "provenance": {
            "buildSystem": build_system,
            "sha256": hashlib.sha256(dso.read_bytes()).hexdigest(),
        },
    }


def compare_contracts(reference, candidate):
    if reference.get("contract") != candidate.get("contract"):
        _fail("candidate build contracts differ")
    return {"status": "equivalent"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    inspect = commands.add_parser("inspect")
    for name in ("dso", "readelf", "evidence", "output"):
        inspect.add_argument(f"--{name}", type=Path, required=True)
    inspect.add_argument("--abi", required=True)
    inspect.add_argument("--api", required=True)
    inspect.add_argument(
        "--build-system", choices=("ndk-build", "cmake"), required=True)
    compare = commands.add_parser("compare")
    compare.add_argument("reference", type=Path)
    compare.add_argument("candidate", type=Path)
    compare.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "inspect":
            report = inspect_candidate(
                args.dso, args.readelf, args.evidence, abi=args.abi,
                api=args.api, build_system=args.build_system)
        else:
            report = compare_contracts(
                json.loads(args.reference.read_text()),
                json.loads(args.candidate.read_text()))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"narfs JNI validation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
