package com.cattailsw.nanidroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.cattailsw.nanidroid.compose.NanidroidComposeShell
import com.cattailsw.nanidroid.compose.NanidroidSimpleDialog
import com.cattailsw.nanidroid.compose.ComposeGhostStageHost
import com.cattailsw.nanidroid.compose.PlainTextDocument
import com.cattailsw.nanidroid.compose.SurfaceInteractionPort
import com.cattailsw.nanidroid.util.PrefUtil
import com.cattailsw.nanidroid.install.NarDownloadRepository
import com.cattailsw.nanidroid.install.NarLiveGrantHandoff
import com.cattailsw.nanidroid.install.NarDownloadState
import com.cattailsw.nanidroid.install.NarUserEnqueueResult
import com.cattailsw.nanidroid.install.StageLocalNarWorker
import com.cattailsw.nanidroid.install.ForegroundNarImportCoordinator
import com.cattailsw.nanidroid.install.ForegroundNarImportState
import com.cattailsw.nanidroid.install.NarDocumentSelection
import com.cattailsw.nanidroid.install.NarImportAttemptToken
import com.cattailsw.nanidroid.install.NarImportPrimaryOutcome
import com.cattailsw.nanidroid.durable.DurableAttentionNotificationPolicy
import com.cattailsw.nanidroid.durable.SharedDurableOperationSupervisor
import com.cattailsw.nanidroid.runtime.dialogue.ActionOrigin
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.dialogue.GhostActionGuard
import com.cattailsw.nanidroid.runtime.dialogue.GuardedAction
import com.cattailsw.nanidroid.runtime.dialogue.PendingInputState
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun launchCandidateIds(preferred: String, available: List<String>): List<String> =
    listOf(preferred) + available.filterNot { it.equals(preferred, ignoreCase = true) }

internal fun finishAfterRestoredNotice(message: Int): Boolean = message in setOf(
    R.string.err_no_sdcard,
    R.string.err_no_ghost_available,
)

internal fun ownsGhostSwitchRequest(targetGhostId: String, pendingGhostId: String?): Boolean =
    targetGhostId == pendingGhostId

internal fun tryLaunchDialogueExternalUri(launch: () -> Unit): Boolean = try {
    launch()
    true
} catch (_: RuntimeException) {
    false
}

internal fun tryLaunchDocumentExternalUrl(value: String, launch: (String) -> Unit): Boolean {
    val uri = try {
        java.net.URI(value)
    } catch (_: Exception) {
        return false
    }
    val scheme = uri.scheme ?: return false
    val isAllowed = when {
        scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true) ->
            try {
                uri.toURL().host.isNotBlank()
            } catch (_: Exception) {
                false
            }
        scheme.equals("mailto", ignoreCase = true) ->
            uri.schemeSpecificPart.substringBefore('?').isNotBlank()
        else -> false
    }
    return isAllowed && tryLaunchDialogueExternalUri { launch(value) }
}

internal fun allowsArchiveIntentIngress(
    activityRunner: SScriptRunner?,
    retainedRunner: () -> SScriptRunner,
): Boolean {
    val activeRunner = activityRunner ?: retainedRunner()
    return GhostActionGuard(activeRunner.runtimeModeSnapshot())
        .allows(GuardedAction.IMPORT_INSTALL, ActionOrigin.USER)
}

internal fun <T : Any> routeGhostSwitchResult(
    result: T?,
    destroyed: Boolean,
    finishing: Boolean,
    targetGhostId: String,
    pendingGhostId: String?,
    abandon: (T) -> Unit,
    apply: (T?) -> Unit,
) {
    if (destroyed || finishing || !ownsGhostSwitchRequest(targetGhostId, pendingGhostId)) {
        result?.let(abandon)
        return
    }
    apply(result)
}

internal fun <T : Any> abandonUnclaimedReservation(
    reservation: T?,
    claimed: Boolean,
    abandon: (T) -> Unit,
) {
    if (!claimed) {
        reservation?.let(abandon)
    }
}

internal data class TransientUiSnapshot(
    val toolbarVisible: Boolean,
)

internal fun restoredTransientUiSnapshot(toolbarVisible: Boolean): TransientUiSnapshot =
    TransientUiSnapshot(toolbarVisible)

internal fun transientUiSnapshotToSave(
    pending: TransientUiSnapshot?,
    initialized: Boolean,
    toolbarVisible: Boolean,
): TransientUiSnapshot? = pending ?: if (initialized) {
    TransientUiSnapshot(toolbarVisible)
} else {
    null
}

internal fun Bundle.readTransientUiSnapshot(): TransientUiSnapshot? =
    takeIf { getBoolean(TRANSIENT_UI_PRESENT, false) }?.let { state ->
        restoredTransientUiSnapshot(state.getBoolean(TRANSIENT_TOOLBAR_VISIBLE, true))
    }

internal fun Bundle.writeTransientUiSnapshot(snapshot: TransientUiSnapshot) {
    putBoolean(TRANSIENT_UI_PRESENT, true)
    putBoolean(TRANSIENT_TOOLBAR_VISIBLE, snapshot.toolbarVisible)
}

internal fun Bundle.writeNarPickerOwnerToken(token: NarImportAttemptToken) {
    putString(NAR_PICKER_OWNER_PROCESS_NONCE, token.processNonce)
    putLong(NAR_PICKER_OWNER_SEQUENCE, token.sequence)
}

internal fun Bundle.readNarPickerOwnerToken(): NarImportAttemptToken? = runCatching {
    val processNonce = getString(NAR_PICKER_OWNER_PROCESS_NONCE)
        ?.takeIf(String::isNotBlank)
        ?: return null
    if (!containsKey(NAR_PICKER_OWNER_SEQUENCE)) return null
    val sequence = getLong(NAR_PICKER_OWNER_SEQUENCE).takeIf { it > 0L } ?: return null
    NarImportAttemptToken(processNonce, sequence)
}.getOrNull()

internal fun reconcileNarPickerOwner(
    restored: NarImportAttemptToken?,
    state: ForegroundNarImportState,
    abandon: (NarImportAttemptToken) -> Boolean,
): NarImportAttemptToken? {
    val awaiting = state as? ForegroundNarImportState.AwaitingSelection ?: return null
    if (restored == awaiting.token) return restored
    abandon(awaiting.token)
    return null
}

internal fun armAndLaunchNarDocumentPicker(
    coordinator: ForegroundNarImportCoordinator,
    setOwner: (NarImportAttemptToken?) -> Unit,
    launch: () -> Unit,
    failureMessage: String,
): Boolean {
    val token = coordinator.armPicker() ?: return false
    setOwner(token)
    return try {
        launch()
        true
    } catch (_: RuntimeException) {
        coordinator.failPickerLaunch(token, failureMessage)
        setOwner(null)
        false
    }
}

internal class NanidroidLifecycleTestHooks(
    val afterGhostMgrCreatedBeforeReady: (GhostMgr) -> Unit = {},
    val onForegroundNarRefresh: (GhostMgr, NarImportAttemptToken) -> Unit = { _, _ -> },
)

private const val TRANSIENT_UI_PRESENT = "transient_ui_present"
private const val TRANSIENT_TOOLBAR_VISIBLE = "transient_toolbar_visible"
private const val NAR_PICKER_OWNER_PROCESS_NONCE = "nar_picker_owner_process_nonce"
private const val NAR_PICKER_OWNER_SEQUENCE = "nar_picker_owner_sequence"
private const val TEXT_DOCUMENT_RESTORE_KIND = "text_document_restore_kind"
private const val TEXT_DOCUMENT_RESTORE_TITLE = "text_document_restore_title"
private const val TEXT_DOCUMENT_RESTORE_TEXT = "text_document_restore_text"
private const val TEXT_DOCUMENT_RESTORE_SOURCE_ID = "text_document_restore_source_id"

internal enum class TextDocumentRestoreKind {
    INSTALLED_GHOST_README,
    CURRENT_GHOST_README,
}

internal data class TextDocumentRestoreSnapshot(
    val kind: TextDocumentRestoreKind,
    val title: String,
    val text: String,
    val sourceId: String?,
)

internal fun NanidroidSimpleDialog.TextDocument.toTextDocumentRestoreSnapshot() =
    TextDocumentRestoreSnapshot(
        kind = when {
            onSwitch != null -> TextDocumentRestoreKind.INSTALLED_GHOST_README
            else -> TextDocumentRestoreKind.CURRENT_GHOST_README
        },
        title = title,
        text = text,
        sourceId = sourceId,
    )

internal fun Bundle.writeTextDocumentRestoreSnapshot(snapshot: TextDocumentRestoreSnapshot) {
    putString(TEXT_DOCUMENT_RESTORE_KIND, snapshot.kind.name)
    putString(TEXT_DOCUMENT_RESTORE_TITLE, snapshot.title)
    putString(TEXT_DOCUMENT_RESTORE_TEXT, snapshot.text)
    putString(TEXT_DOCUMENT_RESTORE_SOURCE_ID, snapshot.sourceId)
}

internal fun Bundle.readTextDocumentRestoreSnapshot(): TextDocumentRestoreSnapshot? {
    val kind = getString(TEXT_DOCUMENT_RESTORE_KIND)
        ?.let { value -> TextDocumentRestoreKind.entries.firstOrNull { it.name == value } }
        ?: return null
    return TextDocumentRestoreSnapshot(
        kind = kind,
        title = getString(TEXT_DOCUMENT_RESTORE_TITLE).orEmpty(),
        text = getString(TEXT_DOCUMENT_RESTORE_TEXT).orEmpty(),
        sourceId = getString(TEXT_DOCUMENT_RESTORE_SOURCE_ID),
    )
}

/**
 * The production activity. Compose owns both chrome and ghost presentation;
 * SScriptRunner supplies immutable frames through KotlinGhostPresentationRuntime.
 */
@AndroidEntryPoint
class Nanidroid : ComponentActivity(), SScriptRunner.UICallback {

    private var stageInputEpoch = 0L
    private val loadingState = mutableStateOf(true)
    private var loading: Boolean
        get() = loadingState.value
        set(value) {
            if (loadingState.value != value) {
                stageInputEpoch++
                loadingState.value = value
            }
        }
    private var progressMessage by mutableStateOf("")
    private var toolbarVisible by mutableStateOf(false)
    private var transientUiInitialized = false
    private var pendingRestoredTransientUi: TransientUiSnapshot? = null
    private val simpleDialogState = mutableStateOf<NanidroidSimpleDialog?>(null)
    private var simpleDialog: NanidroidSimpleDialog?
        get() = simpleDialogState.value
        set(value) {
            if ((simpleDialogState.value == null) != (value == null)) stageInputEpoch++
            simpleDialogState.value = value
        }
    private var runner: SScriptRunner? = null
    private val dialogueDialogBinding = DialogueDialogBinding { runner }
    private val composeStage = ComposeGhostStageHost(
        SurfaceInteractionPort { effect -> runner?.dispatchSurfaceInteraction(effect) },
    )
    private var gm: GhostMgr? = null
    private val ghostMgrReady = CompletableDeferred<GhostMgr>()
    private var currentGhost: Ghost? = null
    private var restoreFromMinimize = false
    private var currentRunCount = -1L
    private var initComplete = false
    private var nextGhostId: String? = null
    private val foregroundNarImport by lazy {
        ForegroundNarImportCoordinator.get(applicationContext)
    }
    private var narPickerOwnerToken: NarImportAttemptToken? = null
    private var installedReadyToken by mutableStateOf<NarImportAttemptToken?>(null)
    private var archiveIntentState = ArchiveIntentState()
    private val narDocumentPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val expectedToken = narPickerOwnerToken
        narPickerOwnerToken = null
        if (expectedToken == null) return@registerForActivityResult
        val accepted = foregroundNarImport.consumePickerResult(
            expectedToken = expectedToken,
            selection = uri?.let { NarDocumentSelection(it.toString(), it.scheme) },
            importAllowed = allows(GuardedAction.IMPORT_INSTALL, ActionOrigin.USER),
        )
        if (!accepted) return@registerForActivityResult
    }
    private var pendingDurableNotificationPermission = false
    private val durableNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pendingDurableNotificationPermission = false
        refreshDurableAttention()
    }
    private val narDownloads by lazy { NarDownloadRepository.get(applicationContext) }
    private val narLiveGrantExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "nar-live-grant-copy")
    }
    private val narLiveGrantHandoff by lazy {
        val privateDirectory = StageLocalNarWorker.localImportDirectory(applicationContext)
        NarLiveGrantHandoff(narDownloads, narLiveGrantExecutor, privateDirectory)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
        narPickerOwnerToken = reconcileNarPickerOwner(
            restored = savedInstanceState?.readNarPickerOwnerToken(),
            state = foregroundNarImport.state.value,
            abandon = foregroundNarImport::abandonPicker,
        )
        archiveIntentState = ArchiveIntentState(
            consumedUri = savedInstanceState?.getString(NAR_CONSUMED_INTENT_URI),
            pendingUri = savedInstanceState?.getString(NAR_PENDING_INTENT_URI),
            pendingFlags = savedInstanceState?.getInt(NAR_PENDING_INTENT_FLAGS, 0) ?: 0,
        )
        resolveRunnerBeforeColdArchiveIngress(
            resolveRunner = { SScriptRunner.getInstance(this) },
            bindRunner = { runner = it },
            handleArchiveIngress = { handleIncomingIntent(intent) },
        )
        setupViews()
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED, true)) {
            simpleDialog = NanidroidSimpleDialog.Notice(
                R.string.err_title,
                R.string.err_no_sdcard,
                onConfirm = { finish() },
            )
            return
        }
        checkIsRestore(savedInstanceState)
        pendingRestoredTransientUi = savedInstanceState?.readTransientUiSnapshot()
        restoreSimpleDialog(savedInstanceState)
        initOnSeparateThread()
    }

    private fun initOnSeparateThread() {
        lifecycleScope.launch {
            var reservation: ReservedGhost? = null
            var reservationClaimed = false
            try {
                withContext(Dispatchers.IO) {
                    createSvcs2ndThread()
                    if (gm!!.shouldInstallFirstGhost()) installFirstGhost()
                    reservation = createGhost()
                    currentRunCount = getStartCount()
                    if (currentRunCount == 0L) loadFirstRunScript()
                    setStartCount(++currentRunCount)
                }
                if (isDestroyed || isFinishing) {
                    return@launch
                }
                val ghost = reservation?.ghost
                if (reservation == null || ghost == null) {
                    hideProgress()
                    simpleDialog = NanidroidSimpleDialog.Notice(
                        R.string.err_title,
                        R.string.err_no_ghost_available,
                        onConfirm = { finish() },
                    )
                    return@launch
                }
                // Compose state and its caches are main-thread owned. The
                // ghost files were prepared above; bind them only on the UI thread.
                if (!setGhostToRunner(reservation)) {
                    hideProgress()
                    simpleDialog = NanidroidSimpleDialog.Notice(
                        R.string.err_title,
                        R.string.err_no_ghost_available,
                        onConfirm = { finish() },
                    )
                    return@launch
                }
                reservationClaimed = true
                enqueuePendingArchiveIntent()
                hideProgress()
                initComplete = true
                runner!!.startClock()
                runner!!.run()
            } finally {
                abandonUnclaimedReservation(reservation, reservationClaimed) {
                    runner?.abandonReservedGhost(it)
                }
            }
        }
    }

    private fun createSvcs2ndThread() {
        val manager = GhostMgr(this)
        gm = manager
        lifecycleTestHooks.afterGhostMgrCreatedBeforeReady(manager)
        check(ghostMgrReady.complete(manager))
    }
    private fun createGhost(): ReservedGhost? {
        val lastId = gm!!.getLastRunGhostId() ?: "nanidroid"
        mGH.sendEmptyMessage(MSG_LOAD_F)
        val ghost = launchCandidateIds(lastId, gm!!.getGnames().orEmpty().toList())
            .firstNotNullOfOrNull(gm!!::createGhost)
            ?: return null
        gm!!.setLastRunGhost(ghost.ghost)
        currentGhost = ghost.ghost
        return ghost
    }

    private fun setGhostToRunner(reservation: ReservedGhost): Boolean {
        val ghost = reservation.ghost
        composeStage.setSurfaceManager(ghost.mgr, ghost.getGhostId())
        runner!!.setPresentationRenderer(composeStage.renderer)
        runner!!.setDialogueStateObserver(composeStage::updateDialogueState)
        // The runner remains attached precisely once, on the initialized UI thread.
        runner!!.setUICallback(this@Nanidroid)
        val attached = runner!!.attachReservedGhost(reservation)
        if (!attached) runner!!.abandonReservedGhost(reservation)
        return attached
    }
    private fun setupViews() {
        progressMessage = getString(R.string.prog_startup)
        setContent {
            val downloads by narDownloads.observeDownloads().collectAsState()
            val importState by foregroundNarImport.state.collectAsState()
            val stalledOperations by SharedDurableOperationSupervisor
                .attention(applicationContext)
                .observeStalledOperations()
                .collectAsState()
            var durableRecoveryRequired by remember {
                mutableStateOf(SharedDurableOperationSupervisor.isRecoveryRequired())
            }
            LaunchedEffect(downloads) {
                if (downloads.any { it.state is NarDownloadState.Complete }) gm?.refreshGhost()
            }
            LaunchedEffect(importState) {
                val token = when (val state = importState) {
                    is ForegroundNarImportState.Installed -> state.token
                    is ForegroundNarImportState.RecoveryRequired ->
                        state.token.takeIf { state.primary is NarImportPrimaryOutcome.Installed }
                    else -> null
                } ?: return@LaunchedEffect
                if (installedReadyToken == token) return@LaunchedEffect
                val manager = ghostMgrReady.await()
                if (installedReadyToken == token) return@LaunchedEffect
                val publishedToken = when (val state = foregroundNarImport.state.value) {
                    is ForegroundNarImportState.Installed -> state.token
                    is ForegroundNarImportState.RecoveryRequired ->
                        state.token.takeIf { state.primary is NarImportPrimaryOutcome.Installed }
                    else -> null
                }
                if (publishedToken != token) return@LaunchedEffect
                manager.refreshGhost()
                lifecycleTestHooks.onForegroundNarRefresh(manager, token)
                installedReadyToken = token
            }
            Box(Modifier.fillMaxSize()) {
                NanidroidComposeShell(
                    ghostStage = {
                        composeStage.Stage(
                            blockingInput = ::isStageInputBlocked,
                            blockingInputEpoch = { stageInputEpoch },
                            onSurfaceTap = ::frameClick,
                            onDialogueChoice = { action -> runner?.activateChoice(action) },
                            onDialogueAnchor = { action -> runner?.activateAnchor(action) },
                            onDialogueExternalUrl = ::openDialogueExternalUrl,
                            onDialogueInput = ::openDialogueInput,
                        )
                    },
                    loading = loading,
                    progressMessage = progressMessage,
                    toolbarVisible = toolbarVisible,
                    onListGhost = ::onListGhost,
                    onReadme = ::openCurrentGhostReadme,
                    onArchiveQueue = {
                        simpleDialog = NanidroidSimpleDialog.ArchiveQueue(
                            onRetry = { if (allows(GuardedAction.IMPORT_INSTALL)) narDownloads.retry(it) },
                            onReselect = { startInstallFromSDCard() },
                            onDelete = { if (allows(GuardedAction.UNINSTALL)) narDownloads.delete(it) },
                        )
                    },
                    archiveDownloads = downloads,
                    narImportState = importState,
                    installedReadyToken = installedReadyToken,
                    onAcknowledgeNarImport = { foregroundNarImport.acknowledge(it) },
                    onSelectAnotherNarImport = { token ->
                        if (foregroundNarImport.acknowledge(token)) startInstallFromSDCard()
                    },
                    onRetryNarImportCleanup = { foregroundNarImport.retryCleanup(it) },
                    simpleDialog = simpleDialog,
                    onDismissSimpleDialog = { simpleDialog = null },
                    stalledOperations = stalledOperations,
                    onDurableAttentionAction = { handle, action ->
                        SharedDurableOperationSupervisor.get(applicationContext)
                            .performAttentionAction(handle, action)
                    },
                    durableRecoveryRequired = durableRecoveryRequired,
                    onResolveDurableRecovery = {
                        val resolved = SharedDurableOperationSupervisor.resolveRecovery()
                        durableRecoveryRequired =
                            SharedDurableOperationSupervisor.isRecoveryRequired()
                        resolved
                    },
                )
            }
        }
        showProgress()
    }

    private fun showProgress() {
        loading = true
    }
    private fun hideProgress() {
        val restored = pendingRestoredTransientUi
        if (restored != null) {
            toolbarVisible = restored.toolbarVisible
        } else {
            toolbarVisible = true
        }
        pendingRestoredTransientUi = null
        transientUiInitialized = true
        loading = false
    }
    private fun checkIsRestore(state: Bundle?): Boolean {
        if (state != null) { Log.d(TAG, "was minimized"); restoreFromMinimize = state.getBoolean(MIN_TAG, false); return restoreFromMinimize }; return false
    }
    private fun getStartCount() = PrefUtil.getKeyValueLong(applicationContext, PREF_KEY_LAUNCH_TIME)
    private fun setStartCount(count: Long) = PrefUtil.setKey(applicationContext, PREF_KEY_LAUNCH_TIME, count)
    private fun loadFirstRunScript() = try {
        BufferedReader(InputStreamReader(resources.openRawResource(R.raw.first_run_script), "UTF-8")).use { br ->
            var line = br.readLine(); while (line != null) { if (line.isNotEmpty() && !line.startsWith("#")) runner!!.addMsgToQueue(arrayOf(line)); line = br.readLine() }
        }
    } catch (_: Exception) { runner!!.addMsgToQueue(arrayOf("\\0Oops, something wrong with first run script!\\e")) }
    override fun onPause() { super.onPause(); runner?.stopClock() }
    override fun onSaveInstanceState(outState: Bundle) {
        saveSimpleDialog(outState)
        transientUiSnapshotToSave(
            pending = pendingRestoredTransientUi,
            initialized = transientUiInitialized,
            toolbarVisible = toolbarVisible,
        )?.let(outState::writeTransientUiSnapshot)
        narPickerOwnerToken?.let(outState::writeNarPickerOwnerToken)
        outState.putString(NAR_CONSUMED_INTENT_URI, archiveIntentState.consumedUri)
        outState.putString(NAR_PENDING_INTENT_URI, archiveIntentState.pendingUri)
        outState.putInt(NAR_PENDING_INTENT_FLAGS, archiveIntentState.pendingFlags)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        narLiveGrantExecutor.shutdown()
        super.onDestroy()
    }
    override fun onResume() {
        super.onResume()
        refreshDurableAttention()
        requestPendingDurableNotificationPermission()
        if (initComplete) {
            runner?.startClock()
            runner?.run()
        }
    }
    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (!allows(GuardedAction.EXIT)) return
            val activeRunner = runner
            if (activeRunner != null) {
                activeRunner.stopClock()
                activeRunner.setCallback(mscb)
                activeRunner.stop()
                activeRunner.doExit()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }
    private val mscb = object : SScriptRunner.StatusCallback {
        override fun stop() = Unit
        override fun canExit() { runner!!.setCallback(null); finish() }
        override fun ghostSwitchScriptComplete() { runner!!.setCallback(null); runOnUiThread(ghostSwitchStep2Caller) }
    }
    private val mGH = object : Handler(Looper.getMainLooper()) { override fun handleMessage(m: Message) { when (m.what) { MSG_START -> progressMessage = getString(R.string.prog_startup); MSG_LOAD_F -> progressMessage = String.format(getString(R.string.load_g), gm!!.getGhostDispName(gm!!.getLastRunGhostId() ?: "nanidroid")); MSG_LOAD_N -> (m.obj as? String)?.let { ghostId -> progressMessage = String.format(getString(R.string.load_g), gm!!.getGhostDispName(ghostId)) } } } }
    private val ghostSwitchStep2Caller = Runnable { showProgress(); ghostSwitchStep2() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus) }
    private fun addNarToDownload(target: Uri) {
        if (!allows(GuardedAction.IMPORT_INSTALL)) return
        val value = target.toString()
        if (!isApprovedArchiveUrl(value)) {
            Toast.makeText(this, R.string.err_https_nar_only, Toast.LENGTH_LONG).show()
            return
        }
        val result = narDownloads.enqueueRemoteForUser(value)
        handleAcceptedNarUserEnqueueResult(result)
    }
    private fun installFirstGhost() {
        var target: File? = null
        try {
            val archive = File.createTempFile("nanidroid-", ".nar", cacheDir)
            target = archive
            assets.open("nanidroid.zip").use { input ->
                archive.outputStream().use(input::copyTo)
            }
            gm!!.installFirstGhost("nanidroid", archive.path)
        } catch (exception: IOException) {
            exception.printStackTrace()
        } finally {
            target?.delete()
        }
    }
    private fun showReadme(readme: File, ghostId: String) {
        simpleDialog = createReadmeDialog(readme, ghostId)
    }
    private fun openCurrentGhostReadme() {
        val ghost = currentGhost ?: return
        val ghostId = ghost.getGhostId()
        val readme = gm?.getGhostReadMe(ghostId)
        if (readme?.exists() == true) {
            simpleDialog = NanidroidSimpleDialog.TextDocument(
                getString(R.string.readme_menu_text),
                PlainTextDocument.read(readme),
                ::openDocumentLink,
                ghostId,
            )
        } else {
            simpleDialog = NanidroidSimpleDialog.Notice(
                R.string.current_ghost_no_readme_title,
                R.string.current_ghost_no_readme_message,
            )
        }
    }
    private fun showGhostInstalledDlg(ghostId: String) {
        simpleDialog = createNoReadmeDialog(ghostId, gm!!.getGhostDispName(ghostId) ?: ghostId)
    }
    fun switchGhost(nextId: String) { if (!allows(GuardedAction.SWITCH_GHOST)) return; val name = gm!!.getGhostSakuraName(nextId) ?: run { Log.d(TAG, "invalid next ghost id"); return }; nextGhostId = nextId; runner!!.stopClock(); runner!!.clearMsgQueue(); runner!!.setCallback(mscb); runner!!.doGhostChanging(name, "manual", gm!!.getGhostPath(nextId)) }
    fun ghostSwitchStep2() {
        val targetGhostId = nextGhostId ?: run {
            Log.w(TAG, "ghost switch completed without a target ghost")
            hideProgress()
            return
        }
        mGH.obtainMessage(MSG_LOAD_N, targetGhostId).sendToTarget()
        showProgress()
        lifecycleScope.launch {
            var reservation: ReservedGhost? = null
            var reservationClaimed = false
            try {
                withContext(Dispatchers.IO) {
                    try {
                        reservation = gm?.createGhost(targetGhostId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.d(TAG, "failed to switch to ghost:$targetGhostId", e)
                    }
                }
                routeGhostSwitchResult(
                    reservation,
                    isDestroyed,
                    isFinishing,
                    targetGhostId,
                    nextGhostId,
                    abandon = {
                        reservationClaimed = true
                        runner?.abandonReservedGhost(it)
                    },
                ) { ownedReservation ->
                    reservationClaimed = true
                    nextGhostId = null
                    hideProgress()
                    val exactReservation = ownedReservation ?: return@routeGhostSwitchResult
                    val ghost = exactReservation.ghost
                    // Keep the Compose stage and runner on the UI thread; its frame
                    // cache and scheduler state are intentionally not synchronized.
                    composeStage.setSurfaceManager(ghost.mgr, ghost.getGhostId())
                    gm!!.setLastRunGhost(ghost)
                    if (runner?.attachReservedGhost(exactReservation) != true) {
                        runner?.abandonReservedGhost(exactReservation)
                        return@routeGhostSwitchResult
                    }
                    currentGhost = ghost
                    runner!!.startClock()
                }
            } finally {
                abandonUnclaimedReservation(reservation, reservationClaimed) {
                    runner?.abandonReservedGhost(it)
                }
            }
        }
    }
    private fun handleIncomingIntent(incoming: Intent?, isNewIntent: Boolean = false) {
        if (!allowsArchiveIntentIngress(runner) { SScriptRunner.getInstance(this) }) return
        val resolvedMimeType = incoming?.type ?: runCatching {
            incoming?.data?.let(contentResolver::getType)
        }.getOrNull()
        val uri = ArchiveIntentAdapter.contentUri(incoming, resolvedMimeType) ?: return
        val flags = incoming?.flags ?: 0
        val reception = if (isNewIntent) {
            archiveIntentState.receiveNewIntent(uri.toString(), flags)
        } else {
            archiveIntentState.receive(uri.toString(), flags)
        }
        when (reception) {
            is ArchiveIntentState.Reception.Ignored -> Unit
            is ArchiveIntentState.Reception.Pending -> archiveIntentState = reception.state
            is ArchiveIntentState.Reception.Dispatch -> {
                archiveIntentState = reception.state
                enqueueLocalArchive(Uri.parse(reception.uri), reception.flags)
            }
        }
    }

    private fun enqueuePendingArchiveIntent() {
        val pending = archiveIntentState.takePending() ?: return
        archiveIntentState = pending.state
        enqueueLocalArchive(Uri.parse(pending.uri), pending.flags)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent, isNewIntent = true)
        if (initComplete) enqueuePendingArchiveIntent()
    }
    fun onListGhost() { if (!allows(GuardedAction.SWITCH_GHOST)) return; showGhostListDlg() }
    fun getMoreGhost() {
        simpleDialog = createMoreGhostDialog()
    }
    private fun startInstallFromSDCard(origin: ActionOrigin = ActionOrigin.USER) {
        if (!allows(GuardedAction.IMPORT_INSTALL, origin)) return
        armAndLaunchNarDocumentPicker(
            coordinator = foregroundNarImport,
            setOwner = { narPickerOwnerToken = it },
            launch = { narDocumentPicker.launch(arrayOf("*/*")) },
            failureMessage = "Nanidroid could not open the document picker.",
        )
    }
    fun showNarErrDlg(dir: Boolean) {
        simpleDialog = NanidroidSimpleDialog.Notice(
            R.string.err_nar_title,
            if (dir) R.string.err_no_nar_folder else R.string.err_no_nar_file,
        )
    }
    private fun enqueueLocalArchive(
        uri: Uri,
        flags: Int,
        replacementId: String? = null,
        origin: ActionOrigin = ActionOrigin.USER,
    ) {
        if (!allows(GuardedAction.IMPORT_INSTALL, origin)) return
        val canPersist = flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        if (canPersist) {
            val result = if (replacementId == null) {
                narDownloads.enqueuePersistedLocalCopyForUser(uri.toString()) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                        true
                    } catch (_: SecurityException) {
                        false
                    }
                }
            } else narDownloads.replaceWithPersistedLocalSourceForUser(
                replacementId,
                uri.toString(),
            ) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    true
                } catch (_: SecurityException) {
                    false
                }
            }
            handleAcceptedNarUserEnqueueResult(result)
            if (result != null) {
                return
            }
        }

        val accepted = narLiveGrantHandoff.enqueueForUser(uri.toString(), replacementId) {
                contentResolver.openInputStream(uri)
            }
        if (accepted == null) {
            Toast.makeText(
                this,
                "The selected document is no longer available.",
                Toast.LENGTH_LONG,
            ).show()
        } else handleAcceptedNarUserEnqueueResult(accepted)
    }

    private fun handleAcceptedNarUserEnqueueResult(result: NarUserEnqueueResult?) {
        if (result?.acceptedActive == true) onUserDurableWorkAccepted()
    }

    private fun onUserDurableWorkAccepted() {
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        pendingDurableNotificationPermission = true
        requestPendingDurableNotificationPermission()
    }

    private fun requestPendingDurableNotificationPermission() {
        if (!DurableAttentionNotificationPolicy.shouldRequestPermission(
                apiLevel = Build.VERSION.SDK_INT,
                permissionGranted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED,
                userWorkAccepted = pendingDurableNotificationPermission,
                activityResumed = lifecycle.currentState.isAtLeast(
                    androidx.lifecycle.Lifecycle.State.RESUMED,
                ),
            )) {
            return
        }
        pendingDurableNotificationPermission = false
        durableNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun refreshDurableAttention() {
        runCatching {
            SharedDurableOperationSupervisor.attention(applicationContext).refresh()
        }
    }
    private fun showUrlDlg() { simpleDialog = createUrlEntryDialog() }
    fun onMoreGhost() = getMoreGhost()
    private fun showGhostListDlg() {
        val manager = gm ?: return
        simpleDialog = createGhostListDialog(
            manager.getGDispNames()?.map { it ?: "" }.orEmpty(),
            manager.getGnames()?.toList().orEmpty(),
        )
    }
    private fun createMoreGhostDialog() = NanidroidSimpleDialog.MoreGhost(
        onEnterUrl = { showUrlDlg() }, onInstallFromSdCard = { startInstallFromSDCard() },
    )
    private fun createUrlEntryDialog(value: String = "", error: Boolean = false): NanidroidSimpleDialog.UrlEntry = NanidroidSimpleDialog.UrlEntry(
        value, error,
        onValueChanged = { simpleDialog = createUrlEntryDialog(it) },
        onSubmit = { url ->
            if (!PatternHolders.url_ptrn.matcher(url).find() || !isApprovedArchiveUrl(url)) false
            else { addNarToDownload(Uri.parse(url)); true }
        },
        onInvalid = { simpleDialog = createUrlEntryDialog(value, true) },
    )

    private fun isApprovedArchiveUrl(value: String): Boolean = try {
        val parsed = java.net.URI(value)
        parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank()
    } catch (_: Exception) { false }
    private fun allows(action: GuardedAction, origin: ActionOrigin = ActionOrigin.USER): Boolean =
        GhostActionGuard(runner?.runtimeModeSnapshot() ?: return true).allows(action, origin)
    private fun createUserInputDialog(pending: PendingInputState, value: String = ""): NanidroidSimpleDialog.UserInput =
        dialogueDialogBinding.userInput(pending, value, ::updateUserInputValue)
    private fun restoreUserInputDialog(
        id: String,
        restoration: DialogueDialogRestoration?,
        value: String,
    ): NanidroidSimpleDialog.UserInput? =
        dialogueDialogBinding.restoreUserInput(id, restoration, value, ::updateUserInputValue)
    private fun updateUserInputValue(value: String) {
        val dialog = simpleDialog as? NanidroidSimpleDialog.UserInput ?: return
        simpleDialog = dialog.copy(value = value)
    }
    private fun openDialogueInput(input: DialogueSegment.InputBox) {
        val pending = runner?.dialogueStateSnapshot()?.pendingInput ?: return
        if (pending.spec !== input.spec) return
        simpleDialog = createUserInputDialog(pending)
    }
    private fun openDialogueExternalUrl(value: String) {
        val uri = try {
            Uri.parse(value)
        } catch (_: Exception) {
            return
        }
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return
        tryLaunchDialogueExternalUri { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
    private fun isStageInputBlocked(): Boolean =
        loading || simpleDialog != null || runner?.runtimeModeSnapshot()?.pendingUserAction == true
    private fun createUserChoiceDialog(labels: List<String>, ids: List<String>, actions: List<DialogueAction> = emptyList()) =
        dialogueDialogBinding.userChoice(labels, ids, actions)
    private fun restoreUserChoiceDialog(
        labels: List<String>,
        ids: List<String>,
        restoration: DialogueDialogRestoration?,
    ) = dialogueDialogBinding.restoreUserChoice(labels, ids, restoration)
    private fun createGhostListDialog(names: List<String>, ids: List<String>) = NanidroidSimpleDialog.GhostList(
        names, ids,
        onSelect = { index -> selectGhostFromList(ids.getOrNull(index), names.getOrNull(index) ?: "") },
        onMore = { getMoreGhost() },
    )
    private fun createReadmeDialog(readme: File, ghostId: String) = NanidroidSimpleDialog.TextDocument(
        getString(R.string.new_ghost_installed_title), PlainTextDocument.read(readme), ::openDocumentLink, ghostId,
        { switchGhost(ghostId) },
    )
    private fun createNoReadmeDialog(ghostId: String, ghostName: String) = NanidroidSimpleDialog.SwitchConfirmation(
        ghostId, ghostName,
        onSwitch = { switchGhost(ghostId) },
    )
    private fun openDocumentLink(link: String) {
        tryLaunchDocumentExternalUrl(link) { value ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
        }
    }
    private fun selectGhostFromList(id: String?, name: String) {
        val manager = gm ?: return
        val ghostId = id ?: return
        if (manager.getGhostReadMe(ghostId).exists()) showReadme(manager.getGhostReadMe(ghostId), ghostId)
        else simpleDialog = createNoReadmeDialog(ghostId, name)
    }
    private fun saveSimpleDialog(outState: Bundle) {
        when (val dialog = simpleDialog) {
            null -> Unit
            is NanidroidSimpleDialog.Notice -> {
                outState.putString(SIMPLE_DIALOG_TYPE, DIALOG_NOTICE)
                outState.putInt(SIMPLE_DIALOG_TITLE, dialog.title)
                outState.putInt(SIMPLE_DIALOG_MESSAGE, dialog.message)
            }
            is NanidroidSimpleDialog.MoreGhost -> outState.putString(SIMPLE_DIALOG_TYPE, DIALOG_MORE_GHOST)
            is NanidroidSimpleDialog.UrlEntry -> {
                outState.putString(SIMPLE_DIALOG_TYPE, DIALOG_URL_ENTRY)
                outState.putString(SIMPLE_DIALOG_VALUE, dialog.value)
                outState.putBoolean(SIMPLE_DIALOG_ERROR, dialog.validationError)
            }
            is NanidroidSimpleDialog.UserInput -> {
                outState.putString(SIMPLE_DIALOG_TYPE, DIALOG_USER_INPUT)
                outState.putString(SIMPLE_DIALOG_ID, dialog.id)
                outState.putString(SIMPLE_DIALOG_VALUE, dialog.value)
                saveDialogueDialogRestoration(outState, dialog.restoration)
            }
            is NanidroidSimpleDialog.UserChoice -> {
                outState.putString(SIMPLE_DIALOG_TYPE, DIALOG_USER_CHOICE)
                outState.putStringArrayList(SIMPLE_DIALOG_LABELS, ArrayList(dialog.labels))
                outState.putStringArrayList(SIMPLE_DIALOG_IDS, ArrayList(dialog.ids))
                saveDialogueDialogRestoration(outState, dialog.restoration)
            }
            is NanidroidSimpleDialog.GhostList -> {
                outState.putString(SIMPLE_DIALOG_TYPE, DIALOG_GHOST_LIST)
                outState.putStringArrayList(SIMPLE_DIALOG_LABELS, ArrayList(dialog.names))
                outState.putStringArrayList(SIMPLE_DIALOG_IDS, ArrayList(dialog.ids))
            }
            is NanidroidSimpleDialog.ArchiveQueue -> Unit
            is NanidroidSimpleDialog.TextDocument -> {
                val snapshot = dialog.toTextDocumentRestoreSnapshot()
                outState.putString(
                    SIMPLE_DIALOG_TYPE,
                    when (snapshot.kind) {
                        TextDocumentRestoreKind.INSTALLED_GHOST_README -> DIALOG_README
                        TextDocumentRestoreKind.CURRENT_GHOST_README -> DIALOG_CURRENT_GHOST_README
                    },
                )
                outState.writeTextDocumentRestoreSnapshot(snapshot)
            }
            is NanidroidSimpleDialog.SwitchConfirmation -> {
                outState.putString(SIMPLE_DIALOG_TYPE, DIALOG_NO_README)
                outState.putString(SIMPLE_DIALOG_ID, dialog.ghostId)
                outState.putString(SIMPLE_DIALOG_VALUE, dialog.ghostName)
            }
        }
    }
    private fun saveDialogueDialogRestoration(
        outState: Bundle,
        restoration: DialogueDialogRestoration?,
    ) {
        restoration ?: return
        outState.putString(SIMPLE_DIALOG_RESTORATION_OWNER, restoration.owner)
        outState.putLong(SIMPLE_DIALOG_RESTORATION_GENERATION, restoration.generation)
    }
    private fun dialogueDialogRestoration(state: Bundle): DialogueDialogRestoration? {
        val owner = state.getString(SIMPLE_DIALOG_RESTORATION_OWNER) ?: return null
        if (!state.containsKey(SIMPLE_DIALOG_RESTORATION_GENERATION)) return null
        return DialogueDialogRestoration(
            owner,
            state.getLong(SIMPLE_DIALOG_RESTORATION_GENERATION),
        )
    }
    private fun restoreSimpleDialog(state: Bundle?) {
        simpleDialog = when (state?.getString(SIMPLE_DIALOG_TYPE)) {
            DIALOG_NOTICE -> {
                val title = state.getInt(SIMPLE_DIALOG_TITLE)
                val message = state.getInt(SIMPLE_DIALOG_MESSAGE)
                NanidroidSimpleDialog.Notice(
                    title,
                    message,
                    if (finishAfterRestoredNotice(message)) ({ finish() }) else null,
                )
            }
            DIALOG_MORE_GHOST -> createMoreGhostDialog()
            DIALOG_URL_ENTRY -> createUrlEntryDialog(state.getString(SIMPLE_DIALOG_VALUE) ?: "", state.getBoolean(SIMPLE_DIALOG_ERROR, false))
            DIALOG_USER_INPUT -> restoreUserInputDialog(
                state.getString(SIMPLE_DIALOG_ID) ?: "",
                dialogueDialogRestoration(state),
                state.getString(SIMPLE_DIALOG_VALUE) ?: "",
            )
            DIALOG_USER_CHOICE -> restoreUserChoiceDialog(
                state.getStringArrayList(SIMPLE_DIALOG_LABELS)?.toList().orEmpty(),
                state.getStringArrayList(SIMPLE_DIALOG_IDS)?.toList().orEmpty(),
                dialogueDialogRestoration(state),
            )
            DIALOG_GHOST_LIST -> createGhostListDialog(state.getStringArrayList(SIMPLE_DIALOG_LABELS)?.toList().orEmpty(), state.getStringArrayList(SIMPLE_DIALOG_IDS)?.toList().orEmpty())
            DIALOG_README -> {
                val snapshot = state.readTextDocumentRestoreSnapshot()
                val ghostId = snapshot?.sourceId ?: state.getString(SIMPLE_DIALOG_ID).orEmpty()
                NanidroidSimpleDialog.TextDocument(
                    snapshot?.title ?: getString(R.string.new_ghost_installed_title),
                    snapshot?.text ?: state.getString(SIMPLE_DIALOG_VALUE).orEmpty(),
                    ::openDocumentLink,
                    ghostId,
                    { switchGhost(ghostId) },
                )
            }
            DIALOG_CURRENT_GHOST_README -> state.readTextDocumentRestoreSnapshot()
                ?.takeIf {
                    it.kind == TextDocumentRestoreKind.CURRENT_GHOST_README &&
                        !it.sourceId.isNullOrBlank()
                }
                ?.let { snapshot ->
                    val ghostId = snapshot.sourceId ?: return@let null
                    NanidroidSimpleDialog.TextDocument(
                        snapshot.title,
                        snapshot.text,
                        ::openDocumentLink,
                        ghostId,
                    )
                }
            DIALOG_NO_README -> createNoReadmeDialog(state.getString(SIMPLE_DIALOG_ID).orEmpty(), state.getString(SIMPLE_DIALOG_VALUE).orEmpty())
            else -> null
        }
    }
    fun frameClick() {
        toolbarVisible = !toolbarVisible
    }
    override fun showUserInputBox(id: String) {
        // Publication pauses the script. The exact owning bubble opens the
        // dialog, so this legacy callback must not create a second host.
    }
    override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) {
        // The Compose stage observes the same runtime-owned action instances
        // and exposes the owning bubble's reopenable Choose action. Keeping
        // this callback side-effect free prevents the legacy dialog from
        // racing it or consuming a pending choice on host recreation.
    }

    companion object {
        @Volatile
        private var lifecycleTestHooks = NanidroidLifecycleTestHooks()

        internal fun replaceLifecycleTestHooksForTesting(replacement: NanidroidLifecycleTestHooks) {
            lifecycleTestHooks = replacement
        }

        internal fun resetLifecycleTestHooksForTesting() {
            lifecycleTestHooks = NanidroidLifecycleTestHooks()
        }

        private const val TAG = "Nanidroid"
        private const val NAR_CONSUMED_INTENT_URI = "consumed_archive_intent_uri"
        private const val NAR_PENDING_INTENT_URI = "pending_archive_intent_uri"
        private const val NAR_PENDING_INTENT_FLAGS = "pending_archive_intent_flags"
        private const val PREF_KEY_LAUNCH_TIME = "keylaunchtime"
        private const val MIN_TAG = "minimized"
        private const val MSG_START = 2019
        private const val MSG_LOAD_F = 2020
        private const val MSG_LOAD_N = 2021
        private const val SIMPLE_DIALOG_TYPE = "simple_dialog_type"
        private const val SIMPLE_DIALOG_TITLE = "simple_dialog_title"
        private const val SIMPLE_DIALOG_MESSAGE = "simple_dialog_message"
        private const val SIMPLE_DIALOG_VALUE = "simple_dialog_value"
        private const val SIMPLE_DIALOG_ERROR = "simple_dialog_error"
        private const val SIMPLE_DIALOG_ID = "simple_dialog_id"
        private const val SIMPLE_DIALOG_LABELS = "simple_dialog_labels"
        private const val SIMPLE_DIALOG_IDS = "simple_dialog_ids"
        private const val SIMPLE_DIALOG_RESTORATION_OWNER = "simple_dialog_restoration_owner"
        private const val SIMPLE_DIALOG_RESTORATION_GENERATION = "simple_dialog_restoration_generation"
        private const val DIALOG_NOTICE = "notice"
        private const val DIALOG_MORE_GHOST = "more_ghost"
        private const val DIALOG_URL_ENTRY = "url_entry"
        private const val DIALOG_USER_INPUT = "user_input"
        private const val DIALOG_USER_CHOICE = "user_choice"
        private const val DIALOG_GHOST_LIST = "ghost_list"
        private const val DIALOG_README = "readme"
        private const val DIALOG_CURRENT_GHOST_README = "current_ghost_readme"
        private const val DIALOG_NO_README = "no_readme"
    }
}
