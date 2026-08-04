package com.cattailsw.nanidroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueExternalUriLaunchTest {
    @Test
    fun resolverOrSecurityFailureDoesNotEscapeExplicitActivation() {
        assertFalse(tryLaunchDialogueExternalUri { throw SecurityException("blocked") })
        assertFalse(tryLaunchDialogueExternalUri { throw IllegalStateException("no resolver") })
        assertTrue(tryLaunchDialogueExternalUri {})
    }
}
