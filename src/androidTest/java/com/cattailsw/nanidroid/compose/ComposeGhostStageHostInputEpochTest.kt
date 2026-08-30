package com.cattailsw.nanidroid.compose

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.cattailsw.nanidroid.GhostRuntimePhase
import com.cattailsw.nanidroid.runtime.RuntimeHostId
import com.cattailsw.nanidroid.runtime.RuntimeHostLease
import com.cattailsw.nanidroid.runtime.RuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ComposeGhostStageHostInputEpochTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Mutation caught: the production host embeds global RuntimeSnapshot.revision in gesture routing.
    @Test
    fun nonGeometricRuntimePublicationDoesNotCancelProductionStageGesture() {
        val lease = RuntimeHostLease(RuntimeHostId(71L), 3L)
        val snapshot = mutableStateOf(
            RuntimeSnapshot.initial().copy(
                revision = 10L,
                generation = 7L,
                phase = GhostRuntimePhase.Attached,
                activeGhostId = "stable-ghost",
                foregroundHost = lease,
            ),
        )
        val host = ComposeGhostStageHost()
        var chromeToggles = 0
        composeRule.setContent {
            host.Stage(
                snapshot = snapshot.value,
                hostLease = lease,
                submitCommand = {},
                modifier = Modifier.fillMaxSize().testTag(STAGE_TAG),
                blockingInput = { false },
                onSurfaceTap = { chromeToggles++ },
            )
        }
        val stage = composeRule.onNodeWithTag(STAGE_TAG)

        stage.performTouchInput { down(Offset(40f, 40f)) }
        composeRule.runOnIdle {
            snapshot.value = snapshot.value.copy(revision = snapshot.value.revision + 1L)
        }
        stage.performTouchInput { up() }

        composeRule.runOnIdle { assertEquals(1, chromeToggles) }
    }

    private companion object {
        const val STAGE_TAG = "production-stage-input"
    }
}
