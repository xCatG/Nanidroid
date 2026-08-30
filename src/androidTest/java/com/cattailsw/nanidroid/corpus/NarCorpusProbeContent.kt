package com.cattailsw.nanidroid.corpus

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cattailsw.nanidroid.compose.ComposeGhostStageHost
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeHostId
import com.cattailsw.nanidroid.runtime.RuntimeHostLease
import com.cattailsw.nanidroid.runtime.RuntimeSnapshot

internal class NarCorpusProbeContent {
    private val screenshotPayload: MutableState<String> = mutableStateOf("")
    private val hostState: MutableState<ComposeGhostStageHost?> = mutableStateOf(null)
    private val snapshotState: MutableState<RuntimeSnapshot> = mutableStateOf(RuntimeSnapshot.initial())
    private val collisionOverlaySpeakerState: MutableState<SurfaceSpeaker?> = mutableStateOf(null)

    fun updateScreenshotPayload(payload: String) {
        screenshotPayload.value = payload
    }

    fun currentScreenshotPayload(): String = screenshotPayload.value

    fun showStage(host: ComposeGhostStageHost?, snapshot: RuntimeSnapshot = RuntimeSnapshot.initial()) {
        hostState.value = host
        snapshotState.value = snapshot
    }

    fun showCollisionOverlay(speaker: SurfaceSpeaker?) {
        collisionOverlaySpeakerState.value = speaker
    }

    @Composable
    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    fun Content() {
        val payload = remember { screenshotPayload }
        val host = hostState.value
        val collisionOverlaySpeaker = collisionOverlaySpeakerState.value
        Box(
            Modifier
                .fillMaxSize()
                .testTag("nar-corpus-probe-screenshot-root"),
        ) {
            if (host != null) {
                val lease = RuntimeHostLease(RuntimeHostId(1L), 1L)
                host.Stage(
                    snapshot = snapshotState.value.copy(foregroundHost = lease),
                    hostLease = lease,
                    submitCommand = { _: RuntimeCommand -> },
                    modifier = Modifier.fillMaxSize(),
                    collisionOverlaySpeaker = collisionOverlaySpeaker,
                )
            } else {
                Box(Modifier.fillMaxSize().padding(8.dp)) {
                    DisableSelection {
                        Text(
                            payload.value.ifBlank { "Nanidroid Corpus Probe" },
                            modifier = Modifier.testTag("nar-corpus-probe-screenshot-text"),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}
