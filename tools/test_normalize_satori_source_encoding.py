import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


TOOL = Path(__file__).with_name("normalize_satori_source_encoding.py")


def run(mode: str, source: Path) -> int:
    return subprocess.run(
        [sys.executable, str(TOOL), mode, str(source)],
        check=False,
        capture_output=True,
        text=True,
    ).returncode


class NormalizeSatoriSourceEncodingTest(unittest.TestCase):
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
