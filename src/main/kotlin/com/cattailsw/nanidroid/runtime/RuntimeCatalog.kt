package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.InstalledGhostMetadata
import java.util.Collections

internal fun interface RuntimeCatalogScanner {
    fun scan(): List<InstalledGhostMetadata>

    fun scanCommand(epoch: Long): RuntimeCommand.CatalogScanned {
        val outcome = runCatching { scan().map(RuntimeGhostMetadata::from) }.fold(
            onSuccess = { RuntimeCatalogScanOutcome.Scanned(it) },
            onFailure = { RuntimeCatalogScanOutcome.Failed(RuntimeNoticeCode.CATALOG_SCAN_FAILED) },
        )
        return RuntimeCommand.CatalogScanned(epoch, outcome)
    }
}

internal data class RuntimeCatalogOwner(
    val state: RuntimeCatalogState,
    val requestedEpoch: Long,
    val scanInFlight: Boolean,
    val dirty: Boolean,
)

internal sealed interface RuntimeCatalogEffect {
    data class StartScan(val epoch: Long) : RuntimeCatalogEffect

    data class PublicationReady(
        val token: CatalogPublicationToken,
        val targetId: String,
    ) : RuntimeCatalogEffect

    data class PublicationRecoveryRequired(
        val token: CatalogPublicationToken,
        val targetId: String,
        val reason: RuntimeNoticeCode,
    ) : RuntimeCatalogEffect
}

internal data class RuntimeCatalogTransition(
    val owner: RuntimeCatalogOwner,
    val effects: List<RuntimeCatalogEffect>,
)

internal object RuntimeCatalog {
    fun reduce(owner: RuntimeCatalogOwner, command: RuntimeCommand): RuntimeCatalogTransition {
        val transition = when (command) {
            is RuntimeCommand.CatalogChanged -> catalogChanged(owner, command)
            is RuntimeCommand.CatalogScanned -> catalogScanned(owner, command)
            is RuntimeCommand.RetryCatalog -> retry(owner, command)
            else -> unchanged(owner)
        }
        return RuntimeCatalogTransition(
            owner = transition.owner.frozenCopy(),
            effects = catalogFrozenList(transition.effects),
        )
    }

    private fun catalogChanged(
        owner: RuntimeCatalogOwner,
        command: RuntimeCommand.CatalogChanged,
    ): RuntimeCatalogTransition {
        if (command.token in owner.state.publications) return unchanged(owner)
        val epoch = owner.requestedEpoch + 1L
        val publications = owner.state.publications + (
            command.token to RuntimeCatalogPublicationStatus.Pending(command.targetId, epoch)
        )
        return requestScan(owner, epoch, publications)
    }

    private fun catalogScanned(
        owner: RuntimeCatalogOwner,
        command: RuntimeCommand.CatalogScanned,
    ): RuntimeCatalogTransition {
        if (!owner.scanInFlight || command.epoch != owner.state.epoch) return unchanged(owner)

        val effects = mutableListOf<RuntimeCatalogEffect>()
        val publications = settlePublications(
            publications = owner.state.publications,
            epoch = command.epoch,
            outcome = command.outcome,
            effects = effects,
        )
        val settledState = when (val outcome = command.outcome) {
            is RuntimeCatalogScanOutcome.Scanned -> RuntimeCatalogState.Ready(
                epoch = command.epoch,
                entries = outcome.entries,
                publications = publications,
            )
            is RuntimeCatalogScanOutcome.Failed -> RuntimeCatalogState.Failed(
                epoch = command.epoch,
                lastProvenEntries = owner.state.lastProvenEntries,
                publications = publications,
                reason = outcome.reason,
            )
        }
        if (owner.dirty && owner.requestedEpoch > command.epoch) {
            effects += RuntimeCatalogEffect.StartScan(owner.requestedEpoch)
            return RuntimeCatalogTransition(
                owner.copy(
                    state = RuntimeCatalogState.Loading(
                        epoch = owner.requestedEpoch,
                        lastProvenEntries = settledState.lastProvenEntries,
                        publications = publications,
                    ),
                    scanInFlight = true,
                    dirty = false,
                ),
                effects,
            )
        }
        return RuntimeCatalogTransition(
            owner.copy(state = settledState, scanInFlight = false, dirty = false),
            effects,
        )
    }

    private fun retry(
        owner: RuntimeCatalogOwner,
        command: RuntimeCommand.RetryCatalog,
    ): RuntimeCatalogTransition {
        val token = command.publication
        return if (token == null) {
            retryGlobal(owner, command.expectedFailureEpoch)
        } else {
            retryPublication(owner, token, command.expectedFailureEpoch)
        }
    }

    private fun retryGlobal(owner: RuntimeCatalogOwner, expectedFailureEpoch: Long): RuntimeCatalogTransition {
        val failed = owner.state as? RuntimeCatalogState.Failed ?: return unchanged(owner)
        if (failed.epoch != expectedFailureEpoch) return unchanged(owner)
        val epoch = owner.requestedEpoch + 1L
        val publications = failed.publications.mapValues { (_, status) ->
            if (status is RuntimeCatalogPublicationStatus.RecoveryRequired &&
                status.failedEpoch == expectedFailureEpoch
            ) {
                RuntimeCatalogPublicationStatus.Pending(status.targetId, epoch)
            } else {
                status
            }
        }
        return requestScan(owner, epoch, publications)
    }

    private fun retryPublication(
        owner: RuntimeCatalogOwner,
        token: CatalogPublicationToken,
        expectedFailureEpoch: Long,
    ): RuntimeCatalogTransition {
        val status = owner.state.publications[token] as? RuntimeCatalogPublicationStatus.RecoveryRequired
            ?: return unchanged(owner)
        if (status.failedEpoch != expectedFailureEpoch) return unchanged(owner)
        val epoch = owner.requestedEpoch + 1L
        val publications = owner.state.publications + (
            token to RuntimeCatalogPublicationStatus.Pending(status.targetId, epoch)
        )
        return requestScan(owner, epoch, publications)
    }

    private fun requestScan(
        owner: RuntimeCatalogOwner,
        epoch: Long,
        publications: Map<CatalogPublicationToken, RuntimeCatalogPublicationStatus>,
    ): RuntimeCatalogTransition = if (owner.scanInFlight) {
        RuntimeCatalogTransition(
            owner.copy(
                state = owner.state.withPublications(publications),
                requestedEpoch = epoch,
                dirty = true,
            ),
            emptyList(),
        )
    } else {
        RuntimeCatalogTransition(
            owner.copy(
                state = RuntimeCatalogState.Loading(
                    epoch = epoch,
                    lastProvenEntries = owner.state.lastProvenEntries,
                    publications = publications,
                ),
                requestedEpoch = epoch,
                scanInFlight = true,
                dirty = false,
            ),
            listOf(RuntimeCatalogEffect.StartScan(epoch)),
        )
    }

    private fun settlePublications(
        publications: Map<CatalogPublicationToken, RuntimeCatalogPublicationStatus>,
        epoch: Long,
        outcome: RuntimeCatalogScanOutcome,
        effects: MutableList<RuntimeCatalogEffect>,
    ): Map<CatalogPublicationToken, RuntimeCatalogPublicationStatus> = publications.mapValues { (token, status) ->
        if (status !is RuntimeCatalogPublicationStatus.Pending || status.requestedEpoch > epoch) {
            return@mapValues status
        }
        when (outcome) {
            is RuntimeCatalogScanOutcome.Scanned -> {
                if (outcome.entries.any { it.id.equals(status.targetId, ignoreCase = true) }) {
                    effects += RuntimeCatalogEffect.PublicationReady(token, status.targetId)
                    RuntimeCatalogPublicationStatus.Ready(status.targetId, epoch)
                } else {
                    effects += RuntimeCatalogEffect.PublicationRecoveryRequired(
                        token,
                        status.targetId,
                        RuntimeNoticeCode.CATALOG_TARGET_MISSING,
                    )
                    RuntimeCatalogPublicationStatus.RecoveryRequired(
                        status.targetId,
                        epoch,
                        RuntimeNoticeCode.CATALOG_TARGET_MISSING,
                    )
                }
            }
            is RuntimeCatalogScanOutcome.Failed -> {
                effects += RuntimeCatalogEffect.PublicationRecoveryRequired(token, status.targetId, outcome.reason)
                RuntimeCatalogPublicationStatus.RecoveryRequired(status.targetId, epoch, outcome.reason)
            }
        }
    }

    private fun unchanged(owner: RuntimeCatalogOwner): RuntimeCatalogTransition =
        RuntimeCatalogTransition(owner, emptyList())
}

private fun RuntimeCatalogState.withPublications(
    publications: Map<CatalogPublicationToken, RuntimeCatalogPublicationStatus>,
): RuntimeCatalogState = when (this) {
    is RuntimeCatalogState.Loading -> copy(publications = publications)
    is RuntimeCatalogState.Ready -> copy(publications = publications)
    is RuntimeCatalogState.Failed -> copy(publications = publications)
}

private fun RuntimeCatalogOwner.frozenCopy(): RuntimeCatalogOwner = copy(state = state.catalogFrozenCopy())

private fun RuntimeCatalogState.catalogFrozenCopy(): RuntimeCatalogState {
    val publications = catalogFrozenMap(
        publications.map { (token, status) -> token.copy() to status.catalogFrozenCopy() }.toMap(),
    )
    return when (this) {
        is RuntimeCatalogState.Loading -> copy(
            lastProvenEntries = catalogFrozenMetadata(lastProvenEntries),
            publications = publications,
        )
        is RuntimeCatalogState.Ready -> copy(
            entries = catalogFrozenMetadata(entries),
            publications = publications,
        )
        is RuntimeCatalogState.Failed -> copy(
            lastProvenEntries = catalogFrozenMetadata(lastProvenEntries),
            publications = publications,
        )
    }
}

private fun RuntimeCatalogPublicationStatus.catalogFrozenCopy(): RuntimeCatalogPublicationStatus = when (this) {
    is RuntimeCatalogPublicationStatus.Pending -> copy()
    is RuntimeCatalogPublicationStatus.Ready -> copy()
    is RuntimeCatalogPublicationStatus.RecoveryRequired -> copy()
}

private fun catalogFrozenMetadata(entries: List<RuntimeGhostMetadata>): List<RuntimeGhostMetadata> =
    catalogFrozenList(entries.map(RuntimeGhostMetadata::copy))

private fun <T> catalogFrozenList(source: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(source))

private fun <K, V> catalogFrozenMap(source: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(source))
