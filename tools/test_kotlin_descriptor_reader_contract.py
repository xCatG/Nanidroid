import unittest
from pathlib import Path


class KotlinDescriptorReaderContractTest(unittest.TestCase):
    def test_gradle_descriptor_reader_is_kotlin_with_legacy_java_apis(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/com/cattailsw/nanidroid/DescReader.java").exists())
        source = (root / "src/com/cattailsw/nanidroid/DescReader.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("class DescReader", source)
        self.assertIn("constructor(infile: String)", source)
        self.assertIn("constructor(file: File)", source)
        self.assertIn("constructor(input: InputStream)", source)
        self.assertIn("fun parse(): MutableMap<String, String>", source)
        self.assertIn("fun getTable(): MutableMap<String, String>?", source)
        self.assertIn("fun setTable(table: MutableMap<String, String>?)", source)

    def test_parser_keeps_characterization_boundaries(self):
        root = Path(__file__).resolve().parents[1]
        source = (root / "src/com/cattailsw/nanidroid/DescReader.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("Charset.forName(\"Shift_JIS\")", source)
        self.assertIn("NarUtil.UTF8_BOM", source)
        self.assertIn("split(\",\".toRegex())", source)
        self.assertIn("throw NullPointerException()", source)
        self.assertIn("input.readBytes()", source)

    def test_descriptor_reader_has_no_archived_java_overlay(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "legacy").exists())


if __name__ == "__main__":
    unittest.main()
