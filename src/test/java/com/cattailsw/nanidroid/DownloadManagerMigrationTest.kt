package com.cattailsw.nanidroid

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadManagerMigrationTest {
    @Test fun archiveDownloadsUseTheSystemDownloadManagerAndNonExportedReceiver() {
        val coordinatorFile = File("src/main/kotlin/com/cattailsw/nanidroid/NarDownloadManager.kt")
        val receiver = File("src/main/kotlin/com/cattailsw/nanidroid/NarDownloadReceiver.kt").readText()
        val installJob = File("src/main/kotlin/com/cattailsw/nanidroid/NarDownloadInstallJob.kt")
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(coordinatorFile.exists())
        val coordinator = coordinatorFile.readText()
        assertTrue(coordinator.contains("DownloadManager"))
        assertTrue(installJob.exists())
        assertTrue(receiver.contains("JobScheduler"))
        assertTrue(manifest.contains(".NarDownloadReceiver"))
        assertTrue(manifest.contains(".NarDownloadInstallJob"))
        assertTrue(manifest.contains("android:exported=\"false\""))
    }

    @Test fun pendingDownloadIdsUseIndependentPreferenceKeysAndGhostListsRefresh() {
        val coordinator = File("src/main/kotlin/com/cattailsw/nanidroid/NarDownloadManager.kt").readText()
        val activity = File("src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt").readText()

        assertTrue(coordinator.contains("pending_\$id"))
        assertFalse(coordinator.contains("putStringSet"))
        assertTrue(activity.contains("manager.refreshGhost()"))
    }

    @Test fun serviceNoLongerOwnsArchiveTransferOrNotifications() {
        val service = File("src/main/kotlin/com/cattailsw/nanidroid/NanidroidService.kt").readText()

        assertFalse(service.contains("NarDownloadTask"))
        assertFalse(service.contains("Intent.ACTION_RUN"))
        assertFalse(service.contains("download_in_progress"))
        assertFalse(service.contains("dl_complete"))
    }
}
