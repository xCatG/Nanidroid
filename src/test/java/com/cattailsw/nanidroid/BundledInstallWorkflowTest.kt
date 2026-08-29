package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.install.ArchiveInstallFailure
import com.cattailsw.nanidroid.install.ArchiveInstallResult
import com.cattailsw.nanidroid.runtime.RuntimeCatalogState
import com.cattailsw.nanidroid.runtime.RuntimeGhostMetadata
import com.cattailsw.nanidroid.runtime.RuntimeNoticeCode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledInstallWorkflowTest {
    // Mutation caught: unavailable external storage silently leaves Ready(empty) without recovery.
    @Test
    fun nullStorageRootPreflightCreatesOneStableRecoveryIdentity() {
        val workflow = BundledInstallWorkflow()
        val preflight = bundledInstallEligibility(storageRoot = { null })

        assertEquals(
            BundledInstallEligibility.RecoveryRequired("Nanidroid cannot prepare its ghost storage."),
            preflight,
        )
        val failure = preflight as BundledInstallEligibility.RecoveryRequired
        val operationId = requireNotNull(workflow.recordPreflightFailure(failure.message))
        assertNull(workflow.recordPreflightFailure(failure.message))
        assertEquals(
            BundledInstallState.RecoveryRequired(operationId, failure.message),
            workflow.state.value,
        )
    }

    // Mutation caught: storage enumeration throws before the workflow starts and kills the observer.
    @Test
    fun thrownStorageEnumerationPreflightCreatesActionableRecovery() {
        val root = File("build/bundled-preflight")

        val preflight = bundledInstallEligibility(
            storageRoot = { root },
            storageEntries = { throw SecurityException("storage denied") },
        )

        assertEquals(BundledInstallEligibility.RecoveryRequired("storage denied"), preflight)
    }

    // Mutation caught: a returned installer failure leaves Running latched forever.
    @Test
    fun returnedFailureSettlesToActionableRecoveryWithTheSameIdentity() {
        val workflow = BundledInstallWorkflow()
        val operationId = requireNotNull(workflow.startIfIdle())

        val publication = workflow.execute(operationId) {
            ArchiveInstallResult.Failed(
                "Built-in archive is invalid.",
                ArchiveInstallFailure.InvalidArchive,
            )
        }

        assertNull(publication)
        assertEquals(
            BundledInstallState.RecoveryRequired(operationId, "Built-in archive is invalid."),
            workflow.state.value,
        )
    }

    // Mutation caught: a setup/copy/installer exception kills the observer and leaves Running latched.
    @Test
    fun thrownExceptionSettlesToActionableRecoveryWithTheSameIdentity() {
        val workflow = BundledInstallWorkflow()
        val operationId = requireNotNull(workflow.startIfIdle())

        val publication = workflow.execute(operationId) { throw IllegalStateException("copy failed") }

        assertNull(publication)
        assertEquals(
            BundledInstallState.RecoveryRequired(operationId, "copy failed"),
            workflow.state.value,
        )
    }

    // Mutation caught: stale or duplicate recovery actions start another bundled install.
    @Test
    fun onlyExactCurrentFailureIdentityCanRetryOnce() {
        val workflow = BundledInstallWorkflow()
        val failedId = requireNotNull(workflow.startIfIdle())
        workflow.execute(failedId) {
            ArchiveInstallResult.Failed("failed", ArchiveInstallFailure.PublishFailed)
        }

        assertNull(workflow.retry(failedId + 1L))
        val retryId = requireNotNull(workflow.retry(failedId))
        assertTrue(retryId > failedId)
        assertEquals(BundledInstallState.Running(retryId), workflow.state.value)
        assertNull(workflow.retry(failedId))
    }

    // Mutation caught: a duplicate completion publishes another catalog change or reruns the installer.
    @Test
    fun committedInstallProducesOnePublicationAndCannotExecuteTwice() {
        val workflow = BundledInstallWorkflow()
        val operationId = requireNotNull(workflow.startIfIdle())
        var executions = 0

        val first = workflow.execute(operationId) {
            executions += 1
            ArchiveInstallResult.Installed("/ghost/nanidroid", "nanidroid")
        }
        val duplicate = workflow.execute(operationId) {
            executions += 1
            ArchiveInstallResult.Installed("/ghost/nanidroid", "nanidroid")
        }

        assertEquals(BundledInstallPublication(operationId, "nanidroid"), first)
        assertNull(duplicate)
        assertEquals(1, executions)
        assertEquals(BundledInstallState.Completed(operationId, "nanidroid"), workflow.state.value)
    }

    // Mutation caught: an exact retry leaves bundled recovery masking a now-nonempty catalog.
    @Test
    fun exactRetryReleasesRecoveryWhenCatalogBecameNonemptyWithoutSideEffects() {
        val workflow = failedWorkflow()
        val failure = workflow.state.value as BundledInstallState.RecoveryRequired
        var installerInvocations = 0
        var catalogChangedSubmissions = 0

        val publication = performBundledInstallRetry(
            workflow = workflow,
            expectedFailureOperationId = failure.operationId,
            currentCatalog = { readyCatalog(entries = listOf(runtimeGhost("manual"))) },
            currentEligibility = { throw AssertionError("nonempty catalog must not inspect bundled storage") },
            install = {
                installerInvocations += 1
                ArchiveInstallResult.Installed("/ghost/nanidroid", "nanidroid")
            },
            publish = { catalogChangedSubmissions += 1 },
        )

        assertNull(publication)
        assertEquals(BundledInstallState.Idle, workflow.state.value)
        assertEquals(0, installerInvocations)
        assertEquals(0, catalogChangedSubmissions)
    }

    // Mutation caught: an exact retry leaves bundled recovery masking manual/import/startup controls.
    @Test
    fun exactRetryReleasesRecoveryWhenReadyEmptyStorageBecameIneligible() {
        val workflow = failedWorkflow()
        val failure = workflow.state.value as BundledInstallState.RecoveryRequired
        var installerInvocations = 0
        var catalogChangedSubmissions = 0

        val publication = performBundledInstallRetry(
            workflow = workflow,
            expectedFailureOperationId = failure.operationId,
            currentCatalog = { readyCatalog() },
            currentEligibility = { BundledInstallEligibility.Ineligible },
            install = {
                installerInvocations += 1
                ArchiveInstallResult.Installed("/ghost/nanidroid", "nanidroid")
            },
            publish = { catalogChangedSubmissions += 1 },
        )

        assertNull(publication)
        assertEquals(BundledInstallState.Idle, workflow.state.value)
        assertEquals(0, installerInvocations)
        assertEquals(0, catalogChangedSubmissions)
    }

    // Mutation caught: a refreshed storage failure loses the exact operation identity or stale message is retained.
    @Test
    fun exactRetryRefreshesReadyEmptyStorageRecoveryMessageWithoutSideEffects() {
        val workflow = failedWorkflow()
        val failure = workflow.state.value as BundledInstallState.RecoveryRequired

        assertNull(
            performBundledInstallRetry(
                workflow = workflow,
                expectedFailureOperationId = failure.operationId,
                currentCatalog = { readyCatalog() },
                currentEligibility = { BundledInstallEligibility.RecoveryRequired("storage still unavailable") },
                install = { throw AssertionError("recovery refresh must not invoke installer") },
                publish = { throw AssertionError("recovery refresh must not publish CatalogChanged") },
            ),
        )
        assertEquals(
            BundledInstallState.RecoveryRequired(failure.operationId, "storage still unavailable"),
            workflow.state.value,
        )
    }

    // Mutation caught: catalog-owned recovery remains hidden behind the bundled retry dialog.
    @Test
    fun exactRetryReleasesRecoveryForLoadingOrFailedCatalogWithoutInspectingStorage() {
        listOf(
            RuntimeCatalogState.Loading(7L, emptyList(), emptyMap()),
            RuntimeCatalogState.Failed(
                7L,
                emptyList(),
                emptyMap(),
                RuntimeNoticeCode.CATALOG_SCAN_FAILED,
            ),
        ).forEach { catalog ->
            val workflow = failedWorkflow()
            val failure = workflow.state.value as BundledInstallState.RecoveryRequired

            assertNull(
                performBundledInstallRetry(
                    workflow = workflow,
                    expectedFailureOperationId = failure.operationId,
                    currentCatalog = { catalog },
                    currentEligibility = { throw AssertionError("unproven catalog must not inspect storage") },
                    install = { throw AssertionError("rejected retry must not invoke installer") },
                    publish = { throw AssertionError("rejected retry must not publish CatalogChanged") },
                ),
            )
            assertEquals(BundledInstallState.Idle, workflow.state.value)
        }
    }

    // Mutation caught: a stale retry still reads catalog/storage or mutates the current exact failure.
    @Test
    fun staleRetryIsEffectFreeBeforeCatalogOrStorageInspection() {
        val workflow = failedWorkflow()
        val failure = workflow.state.value as BundledInstallState.RecoveryRequired

        assertNull(
            performBundledInstallRetry(
                workflow = workflow,
                expectedFailureOperationId = failure.operationId + 1L,
                currentCatalog = { throw AssertionError("stale retry must not inspect catalog") },
                currentEligibility = { throw AssertionError("stale retry must not inspect storage") },
                install = { throw AssertionError("stale retry must not install") },
                publish = { throw AssertionError("stale retry must not publish") },
            ),
        )
        assertEquals(failure, workflow.state.value)
    }

    // Mutation caught: process-owned recovery remains published while catalog recovery owns the UI.
    @Test
    fun catalogObservationReleasesOnlyObsoleteExactRecovery() {
        listOf(
            RuntimeCatalogState.Loading(8L, emptyList(), emptyMap()),
            RuntimeCatalogState.Failed(
                8L,
                emptyList(),
                emptyMap(),
                RuntimeNoticeCode.CATALOG_SCAN_FAILED,
            ),
            readyCatalog(entries = listOf(runtimeGhost("manual"))),
        ).forEach { catalog ->
            val workflow = failedWorkflow()
            val failure = workflow.state.value as BundledInstallState.RecoveryRequired

            assertTrue(releaseObsoleteBundledRecovery(workflow, failure.operationId, catalog))
            assertEquals(BundledInstallState.Idle, workflow.state.value)
        }

        val readyEmpty = failedWorkflow()
        val current = readyEmpty.state.value as BundledInstallState.RecoveryRequired
        assertFalse(releaseObsoleteBundledRecovery(readyEmpty, current.operationId, readyCatalog()))
        assertEquals(current, readyEmpty.state.value)

        val stale = failedWorkflow()
        val staleCurrent = stale.state.value as BundledInstallState.RecoveryRequired
        assertFalse(
            releaseObsoleteBundledRecovery(
                stale,
                staleCurrent.operationId + 1L,
                readyCatalog(entries = listOf(runtimeGhost("manual"))),
            ),
        )
        assertEquals(staleCurrent, stale.state.value)
    }

    // Mutation caught: an eligible exact retry executes or publishes more than once.
    @Test
    fun eligibleExactRetryInstallsOnceAndPublishesOnlyAfterCommit() {
        val workflow = failedWorkflow()
        val failure = workflow.state.value as BundledInstallState.RecoveryRequired
        var installerInvocations = 0
        val publications = mutableListOf<BundledInstallPublication>()

        val first = performBundledInstallRetry(
            workflow = workflow,
            expectedFailureOperationId = failure.operationId,
            currentCatalog = { readyCatalog() },
            currentEligibility = { BundledInstallEligibility.Eligible(File("build/ghost")) },
            install = {
                installerInvocations += 1
                ArchiveInstallResult.Installed("/ghost/nanidroid", "nanidroid")
            },
            publish = publications::add,
        )
        val duplicate = performBundledInstallRetry(
            workflow = workflow,
            expectedFailureOperationId = failure.operationId,
            currentCatalog = { readyCatalog() },
            currentEligibility = { BundledInstallEligibility.Eligible(File("build/ghost")) },
            install = { throw AssertionError("duplicate retry must not invoke installer") },
            publish = { throw AssertionError("duplicate retry must not publish CatalogChanged") },
        )

        assertEquals(first, publications.single())
        assertNull(duplicate)
        assertEquals(1, installerInvocations)
        assertTrue(workflow.state.value is BundledInstallState.Completed)
    }

    private fun failedWorkflow() = BundledInstallWorkflow().also { workflow ->
        val operationId = requireNotNull(workflow.startIfIdle())
        workflow.execute(operationId) {
            ArchiveInstallResult.Failed("copy failed", ArchiveInstallFailure.StorageUnavailable)
        }
    }

    private fun readyCatalog(entries: List<RuntimeGhostMetadata> = emptyList()) =
        RuntimeCatalogState.Ready(7L, entries, emptyMap())

    private fun runtimeGhost(id: String) = RuntimeGhostMetadata(
        id = id,
        canonicalRootPath = "/ghost/$id",
        name = id,
        sakuraName = null,
        readmePath = "/ghost/$id/readme.txt",
    )
}
