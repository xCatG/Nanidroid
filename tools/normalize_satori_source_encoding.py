"""Normalize one CP932 C++ source file while preserving narrow literal bytes.

This initial version intentionally operates on explicitly named files only.  The
repository-wide scope and literal manifest are added by the conversion task;
keeping the core lexer path-oriented makes it safe to exercise on synthetic
files first.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path


LITERAL_PREFIXES = (
    b"u8R\"",
    b"LR\"",
    b"uR\"",
    b"UR\"",
    b"R\"",
    b"u8\"",
    b"L\"",
    b"u\"",
    b"U\"",
    b"\"",
    b"L'",
    b"u'",
    b"U'",
    b"'",
)

SOURCE_SUFFIXES = frozenset({".c", ".cc", ".cpp", ".cxx", ".h", ".hh", ".hpp"})


def is_hex_digit(value: int) -> bool:
    return 48 <= value <= 57 or 65 <= value <= 70 or 97 <= value <= 102


def decode_cp932(data: bytes) -> bytes:
    return data.decode("cp932").encode("utf-8")


def is_cp932_double_byte(first: int, second: int) -> bool:
    try:
        return len(bytes((first, second)).decode("cp932")) == 1
    except UnicodeDecodeError:
        return False


def rewrite_narrow_body(body: bytes, delimiter: int, *, raw: bool) -> bytes:
    """Return ASCII spelling whose runtime bytes equal a narrow literal body."""

    output = bytearray()
    last_was_hex_escape = False
    index = 0
    while index < len(body):
        value = body[index]
        if value >= 0x80:
            output.extend(f"\\x{value:02X}".encode("ascii"))
            if index + 1 < len(body) and is_cp932_double_byte(value, body[index + 1]):
                output.extend(f"\\x{body[index + 1]:02X}".encode("ascii"))
                index += 2
                last_was_hex_escape = True
                continue
            last_was_hex_escape = True
            index += 1
            continue
        if last_was_hex_escape and is_hex_digit(value):
            if delimiter == ord("'"):
                raise ValueError("cannot safely split a character literal after a hex escape")
            output.extend(b'" "')
        last_was_hex_escape = False
        if raw and value == ord("\\"):
            output.extend(b"\\\\")
        elif raw and value == ord('"'):
            output.extend(b'\\"')
        elif raw and value == ord("\r"):
            output.extend(b"\\r")
        elif raw and value == ord("\n"):
            output.extend(b"\\n")
        else:
            output.append(value)
        if value == ord("\\") and not raw and index + 1 < len(body):
            output.append(body[index + 1])
            index += 2
        else:
            index += 1
    return bytes(output)


def find_normal_literal_end(data: bytes, start: int, delimiter: int) -> int:
    index = start
    while index < len(data):
        if data[index] == ord("\\"):
            index += 2
            continue
        if data[index] == delimiter:
            return index
        index += 1
    raise ValueError("unterminated literal")


def find_raw_literal_end(data: bytes, body_start: int, delimiter: bytes) -> int:
    closing = b")" + delimiter + b'"'
    end = data.find(closing, body_start)
    if end < 0:
        raise ValueError("unterminated raw string literal")
    return end


def literal_at(data: bytes, index: int) -> tuple[bytes, int, bool, bool] | None:
    for prefix in LITERAL_PREFIXES:
        if not data.startswith(prefix, index):
            continue
        raw = prefix.endswith(b'R"')
        delimiter = prefix[-1:]
        narrow = prefix in (b'"', b"'", b"u8\"", b"R\"", b"u8R\"")
        return prefix, ord(delimiter), raw, narrow
    return None


def normalize_cp932_source(data: bytes) -> bytes:
    """Convert code/comments to UTF-8 and narrow literal bytes to ASCII escapes."""

    output = bytearray()
    index = 0
    ordinary_start = 0
    while index < len(data):
        if data.startswith(b"//", index):
            output.extend(decode_cp932(data[ordinary_start:index]))
            end = data.find(b"\n", index)
            end = len(data) if end < 0 else end + 1
            output.extend(decode_cp932(data[index:end]))
            index = end
            ordinary_start = index
            continue
        if data.startswith(b"/*", index):
            output.extend(decode_cp932(data[ordinary_start:index]))
            end = data.find(b"*/", index + 2)
            if end < 0:
                raise ValueError("unterminated block comment")
            end += 2
            output.extend(decode_cp932(data[index:end]))
            index = end
            ordinary_start = index
            continue
        literal = literal_at(data, index)
        if literal is None:
            index += 1
            continue

        prefix, delimiter, raw, narrow = literal
        output.extend(decode_cp932(data[ordinary_start:index]))
        prefix_without_raw = prefix.replace(b"R", b"") if raw else prefix
        body_start = index + len(prefix)
        if raw:
            delimiter_end = data.find(b"(", body_start)
            if delimiter_end < 0:
                raise ValueError("unterminated raw string delimiter")
            raw_delimiter = data[body_start:delimiter_end]
            body_start = delimiter_end + 1
            body_end = find_raw_literal_end(data, body_start, raw_delimiter)
            literal_end = body_end + len(raw_delimiter) + 2
        else:
            body_end = find_normal_literal_end(data, body_start, delimiter)
            literal_end = body_end + 1

        body = data[body_start:body_end]
        if narrow:
            output.extend(prefix_without_raw)
            output.extend(rewrite_narrow_body(body, delimiter, raw=raw))
            output.append(delimiter)
        else:
            output.extend(prefix_without_raw)
            output.extend(decode_cp932_source_fragment(body))
            output.append(delimiter)
        index = literal_end
        ordinary_start = index
    output.extend(decode_cp932(data[ordinary_start:]))
    return bytes(output)


def decode_cp932_source_fragment(data: bytes) -> bytes:
    """Decode a non-narrow literal without interpreting its existing escapes."""

    return decode_cp932(data)


def has_non_ascii_narrow_literal(data: bytes) -> bool:
    """Return whether valid UTF-8 source still embeds non-ASCII narrow bytes."""

    index = 0
    while index < len(data):
        if data.startswith(b"//", index):
            end = data.find(b"\n", index)
            index = len(data) if end < 0 else end + 1
            continue
        if data.startswith(b"/*", index):
            end = data.find(b"*/", index + 2)
            index = len(data) if end < 0 else end + 2
            continue
        literal = literal_at(data, index)
        if literal is None:
            index += 1
            continue
        prefix, delimiter, raw, narrow = literal
        body_start = index + len(prefix)
        if raw:
            delimiter_end = data.find(b"(", body_start)
            if delimiter_end < 0:
                return True
            raw_delimiter = data[body_start:delimiter_end]
            body_start = delimiter_end + 1
            try:
                body_end = find_raw_literal_end(data, body_start, raw_delimiter)
            except ValueError:
                return True
            literal_end = body_end + len(raw_delimiter) + 2
        else:
            try:
                body_end = find_normal_literal_end(data, body_start, delimiter)
            except ValueError:
                return True
            literal_end = body_end + 1
        if narrow and any(value >= 0x80 for value in data[body_start:body_end]):
            return True
        index = literal_end
    return False


def narrow_literal_payload_spans(data: bytes) -> list[tuple[int, int, int, bytes]]:
    """Extract narrow literal source spans and runtime bytes in source order."""

    payloads = []
    index = 0
    while index < len(data):
        if data.startswith(b"//", index):
            end = data.find(b"\n", index)
            index = len(data) if end < 0 else end + 1
            continue
        if data.startswith(b"/*", index):
            end = data.find(b"*/", index + 2)
            if end < 0:
                raise ValueError("unterminated block comment")
            index = end + 2
            continue
        literal = literal_at(data, index)
        if literal is None:
            index += 1
            continue
        prefix, delimiter, raw, narrow = literal
        body_start = index + len(prefix)
        if raw:
            delimiter_end = data.find(b"(", body_start)
            if delimiter_end < 0:
                raise ValueError("unterminated raw string delimiter")
            raw_delimiter = data[body_start:delimiter_end]
            body_start = delimiter_end + 1
            body_end = find_raw_literal_end(data, body_start, raw_delimiter)
            literal_end = body_end + len(raw_delimiter) + 2
        else:
            body_end = find_normal_literal_end(data, body_start, delimiter)
            literal_end = body_end + 1
        if narrow:
            body = data[body_start:body_end]
            payloads.append(
                (index, literal_end, delimiter, body if raw else decode_narrow_escapes(body)),
            )
        index = literal_end
    return payloads


def decode_narrow_escapes(body: bytes) -> bytes:
    """Interpret the C++ narrow-literal escapes needed for semantic comparison."""

    simple_escapes = {
        ord("a"): 7,
        ord("b"): 8,
        ord("f"): 12,
        ord("n"): 10,
        ord("r"): 13,
        ord("t"): 9,
        ord("v"): 11,
    }
    output = bytearray()
    index = 0
    while index < len(body):
        value = body[index]
        if value != ord("\\") or index + 1 == len(body):
            output.append(value)
            index += 1
            continue
        escaped = body[index + 1]
        if escaped == ord("x"):
            end = index + 2
            while end < len(body) and is_hex_digit(body[end]):
                end += 1
            if end == index + 2:
                raise ValueError("invalid hex escape in narrow literal")
            output.append(int(body[index + 2 : end], 16) & 0xFF)
            index = end
        elif 48 <= escaped <= 55:
            end = index + 2
            while end < min(index + 4, len(body)) and 48 <= body[end] <= 55:
                end += 1
            output.append(int(body[index + 1 : end], 8) & 0xFF)
            index = end
        elif escaped in simple_escapes:
            output.append(simple_escapes[escaped])
            index += 2
        else:
            output.append(escaped)
            index += 2
    return bytes(output)


def literal_fingerprint(data: bytes) -> dict[str, int | str]:
    digest = hashlib.sha256()
    groups: list[bytes] = []
    for start, end, delimiter, payload in narrow_literal_payload_spans(data):
        if (
            delimiter == ord('"')
            and groups
            and data[previous_end:start].strip(b" \t\r\n\v\f") == b""
            and previous_delimiter == ord('"')
        ):
            groups[-1] += payload
        else:
            groups.append(payload)
        previous_end = end
        previous_delimiter = delimiter
    for payload in groups:
        digest.update(len(payload).to_bytes(8, "big"))
        digest.update(payload)
    return {"literal_count": len(groups), "sha256": digest.hexdigest()}


def manifest_entries(path: Path, paths: list[Path]) -> dict[str, dict[str, int | str]]:
    root = path if path.is_dir() else path.parent
    return {
        candidate.relative_to(root).as_posix(): literal_fingerprint(candidate.read_bytes())
        for candidate in paths
    }


def write_manifest(manifest: Path, entries: dict[str, dict[str, int | str]]) -> None:
    manifest.write_text(
        json.dumps({"files": entries, "version": 1}, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def check_manifest(
    path: Path,
    paths: list[Path],
    manifest: Path,
) -> int:
    try:
        saved = json.loads(manifest.read_text(encoding="utf-8"))
        expected = saved["files"]
    except (OSError, json.JSONDecodeError, KeyError, TypeError) as error:
        print(f"{manifest}: invalid literal manifest: {error}", file=sys.stderr)
        return 1
    actual = manifest_entries(path, paths)
    if saved.get("version") != 1 or expected != actual:
        print(f"{manifest}: narrow literal semantic fingerprint mismatch", file=sys.stderr)
        return 1
    return 0


def source_paths(path: Path) -> list[Path]:
    if path.is_file():
        return [path]
    if path.is_dir():
        return sorted(
            candidate
            for candidate in path.rglob("*")
            if candidate.is_file() and candidate.suffix.lower() in SOURCE_SUFFIXES
        )
    raise ValueError(f"{path}: not a file or directory")


def check_one(path: Path) -> int:
    data = path.read_bytes()
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        print(f"{path}: not valid UTF-8", file=sys.stderr)
        return 1
    if has_non_ascii_narrow_literal(data):
        print(f"{path}: contains non-ASCII narrow literal bytes", file=sys.stderr)
        return 1
    return 0


def write_one(path: Path) -> int:
    data = path.read_bytes()
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        try:
            normalized = normalize_cp932_source(data)
        except (UnicodeDecodeError, ValueError) as error:
            print(f"{path}: {error}", file=sys.stderr)
            return 1
        path.write_bytes(normalized)
    return check_one(path)


def check(path: Path, manifest: Path | None = None) -> int:
    try:
        paths = source_paths(path)
    except ValueError as error:
        print(error, file=sys.stderr)
        return 1
    results = [check_one(candidate) for candidate in paths]
    if any(results):
        return 1
    return check_manifest(path, paths, manifest) if manifest is not None else 0


def write(path: Path, manifest: Path | None = None) -> int:
    try:
        paths = source_paths(path)
    except ValueError as error:
        print(error, file=sys.stderr)
        return 1
    try:
        entries = manifest_entries(path, paths) if manifest is not None else None
    except ValueError as error:
        print(f"{error}", file=sys.stderr)
        return 1
    results = [write_one(candidate) for candidate in paths]
    if any(results):
        return 1
    if manifest is not None:
        write_manifest(manifest, entries)
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--check", action="store_true")
    action.add_argument("--write", action="store_true")
    parser.add_argument("path", type=Path)
    parser.add_argument("--manifest", type=Path)
    arguments = parser.parse_args(argv)
    return (
        check(arguments.path, arguments.manifest)
        if arguments.check
        else write(arguments.path, arguments.manifest)
    )


if __name__ == "__main__":
    raise SystemExit(main())
