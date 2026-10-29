package com.cattailsw.nanidroid.ui

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.cattailsw.nanidroid.ghost.SurfaceImageLoader
import com.cattailsw.nanidroid.runtime.InteractionToken
import com.cattailsw.nanidroid.runtime.PlaybackFrame
import com.cattailsw.nanidroid.runtime.PresentedInput
import com.cattailsw.nanidroid.runtime.SpeakerFrame
import com.cattailsw.nanidroid.runtime.StageState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GhostTalkUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun talkRequiresOptInAndAvailabilityAndUsesExistingInputDialog() {
        val image = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).asImageBitmap()
        val state = mutableStateOf(
            StageState.Ready(
                PlaybackFrame(SpeakerFrame(0, true, "", false), SpeakerFrame(10, true, "", false), true),
                "Ghost", "Sakura", "Kero", mapOf(0 to File("sakura"), 10 to File("kero")),
                genAiSupported = true
            )
        )
        val token = InteractionToken(1, 1, 1)
        val submitted = mutableListOf<String>()
        compose.setContent {
            GhostStage(
                state.value, SurfaceImageLoader { image },
                onSelectGhost = {}, onConfirmSwitch = {}, onDismissSwitch = {},
                onOpenTalk = { state.value = state.value.copy(talkInput = PresentedInput(token, "Sakura")) },
                onSubmitTalk = { actual, text ->
                    assertEquals(token, actual)
                    submitted += text
                    state.value = state.value.copy(talkInput = null)
                }
            )
        }
        compose.onNodeWithTag("talk-action").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(genAiEnabled = true) }
        compose.onNodeWithTag("talk-action").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(talkAvailable = true) }
        compose.onNodeWithTag("talk-action").performClick()
        compose.onNodeWithTag("ghost-input").performTextInput("Hello")
        compose.onNodeWithContentDescription("Input OK").performClick()
        compose.runOnIdle { assertEquals(listOf("Hello"), submitted) }
        compose.onNodeWithTag("ghost-input").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(genAiEnabled = false) }
        compose.onNodeWithTag("talk-action").assertDoesNotExist()
    }
}
