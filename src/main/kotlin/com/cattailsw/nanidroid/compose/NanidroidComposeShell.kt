package com.cattailsw.nanidroid.compose

import android.graphics.drawable.Drawable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.install.NarDownload

/**
 * The activity's Compose-owned chrome.
 *
 * [ghostStage] is production Compose content.  The shell intentionally has no
 * AndroidView boundary: image composition, pointer routing, and balloons are
 * supplied by the declarative ghost-stage host.
 */
@Composable
internal fun NanidroidComposeShell(
    ghostStage: @Composable () -> Unit,
    loading: Boolean,
    progressMessage: String,
    toolbarVisible: Boolean,
    onListGhost: () -> Unit,
    onUpdate: () -> Unit,
    onPreferences: () -> Unit,
    onHelp: () -> Unit,
    onArchiveQueue: () -> Unit = {},
    archiveDownloads: List<NarDownload> = emptyList(),
    showDebugControls: Boolean = false,
    onNextSurface: () -> Unit = {},
    onAnimate: () -> Unit = {},
    onNextGhost: () -> Unit = {},
    onRun: () -> Unit = {},
    onNarTest: () -> Unit = {},
    onStageClick: () -> Unit = {},
    simpleDialog: NanidroidSimpleDialog?,
    onDismissSimpleDialog: () -> Unit,
    wallpaper: Drawable? = null,
    modifier: Modifier = Modifier,
) {
    MaterialTheme {
        Surface(
            modifier = modifier.fillMaxSize().drawBehind {
                wallpaper?.apply {
                    setBounds(0, 0, size.width.toInt(), size.height.toInt())
                    draw(drawContext.canvas.nativeCanvas)
                }
            },
            color = Color.Transparent,
        ) {
            Column(modifier = Modifier.statusBarsPadding()) {
                if (toolbarVisible) {
                    NanidroidToolbar(
                        onListGhost = onListGhost,
                        onUpdate = onUpdate,
                        onPreferences = onPreferences,
                        onHelp = onHelp,
                        onArchiveQueue = onArchiveQueue,
                    )
                    if (showDebugControls) {
                        DebugToolbar(onNextSurface, onAnimate, onNextGhost, onRun, onNarTest)
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize().testTag("ghost-stage").clickable(onClick = onStageClick)) { ghostStage() }
                    if (loading) {
                        LoadingOverlay(progressMessage)
                    }
                    NanidroidSimpleDialogHost(
                        dialog = simpleDialog,
                        onDismiss = onDismissSimpleDialog,
                        archiveDownloads = archiveDownloads,
                    )
                }
            }
        }
    }
}

@Composable
internal fun NanidroidToolbar(
    onListGhost: () -> Unit,
    onUpdate: () -> Unit,
    onPreferences: () -> Unit,
    onHelp: () -> Unit,
    onArchiveQueue: () -> Unit = {},
) {
    // The old desktop-style strip had no title. Scrolling preserves access to
    // every localized control on compact widths and at large font scales.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        Button(onClick = onListGhost, modifier = Modifier.testTag("list-ghost")) {
            Text(stringResource(R.string.list_ghost_btn_text))
        }
        Button(onClick = onUpdate, modifier = Modifier.testTag("update")) {
            Text(stringResource(R.string.update_btn_text))
        }
        Button(onClick = onPreferences, modifier = Modifier.testTag("preferences")) {
            Text(stringResource(R.string.setup_btn_text))
        }
        Button(onClick = onHelp, modifier = Modifier.testTag("help")) {
            Text(stringResource(R.string.help_btn_text))
        }
        Button(onClick = onArchiveQueue, modifier = Modifier.testTag("archive-queue")) { Text("Downloads") }
    }
}

@Composable
private fun DebugToolbar(
    onNextSurface: () -> Unit,
    onAnimate: () -> Unit,
    onNextGhost: () -> Unit,
    onRun: () -> Unit,
    onNarTest: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        Button(onClick = onNextSurface, modifier = Modifier.testTag("debug-next-surface")) { Text("next s.") }
        Button(onClick = onAnimate, modifier = Modifier.testTag("debug-draw-cbox")) { Text("draw CBox") }
        Button(onClick = onNextGhost, modifier = Modifier.testTag("debug-dump-surfaces")) { Text("dump S") }
        Button(onClick = onRun, modifier = Modifier.testTag("debug-run")) { Text("run") }
        Button(onClick = onNarTest, modifier = Modifier.testTag("debug-nar")) { Text("nar") }
    }
}

@Composable
internal fun LoadingOverlay(progressMessage: String) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("loading-overlay")
            // The legacy progress view hid the stage. Keep it inert while a
            // ghost is being created or switched, rather than only obscuring it.
            .pointerInteropFilter { true },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(progressMessage)
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun NanidroidToolbarPreview() {
    MaterialTheme {
        NanidroidToolbar({}, {}, {}, {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun NanidroidLoadingPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) { LoadingOverlay("Loading Nanidroid") }
        }
    }
}
