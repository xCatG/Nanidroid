package com.cattailsw.nanidroid

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadManagerMigrationTest {
    @Test fun archiveDownloadsUseTheSystemDownloadManagerAndNonExportedReceiver() {
        val coordinatorFile = File("src/main/kotlin/com/cattailsw/nanidroid/NarDownloadManager.kt")
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(coordinatorFile.exists())
        val coordinator = coordinatorFile.readText()
        assertTrue(coordinator.contains("DownloadManager"))
        assertTrue(manifest.contains(".NarDownloadReceiver"))
        assertTrue(manifest.contains("android:exported=\"false\""))
    }

    @Test fun serviceNoLongerOwnsArchiveTransferOrNotifications() {
        val service = File("src/main/kotlin/com/cattailsw/nanidroid/NanidroidService.kt").readText()

        assertFalse(service.contains("NarDownloadTask"))
        assertFalse(service.contains("Intent.ACTION_RUN"))
        assertFalse(service.contains("download_in_progress"))
        assertFalse(service.contains("dl_complete"))
    }
}
