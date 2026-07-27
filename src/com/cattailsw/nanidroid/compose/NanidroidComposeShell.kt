package com.cattailsw.nanidroid.compose

import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.cattailsw.nanidroid.R

/**
 * The activity's Compose-owned chrome.
 *
 * [ghostStage] is deliberately the only View interoperability boundary: its
 * FrameLayout retains SakuraView, KeroView and both Balloon instances.  The
 * shell does not draw surfaces or interpret script input; those remain in the
 * compatibility renderer until their behavior has a dedicated migration.
 */
@Composable
internal fun NanidroidComposeShell(
    ghostStage: FrameLayout,
    loading: Boolean,
    progressMessage: String,
    toolbarVisible: Boolean,
    onListGhost: () -> Unit,
    onUpdate: () -> Unit,
    onPreferences: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MaterialTheme {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = Color.Transparent,
        ) {
            Column {
                if (toolbarVisible) {
                    NanidroidToolbar(
                        onListGhost = onListGhost,
                        onUpdate = onUpdate,
                        onPreferences = onPreferences,
                        onHelp = onHelp,
                    )
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ghostStage },
                        modifier = Modifier.fillMaxSize().testTag("ghost-stage"),
                    )
                    if (loading) {
                        LoadingOverlay(progressMessage)
                    }
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
    }
}

@Composable
internal fun LoadingOverlay(progressMessage: String) {
    Surface(
        modifier = Modifier.fillMaxSize().testTag("loading-overlay"),
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

/** Supplies Compose lifecycle ownership while the activity remains on support-v4. */
internal class ComposeShellLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this).apply {
        performAttach()
        performRestore(null)
    }

    fun install(root: View) {
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
    }

    fun resume() { registry.currentState = Lifecycle.State.RESUMED }

    fun pause() { registry.currentState = Lifecycle.State.CREATED }

    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
}
