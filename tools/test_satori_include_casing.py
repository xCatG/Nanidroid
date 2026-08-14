from pathlib import Path
import re
import unittest


class SatoriIncludeCasingTest(unittest.TestCase):
    def test_local_header_includes_match_the_tracked_file_case(self):
        jni_dir = Path("jni")
        source_dir = jni_dir / "satori"
        local_includes = re.compile(r'^\s*#include\s+"([^"]+)"', re.MULTILINE)
        missing_headers = []
        visited = set()

        cmake_sources = re.findall(
            r"^\s*(satori/[^\s]+\.cpp)$",
            (jni_dir / "CMakeLists.txt").read_text(encoding="utf-8"),
            re.MULTILINE,
        )

        def check_includes(source_file):
            if source_file in visited:
                return
            visited.add(source_file)
            for header_name in local_includes.findall(source_file.read_text(encoding="latin-1")):
                header_path = source_file.parent / header_name
                tracked_names = {path.name for path in header_path.parent.iterdir()}
                if header_path.name not in tracked_names:
                    missing_headers.append(f"{source_file}:{header_name}")
                    continue
                if header_path.suffix == ".h":
                    check_includes(header_path)

        for source in cmake_sources:
            check_includes(jni_dir / source)

        self.assertEqual([], missing_headers)
