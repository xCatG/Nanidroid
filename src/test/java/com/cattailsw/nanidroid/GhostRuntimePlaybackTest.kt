package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.install.ForegroundNarImportState
import com.cattailsw.nanidroid.install.NarImportAttemptToken
import com.cattailsw.nanidroid.runtime.CatalogPublicationToken
import com.cattailsw.nanidroid.runtime.RuntimeCatalogScanner
import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeNativeLifecycleOutcome
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
            fixture.nativePort.loads.remove().complete(RuntimeNativeLifecycleOutcome.Success)
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

    private fun fixtureFor(id: String, root: File) = SnapshotRuntimeFixture(
        catalogScanner = RuntimeCatalogScanner {
            listOf(InstalledGhostMetadata(id, root, null, null, File(root, "readme.txt")))
        },
    )
}
