package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.install.ArchiveInstallFailure
import com.cattailsw.nanidroid.install.ArchiveInstallResult
import java.io.File
import org.junit.Assert.assertEquals
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
}
