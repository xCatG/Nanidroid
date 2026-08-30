package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.InstalledGhostMetadata
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCatalogTest {
    @Test
    fun scanAdapterConvertsInstalledMetadataToDataOnlyRuntimeMetadata() {
        val root = File("ghost/alpha")
        val scanner = RuntimeCatalogScanner {
            listOf(InstalledGhostMetadata("alpha", root, "Alpha", "Sakura", File(root, "readme.txt")))
        }

        val command = scanner.scanCommand(epoch = 4L)
        val outcome = command.outcome as RuntimeCatalogScanOutcome.Scanned

        assertEquals(
            listOf(RuntimeGhostMetadata("alpha", root.path, "Alpha", "Sakura", File(root, "readme.txt").path)),
            outcome.entries,
        )
        assertTrue(outcome.entries.none { valueGraphContainsFile(it) })
    }

    @Test
    fun scanAdapterTurnsScannerFailureIntoTypedCatalogFailure() {
        val scanner = RuntimeCatalogScanner { error("disk failed") }

        assertEquals(
            RuntimeCatalogScanOutcome.Failed(RuntimeNoticeCode.CATALOG_SCAN_FAILED),
            scanner.scanCommand(epoch = 9L).outcome,
        )
    }

    @Test
    fun initialLoadingScanBecomesReady() {
        val owner = loadingOwner(epoch = 1L)
        val entries = listOf(metadata("alpha"))

        val transition = RuntimeCatalog.reduce(owner, scanned(1L, entries))

        assertEquals(RuntimeCatalogState.Ready(1L, entries, emptyMap()), transition.owner.state)
        assertFalse(transition.owner.scanInFlight)
        assertFalse(transition.owner.dirty)
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun initialFailedScanPreservesLastProvenEntries() {
        val lastProven = listOf(metadata("old"))
        val owner = loadingOwner(epoch = 2L, lastProven = lastProven)

        val transition = RuntimeCatalog.reduce(
            owner,
            failed(2L, RuntimeNoticeCode.CATALOG_SCAN_FAILED),
        )

        assertEquals(
            RuntimeCatalogState.Failed(
                epoch = 2L,
                lastProvenEntries = lastProven,
                publications = emptyMap(),
                reason = RuntimeNoticeCode.CATALOG_SCAN_FAILED,
            ),
            transition.owner.state,
        )
        assertFalse(transition.owner.scanInFlight)
    }

    @Test
    fun publicationAfterReadyEmptySchedulesRequiredTargetScan() {
        val token = token("bundled", "1")
        val owner = readyOwner(epoch = 1L, entries = emptyList())

        val transition = RuntimeCatalog.reduce(owner, RuntimeCommand.CatalogChanged(token, "alpha"))

        assertEquals(2L, transition.owner.requestedEpoch)
        assertEquals(
            RuntimeCatalogPublicationStatus.Pending("alpha", requestedEpoch = 2L),
            transition.owner.state.publications[token],
        )
        assertEquals(listOf(RuntimeCatalogEffect.StartScan(2L)), transition.effects)
        assertTrue(transition.owner.scanInFlight)
        assertEquals(2L, transition.owner.state.epoch)
    }

    @Test
    fun changesDuringBlockedScanCoalesceIntoOneNewestEpochScan() {
        val first = token("foreground", "1")
        val second = token("foreground", "2")
        var transition = RuntimeCatalog.reduce(loadingOwner(1L), RuntimeCommand.CatalogChanged(first, "alpha"))
        assertTrue(transition.effects.isEmpty())
        assertTrue(transition.owner.dirty)

        transition = RuntimeCatalog.reduce(transition.owner, RuntimeCommand.CatalogChanged(second, "beta"))
        assertTrue(transition.effects.isEmpty())
        assertEquals(3L, transition.owner.requestedEpoch)

        transition = RuntimeCatalog.reduce(transition.owner, scanned(1L, emptyList()))

        assertEquals(listOf(RuntimeCatalogEffect.StartScan(3L)), transition.effects)
        assertTrue(transition.owner.scanInFlight)
        assertFalse(transition.owner.dirty)
        assertEquals(3L, transition.owner.state.epoch)
        assertTrue(transition.owner.state.publications.values.all {
            it is RuntimeCatalogPublicationStatus.Pending
        })
    }

    @Test
    fun staleOrUnexpectedScanCompletionIsAnEffectFreeNoOp() {
        val owner = loadingOwner(epoch = 3L)

        val stale = RuntimeCatalog.reduce(owner, scanned(2L, listOf(metadata("stale"))))

        assertEquals(owner, stale.owner)
        assertTrue(stale.effects.isEmpty())
    }

    @Test
    fun oneScanSettlesEveryEligiblePublicationIndependently() {
        val alpha = token("foreground", "alpha")
        val missing = token("foreground", "missing")
        val ready = token("earlier", "ready")
        val publications = linkedMapOf<CatalogPublicationToken, RuntimeCatalogPublicationStatus>(
            alpha to RuntimeCatalogPublicationStatus.Pending("ALPHA", 5L),
            missing to RuntimeCatalogPublicationStatus.Pending("missing", 5L),
            ready to RuntimeCatalogPublicationStatus.Ready("old", 4L),
        )
        val owner = loadingOwner(5L, publications = publications)

        val transition = RuntimeCatalog.reduce(owner, scanned(5L, listOf(metadata("alpha"))))

        assertEquals(RuntimeCatalogPublicationStatus.Ready("ALPHA", 5L), transition.owner.state.publications[alpha])
        assertEquals(
            RuntimeCatalogPublicationStatus.RecoveryRequired(
                "missing",
                5L,
                RuntimeNoticeCode.CATALOG_TARGET_MISSING,
            ),
            transition.owner.state.publications[missing],
        )
        assertEquals(RuntimeCatalogPublicationStatus.Ready("old", 4L), transition.owner.state.publications[ready])
        assertEquals(
            listOf(
                RuntimeCatalogEffect.PublicationReady(alpha, "ALPHA"),
                RuntimeCatalogEffect.PublicationRecoveryRequired(
                    missing,
                    "missing",
                    RuntimeNoticeCode.CATALOG_TARGET_MISSING,
                ),
            ),
            transition.effects,
        )
    }

    @Test
    fun failedScanGlobalRetryReactivatesSameEpochTokensThenSettlesEachReady() {
        val alpha = token("foreground", "alpha")
        val beta = token("foreground", "beta")
        val pending = linkedMapOf<CatalogPublicationToken, RuntimeCatalogPublicationStatus>(
            alpha to RuntimeCatalogPublicationStatus.Pending("alpha", 7L),
            beta to RuntimeCatalogPublicationStatus.Pending("beta", 7L),
        )
        var transition = RuntimeCatalog.reduce(
            loadingOwner(7L, publications = pending),
            failed(7L, RuntimeNoticeCode.CATALOG_SCAN_FAILED),
        )
        assertTrue(transition.owner.state.publications.values.all {
            it == RuntimeCatalogPublicationStatus.RecoveryRequired(
                targetId = it.targetId,
                failedEpoch = 7L,
                reason = RuntimeNoticeCode.CATALOG_SCAN_FAILED,
            )
        })
        assertEquals(2, transition.effects.size)

        transition = RuntimeCatalog.reduce(
            transition.owner,
            RuntimeCommand.RetryCatalog(publication = null, expectedFailureEpoch = 7L),
        )
        assertEquals(8L, transition.owner.requestedEpoch)
        assertTrue(transition.owner.state.publications.values.all {
            it is RuntimeCatalogPublicationStatus.Pending && it.requestedEpoch == 8L
        })
        assertEquals(listOf(RuntimeCatalogEffect.StartScan(8L)), transition.effects)

        transition = RuntimeCatalog.reduce(
            transition.owner,
            scanned(8L, listOf(metadata("ALPHA"), metadata("Beta"))),
        )
        assertEquals(
            listOf(
                RuntimeCatalogEffect.PublicationReady(alpha, "alpha"),
                RuntimeCatalogEffect.PublicationReady(beta, "beta"),
            ),
            transition.effects,
        )
        assertTrue(transition.owner.state.publications.values.all {
            it is RuntimeCatalogPublicationStatus.Ready && it.provenEpoch == 8L
        })
    }

    @Test
    fun tokenRetryRequiresExactTokenAndFailureEpoch() {
        val token = token("foreground", "1")
        val other = token("foreground", "2")
        val recovery = RuntimeCatalogPublicationStatus.RecoveryRequired(
            "alpha",
            failedEpoch = 4L,
            reason = RuntimeNoticeCode.CATALOG_TARGET_MISSING,
        )
        val owner = readyOwner(
            epoch = 4L,
            entries = emptyList(),
            publications = linkedMapOf(token to recovery),
        )

        val wrongToken = RuntimeCatalog.reduce(owner, RuntimeCommand.RetryCatalog(other, 4L))
        val wrongEpoch = RuntimeCatalog.reduce(owner, RuntimeCommand.RetryCatalog(token, 3L))
        assertEquals(owner, wrongToken.owner)
        assertEquals(owner, wrongEpoch.owner)
        assertTrue(wrongToken.effects.isEmpty())
        assertTrue(wrongEpoch.effects.isEmpty())

        val accepted = RuntimeCatalog.reduce(owner, RuntimeCommand.RetryCatalog(token, 4L))
        assertEquals(RuntimeCatalogPublicationStatus.Pending("alpha", 5L), accepted.owner.state.publications[token])
        assertEquals(listOf(RuntimeCatalogEffect.StartScan(5L)), accepted.effects)
    }

    @Test
    fun globalRetryRequiresMatchingGlobalFailureAndMovesOnlyThatFailureEpoch() {
        val old = token("foreground", "old")
        val current = token("foreground", "current")
        val publications = linkedMapOf<CatalogPublicationToken, RuntimeCatalogPublicationStatus>(
            old to RuntimeCatalogPublicationStatus.RecoveryRequired(
                "old",
                4L,
                RuntimeNoticeCode.CATALOG_SCAN_FAILED,
            ),
            current to RuntimeCatalogPublicationStatus.RecoveryRequired(
                "current",
                5L,
                RuntimeNoticeCode.CATALOG_SCAN_FAILED,
            ),
        )
        val failed = RuntimeCatalogOwner(
            state = RuntimeCatalogState.Failed(
                5L,
                emptyList(),
                publications,
                RuntimeNoticeCode.CATALOG_SCAN_FAILED,
            ),
            requestedEpoch = 5L,
            scanInFlight = false,
            dirty = false,
        )

        val transition = RuntimeCatalog.reduce(failed, RuntimeCommand.RetryCatalog(null, 5L))

        assertEquals(publications[old], transition.owner.state.publications[old])
        assertEquals(RuntimeCatalogPublicationStatus.Pending("current", 6L), transition.owner.state.publications[current])
        assertEquals(listOf(RuntimeCatalogEffect.StartScan(6L)), transition.effects)
    }

    @Test
    fun duplicatePublicationReadyRetryAndUnsupportedCommandsAreNoOps() {
        val token = token("foreground", "1")
        val publications = linkedMapOf<CatalogPublicationToken, RuntimeCatalogPublicationStatus>(
            token to RuntimeCatalogPublicationStatus.Ready("alpha", 2L),
        )
        val owner = readyOwner(2L, listOf(metadata("alpha")), publications)

        val duplicate = RuntimeCatalog.reduce(owner, RuntimeCommand.CatalogChanged(token, "alpha"))
        val readyRetry = RuntimeCatalog.reduce(owner, RuntimeCommand.RetryCatalog(token, 2L))
        val unsupported = RuntimeCatalog.reduce(owner, RuntimeCommand.PlaybackDue(99L, 10L))

        assertEquals(owner, duplicate.owner)
        assertEquals(owner, readyRetry.owner)
        assertEquals(owner, unsupported.owner)
        assertTrue(duplicate.effects.isEmpty())
        assertTrue(readyRetry.effects.isEmpty())
        assertTrue(unsupported.effects.isEmpty())
    }

    @Test
    fun returnedCatalogCollectionsAreCopiedUnmodifiableAndContainNoInstalledMetadata() {
        val token = token("foreground", "1")
        val entries = arrayListOf(metadata("alpha"))
        val publications = linkedMapOf<CatalogPublicationToken, RuntimeCatalogPublicationStatus>(
            token to RuntimeCatalogPublicationStatus.Ready("alpha", 1L),
        )
        val owner = readyOwner(1L, entries, publications)

        val returned = RuntimeCatalog.reduce(owner, RuntimeCommand.PlaybackDue(1L, 1L)).owner
        entries.clear()
        publications.clear()
        val state = returned.state as RuntimeCatalogState.Ready

        assertEquals(1, state.entries.size)
        assertEquals(1, state.publications.size)
        assertTrue(state.entries.none { valueGraphContainsFile(it) })
        assertThrows(UnsupportedOperationException::class.java) {
            (state.entries as MutableList<RuntimeGhostMetadata>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (state.publications as MutableMap<CatalogPublicationToken, RuntimeCatalogPublicationStatus>).clear()
        }
    }

    private fun loadingOwner(
        epoch: Long,
        lastProven: List<RuntimeGhostMetadata> = emptyList(),
        publications: Map<CatalogPublicationToken, RuntimeCatalogPublicationStatus> = emptyMap(),
    ) = RuntimeCatalogOwner(
        state = RuntimeCatalogState.Loading(epoch, lastProven, publications),
        requestedEpoch = epoch,
        scanInFlight = true,
        dirty = false,
    )

    private fun readyOwner(
        epoch: Long,
        entries: List<RuntimeGhostMetadata>,
        publications: Map<CatalogPublicationToken, RuntimeCatalogPublicationStatus> = emptyMap(),
    ) = RuntimeCatalogOwner(
        state = RuntimeCatalogState.Ready(epoch, entries, publications),
        requestedEpoch = epoch,
        scanInFlight = false,
        dirty = false,
    )

    private fun scanned(epoch: Long, entries: List<RuntimeGhostMetadata>) =
        RuntimeCommand.CatalogScanned(epoch, RuntimeCatalogScanOutcome.Scanned(entries))

    private fun failed(epoch: Long, reason: RuntimeNoticeCode) =
        RuntimeCommand.CatalogScanned(epoch, RuntimeCatalogScanOutcome.Failed(reason))

    private fun metadata(id: String) = RuntimeGhostMetadata(
        id = id,
        canonicalRootPath = "ghost/$id",
        name = id.uppercase(),
        sakuraName = "$id-sakura",
        readmePath = "ghost/$id/readme.txt",
    )

    private fun token(source: String, value: String) = CatalogPublicationToken(source, value)

    private fun valueGraphContainsFile(value: Any): Boolean = value.javaClass.declaredFields.any { field ->
        field.isAccessible = true
        field.get(value) is File
    }
}
