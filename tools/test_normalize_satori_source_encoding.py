import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


TOOL = Path(__file__).with_name("normalize_satori_source_encoding.py")


def run(mode: str, source: Path, manifest: Path | None = None) -> int:
    command = [sys.executable, str(TOOL), mode, str(source)]
    if manifest is not None:
        command.extend(["--manifest", str(manifest)])
    return subprocess.run(
        command,
        check=False,
        capture_output=True,
        text=True,
    ).returncode


class NormalizeSatoriSourceEncodingTest(unittest.TestCase):
    def test_preserves_cp932_character_whose_trailing_byte_is_backslash(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            source = Path(temporary_directory) / "sample.cpp"
            source.write_bytes(b'const char* s = "\x95\\\x82\xA0";\r\n')

            self.assertEqual(0, run("--write", source))
            self.assertEqual(
                b'const char* s = "\\x95\\x5C\\x82\\xA0";\r\n',
                source.read_bytes(),
            )

    def test_manifest_detects_converted_literal_byte_drift(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            source = root / "sample.cpp"
            manifest = root / "literal-bytes.json"
            source.write_bytes(b'const char* s = "\x82\xA0A";\r\n')

            self.assertEqual(0, run("--write", source, manifest))
            self.assertEqual(0, run("--check", source, manifest))
            source.write_bytes(source.read_bytes().replace(b"\\xA0", b"\\xA1"))
            self.assertNotEqual(0, run("--check", source, manifest))

    def test_rewrites_only_cpp_sources_in_a_directory_scope(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            source = root / "nested" / "sample.cpp"
            source.parent.mkdir()
            source.write_bytes(b'const char* s = "\x82\xA0";\r\n')
            header = root / "sample.h"
            header.write_bytes(b'const char* h = "\x82\xA0";\r\n')
            ignored = root / "ignored.txt"
            ignored.write_bytes(b'\x82\xA0\r\n')

            self.assertEqual(0, run("--write", root))
            self.assertEqual(
                b'const char* s = "\\x82\\xA0";\r\n',
                source.read_bytes(),
            )
            self.assertEqual(
                b'const char* h = "\\x82\\xA0";\r\n',
                header.read_bytes(),
            )
            self.assertEqual(b'\x82\xA0\r\n', ignored.read_bytes())

    def test_preserves_cp932_literal_bytes_and_prevents_hex_run_on(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            source = Path(temporary_directory) / "sample.cpp"
            source.write_bytes(b'const char* s = "\x82\xA0A";\r\n')

            self.assertNotEqual(0, run("--check", source))
            self.assertEqual(0, run("--write", source))
            self.assertEqual(
                b'const char* s = "\\x82\\xA0" "A";\r\n',
                source.read_bytes(),
            )

    def test_decodes_comments_without_corrupting_escaped_string_characters(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            source = Path(temporary_directory) / "sample.cpp"
            source.write_bytes(
                b'// \x82\xA0\r\nconst char* s = "x\\"\x82\xA0A";\r\n',
            )

            self.assertEqual(0, run("--write", source))
            self.assertEqual(
                '// あ\r\nconst char* s = "x\\"\\x82\\xA0" "A";\r\n'.encode("utf-8"),
                source.read_bytes(),
            )

    def test_rewrites_raw_narrow_literal_to_avoid_hex_run_on(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            source = Path(temporary_directory) / "sample.cpp"
            source.write_bytes(b'const char* s = R"tag(\x82\xA0A)tag";\r\n')

            self.assertEqual(0, run("--write", source))
            self.assertEqual(
                b'const char* s = "\\x82\\xA0" "A";\r\n',
                source.read_bytes(),
            )
            self.assertEqual(0, run("--check", source))


if __name__ == "__main__":
    unittest.main()
