package com.cattailsw.nanidroid

import org.junit.Rule
import org.junit.Test

class GhostRuntimeDialogueTest {
    @Rule
    @JvmField
    val androidStubs = HostAndroidStubRule()

    @Test
    fun pendingInputRestoresOnlyAgainstSameDialogueIncarnationAndGeneration() {
        DialogueDialogBindingTest().matchedRestorationKeepsLivePresentationAndSavedValue()
        DialogueDialogBindingTest().unmatchedRestorationDoesNotCreateRenderableInputDialog()
        DialogueDialogBindingTest().staleInputAfterReplacementCannotSubmitAndKeepsItsPresentation()
    }
}
