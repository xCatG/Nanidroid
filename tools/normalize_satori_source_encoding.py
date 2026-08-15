"""Normalize one CP932 C++ source file while preserving narrow literal bytes.

This initial version intentionally operates on explicitly named files only.  The
repository-wide scope and literal manifest are added by the conversion task;
keeping the core lexer path-oriented makes it safe to exercise on synthetic
files first.
"""

from __future__ import annotations

import argparse
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


def is_hex_digit(value: int) -> bool:
    return 48 <= value <= 57 or 65 <= value <= 70 or 97 <= value <= 102


def decode_cp932(data: bytes) -> bytes:
    return data.decode("cp932").encode("utf-8")


def rewrite_narrow_body(body: bytes, delimiter: int, *, raw: bool) -> bytes:
    """Return ASCII spelling whose runtime bytes equal a narrow literal body."""

    output = bytearray()
    last_was_hex_escape = False
    index = 0
    while index < len(body):
        value = body[index]
        if value >= 0x80:
            output.extend(f"\\x{value:02X}".encode("ascii"))
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


def check(path: Path) -> int:
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


def write(path: Path) -> int:
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
    return check(path)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--check", action="store_true")
    action.add_argument("--write", action="store_true")
    parser.add_argument("path", type=Path)
    arguments = parser.parse_args(argv)
    return check(arguments.path) if arguments.check else write(arguments.path)


if __name__ == "__main__":
    raise SystemExit(main())
