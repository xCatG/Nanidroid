package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.runtime.RuntimeCatalogScanner
import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeHostLease
import com.cattailsw.nanidroid.runtime.RuntimeSnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostRuntimeDialogueHostFenceTest {
    @Test
    fun choiceRequiresExactCurrentForegroundHost() = verifyUserActionFence(
        id = "choice",
        script = "\\q[One,id]\\e",
        ready = { it.dialogue.choices.size == 1 },
        command = { snapshot, host -> RuntimeCommand.ActivateChoice(snapshot.dialogue.choices.single().key, host) },
    )

    @Test
    fun anchorRequiresExactCurrentForegroundHost() = verifyUserActionFence(
        id = "anchor",
        script = "\\_a[id]Link\\_a\\e",
        ready = { it.dialogue.anchors.size == 1 },
        command = { snapshot, host -> RuntimeCommand.ActivateAnchor(snapshot.dialogue.anchors.single().key, host) },
    )

    @Test
    fun inputSubmitRequiresExactCurrentForegroundHost() = verifyUserActionFence(
        id = "submit",
        script = "\\![open,inputbox,name,5000]\\e",
        ready = { it.dialogue.input != null },
        command = { snapshot, host ->
            RuntimeCommand.SubmitInput(requireNotNull(snapshot.dialogue.input).key, "answer", host)
        },
    )

    @Test
    fun inputDismissRequiresExactCurrentForegroundHost() = verifyUserActionFence(
        id = "dismiss",
        script = "\\![open,inputbox,name,5000]\\e",
        ready = { it.dialogue.input != null },
        command = { snapshot, host ->
            RuntimeCommand.DismissInput(requireNotNull(snapshot.dialogue.input).key, host)
        },
    )

    private fun verifyUserActionFence(
        id: String,
        script: String,
        ready: (RuntimeSnapshot) -> Boolean,
        command: (RuntimeSnapshot, RuntimeHostLease) -> RuntimeCommand,
    ) {
        val root = File("build/runtime-dialogue-host/$id").canonicalFile
        SnapshotRuntimeFixture(
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata(id, root, null, null, File(root, "readme.txt")))
            },
        ).use { fixture ->
            fixture.startAttached(id, root)
            val hostA = fixture.makeTopHost(101L)
            fixture.runtime.enqueueScriptForTesting(script)
            fixture.drain()
            fixture.runPlaybackUntil(ready)
            val hostB = fixture.makeTopHost(102L)
            val current = fixture.runtime.snapshots.value

            fixture.runtime.submit(command(current, hostA))
            fixture.drain()

            assertEquals(current, fixture.runtime.snapshots.value)
            assertTrue(fixture.nativePort.requests.isEmpty())
            assertEquals(0, fixture.runtime.pendingSnapshotRequestCountForTesting())
            assertEquals(0, fixture.runtime.claimedDialogueCountForTesting())

            fixture.runtime.submit(command(current, hostB))
            fixture.drain()
            fixture.awaitNativeWork()

            assertEquals(1, fixture.nativePort.requests.size)
            assertEquals(1, fixture.runtime.pendingSnapshotRequestCountForTesting())
            assertEquals(1, fixture.runtime.claimedDialogueCountForTesting())
        }
    }
}
