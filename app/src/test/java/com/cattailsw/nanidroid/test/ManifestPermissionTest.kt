package com.cattailsw.nanidroid.test

import android.Manifest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ManifestPermissionTest {
    @Test
    fun testPostNotificationsPermissionDeclared() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).first { it.isFile }
        val manifestText = manifest.readText()

        assertTrue(
            "POST_NOTIFICATIONS must be declared before MainActivity can request it",
            manifestText.contains("android:name=\"${Manifest.permission.POST_NOTIFICATIONS}\"")
        )
    }
}
