from pathlib import Path
import re
import unittest


class SatoriIncludeCasingTest(unittest.TestCase):
    def test_local_header_includes_match_the_tracked_file_case(self):
        source_dir = Path("jni/satori")
        local_includes = re.compile(r'^\s*#include\s+"([^"]+)"', re.MULTILINE)
        missing_headers = []

        for source_file in (
            source_dir / "satori.cpp",
            source_dir / "satori.h",
            source_dir / "SakuraDLLHost.cpp",
        ):
            for header_name in local_includes.findall(source_file.read_text(encoding="latin-1")):
                header_path = source_dir / header_name
                tracked_names = {path.name for path in header_path.parent.iterdir()}
                if header_path.name not in tracked_names:
                    missing_headers.append(f"{source_file}:{header_name}")

        self.assertEqual([], missing_headers)
