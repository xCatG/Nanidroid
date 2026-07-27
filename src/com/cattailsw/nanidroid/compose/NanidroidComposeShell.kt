package com.cattailsw.nanidroid.compose

import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

/**
 * The activity's Compose-owned chrome.
 *
 * [ghostStage] is deliberately the only View interoperability boundary: its
 * FrameLayout retains SakuraView, KeroView and both Balloon instances.  The
 * shell does not draw surfaces or interpret script input; those remain in the
 * compatibility renderer until their behavior has a dedicated migration.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
        Surface(modifier = modifier.fillMaxSize()) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NanidroidToolbar(
    onListGhost: () -> Unit,
    onUpdate: () -> Unit,
    onPreferences: () -> Unit,
    onHelp: () -> Unit,
) {
    TopAppBar(
        // The legacy button strip had no title. Keeping this slot empty leaves
        // enough horizontal space for every desktop-style control on a phone.
        title = {},
        actions = {
            Button(onClick = onListGhost, modifier = Modifier.testTag("list-ghost")) { Text("Ghosts") }
            Button(onClick = onUpdate, modifier = Modifier.testTag("update")) { Text("Update") }
            Button(onClick = onPreferences, modifier = Modifier.testTag("preferences")) { Text("Settings") }
            Button(onClick = onHelp, modifier = Modifier.testTag("help")) { Text("Help") }
        },
    )
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
