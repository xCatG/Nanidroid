package com.cattailsw.nanidroid

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import com.cattailsw.nanidroid.ghost.CachedSurfaceImageLoader
import com.cattailsw.nanidroid.runtime.StageState
import com.cattailsw.nanidroid.ui.GhostStage
import com.cattailsw.nanidroid.ui.NanidroidTheme
import com.cattailsw.nanidroid.ui.StageViewModel
import com.cattailsw.nanidroid.ui.BalloonLinks
import com.cattailsw.nanidroid.ui.Notice
import com.cattailsw.nanidroid.ui.NoticeCatalog
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val characterInteractionActive = mutableStateOf(false)
    private val importHost = Any()
    private val documentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val resolver = application.contentResolver
        viewModel.submitPickerResult(uri?.let { selected ->
            { if (selected.scheme != "content") throw IOException("Unsupported document URI")
              resolver.openInputStream(selected) ?: throw IOException("Unable to read document") }
        })
    }
    private val viewModel: StageViewModel by viewModels {
        val app = application as NanidroidApplication
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T =
                StageViewModel(app.runtime, app.importCoordinator, extras.createSavedStateHandle(),
                    app.importAttemptStore) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val installedVersionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"
        val loadedNotices = mutableStateOf<List<Notice>>(emptyList())
        lifecycleScope.launch {
            loadedNotices.value = withContext(Dispatchers.IO) {
                runCatching { NoticeCatalog.load(assets) }.getOrElse { error ->
                    listOf(Notice("Notices unavailable", error.message ?: "Unable to read bundled notices"))
                }
            }
        }
        enableEdgeToEdge()
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.setResumed(true)
                    characterInteractionActive.value = true
                }
                Lifecycle.Event.ON_PAUSE -> {
                    characterInteractionActive.value = false
                    viewModel.setResumed(false)
                }
                else -> Unit
            }
        })
        lifecycleScope.launch { viewModel.start(resources.configuration.locales[0].language) }
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val importState by viewModel.importState.collectAsStateWithLifecycle()
            val interrupted by viewModel.importInterrupted.collectAsStateWithLifecycle()
            var linkError by remember { mutableStateOf<String?>(null) }
            BackHandler(state !is StageState.Finished) { viewModel.close() }
            LaunchedEffect(state) { if (state is StageState.Finished) finish() }
            LaunchedEffect((state as? StageState.Ready)?.dialogueToken) { linkError = null }
            NanidroidTheme {
                GhostStage(state = state, imageLoader = imageLoader,
                    versionName = installedVersionName,
                    notices = loadedNotices.value,
                    interactionActive = characterInteractionActive.value,
                    onSelectGhost = viewModel::selectGhost,
                    onConfirmSwitch = viewModel::confirmSwitch,
                    onDismissSwitch = viewModel::dismissSwitch,
                    onCharacterMoveCancel = viewModel::cancelMove,
                    onCharacterClickResolved = viewModel::click,
                    onCharacterDoubleClickResolved = viewModel::doubleClick,
                    onCharacterMoveResolved = viewModel::move,
                    importState = importState,
                    onImport = { viewModel.launchImport { documentPicker.launch(arrayOf("*/*")) } },
                    onCancelImport = viewModel::cancelImport,
                    onClose = viewModel::close,
                    onAcknowledgeImport = viewModel::acknowledgeImport,
                    importInterrupted = interrupted,
                    onDismissInterrupted = viewModel::dismissInterrupted,
                    onChoose = viewModel::choose,
                    onSubmitInput = viewModel::submitInput,
                    onCancelInput = viewModel::cancelInput,
                    isFarewellInput = viewModel::isFarewellInput,
                    onOpenLink = { token, url ->
                        linkError = BalloonLinks.open(token, url, viewModel::isCurrentDialogue) { safeUrl ->
                            val uri = Uri.parse(safeUrl)
                            val action = if (uri.scheme.equals("mailto", ignoreCase = true))
                                Intent.ACTION_SENDTO else Intent.ACTION_VIEW
                            startActivity(Intent(action, uri))
                        }
                    },
                    linkError = linkError)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onImportActivityStart(importHost)
    }

    override fun onStop() {
        viewModel.setImportActivityStarted(importHost, false)
        if (!isChangingConfigurations) viewModel.cancelRunningOnStop()
        super.onStop()
    }

    private companion object {
        val imageLoader = CachedSurfaceImageLoader()
    }
}
