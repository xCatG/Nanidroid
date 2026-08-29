package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.GhostRuntimePhase
import com.cattailsw.nanidroid.InstalledGhostMetadata
import com.cattailsw.nanidroid.PreparedGhost
import com.cattailsw.nanidroid.RuntimeResult
import com.cattailsw.nanidroid.SurfaceCatalog
import com.cattailsw.nanidroid.TaggedShioriResponse
import com.cattailsw.nanidroid.runtime.dialogue.AnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueActionKey
import com.cattailsw.nanidroid.runtime.dialogue.DialogueContent
import com.cattailsw.nanidroid.runtime.dialogue.DialogueRuntimeState
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.dialogue.GhostRuntimeMode
import com.cattailsw.nanidroid.runtime.dialogue.InputBoxSpec
import com.cattailsw.nanidroid.runtime.dialogue.InputDispatch
import com.cattailsw.nanidroid.runtime.dialogue.PendingInputState
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeAnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeChoiceAction
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeInputAction
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

enum class GhostSpeaker { SAKURA, KERO }

@JvmInline
internal value class RuntimeHostId(val value: Long)

internal data class RuntimeHostLease(val hostId: RuntimeHostId, val hostEpoch: Long)

internal data class RuntimeGhostMetadata(
    val id: String,
    val canonicalRootPath: String,
    val name: String?,
    val sakuraName: String?,
    val readmePath: String,
) {
    companion object {
        fun from(metadata: InstalledGhostMetadata): RuntimeGhostMetadata = RuntimeGhostMetadata(
            id = metadata.id,
            canonicalRootPath = metadata.canonicalRoot.path,
            name = metadata.name,
            sakuraName = metadata.sakuraName,
            readmePath = metadata.readme.path,
        )
    }
}

internal data class RuntimePendingGhostIdentity(
    val operationId: Long,
    val ghostId: String,
    val canonicalRootPath: String,
)

internal data class RuntimeSpeakerPresentation(
    val text: String,
    val surfaceId: String,
    val surfaceEpoch: Long,
    val balloonVisible: Boolean,
)

internal data class RuntimeSurfaceIdentity(
    val generation: Long,
    val speaker: GhostSpeaker,
    val surfaceId: String,
    val surfaceEpoch: Long,
)

internal data class RuntimePresentation(
    val sakura: RuntimeSpeakerPresentation,
    val kero: RuntimeSpeakerPresentation,
    val talkingAnimationEnabled: Boolean,
)

internal enum class RuntimeCueKind { TALKING, ONE_SHOT }

internal data class RuntimePresentationCue(
    val cueId: Long,
    val generation: Long,
    val hostLease: RuntimeHostLease,
    val speaker: GhostSpeaker,
    val kind: RuntimeCueKind,
    val animationId: String?,
)

internal enum class RuntimeNoticeCode {
    CATALOG_SCAN_FAILED,
    CATALOG_TARGET_MISSING,
    PREPARATION_FAILED,
    NATIVE_LOAD_FAILED,
    NATIVE_UNLOAD_FAILED,
    NATIVE_OWNERSHIP_UNCERTAIN,
    REQUEST_FAILED,
    PLAYER_FAILED,
    RUNTIME_POISONED,
}

internal data class RuntimeNotice(val operationId: Long, val code: RuntimeNoticeCode)

internal sealed interface RuntimeCatalogState {
    val epoch: Long
    val lastProvenEntries: List<RuntimeGhostMetadata>
    val publications: Map<CatalogPublicationToken, RuntimeCatalogPublicationStatus>

    data class Loading(
        override val epoch: Long,
        override val lastProvenEntries: List<RuntimeGhostMetadata>,
        override val publications: Map<CatalogPublicationToken, RuntimeCatalogPublicationStatus>,
    ) : RuntimeCatalogState

    data class Ready(
        override val epoch: Long,
        val entries: List<RuntimeGhostMetadata>,
        override val publications: Map<CatalogPublicationToken, RuntimeCatalogPublicationStatus>,
    ) : RuntimeCatalogState {
        override val lastProvenEntries: List<RuntimeGhostMetadata> = entries
    }

    data class Failed(
        override val epoch: Long,
        override val lastProvenEntries: List<RuntimeGhostMetadata>,
        override val publications: Map<CatalogPublicationToken, RuntimeCatalogPublicationStatus>,
        val reason: RuntimeNoticeCode,
    ) : RuntimeCatalogState
}

internal sealed interface RuntimeCatalogPublicationStatus {
    val targetId: String

    data class Pending(
        override val targetId: String,
        val requestedEpoch: Long,
    ) : RuntimeCatalogPublicationStatus

    data class Ready(
        override val targetId: String,
        val provenEpoch: Long,
    ) : RuntimeCatalogPublicationStatus

    data class RecoveryRequired(
        override val targetId: String,
        val failedEpoch: Long,
        val reason: RuntimeNoticeCode,
    ) : RuntimeCatalogPublicationStatus
}

internal data class RuntimeExitLease(
    val operationId: Long,
    val leaseId: Long,
    val generation: Long?,
    val hostLease: RuntimeHostLease,
)

internal data class RuntimeExitSnapshot(
    val operationId: Long,
    val generation: Long?,
    /** Present only while this unclaimed lease is offered to its exact host. */
    val offeredLease: RuntimeExitLease?,
)

internal data class RuntimeDialogueSnapshot(
    val state: DialogueRuntimeState,
    val choices: List<RuntimeChoiceAction>,
    val anchors: List<RuntimeAnchorAction>,
    val input: RuntimeInputAction?,
)

internal data class RuntimeSnapshot(
    val revision: Long,
    val generation: Long?,
    val phase: GhostRuntimePhase,
    val activeGhostId: String?,
    val activeSurfaces: SurfaceCatalog?,
    val pending: RuntimePendingGhostIdentity?,
    val catalog: RuntimeCatalogState,
    val presentation: RuntimePresentation,
    val cues: List<RuntimePresentationCue>,
    val dialogue: RuntimeDialogueSnapshot,
    val mode: GhostRuntimeMode,
    val modeIdentity: RuntimeModeIdentity,
    val clockRunning: Boolean,
    val foregroundHost: RuntimeHostLease?,
    val exit: RuntimeExitSnapshot?,
    val notice: RuntimeNotice?,
) {
    companion object {
        fun initial(): RuntimeSnapshot = RuntimeSnapshot(
            revision = 0L,
            generation = null,
            phase = GhostRuntimePhase.Idle,
            activeGhostId = null,
            activeSurfaces = null,
            pending = null,
            catalog = RuntimeCatalogState.Loading(
                epoch = 0L,
                lastProvenEntries = frozenList(emptyList()),
                publications = frozenMap(emptyMap()),
            ),
            presentation = RuntimePresentation(
                sakura = RuntimeSpeakerPresentation("", "0", 0L, false),
                kero = RuntimeSpeakerPresentation("", "10", 0L, false),
                talkingAnimationEnabled = false,
            ),
            cues = frozenList(emptyList()),
            dialogue = RuntimeDialogueSnapshot(
                state = frozenDialogueState(DialogueRuntimeState()),
                choices = frozenList(emptyList()),
                anchors = frozenList(emptyList()),
                input = null,
            ),
            mode = GhostRuntimeMode(playingTalk = false, pendingUserAction = false, passive = false),
            modeIdentity = RuntimeModeIdentity(null, 0L, null, null),
            clockRunning = false,
            foregroundHost = null,
            exit = null,
            notice = null,
        )

        /** The only factory used before a snapshot is published by a runtime owner. */
        fun freeze(source: RuntimeSnapshot): RuntimeSnapshot = source.copy(
            catalog = frozenCatalog(source.catalog),
            cues = frozenList(source.cues.map { it.copy() }),
            dialogue = frozenDialogue(source.dialogue),
        )
    }
}

internal data class RuntimeModeIdentity(
    val generation: Long?,
    val modeRevision: Long,
    val parentOperationId: Long?,
    val parentPhaseRevision: Long?,
)

internal sealed interface RuntimeRequestOrigin {
    data class Playback(val playbackToken: Long) : RuntimeRequestOrigin

    data class Timer(
        val clockEpoch: Long,
        val kind: RuntimeTimerKind,
        val bucket: Long,
        val mode: RuntimeModeIdentity,
        val acceptsResponseValue: Boolean,
    ) : RuntimeRequestOrigin

    data class Dialogue(val action: DialogueActionKey) : RuntimeRequestOrigin

    data class Pointer(
        val surface: RuntimeSurfaceIdentity,
        val passiveAtCapture: Boolean,
    ) : RuntimeRequestOrigin

    data class Parent(val operationId: Long, val phaseRevision: Long) : RuntimeRequestOrigin

    data class Attachment(val operationId: Long) : RuntimeRequestOrigin
}

internal data class RuntimeRequestToken(
    val generation: Long,
    val requestId: Long,
    val parentOperationId: Long?,
    val origin: RuntimeRequestOrigin,
)

internal data class CatalogPublicationToken(val source: String, val value: String)

internal enum class RuntimeTimerKind { SECOND, MINUTE }

internal sealed interface RuntimeCatalogScanOutcome {
    data class Scanned(val entries: List<RuntimeGhostMetadata>) : RuntimeCatalogScanOutcome

    data class Failed(val reason: RuntimeNoticeCode) : RuntimeCatalogScanOutcome
}

internal sealed interface RuntimePreparationOutcome {
    data class Prepared(val value: PreparedGhost) : RuntimePreparationOutcome

    data class Failed(val reason: RuntimeNoticeCode) : RuntimePreparationOutcome
}

internal sealed interface RuntimeNativeLifecycleOutcome {
    data object Success : RuntimeNativeLifecycleOutcome

    data class Failed(
        val reason: RuntimeNoticeCode,
        val ownershipCertain: Boolean,
    ) : RuntimeNativeLifecycleOutcome
}

internal sealed interface RuntimeNativeLoadOutcome {
    data class Loaded(
        val pointerCapabilities: PointerEventCapabilities,
    ) : RuntimeNativeLoadOutcome

    data class Failed(
        val reason: RuntimeNoticeCode,
        val ownershipCertain: Boolean,
    ) : RuntimeNativeLoadOutcome
}

internal sealed interface RuntimeCommand {
    data class RegisterHost(val lease: RuntimeHostLease) : RuntimeCommand

    data class SetResumed(val lease: RuntimeHostLease, val resumed: Boolean) : RuntimeCommand

    data class SetTopResumed(val lease: RuntimeHostLease, val topResumed: Boolean) : RuntimeCommand

    data class UnregisterHost(val lease: RuntimeHostLease) : RuntimeCommand

    data class StartGhost(val ghostId: String, val canonicalRoot: File) : RuntimeCommand

    data class PreparationCompleted(
        val operationId: Long,
        val outcome: RuntimePreparationOutcome,
    ) : RuntimeCommand

    data class NativeLoadCompleted(
        val operationId: Long,
        val generation: Long,
        val outcome: RuntimeNativeLoadOutcome,
    ) : RuntimeCommand

    data class NativeUnloadCompleted(
        val operationId: Long,
        val generation: Long,
        val outcome: RuntimeNativeLifecycleOutcome,
    ) : RuntimeCommand

    data class PlaybackDue(val generation: Long, val token: Long) : RuntimeCommand

    data class TimerDue(
        val generation: Long,
        val clockEpoch: Long,
        val kind: RuntimeTimerKind,
        val bucket: Long,
    ) : RuntimeCommand

    data class InputExpired(val key: DialogueActionKey, val elapsedMillis: Long) : RuntimeCommand

    data class NativeResponse(
        val token: RuntimeRequestToken,
        val result: RuntimeResult<TaggedShioriResponse>,
    ) : RuntimeCommand

    data class CatalogChanged(val token: CatalogPublicationToken, val targetId: String) : RuntimeCommand

    data class CatalogScanned(val epoch: Long, val outcome: RuntimeCatalogScanOutcome) : RuntimeCommand

    data class RetryCatalog(
        val publication: CatalogPublicationToken?,
        val expectedFailureEpoch: Long,
    ) : RuntimeCommand

    data class Back(
        val generation: Long?,
        val host: RuntimeHostLease,
        val expected: RuntimeModeIdentity,
    ) : RuntimeCommand

    data class SwitchGhost(
        val generation: Long,
        val host: RuntimeHostLease,
        val expected: RuntimeModeIdentity,
        val targetGhostId: String,
    ) : RuntimeCommand

    data class Pointer(
        val generation: Long,
        val host: RuntimeHostLease,
        val surface: RuntimeSurfaceIdentity,
        val effect: SurfaceInteractionEffect,
    ) : RuntimeCommand

    data class ActivateChoice(val key: DialogueActionKey) : RuntimeCommand

    data class ActivateAnchor(val key: DialogueActionKey) : RuntimeCommand

    data class SubmitInput(val key: DialogueActionKey, val value: String) : RuntimeCommand

    data class DismissInput(val key: DialogueActionKey) : RuntimeCommand

    data class ClaimExit(val lease: RuntimeExitLease) : RuntimeCommand

    data class AcknowledgeExit(val lease: RuntimeExitLease) : RuntimeCommand

    data class AcknowledgeCues(val host: RuntimeHostLease, val throughCueId: Long) : RuntimeCommand
}

private fun frozenCatalog(source: RuntimeCatalogState): RuntimeCatalogState = when (source) {
    is RuntimeCatalogState.Loading -> source.copy(
        lastProvenEntries = frozenGhostMetadata(source.lastProvenEntries),
        publications = frozenPublications(source.publications),
    )
    is RuntimeCatalogState.Ready -> source.copy(
        entries = frozenGhostMetadata(source.entries),
        publications = frozenPublications(source.publications),
    )
    is RuntimeCatalogState.Failed -> source.copy(
        lastProvenEntries = frozenGhostMetadata(source.lastProvenEntries),
        publications = frozenPublications(source.publications),
    )
}

private fun frozenGhostMetadata(entries: List<RuntimeGhostMetadata>): List<RuntimeGhostMetadata> =
    frozenList(entries.map { it.copy() })

private fun frozenPublications(
    source: Map<CatalogPublicationToken, RuntimeCatalogPublicationStatus>,
): Map<CatalogPublicationToken, RuntimeCatalogPublicationStatus> = frozenMap(
    source.entries.associate { (token, status) -> token.copy() to frozenPublicationStatus(status) },
)

private fun frozenPublicationStatus(status: RuntimeCatalogPublicationStatus): RuntimeCatalogPublicationStatus =
    when (status) {
        is RuntimeCatalogPublicationStatus.Pending -> status.copy()
        is RuntimeCatalogPublicationStatus.Ready -> status.copy()
        is RuntimeCatalogPublicationStatus.RecoveryRequired -> status.copy()
    }

private fun frozenDialogue(source: RuntimeDialogueSnapshot): RuntimeDialogueSnapshot =
    DialogueGraphFreezer().freeze(source)

private fun frozenDialogueState(source: DialogueRuntimeState): DialogueRuntimeState =
    DialogueGraphFreezer().freezeState(source)

internal class DialogueGraphFreezer {
    private val dialogueActions = IdentityHashMap<DialogueAction, DialogueAction>()
    private val anchorActions = IdentityHashMap<AnchorAction, AnchorAction>()
    private val inputSpecs = IdentityHashMap<InputBoxSpec, InputBoxSpec>()
    private val pendingInputs = IdentityHashMap<PendingInputState, PendingInputState>()

    fun freeze(source: RuntimeDialogueSnapshot): RuntimeDialogueSnapshot = source.copy(
        state = freezeState(source.state),
        choices = frozenList(source.choices.map {
            RuntimeChoiceAction(it.key.copy(), freezeDialogueAction(it.action))
        }),
        anchors = frozenList(source.anchors.map {
            RuntimeAnchorAction(it.key.copy(), freezeAnchorAction(it.action))
        }),
        input = source.input?.let { RuntimeInputAction(it.key.copy(), freezePendingInput(it.pending)) },
    )

    fun freezeState(source: DialogueRuntimeState): DialogueRuntimeState = source.copy(
        contents = frozenList(source.contents.map(::freezeContent)),
        pendingChoices = frozenList(source.pendingChoices.map(::freezeDialogueAction)),
        pendingInput = source.pendingInput?.let(::freezePendingInput),
    )

    fun freezeContents(source: List<DialogueContent>): List<DialogueContent> =
        frozenList(source.map(::freezeContent))

    private fun freezeContent(source: DialogueContent): DialogueContent = source.copy(
        segments = frozenList(source.segments.map(::freezeSegment)),
    )

    private fun freezeSegment(source: DialogueSegment): DialogueSegment = when (source) {
        is DialogueSegment.Choice -> source.copy(action = freezeDialogueAction(source.action))
        is DialogueSegment.Anchor -> source.copy(action = freezeAnchorAction(source.action))
        is DialogueSegment.InputBox -> source.copy(spec = freezeInputSpec(source.spec))
        else -> source
    }

    fun freezeDialogueAction(source: DialogueAction): DialogueAction =
        dialogueActions.getOrPut(source) {
            when (source) {
                is DialogueAction.Normal -> source.copy(extraReferences = frozenList(source.extraReferences))
                is DialogueAction.DirectEvent -> source.copy(references = frozenList(source.references))
                is DialogueAction.Script -> source.copy()
            }
        }

    fun freezeAnchorAction(source: AnchorAction): AnchorAction =
        anchorActions.getOrPut(source) {
            when (source) {
                is AnchorAction.Normal -> source.copy(extraReferences = frozenList(source.extraReferences))
                is AnchorAction.DirectEvent -> source.copy(references = frozenList(source.references))
            }
        }

    fun freezePendingInput(source: PendingInputState): PendingInputState =
        pendingInputs.getOrPut(source) { source.copy(spec = freezeInputSpec(source.spec)) }

    fun freezeInputSpec(source: InputBoxSpec): InputBoxSpec = inputSpecs.getOrPut(source) {
        source.copy(
            dispatch = when (val dispatch = source.dispatch) {
                is InputDispatch.Normal -> dispatch.copy()
                is InputDispatch.DirectEvent -> dispatch.copy()
            },
            behaviorOptions = frozenSet(source.behaviorOptions),
            presentation = source.presentation.copy(),
            extraReferences = frozenList(source.extraReferences),
            unknownOptions = frozenList(source.unknownOptions),
        )
    }
}

private fun <T> frozenList(source: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(source))

private fun <T> frozenSet(source: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(source))

private fun <K, V> frozenMap(source: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(source))
