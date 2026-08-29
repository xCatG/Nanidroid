package com.cattailsw.nanidroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.cattailsw.nanidroid.compose.NanidroidComposeShell
import com.cattailsw.nanidroid.compose.NanidroidSimpleDialog
import com.cattailsw.nanidroid.compose.ComposeGhostStageHost
import com.cattailsw.nanidroid.compose.PlainTextDocument
import com.cattailsw.nanidroid.compose.SurfaceInteractionPort
import com.cattailsw.nanidroid.util.PrefUtil
import com.cattailsw.nanidroid.install.ForegroundNarImportCoordinator
import com.cattailsw.nanidroid.install.ForegroundNarImportState
import com.cattailsw.nanidroid.install.NarDocumentSelection
import com.cattailsw.nanidroid.install.NarImportAttemptToken
import com.cattailsw.nanidroid.install.NarImportPrimaryOutcome
import com.cattailsw.nanidroid.runtime.dialogue.ActionOrigin
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.dialogue.GhostActionGuard
import com.cattailsw.nanidroid.runtime.dialogue.GuardedAction
import com.cattailsw.nanidroid.runtime.dialogue.PendingInputState
import com.cattailsw.nanidroid.runtime.RuntimeCatalogPublicationStatus
import com.cattailsw.nanidroid.runtime.RuntimeCatalogState
import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeHostLease
import com.cattailsw.nanidroid.runtime.RuntimeSnapshot
import com.cattailsw.nanidroid.runtime.dialogue.DialogueActionKey
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeInputAction
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun launchCandidateIds(preferred: String?, available: List<String>): List<String> {
    val installedPreferred = preferred?.let { requested ->
        available.firstOrNull { it.equals(requested, ignoreCase = true) }
    }
    val bundled = available.firstOrNull { it.equals("nanidroid", ignoreCase = true) }
    return listOfNotNull(installedPreferred, bundled) + available.filterNot { candidate ->
        candidate.equals(installedPreferred, ignoreCase = true) || candidate.equals(bundled, ignoreCase = true)
    }
}

internal fun ghostSelectionCommand(
    snapshot: RuntimeSnapshot,
    lease: RuntimeHostLease,
    targetId: String,
): RuntimeCommand? {
    if (snapshot.exit != null || snapshot.modeIdentity.parentOperationId != null) return null
    val target = snapshot.catalog.lastProvenEntries.firstOrNull {
        it.id.equals(targetId, ignoreCase = true)
    } ?: return null
    val activeGeneration = snapshot.generation
    return if (activeGeneration == null) {
        RuntimeCommand.StartGhost(target.id, File(target.canonicalRootPath))
            .takeIf { snapshot.phase == GhostRuntimePhase.Idle }
    } else {
        RuntimeCommand.SwitchGhost(activeGeneration, lease, snapshot.modeIdentity, target.id)
    }
}

internal fun userActionAllowed(snapshot: RuntimeSnapshot, action: GuardedAction): Boolean =
    (snapshot.phase == GhostRuntimePhase.Poisoned && action == GuardedAction.EXIT) ||
        GhostActionGuard(snapshot.mode).allows(action, ActionOrigin.USER)

internal fun finishAfterRestoredNotice(message: Int): Boolean = message in setOf(
    R.string.err_no_sdcard,
    R.string.err_no_ghost_available,
)

internal fun switchProgressVisibleFor(phase: GhostRuntimePhase): Boolean =
    phase == GhostRuntimePhase.Replacing

internal fun runtimeBusy(snapshot: RuntimeSnapshot): Boolean =
    snapshot.catalog is RuntimeCatalogState.Loading || snapshot.phase in setOf(
        GhostRuntimePhase.Starting,
        GhostRuntimePhase.Attaching,
        GhostRuntimePhase.Replacing,
    )

internal fun installedGhostListDialog(
    snapshot: RuntimeSnapshot,
    onSelect: (com.cattailsw.nanidroid.runtime.RuntimeGhostMetadata) -> Unit,
    onMore: () -> Unit,
): NanidroidSimpleDialog.GhostList {
    val entries = snapshot.catalog.lastProvenEntries
    return NanidroidSimpleDialog.GhostList(
        names = entries.map { it.name ?: it.id },
        ids = entries.map { it.id },
        onSelect = { index -> entries.getOrNull(index)?.let(onSelect) },
        onMore = onMore,
    )
}

internal fun installedGhostDialog(
    snapshot: RuntimeSnapshot,
    metadata: com.cattailsw.nanidroid.runtime.RuntimeGhostMetadata,
    requestSwitch: (String) -> Unit,
    openDocumentLink: (String) -> Unit,
): NanidroidSimpleDialog {
    val switch = { requestSwitch(metadata.id) }
    val readme = File(metadata.readmePath)
    return if (readme.exists()) {
        NanidroidSimpleDialog.TextDocument(
            metadata.name ?: metadata.id,
            PlainTextDocument.read(readme),
            openDocumentLink,
            metadata.id,
            switch.takeUnless { metadata.id == snapshot.activeGhostId },
        )
    } else {
        NanidroidSimpleDialog.SwitchConfirmation(metadata.id, metadata.name ?: metadata.id, switch)
    }
}

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

internal data class ForegroundCatalogRecovery(
    val importToken: NarImportAttemptToken,
    val publicationToken: com.cattailsw.nanidroid.runtime.CatalogPublicationToken,
    val failedEpoch: Long,
)

internal fun foregroundCatalogRecovery(
    state: ForegroundNarImportState,
    snapshot: RuntimeSnapshot,
): ForegroundCatalogRecovery? {
    val importToken = foregroundCatalogPublication(state)?.first ?: return null
    val publicationToken = foregroundPublicationToken(importToken)
    val recovery = snapshot.catalog.publications[publicationToken]
        as? RuntimeCatalogPublicationStatus.RecoveryRequired
        ?: return null
    return ForegroundCatalogRecovery(importToken, publicationToken, recovery.failedEpoch)
}

internal fun foregroundCatalogRetryCommand(recovery: ForegroundCatalogRecovery): RuntimeCommand =
    RuntimeCommand.RetryCatalog(recovery.publicationToken, recovery.failedEpoch)

internal fun installedReadyToken(
    state: ForegroundNarImportState,
    snapshot: RuntimeSnapshot,
): NarImportAttemptToken? {
    val installed = state as? ForegroundNarImportState.Installed ?: return null
    return installed.token.takeIf {
        snapshot.catalog.publications[foregroundPublicationToken(installed.token)] is
            RuntimeCatalogPublicationStatus.Ready
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
    putInt(NAR_PICKER_OWNER_TASK_ID, token.ownerTaskId)
}

internal fun Bundle.readNarPickerOwnerToken(): NarImportAttemptToken? = runCatching {
    val processNonce = getString(NAR_PICKER_OWNER_PROCESS_NONCE)
        ?.takeIf(String::isNotBlank)
        ?: return null
    if (!containsKey(NAR_PICKER_OWNER_SEQUENCE)) return null
    val sequence = getLong(NAR_PICKER_OWNER_SEQUENCE).takeIf { it > 0L } ?: return null
    if (!containsKey(NAR_PICKER_OWNER_TASK_ID)) return null
    val ownerTaskId = getInt(NAR_PICKER_OWNER_TASK_ID).takeIf { it >= 0 } ?: return null
    NarImportAttemptToken(processNonce, sequence, ownerTaskId)
}.getOrNull()

internal fun reconcileNarPickerOwner(
    restored: NarImportAttemptToken?,
    state: ForegroundNarImportState,
): NarImportAttemptToken? {
    val awaiting = state as? ForegroundNarImportState.AwaitingSelection ?: return null
    if (restored == awaiting.token) return restored
    return null
}

internal fun abandonNarPickerOwnerOnFinalDestroy(
    owner: NarImportAttemptToken?,
    isFinishing: Boolean,
    isChangingConfigurations: Boolean,
    abandon: (NarImportAttemptToken) -> Boolean,
) {
    if (owner != null && isFinishing && !isChangingConfigurations) {
        abandon(owner)
    }
}

internal fun armAndLaunchNarDocumentPicker(
    coordinator: ForegroundNarImportCoordinator,
    ownerTaskId: Int,
    currentOwner: () -> NarImportAttemptToken?,
    setOwner: (NarImportAttemptToken?) -> Unit,
    launch: () -> Unit,
    failureMessage: String,
    actionAllowed: () -> Boolean = { true },
): Boolean {
    if (!actionAllowed()) return false
    val token = claimNarPickerAttempt(
        coordinator = coordinator,
        ownerTaskId = ownerTaskId,
        currentOwner = currentOwner(),
    ) ?: return false
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

private fun claimNarPickerAttempt(
    coordinator: ForegroundNarImportCoordinator,
    ownerTaskId: Int,
    currentOwner: NarImportAttemptToken?,
): NarImportAttemptToken? {
    coordinator.armPicker(ownerTaskId)?.let { return it }
    val awaiting = coordinator.state.value as? ForegroundNarImportState.AwaitingSelection ?: return null
    if (currentOwner == awaiting.token) return null
    if (awaiting.token.ownerTaskId != ownerTaskId) return null
    coordinator.abandonPicker(awaiting.token)
    return coordinator.armPicker(ownerTaskId)
}

internal fun dispatchNarPickerResult(
    actionAllowed: () -> Boolean = { true },
    takeOwner: () -> NarImportAttemptToken?,
    selection: () -> NarDocumentSelection?,
    importAllowed: () -> Boolean,
    consume: (NarImportAttemptToken, NarDocumentSelection?, Boolean) -> Boolean,
): Boolean {
    if (!actionAllowed()) return false
    val expectedToken = takeOwner() ?: return false
    return consume(expectedToken, selection(), importAllowed())
}

internal fun handleNarPickerResult(
    actionAllowed: () -> Boolean,
    takeOwner: () -> NarImportAttemptToken?,
    abandon: (NarImportAttemptToken) -> Boolean,
    selection: () -> NarDocumentSelection?,
    importAllowed: () -> Boolean,
    consume: (NarImportAttemptToken, NarDocumentSelection?, Boolean) -> Boolean,
): Boolean {
    val allowed = actionAllowed()
    if (!allowed) {
        takeOwner()?.let(abandon)
        return false
    }
    return dispatchNarPickerResult(
        actionAllowed = { allowed },
        takeOwner = takeOwner,
        selection = selection,
        importAllowed = importAllowed,
        consume = consume,
    )
}

private const val TRANSIENT_UI_PRESENT = "transient_ui_present"
private const val TRANSIENT_TOOLBAR_VISIBLE = "transient_toolbar_visible"
private const val NAR_PICKER_OWNER_PROCESS_NONCE = "nar_picker_owner_process_nonce"
private const val NAR_PICKER_OWNER_SEQUENCE = "nar_picker_owner_sequence"
private const val NAR_PICKER_OWNER_TASK_ID = "nar_picker_owner_task_id"
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

/** Activity adapter for the application-owned snapshot runtime. */
class Nanidroid : ComponentActivity() {
    private val snapshotState = mutableStateOf(RuntimeSnapshot.initial())
    private val hostLeaseState = mutableStateOf<RuntimeHostLease?>(null)
    private val simpleDialogState = mutableStateOf<NanidroidSimpleDialog?>(null)
    private var toolbarVisible by mutableStateOf(true)
    private var hostEpoch = 0L
    private var restoredPickerOwner: NarImportAttemptToken? = null
    private var inputDraft: InputDraft? = null
    private var pendingRestoredInputDraft: InputDraft? = null
    private var deliveredExitOperationId: Long? = null
    private val lifecycleTrace = Collections.synchronizedList(mutableListOf<String>())
    private val shownCatalogRecoveries = mutableSetOf<Pair<com.cattailsw.nanidroid.runtime.CatalogPublicationToken, Long>>()
    private val handledStartupExhaustions = mutableSetOf<Long>()
    private val startupExhaustionEpochState = mutableStateOf<Long?>(null)

    private lateinit var runtime: GhostRuntime
    private lateinit var applicationOwner: CatTailApplication
    private lateinit var foregroundNarImport: ForegroundNarImportCoordinator
    private lateinit var dialogueBinding: DialogueDialogBinding
    private val composeStage = ComposeGhostStageHost()

    private val narPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        handleNarPickerResult(
            actionAllowed = { userActionAllowed(GuardedAction.IMPORT_INSTALL) },
            takeOwner = { restoredPickerOwner.also { restoredPickerOwner = null } },
            abandon = foregroundNarImport::abandonPicker,
            selection = { uri?.toNarSelection() },
            importAllowed = { getExternalFilesDir(null) != null },
            consume = foregroundNarImport::consumePickerResult,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applicationOwner = application as CatTailApplication
        runtime = applicationOwner.ghostRuntime
        foregroundNarImport = applicationOwner.foregroundNarImport
        dialogueBinding = DialogueDialogBinding(
            currentSnapshot = { snapshotState.value },
            currentHost = { hostLeaseState.value },
            submit = runtime::submit,
        )

        val restoredUi = savedInstanceState?.readTransientUiSnapshot()
        toolbarVisible = restoredUi?.toolbarVisible ?: true
        restoredPickerOwner = reconcileNarPickerOwner(
            savedInstanceState?.readNarPickerOwnerToken(),
            foregroundNarImport.state.value,
        )
        pendingRestoredInputDraft = savedInstanceState?.readInputDraft()
        savedInstanceState?.readTextDocumentRestoreSnapshot()?.let(::restoreTextDocument)

        val initialLease = RuntimeHostLease(applicationOwner.allocateRuntimeHostId(), ++hostEpoch)
        hostLeaseState.value = initialLease
        runtime.submit(RuntimeCommand.RegisterHost(initialLease))

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    lifecycleTrace += "back"
                    if (!userActionAllowed(GuardedAction.EXIT)) return
                    val snapshot = runtime.snapshots.value
                    val lease = hostLeaseState.value ?: return
                    runtime.submit(RuntimeCommand.Back(snapshot.generation, lease, snapshot.modeIdentity))
                }
            },
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runtime.snapshots.collect { snapshot ->
                    snapshotState.value = snapshot
                    reconcileSnapshot(snapshot)
                }
            }
        }

        setContent {
            val snapshot by snapshotState
            val hostLease by hostLeaseState
            val simpleDialog by simpleDialogState
            val importState by foregroundNarImport.state.collectAsState()
            val bundledInstallState by applicationOwner.bundledInstallWorkflow.state.collectAsState()
            val lease = hostLease
            NanidroidComposeShell(
                ghostStage = {
                    if (lease != null) {
                        composeStage.Stage(
                            snapshot = snapshot,
                            hostLease = lease,
                            submitCommand = runtime::submit,
                            modifier = Modifier.fillMaxSize(),
                            blockingInput = { simpleDialogState.value != null || runtimeBusy(snapshotState.value) },
                            onSurfaceTap = { toolbarVisible = !toolbarVisible },
                            onDialogueExternalUrl = ::openDocumentLink,
                            onDialogueInputDraft = ::showInput,
                        )
                    } else {
                        Box(Modifier.fillMaxSize())
                    }
                },
                loading = runtimeBusy(snapshot),
                progressMessage = getString(R.string.load_g, snapshot.activeGhostId ?: "Nanidroid"),
                toolbarVisible = toolbarVisible,
                onListGhost = ::showGhostList,
                onReadme = ::openCurrentGhostReadme,
                narImportState = importState,
                installedReadyToken = installedReadyToken(importState, snapshot),
                foregroundCatalogRecovery = foregroundCatalogRecovery(importState, snapshot),
                onAcknowledgeNarImport = foregroundNarImport::acknowledge,
                onSelectAnotherNarImport = {
                    foregroundNarImport.acknowledge(it)
                    launchNarPicker()
                },
                onRetryNarImportCleanup = foregroundNarImport::retryCleanup,
                onRetryForegroundCatalog = { recovery ->
                    runtime.submit(foregroundCatalogRetryCommand(recovery))
                },
                bundledInstallState = bundledInstallState,
                onRetryBundledInstall = applicationOwner::retryBundledInstall,
                startupExhaustedEpoch = startupExhaustionEpochState.value,
                onRecoverStartup = ::openStartupRecovery,
                simpleDialog = simpleDialog,
                onDismissSimpleDialog = { simpleDialogState.value = null },
                wallpaper = null,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        submitHostCommand { RuntimeCommand.SetResumed(it, true) }
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        lifecycleTrace += if (isTopResumedActivity) "topResumedTrue" else "topResumedFalse"
        submitHostCommand { RuntimeCommand.SetTopResumed(it, isTopResumedActivity) }
    }

    override fun onPause() {
        lifecycleTrace += "onPause"
        submitHostCommand { RuntimeCommand.SetResumed(it, false) }
        super.onPause()
    }

    override fun onStop() {
        lifecycleTrace += "onStop"
        super.onStop()
    }

    override fun onDestroy() {
        val lease = nextHostLease()
        runtime.submit(RuntimeCommand.UnregisterHost(lease))
        abandonNarPickerOwnerOnFinalDestroy(
            restoredPickerOwner,
            isFinishing,
            isChangingConfigurations,
            foregroundNarImport::abandonPicker,
        )
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.writeTransientUiSnapshot(TransientUiSnapshot(toolbarVisible))
        restoredPickerOwner?.let(outState::writeNarPickerOwnerToken)
        inputDraft
            ?.takeIf { snapshotState.value.dialogue.input?.key == it.key }
            ?.let { outState.writeInputDraft(it) }
        (simpleDialogState.value as? NanidroidSimpleDialog.TextDocument)
            ?.toTextDocumentRestoreSnapshot()
            ?.let(outState::writeTextDocumentRestoreSnapshot)
    }

    private fun submitHostCommand(command: (RuntimeHostLease) -> RuntimeCommand) {
        val lease = nextHostLease()
        runtime.submit(command(lease))
    }

    private fun nextHostLease(): RuntimeHostLease {
        val hostId = requireNotNull(hostLeaseState.value).hostId
        return RuntimeHostLease(hostId, ++hostEpoch).also { hostLeaseState.value = it }
    }

    private fun reconcileSnapshot(snapshot: RuntimeSnapshot) {
        maybeStartGhost(snapshot)
        reconcileStartupExhaustion(snapshot)
        showCatalogRecovery(snapshot)
        reconcileInputDraft(snapshot)
        deliverExit(snapshot)
    }

    private fun showCatalogRecovery(snapshot: RuntimeSnapshot) {
        val recovery = snapshot.catalog.publications.entries.firstNotNullOfOrNull { (token, status) ->
            (status as? RuntimeCatalogPublicationStatus.RecoveryRequired)?.let { token to it }
        } ?: return
        if (
            foregroundCatalogRecovery(foregroundNarImport.state.value, snapshot)?.publicationToken == recovery.first
        ) return
        val key = recovery.first to recovery.second.failedEpoch
        if (!shownCatalogRecoveries.add(key)) return
        simpleDialogState.value = NanidroidSimpleDialog.Notice(
            R.string.err_no_ghost_available,
            R.string.err_no_ghost_available,
        ) {
            runtime.submit(RuntimeCommand.RetryCatalog(recovery.first, recovery.second.failedEpoch))
        }
    }

    private fun reconcileStartupExhaustion(snapshot: RuntimeSnapshot) {
        val ready = snapshot.catalog as? RuntimeCatalogState.Ready
        val exhaustedEpoch = ready?.let { applicationOwner.startupCandidateAttempts.exhaustedEpoch(it.epoch) }
        startupExhaustionEpochState.value = exhaustedEpoch?.takeUnless(handledStartupExhaustions::contains)
    }

    private fun openStartupRecovery(epoch: Long) {
        if (startupExhaustionEpochState.value != epoch) return
        handledStartupExhaustions += epoch
        startupExhaustionEpochState.value = null
        simpleDialogState.value = NanidroidSimpleDialog.MoreGhost(::launchNarPicker)
    }

    private fun maybeStartGhost(snapshot: RuntimeSnapshot) {
        val ready = snapshot.catalog as? RuntimeCatalogState.Ready ?: return
        if (ready.entries.isEmpty()) return
        val attempts = applicationOwner.startupCandidateAttempts
        if (attempts.reserve(ready.epoch)) {
            lifecycleScope.launch {
                val preferred = withContext(Dispatchers.IO) { runtime.preferredGhostId() }
                val latest = snapshotState.value
                val latestReady = latest.catalog as? RuntimeCatalogState.Ready ?: return@launch
                if (latestReady.epoch != ready.epoch) return@launch
                attempts.configure(
                    ready.epoch,
                    launchCandidateIds(preferred, latestReady.entries.map { it.id }),
                )
                maybeStartGhost(latest)
            }
        }
        val candidateId = attempts.nextCandidate(
            ready.epoch,
            snapshot.phase,
            snapshot.generation,
            snapshot.modeIdentity.parentOperationId,
            snapshot.exit != null,
            snapshot.revision,
            snapshot.notice,
        ) ?: return
        val candidate = ready.entries.firstOrNull { it.id.equals(candidateId, ignoreCase = true) } ?: return
        runtime.submit(RuntimeCommand.StartGhost(candidate.id, File(candidate.canonicalRootPath)))
    }

    private fun reconcileInputDraft(snapshot: RuntimeSnapshot) {
        val current = snapshot.dialogue.input
        val restored = pendingRestoredInputDraft
        if (restored != null) {
            pendingRestoredInputDraft = null
            if (current?.key == restored.key) showInput(current, restored.value)
        }
        val draft = inputDraft ?: return
        if (current?.key != draft.key) {
            inputDraft = null
            if ((simpleDialogState.value as? NanidroidSimpleDialog.UserInput)?.restoration?.key == draft.key) {
                simpleDialogState.value = null
            }
        }
    }

    private fun deliverExit(snapshot: RuntimeSnapshot) {
        val offered = snapshot.exit?.offeredLease ?: return
        val currentLease = hostLeaseState.value ?: return
        if (offered.hostLease != currentLease || deliveredExitOperationId == offered.operationId) return
        deliveredExitOperationId = offered.operationId
        lifecycleTrace += "claim"
        runtime.submit(RuntimeCommand.ClaimExit(offered))
        try {
            lifecycleTrace += "finish"
            finish()
        } finally {
            lifecycleTrace += "acknowledge"
            runtime.submit(RuntimeCommand.AcknowledgeExit(offered))
        }
    }

    private fun showInput(action: RuntimeInputAction, value: String = action.pending.spec.initialText) {
        inputDraft = InputDraft(action.key, value)
        simpleDialogState.value = dialogueBinding.userInput(action, value) { updated ->
            showInput(action, updated)
        }
    }

    private fun showGhostList() {
        if (!userActionAllowed(GuardedAction.SWITCH_GHOST)) return
        val snapshot = runtime.snapshots.value
        val failed = snapshot.catalog as? RuntimeCatalogState.Failed
        if (failed != null) {
            simpleDialogState.value = NanidroidSimpleDialog.Notice(
                R.string.err_no_ghost_available,
                R.string.err_no_ghost_available,
            ) {
                runtime.submit(RuntimeCommand.RetryCatalog(null, failed.epoch))
            }
            return
        }
        simpleDialogState.value = installedGhostListDialog(
            snapshot = snapshot,
            onSelect = ::showInstalledGhost,
            onMore = { simpleDialogState.value = NanidroidSimpleDialog.MoreGhost(::launchNarPicker) },
        )
    }

    private fun showInstalledGhost(metadata: com.cattailsw.nanidroid.runtime.RuntimeGhostMetadata) {
        simpleDialogState.value = installedGhostDialog(
            snapshot = snapshotState.value,
            metadata = metadata,
            requestSwitch = ::requestSwitch,
            openDocumentLink = ::openDocumentLink,
        )
    }

    private fun requestSwitch(targetId: String) {
        if (!userActionAllowed(GuardedAction.SWITCH_GHOST)) return
        val snapshot = runtime.snapshots.value
        val lease = hostLeaseState.value ?: return
        ghostSelectionCommand(snapshot, lease, targetId)?.let(runtime::submit)
    }

    private fun openCurrentGhostReadme() {
        val snapshot = snapshotState.value
        val activeId = snapshot.activeGhostId ?: return
        val metadata = snapshot.catalog.lastProvenEntries.firstOrNull { it.id == activeId }
        val readme = metadata?.let { File(it.readmePath) }
        simpleDialogState.value = if (readme?.exists() == true) {
            NanidroidSimpleDialog.TextDocument(
                getString(R.string.readme_menu_text),
                PlainTextDocument.read(readme),
                ::openDocumentLink,
                activeId,
            )
        } else {
            NanidroidSimpleDialog.Notice(
                R.string.current_ghost_no_readme_title,
                R.string.current_ghost_no_readme_message,
            )
        }
    }

    private fun restoreTextDocument(restored: TextDocumentRestoreSnapshot) {
        simpleDialogState.value = NanidroidSimpleDialog.TextDocument(
            restored.title,
            restored.text,
            ::openDocumentLink,
            restored.sourceId.orEmpty(),
            restored.sourceId
                ?.takeIf { restored.kind == TextDocumentRestoreKind.INSTALLED_GHOST_README }
                ?.let { id -> ({ requestSwitch(id) }) },
        )
    }

    private fun launchNarPicker() {
        if (!userActionAllowed(GuardedAction.IMPORT_INSTALL)) return
        armAndLaunchNarDocumentPicker(
            coordinator = foregroundNarImport,
            ownerTaskId = taskId,
            currentOwner = { restoredPickerOwner },
            setOwner = { restoredPickerOwner = it },
            launch = { narPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
            failureMessage = getString(R.string.err_no_sdcard),
            actionAllowed = { userActionAllowed(GuardedAction.IMPORT_INSTALL) },
        )
    }

    private fun userActionAllowed(action: GuardedAction): Boolean =
        userActionAllowed(runtime.snapshots.value, action)

    private fun openDocumentLink(value: String) {
        tryLaunchDocumentExternalUrl(value) { url ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    internal fun snapshotForTesting(): RuntimeSnapshot = snapshotState.value
    internal fun hostLeaseForTesting(): RuntimeHostLease? = hostLeaseState.value
    internal fun lifecycleTraceForTesting(): List<String> = synchronized(lifecycleTrace) { lifecycleTrace.toList() }

    private data class InputDraft(val key: DialogueActionKey, val value: String)

    private fun Bundle.writeInputDraft(draft: InputDraft) {
        putLong(INPUT_DRAFT_GENERATION, draft.key.generation)
        putLong(INPUT_DRAFT_INCARNATION, draft.key.incarnation)
        putLong(INPUT_DRAFT_ACTION, draft.key.actionId)
        putString(INPUT_DRAFT_VALUE, draft.value)
    }

    private fun Bundle.readInputDraft(): InputDraft? {
        if (!containsKey(INPUT_DRAFT_GENERATION) || !containsKey(INPUT_DRAFT_INCARNATION) ||
            !containsKey(INPUT_DRAFT_ACTION)
        ) return null
        return InputDraft(
            DialogueActionKey(
                getLong(INPUT_DRAFT_GENERATION),
                getLong(INPUT_DRAFT_INCARNATION),
                getLong(INPUT_DRAFT_ACTION),
            ),
            getString(INPUT_DRAFT_VALUE).orEmpty(),
        )
    }

    private fun Uri.toNarSelection() = NarDocumentSelection(toString(), scheme)

    private companion object {
        const val INPUT_DRAFT_GENERATION = "input_draft_generation"
        const val INPUT_DRAFT_INCARNATION = "input_draft_incarnation"
        const val INPUT_DRAFT_ACTION = "input_draft_action"
        const val INPUT_DRAFT_VALUE = "input_draft_value"
    }
}
