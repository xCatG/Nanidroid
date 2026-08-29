package com.cattailsw.nanidroid

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.cattailsw.nanidroid.compose.NanidroidComposeShell
import com.cattailsw.nanidroid.compose.NanidroidSimpleDialog
import com.cattailsw.nanidroid.runtime.RuntimeCatalogScanner
import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeCommandDispatcher
import com.cattailsw.nanidroid.runtime.RuntimeHostId
import com.cattailsw.nanidroid.runtime.RuntimeHostLease
import com.cattailsw.nanidroid.runtime.RuntimeNativeLifecycleOutcome
import com.cattailsw.nanidroid.runtime.RuntimeNativeLoadOutcome
import com.cattailsw.nanidroid.runtime.RuntimeNativePort
import com.cattailsw.nanidroid.runtime.RuntimeNoticeCode
import com.cattailsw.nanidroid.runtime.RuntimeRequestToken
import com.cattailsw.nanidroid.runtime.RuntimeScheduleKey
import com.cattailsw.nanidroid.runtime.RuntimeScheduler
import java.io.File
import java.util.Hashtable
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class NanidroidSwitchRecoveryBoundaryTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun postUnloadReplacementFailureKeepsToolbarListAndGoodSelectionReachable() {
        RecoveryFixture().use { fixture ->
            fixture.startAttached("old")
            val top = fixture.makeTopHost()
            val before = fixture.runtime.snapshots.value
            fixture.runtime.submit(RuntimeCommand.SwitchGhost(1L, top, before.modeIdentity, "bad"))
            fixture.awaitRequest().complete(fixture.success(1L))
            fixture.awaitUnload().complete(RuntimeNativeLifecycleOutcome.Success)
            fixture.awaitLoad().complete(
                RuntimeNativeLoadOutcome.Failed(RuntimeNoticeCode.NATIVE_LOAD_FAILED, ownershipCertain = true),
            )
            fixture.drainUntil { fixture.runtime.snapshots.value.phase == GhostRuntimePhase.Idle }

            val failed = fixture.runtime.snapshots.value
            assertEquals(null, failed.generation)
            assertFalse(runtimeBusy(failed))
            val dialog = mutableStateOf<NanidroidSimpleDialog?>(null)
            composeRule.setContent {
                NanidroidComposeShell(
                    ghostStage = { Box(androidx.compose.ui.Modifier) },
                    loading = runtimeBusy(failed),
                    progressMessage = "",
                    toolbarVisible = true,
                    onListGhost = {
                        dialog.value = installedGhostListDialog(
                            snapshot = failed,
                            onSelect = { metadata ->
                                dialog.value = installedGhostDialog(
                                    snapshot = failed,
                                    metadata = metadata,
                                    requestSwitch = { target ->
                                        ghostSelectionCommand(fixture.runtime.snapshots.value, top, target)
                                            ?.let(fixture.runtime::submit)
                                    },
                                    openDocumentLink = {},
                                )
                            },
                            onMore = {},
                        )
                    },
                    simpleDialog = dialog.value,
                    onDismissSimpleDialog = { dialog.value = null },
                )
            }

            composeRule.onNodeWithTag("list-ghost").performClick()
            composeRule.onNodeWithTag("ghost-choice-2").performClick()
            composeRule.onNodeWithTag("no-readme-switch").performClick()

            assertEquals("good", fixture.awaitLoad().prepared.id)
        }
    }

    private class RecoveryFixture : AutoCloseable {
        private val roots = listOf("old", "bad", "good").associateWith { id ->
            File("build/android-recovery/$id").absoluteFile
        }
        private val dispatcher = ManualDispatcher()
        private val scheduler = NoOpScheduler()
        private val native = RecordingNativePort()
        val runtime = GhostRuntime.testRuntime(
            context = null,
            preparer = GhostPreparer { operationId, id, root -> prepared(operationId, id, root) },
            persistence = NoOpPersistence,
            nativePort = native,
            runtimeScheduler = scheduler,
            coordinationDispatcher = dispatcher,
            catalogScanner = RuntimeCatalogScanner {
                roots.map { (id, root) ->
                    InstalledGhostMetadata(id, root, id.replaceFirstChar(Char::uppercase), null, File(root, "missing.txt"))
                }
            },
            canonicalizeRoot = File::getAbsoluteFile,
        )

        init {
            drainUntil { runtime.snapshots.value.catalog.lastProvenEntries.size == roots.size }
        }

        fun startAttached(id: String) {
            runtime.submit(RuntimeCommand.StartGhost(id, roots.getValue(id)))
            awaitLoad().complete(
                RuntimeNativeLoadOutcome.Loaded(
                    com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities(),
                ),
            )
            awaitRequest().complete(success(1L))
            drainUntil { runtime.snapshots.value.phase == GhostRuntimePhase.Attached }
        }

        fun makeTopHost(): RuntimeHostLease {
            val hostId = RuntimeHostId(301L)
            runtime.submit(RuntimeCommand.RegisterHost(RuntimeHostLease(hostId, 1L)))
            runtime.submit(RuntimeCommand.SetResumed(RuntimeHostLease(hostId, 2L), true))
            val top = RuntimeHostLease(hostId, 3L)
            runtime.submit(RuntimeCommand.SetTopResumed(top, true))
            dispatcher.drain()
            return top
        }

        fun awaitLoad(): RecordingNativePort.PendingLoad = await(native.loads)
        fun awaitRequest(): RecordingNativePort.PendingRequest = await(native.requests)
        fun awaitUnload(): RecordingNativePort.PendingUnload = await(native.unloads)

        fun success(generation: Long) = RuntimeResult.Success(
            TaggedShioriResponse(
                generation,
                ShioriResponse("SHIORI/3.0 204 No Content", Hashtable()),
            ),
        )

        fun drainUntil(predicate: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!predicate()) {
                dispatcher.drain()
                if (System.nanoTime() >= deadline) throw AssertionError("runtime did not settle")
                Thread.yield()
            }
        }

        private fun <T> await(queue: ConcurrentLinkedQueue<T>): T {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (true) {
                dispatcher.drain()
                queue.poll()?.let { return it }
                if (System.nanoTime() >= deadline) throw AssertionError("native work did not arrive")
                Thread.yield()
            }
        }

        private fun prepared(operationId: Long, id: String, root: File) = PreparedGhost(
            operationId = operationId,
            id = id,
            canonicalRoot = root.absoluteFile,
            name = id,
            shellName = "master",
            crafterName = null,
            sakuraName = null,
            keroName = null,
            surfaces = SurfaceCatalog.freeze(emptyMap()),
            ghostDescriptor = emptyMap(),
            shellDescriptor = null,
            engine = GhostEngine.Unsupported,
            nanidroidContent = emptyMap(),
        )

        override fun close() = runtime.close()
    }

    private class ManualDispatcher : RuntimeCommandDispatcher {
        private val pending = ConcurrentLinkedQueue<() -> Unit>()
        override fun dispatch(action: () -> Unit) { pending += action }
        fun drain() { while (true) (pending.poll() ?: return).invoke() }
        override fun close() = pending.clear()
    }

    private class NoOpScheduler : RuntimeScheduler {
        override fun schedule(key: RuntimeScheduleKey, delayMillis: Long, action: () -> Unit) = Unit
        override fun cancel(key: RuntimeScheduleKey) = Unit
        override fun close() = Unit
    }

    private class RecordingNativePort : RuntimeNativePort {
        data class PendingLoad(
            val prepared: PreparedGhost,
            val complete: (RuntimeNativeLoadOutcome) -> Unit,
        )
        data class PendingRequest(
            val complete: (RuntimeResult<TaggedShioriResponse>) -> Unit,
        )
        data class PendingUnload(
            val complete: (RuntimeNativeLifecycleOutcome) -> Unit,
        )

        val loads = ConcurrentLinkedQueue<PendingLoad>()
        val requests = ConcurrentLinkedQueue<PendingRequest>()
        val unloads = ConcurrentLinkedQueue<PendingUnload>()

        override fun load(
            operationId: Long,
            generation: Long,
            prepared: PreparedGhost,
            complete: (RuntimeNativeLoadOutcome) -> Unit,
        ) { loads += PendingLoad(prepared, complete) }

        override fun request(
            token: RuntimeRequestToken,
            intent: ShioriRequestIntent,
            fallback: ShioriRequestIntent?,
            complete: (RuntimeResult<TaggedShioriResponse>) -> Unit,
        ) { requests += PendingRequest(complete) }

        override fun unload(
            operationId: Long,
            generation: Long,
            complete: (RuntimeNativeLifecycleOutcome) -> Unit,
        ) { unloads += PendingUnload(complete) }
    }

    private object NoOpPersistence : GhostRuntimePersistence {
        override fun readLastRunGhostId(): String? = null
        override fun commitLastRunGhostId(ghostId: String) = Unit
        override fun readActivationCount(ghostId: String): Long = 0L
        override fun commitActivationCount(ghostId: String, count: Long) = Unit
    }
}
