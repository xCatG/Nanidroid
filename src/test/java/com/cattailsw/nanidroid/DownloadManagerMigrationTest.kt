package com.cattailsw.nanidroid

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadManagerMigrationTest {
    @Test fun archiveDownloadsUseTheSystemDownloadManagerAndNonExportedReceiver() {
        val coordinatorFile = File("src/main/kotlin/com/cattailsw/nanidroid/NarDownloadManager.kt")
        val receiver = File("src/main/kotlin/com/cattailsw/nanidroid/NarDownloadReceiver.kt").readText()
        val installJobFile = File("src/main/kotlin/com/cattailsw/nanidroid/NarDownloadInstallJob.kt")
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(coordinatorFile.exists())
        val coordinator = coordinatorFile.readText()
        val installJob = installJobFile.readText()
        assertTrue(coordinator.contains("DownloadManager"))
        assertTrue(installJobFile.exists())
        assertTrue(receiver.contains("JobScheduler"))
        assertTrue(receiver.contains("setPersisted(true)"))
        assertTrue(coordinator.contains("maxBytes = MAX_ARCHIVE_BYTES"))
        assertTrue(coordinator.contains("if (installed)"))
        assertTrue(installJob.contains("jobFinished(params, shouldRetry)"))
        assertTrue(manifest.contains(".NarDownloadReceiver"))
        assertTrue(manifest.contains(".NarDownloadInstallJob"))
        assertTrue(manifest.contains("android:exported=\"false\""))
        assertTrue(manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
    }

    @Test fun pendingDownloadIdsUseIndependentPreferenceKeysAndGhostListsRefresh() {
        val coordinator = File("src/main/kotlin/com/cattailsw/nanidroid/NarDownloadManager.kt").readText()
        val activity = File("src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt").readText()

        assertTrue(coordinator.contains("pending_\$id"))
        assertFalse(coordinator.contains("putStringSet"))
        assertTrue(coordinator.contains("catch (error: IllegalStateException)"))
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
