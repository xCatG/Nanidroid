package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.install.ForegroundNarImportState
import com.cattailsw.nanidroid.install.NarImportAttemptToken
import com.cattailsw.nanidroid.runtime.CatalogPublicationToken
import com.cattailsw.nanidroid.runtime.RuntimeCatalogScanner
import com.cattailsw.nanidroid.runtime.RuntimeCatalogPublicationStatus
import com.cattailsw.nanidroid.runtime.RuntimeCatalogState
import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeNoticeCode
import com.cattailsw.nanidroid.runtime.RuntimeSnapshot
import com.cattailsw.nanidroid.runtime.SakuraScriptPlayerTest
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostRuntimePlaybackTest {
    @Test
    fun attachmentSelectsExactlyOneFirstBootGhostChangedOrBootEvent() {
        GhostRuntimeSnapshotTest().firstLoadRequestsOneOnFirstBootAndAttachesOnlyAfterTailResponse()
        GhostRuntimeSnapshotTest().switchNoContentUnloadsBeforePreparingAndAttachesReplacementOnce()

        val root = File("build/runtime-playback/ordinary-boot").canonicalFile
        val persistence = InMemoryGhostRuntimePersistence().apply {
            activationCounts["ordinary-boot"] = 1L
        }
        SnapshotRuntimeFixture(
            persistence = persistence,
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("ordinary-boot", root, null, null, File(root, "readme.txt")))
            },
        ).use { fixture ->
            fixture.runtime.submit(RuntimeCommand.StartGhost("ordinary-boot", root))
            fixture.awaitNativeWork()
            fixture.nativePort.loads.remove().complete(
                com.cattailsw.nanidroid.runtime.RuntimeNativeLoadOutcome.Loaded(
                    com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities(),
                ),
            )
            fixture.drain()
            fixture.awaitNativeWork()

            val requests = fixture.nativePort.requests.toList()
            assertEquals(1, requests.size)
            assertTrue(requests.single().intent.protocolText.contains("ID: OnBoot\r\n"))
        }
    }

    @Test
    fun authoredPlaybackContinuesWhileClockOwnerIsAbsent() {
        val root = File("build/runtime-playback/hostless").canonicalFile
        fixtureFor("hostless", root).use { fixture ->
            fixture.startAttached("hostless", root)
            fixture.runtime.enqueueScriptForTesting(buildString {
                repeat(65) { append("\\i[1]") }
                append("\\hDONE\\e")
            })
            fixture.drain()
            fixture.runPlaybackUntil { !it.mode.playingTalk }

            val snapshot = fixture.runtime.snapshots.value
            val dialogueText = snapshot.dialogue.state.contents
                .flatMap { it.segments }
                .filterIsInstance<DialogueSegment.Text>()
                .joinToString(separator = "", transform = DialogueSegment.Text::value)
            assertEquals("DONE", dialogueText)
            assertEquals("0", snapshot.presentation.sakura.surfaceId)
            assertFalse(snapshot.mode.playingTalk)
            assertTrue(snapshot.cues.isEmpty())
        }
    }

    @Test
    fun blockedTimerResponseCannotEnterAfterClockEpochChanges() {
        GhostRuntimeSnapshotTest().foregroundLossStopsClockAndRejectsOldTimerResponse()
    }

    @Test
    fun switchPlaybackOwnsOutgoingResponseBeforeUnload() {
        GhostRuntimeSnapshotTest().switchNoContentUnloadsBeforePreparingAndAttachesReplacementOnce()
    }

    @Test
    fun equalAnimationIdsFromSeparateCommandsAreSeparateRenderCalls() {
        SakuraScriptPlayerTest().distinctSurfaceTransitionsAndAnimationCuesAreOrdered()
    }

    @Test
    fun foregroundImportRefreshCannotPublishPreCommitCatalogScan() {
        val token = NarImportAttemptToken("process", 7L, 42)

        assertNull(foregroundCatalogPublication(ForegroundNarImportState.Copying(token)))
        assertNull(foregroundCatalogPublication(ForegroundNarImportState.Installing(token, "extracting", 12L)))

        val publication = foregroundCatalogPublication(
            ForegroundNarImportState.Installed(token, "/ghost/alpha", "alpha"),
        )
        assertEquals(token to "alpha", publication)
        assertEquals(
            CatalogPublicationToken("foreground-import", "process:7:42"),
            foregroundPublicationToken(token),
        )
    }

    // Mutation caught: the exact foreground publication recovery is delegated to a hidden lower modal.
    @Test
    fun installedForegroundImportDerivesExactCatalogRecoveryAndRetryCommand() {
        val token = NarImportAttemptToken("process", 9L, 42)
        val publicationToken = CatalogPublicationToken("foreground-import", "process:9:42")
        val snapshot = RuntimeSnapshot.initial().copy(
            catalog = RuntimeCatalogState.Ready(
                epoch = 14L,
                entries = emptyList(),
                publications = mapOf(
                    publicationToken to RuntimeCatalogPublicationStatus.RecoveryRequired(
                        targetId = "alpha",
                        failedEpoch = 14L,
                        reason = RuntimeNoticeCode.CATALOG_TARGET_MISSING,
                    ),
                ),
            ),
        )

        val recovery = foregroundCatalogRecovery(
            ForegroundNarImportState.Installed(token, "/ghost/alpha", "alpha"),
            snapshot,
        )

        assertEquals(
            ForegroundCatalogRecovery(token, publicationToken, failedEpoch = 14L),
            recovery,
        )
        assertEquals(
            RuntimeCommand.RetryCatalog(publicationToken, expectedFailureEpoch = 14L),
            foregroundCatalogRetryCommand(requireNotNull(recovery)),
        )
    }

    // Mutation caught: a non-matching publication failure is offered as the installed import's retry.
    @Test
    fun installedForegroundImportIgnoresUnrelatedCatalogRecovery() {
        val token = NarImportAttemptToken("process", 10L, 42)
        val unrelated = CatalogPublicationToken("foreground-import", "other:1:7")
        val snapshot = RuntimeSnapshot.initial().copy(
            catalog = RuntimeCatalogState.Ready(
                epoch = 15L,
                entries = emptyList(),
                publications = mapOf(
                    unrelated to RuntimeCatalogPublicationStatus.RecoveryRequired(
                        targetId = "other",
                        failedEpoch = 15L,
                        reason = RuntimeNoticeCode.CATALOG_TARGET_MISSING,
                    ),
                ),
            ),
        )

        assertNull(
            foregroundCatalogRecovery(
                ForegroundNarImportState.Installed(token, "/ghost/alpha", "alpha"),
                snapshot,
            ),
        )
    }

    private fun fixtureFor(id: String, root: File) = SnapshotRuntimeFixture(
        catalogScanner = RuntimeCatalogScanner {
            listOf(InstalledGhostMetadata(id, root, null, null, File(root, "readme.txt")))
        },
    )
}
