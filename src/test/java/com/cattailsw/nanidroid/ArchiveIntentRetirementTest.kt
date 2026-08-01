package com.cattailsw.nanidroid

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class ArchiveIntentRetirementTest {
    @Test fun manifestDoesNotClaimGenericHttpsArchiveViewIntents() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertFalse(manifest.contains("<data android:scheme=\"https\" />"))
        assertFalse(manifest.contains("<data android:pathPattern=\".*\\\\.nar\" />"))
        assertFalse(manifest.contains("<data android:pathPattern=\".*\\\\.zip\" />"))
    }

    @Test fun activityDoesNotHandleExternalArchiveIntents() {
        val activity = File("src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt").readText()

        assertFalse(activity.contains("handleIncomingIntent"))
        assertFalse(activity.contains("IncomingNarIntent"))
    }
}
