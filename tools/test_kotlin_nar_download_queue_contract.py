import unittest
from pathlib import Path


class NarDownloadQueueContractTest(unittest.TestCase):
    def test_manifest_and_activity_expose_the_narrow_durable_archive_queue(self):
        root = Path(__file__).resolve().parents[1]
        manifest = (root / "src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        activity = (root / "src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt").read_text(encoding="utf-8")
        dialog = (root / "src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidSimpleDialogs.kt").read_text(encoding="utf-8")

        self.assertNotIn('android:scheme="https"', manifest)
        self.assertIn('android:scheme="content" android:mimeType="application/zip"', manifest)
        self.assertIn('android:scheme="content" android:mimeType="application/x-nar"', manifest)
        self.assertIn("NarDownloadRepository.get(applicationContext)", activity)
        self.assertIn("NarLocalArchiveStager.stage", activity)
        self.assertIn("data class ArchiveQueue", dialog)
        self.assertIn("archive-retry-", dialog)
        self.assertIn("archive-delete-", dialog)


if __name__ == "__main__":
    unittest.main()
