package com.cattailsw.nanidroid

import androidx.compose.ui.unit.IntOffset
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.RuntimeCatalogScanOutcome
import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeGhostMetadata
import com.cattailsw.nanidroid.runtime.RuntimeHostId
import com.cattailsw.nanidroid.runtime.RuntimeHostLease
import com.cattailsw.nanidroid.runtime.RuntimeNativeLifecycleOutcome
import com.cattailsw.nanidroid.runtime.RuntimeNoticeCode
import com.cattailsw.nanidroid.runtime.RuntimeScheduleKind
import com.cattailsw.nanidroid.runtime.RuntimeSurfaceIdentity
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventKind
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import com.cattailsw.nanidroid.runtime.CatalogPublicationToken
import com.cattailsw.nanidroid.runtime.RuntimeCatalogScanner
import com.cattailsw.nanidroid.runtime.RuntimeCatalogState
import java.io.File
import java.util.Hashtable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostRuntimeSnapshotTest {
    // Mutation caught: snapshot mode constructs the legacy runner or legacy mode starts command ownership.
    @Test
    fun constructorSelectsExactlyOneRuntimeAuthority() {
        GhostRuntime(null).use { legacy ->
            assertTrue(legacy.hasLegacyRunnerAuthorityForTesting())
            assertFalse(legacy.hasSnapshotAuthorityForTesting())
            legacy.submit(RuntimeCommand.CatalogScanned(0L, RuntimeCatalogScanOutcome.Scanned(emptyList())))
            assertEquals(0L, legacy.snapshotRevisionForTesting())
        }

        SnapshotRuntimeFixture().use { fixture ->
            assertFalse(fixture.runtime.hasLegacyRunnerAuthorityForTesting())
            assertTrue(fixture.runtime.hasSnapshotAuthorityForTesting())
        }
    }

    // Mutation caught: a command runs inline or skips an older queued command.
    @Test
    fun submitIsNonBlockingAndFifo() {
        SnapshotRuntimeFixture().use { fixture ->
            val initial = fixture.runtime.snapshots.value
            val lease = RuntimeHostLease(RuntimeHostId(7L), 1L)

            fixture.runtime.submit(RuntimeCommand.RegisterHost(lease))
            fixture.runtime.submit(RuntimeCommand.CatalogScanned(0L, RuntimeCatalogScanOutcome.Scanned(emptyList())))

            assertEquals(initial, fixture.runtime.snapshots.value)
            assertEquals(2, fixture.drain())
            assertEquals(
                listOf("RegisterHost", "CatalogScanned"),
                fixture.runtime.snapshotCommandTraceForTesting().takeLast(2),
            )
            assertEquals(initial, fixture.runtime.snapshots.value)
        }
    }

    // Mutation caught: a native completion reduces state inline instead of returning at the command tail.
    @Test
    fun nativeCompletionReentersCoordinationTail() {
        val root = File("build/runtime-snapshot/native-tail").canonicalFile
        SnapshotRuntimeFixture(
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("native-tail", root, null, null, File(root, "readme.txt")))
            },
        ).use { fixture ->
            fixture.runtime.submit(RuntimeCommand.StartGhost("native-tail", root))
            fixture.drain()
            fixture.awaitNativeWork()
            val load = requireNotNull(fixture.nativePort.loads.poll())

            load.complete(RuntimeNativeLifecycleOutcome.Success)
            fixture.runtime.submit(RuntimeCommand.RegisterHost(RuntimeHostLease(RuntimeHostId(9L), 1L)))

            assertEquals("PreparationCompleted", fixture.runtime.snapshotCommandTraceForTesting().last())
            fixture.drain()
            assertEquals(
                listOf("NativeLoadCompleted", "RegisterHost"),
                fixture.runtime.snapshotCommandTraceForTesting().takeLast(2),
            )
            assertEquals(1L, fixture.runtime.snapshots.value.generation)
        }
    }

    // Mutation caught: catalog publication replaces or retires an active generation.
    @Test
    fun catalogPublicationCannotMutateActiveGeneration() {
        val root = File("build/runtime-snapshot/catalog-active").canonicalFile
        SnapshotRuntimeFixture(
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("catalog-active", root, null, null, File(root, "readme.txt")))
            },
        ).use { fixture ->
            fixture.runtime.submit(RuntimeCommand.StartGhost("catalog-active", root))
            fixture.awaitNativeWork()
            fixture.nativePort.loads.remove().complete(RuntimeNativeLifecycleOutcome.Success)
            fixture.drain()
            fixture.drainUntil { fixture.runtime.snapshots.value.generation != null }
            val before = fixture.runtime.snapshots.value

            fixture.runtime.submit(
                RuntimeCommand.CatalogChanged(CatalogPublicationToken("nar", "1"), "new-ghost"),
            )
            fixture.drain()
            fixture.runtime.submit(
                RuntimeCommand.CatalogScanned(
                    1L,
                    RuntimeCatalogScanOutcome.Scanned(
                        listOf(RuntimeGhostMetadata("new-ghost", "new", null, null, "")),
                    ),
                ),
            )
            fixture.drain()

            assertEquals(before.generation, fixture.runtime.snapshots.value.generation)
            assertEquals(before.activeGhostId, fixture.runtime.snapshots.value.activeGhostId)
        }
    }

    // Mutation caught: runtime bypasses the player scheduler or advances playback inside the delayed callback.
    @Test
    fun playbackDelayOnlyEnqueuesAValidatedDueCommand() {
        val root = File("build/runtime-snapshot/player-delay").canonicalFile
        fixtureFor("player-delay", root).use { fixture ->
            fixture.startAttached("player-delay", root)
            fixture.runtime.enqueueScriptForTesting("\\hAB\\e")
            fixture.drain()
            val before = fixture.runtime.snapshots.value
            val scheduled = fixture.scheduler.scheduled().single()

            fixture.scheduler.run(scheduled.key)

            assertEquals(before, fixture.runtime.snapshots.value)
            fixture.drain()
            assertEquals("A", fixture.runtime.snapshots.value.presentation.sakura.text)
        }
    }

    // Mutation caught: a normal choice leaves a sibling claimable or admits more than one response claim.
    @Test
    fun normalChoiceClearsSiblingsAndClaimsOneExactResponse() {
        val root = File("build/runtime-snapshot/dialogue-choice").canonicalFile
        fixtureFor("dialogue-choice", root).use { fixture ->
            fixture.startAttached("dialogue-choice", root)
            fixture.runtime.enqueueScriptForTesting("\\q[One,id1]\\q[Two,id2]\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.dialogue.choices.size == 2 }
            val first = fixture.runtime.snapshots.value.dialogue.choices[0]
            val sibling = fixture.runtime.snapshots.value.dialogue.choices[1]

            fixture.runtime.submit(RuntimeCommand.ActivateChoice(first.key))
            fixture.runtime.submit(RuntimeCommand.ActivateChoice(sibling.key))
            fixture.drain()
            fixture.awaitNativeWork()

            assertTrue(fixture.runtime.snapshots.value.dialogue.choices.isEmpty())
            assertEquals(1, fixture.nativePort.requests.size)
            assertEquals(1, fixture.runtime.pendingSnapshotRequestCountForTesting())
            assertEquals(1, fixture.runtime.claimedDialogueCountForTesting())

            fixture.nativePort.requests.remove().complete(
                RuntimeResult.Success(TaggedShioriResponse(1L, response(204))),
            )
            fixture.drain()
            assertEquals(0, fixture.runtime.pendingSnapshotRequestCountForTesting())
            assertEquals(0, fixture.runtime.claimedDialogueCountForTesting())
        }
    }

    // Mutation caught: a local-script choice invokes SHIORI or leaves sibling choices live.
    @Test
    fun localScriptChoiceQueuesLocallyWithoutNativeClaim() {
        val root = File("build/runtime-snapshot/local-choice").canonicalFile
        fixtureFor("local-choice", root).use { fixture ->
            fixture.startAttached("local-choice", root)
            fixture.runtime.enqueueScriptForTesting("\\q[Remote,id]\\q[Local,script:\\hDone\\e]\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.dialogue.choices.size == 2 }
            val local = fixture.runtime.snapshots.value.dialogue.choices.last()

            fixture.runtime.submit(RuntimeCommand.ActivateChoice(local.key))
            fixture.drain()

            assertTrue(fixture.runtime.snapshots.value.dialogue.choices.isEmpty())
            assertTrue(fixture.nativePort.requests.isEmpty())
            assertEquals(0, fixture.runtime.pendingSnapshotRequestCountForTesting())
        }
    }

    // Mutation caught: a no-content anchor response consumes the published anchor or its next activation.
    @Test
    fun anchorNoContentSettlesOnlyItsClaimAndRemainsReusable() {
        val root = File("build/runtime-snapshot/anchor").canonicalFile
        fixtureFor("anchor", root).use { fixture ->
            fixture.startAttached("anchor", root)
            fixture.runtime.enqueueScriptForTesting("\\_a[id,tail]Link\\_a\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.dialogue.anchors.size == 1 }
            val anchor = fixture.runtime.snapshots.value.dialogue.anchors.single()

            repeat(2) {
                fixture.runtime.submit(RuntimeCommand.ActivateAnchor(anchor.key))
                fixture.drain()
                fixture.awaitNativeWork()
                fixture.nativePort.requests.remove().complete(
                    RuntimeResult.Success(TaggedShioriResponse(1L, response(204))),
                )
                fixture.drain()
                assertEquals(anchor, fixture.runtime.snapshots.value.dialogue.anchors.single())
            }

            assertEquals(0, fixture.runtime.claimedDialogueCountForTesting())
        }
    }

    // Mutation caught: native load publishes Attached before the exact boot response is admitted at the tail.
    @Test
    fun firstLoadRequestsOneOnFirstBootAndAttachesOnlyAfterTailResponse() {
        val root = File("build/runtime-snapshot/attachment").canonicalFile
        fixtureFor("attachment", root).use { fixture ->
            fixture.runtime.submit(RuntimeCommand.StartGhost("attachment", root))
            fixture.awaitNativeWork()
            fixture.nativePort.loads.remove().complete(RuntimeNativeLifecycleOutcome.Success)
            fixture.drain()
            fixture.awaitNativeWork()
            val request = fixture.nativePort.requests.remove()

            assertEquals(GhostRuntimePhase.Attaching, fixture.runtime.snapshots.value.phase)
            assertTrue(request.intent.protocolText.contains("ID: OnFirstBoot\r\n"))
            request.complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(204))))
            assertEquals(GhostRuntimePhase.Attaching, fixture.runtime.snapshots.value.phase)

            fixture.drain()
            fixture.drainUntil { fixture.runtime.snapshots.value.phase == GhostRuntimePhase.Attached }
            assertEquals(0, fixture.runtime.pendingSnapshotRequestCountForTesting())
        }
    }

    // Mutation caught: foreground loss leaves the old clock epoch able to publish a blocked timer response.
    @Test
    fun foregroundLossStopsClockAndRejectsOldTimerResponse() {
        val root = File("build/runtime-snapshot/timer-fence").canonicalFile
        fixtureFor("timer-fence", root).use { fixture ->
            fixture.startAttached("timer-fence", root)
            val top = fixture.makeTopHost(41L)
            val scheduled = fixture.scheduler.scheduled().single { it.key.kind == RuntimeScheduleKind.CLOCK }

            fixture.scheduler.run(scheduled.key)
            fixture.drain()
            fixture.awaitNativeWork()
            val timerRequest = fixture.nativePort.requests.remove()
            val before = fixture.runtime.snapshots.value

            val lost = top.copy(hostEpoch = top.hostEpoch + 1L)
            fixture.runtime.submit(RuntimeCommand.SetTopResumed(lost, false))
            fixture.drain()
            timerRequest.complete(
                RuntimeResult.Success(TaggedShioriResponse(1L, response(200, "\\hSTALE\\e"))),
            )
            fixture.drain()

            assertFalse(fixture.runtime.snapshots.value.clockRunning)
            assertEquals(before.presentation, fixture.runtime.snapshots.value.presentation)
            assertEquals("NativeResponseRejected", fixture.runtime.snapshotCommandTraceForTesting().last())
        }
    }

    // Mutation caught: one clock tick omits the minute bucket or repeats it every second.
    @Test
    fun clockTickAdmitsSecondAndMinuteBucketsIndependently() {
        val root = File("build/runtime-snapshot/clock-buckets").canonicalFile
        val elapsed = AtomicLong(60_000L)
        SnapshotRuntimeFixture(
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("clock-buckets", root, null, null, File(root, "readme.txt")))
            },
            elapsedRealtimeMillis = elapsed::get,
        ).use { fixture ->
            fixture.startAttached("clock-buckets", root)
            fixture.makeTopHost(42L)
            fixture.scheduler.runNext(RuntimeScheduleKind.CLOCK)
            fixture.drain()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (fixture.nativePort.requests.size < 2) {
                if (System.nanoTime() >= deadline) throw AssertionError("second/minute requests did not arrive")
                Thread.yield()
            }
            val firstTick = listOf(fixture.nativePort.requests.remove(), fixture.nativePort.requests.remove())
            assertEquals(
                listOf("OnSecondChange", "OnMinuteChange"),
                firstTick.map { it.intent.protocolText.lineSequence().first { line -> line.startsWith("ID: ") }.removePrefix("ID: ") },
            )
            firstTick.forEach {
                it.complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(204))))
            }
            fixture.drain()
            assertEquals(0, fixture.runtime.pendingSnapshotRequestCountForTesting())

            elapsed.set(61_000L)
            fixture.scheduler.runNext(RuntimeScheduleKind.CLOCK)
            fixture.drain()
            fixture.awaitNativeWork()
            assertTrue(fixture.nativePort.requests.remove().intent.protocolText.contains("ID: OnSecondChange\r\n"))
            assertTrue(fixture.nativePort.requests.isEmpty())
        }
    }

    // Mutation caught: repeated Back creates two OnClose requests or two exit terminals.
    @Test
    fun repeatedBackJoinsOneExitRequestAndOneConsumableLease() {
        val root = File("build/runtime-snapshot/exit").canonicalFile
        fixtureFor("exit", root).use { fixture ->
            fixture.startAttached("exit", root)
            val top = fixture.makeTopHost(51L)
            val before = fixture.runtime.snapshots.value
            val back = RuntimeCommand.Back(before.generation, top, before.modeIdentity)

            fixture.runtime.submit(back)
            fixture.runtime.submit(back)
            fixture.drain()
            fixture.awaitNativeWork()
            val close = fixture.nativePort.requests.remove()

            assertTrue(close.intent.protocolText.contains("ID: OnClose\r\n"))
            assertTrue(fixture.nativePort.requests.isEmpty())
            close.complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(204))))
            fixture.drain()
            val lease = requireNotNull(fixture.runtime.snapshots.value.exit?.offeredLease)

            fixture.runtime.submit(RuntimeCommand.ClaimExit(lease))
            fixture.runtime.submit(RuntimeCommand.AcknowledgeExit(lease))
            fixture.drain()
            assertEquals(null, fixture.runtime.snapshots.value.exit)
            assertTrue(fixture.nativePort.requests.isEmpty())
        }
    }

    @Test
    fun fatalOnClosePreservesSameOneShotExitOperation() {
        val root = File("build/runtime-snapshot/fatal-close").canonicalFile
        fixtureFor("fatal-close", root).use { fixture ->
            fixture.startAttached("fatal-close", root)
            val top = fixture.makeTopHost(52L)
            val before = fixture.runtime.snapshots.value
            val back = RuntimeCommand.Back(1L, top, before.modeIdentity)
            fixture.runtime.submit(back)
            fixture.drain()
            fixture.awaitNativeWork()
            val close = fixture.nativePort.requests.remove()
            val operationId = requireNotNull(close.token.parentOperationId)

            close.complete(RuntimeResult.Failure(RuntimeFailure.Fatal(IllegalStateException("fatal close"))))
            fixture.drain()
            assertEquals(GhostRuntimePhase.Poisoned, fixture.runtime.snapshots.value.phase)
            assertEquals(operationId, fixture.runtime.snapshots.value.exit?.operationId)

            fixture.runtime.submit(back)
            fixture.drain()
            assertEquals(operationId, fixture.runtime.snapshots.value.exit?.operationId)
            assertTrue(fixture.nativePort.requests.isEmpty())
        }
    }

    @Test
    fun fatalExitOwnedPlaybackPreservesSameOneShotExitOperation() {
        val root = File("build/runtime-snapshot/fatal-exit-playback").canonicalFile
        fixtureFor("fatal-exit-playback", root).use { fixture ->
            fixture.startAttached("fatal-exit-playback", root)
            val top = fixture.makeTopHost(53L)
            val before = fixture.runtime.snapshots.value
            val back = RuntimeCommand.Back(1L, top, before.modeIdentity)
            fixture.runtime.submit(back)
            fixture.drain()
            fixture.awaitNativeWork()
            val close = fixture.nativePort.requests.remove()
            val operationId = requireNotNull(close.token.parentOperationId)
            close.complete(
                RuntimeResult.Success(TaggedShioriResponse(1L, response(200, "\\s[42]A\\e"))),
            )
            fixture.drain()
            fixture.scheduler.runNext(RuntimeScheduleKind.PLAYBACK)
            fixture.drain()
            fixture.awaitNativeWork()

            fixture.nativePort.requests.remove().complete(
                RuntimeResult.Failure(RuntimeFailure.Fatal(IllegalStateException("fatal playback"))),
            )
            fixture.drain()
            assertEquals(GhostRuntimePhase.Poisoned, fixture.runtime.snapshots.value.phase)
            assertEquals(operationId, fixture.runtime.snapshots.value.exit?.operationId)

            fixture.runtime.submit(back)
            fixture.drain()
            assertEquals(operationId, fixture.runtime.snapshots.value.exit?.operationId)
            assertTrue(fixture.nativePort.requests.isEmpty())
        }
    }

    // Mutation caught: switch target preparation starts before outgoing unload is proven successful.
    @Test
    fun switchNoContentUnloadsBeforePreparingAndAttachesReplacementOnce() {
        val oldRoot = File("build/runtime-snapshot/switch-old").canonicalFile
        val newRoot = File("build/runtime-snapshot/switch-new").canonicalFile
        SnapshotRuntimeFixture(
            catalogScanner = RuntimeCatalogScanner {
                listOf(
                    InstalledGhostMetadata("switch-old", oldRoot, "Old", null, File(oldRoot, "readme.txt")),
                    InstalledGhostMetadata("switch-new", newRoot, "New", null, File(newRoot, "readme.txt")),
                )
            },
        ).use { fixture ->
            fixture.startAttached("switch-old", oldRoot)
            val top = fixture.makeTopHost(61L)
            val before = fixture.runtime.snapshots.value

            fixture.runtime.submit(
                RuntimeCommand.SwitchGhost(
                    generation = requireNotNull(before.generation),
                    host = top,
                    expected = before.modeIdentity,
                    targetGhostId = "switch-new",
                ),
            )
            fixture.drain()
            fixture.awaitNativeWork()
            val changing = fixture.nativePort.requests.remove()
            assertTrue(changing.intent.protocolText.contains("ID: OnGhostChanging\r\n"))
            assertTrue(fixture.nativePort.unloads.isEmpty())
            assertTrue(fixture.nativePort.loads.isEmpty())

            changing.complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(204))))
            fixture.drain()
            fixture.awaitNativeWork()
            val unload = fixture.nativePort.unloads.remove()
            assertEquals(1L, unload.generation)
            assertTrue(fixture.nativePort.loads.isEmpty())

            unload.complete(RuntimeNativeLifecycleOutcome.Success)
            fixture.awaitNativeWork()
            val replacement = fixture.nativePort.loads.remove()
            assertEquals("switch-new", replacement.prepared.id)
            replacement.complete(RuntimeNativeLifecycleOutcome.Success)
            fixture.drain()
            fixture.awaitNativeWork()
            val changed = fixture.nativePort.requests.remove()
            assertTrue(changed.intent.protocolText.contains("ID: OnGhostChanged\r\n"))
            changed.complete(RuntimeResult.Success(TaggedShioriResponse(2L, response(204))))
            fixture.drainUntil { fixture.runtime.snapshots.value.phase == GhostRuntimePhase.Attached }

            assertEquals(2L, fixture.runtime.snapshots.value.generation)
            assertEquals("switch-new", fixture.runtime.snapshots.value.activeGhostId)
            assertEquals(GhostRuntimePhase.Attached, fixture.runtime.snapshots.value.phase)

            val replacementMode = fixture.runtime.snapshots.value
            fixture.runtime.submit(RuntimeCommand.Back(2L, top, replacementMode.modeIdentity))
            fixture.drain()
            fixture.awaitNativeWork()
            assertTrue(fixture.nativePort.requests.remove().intent.protocolText.contains("ID: OnClose\r\n"))
        }
    }

    @Test
    fun mismatchedParentGenerationCannotAdvanceSwitch() {
        val oldRoot = File("build/runtime-snapshot/mismatch-old").canonicalFile
        val newRoot = File("build/runtime-snapshot/mismatch-new").canonicalFile
        SnapshotRuntimeFixture(
            catalogScanner = RuntimeCatalogScanner {
                listOf(
                    InstalledGhostMetadata("mismatch-old", oldRoot, "Old", null, File(oldRoot, "readme.txt")),
                    InstalledGhostMetadata("mismatch-new", newRoot, "New", null, File(newRoot, "readme.txt")),
                )
            },
        ).use { fixture ->
            fixture.startAttached("mismatch-old", oldRoot)
            val top = fixture.makeTopHost(62L)
            val before = fixture.runtime.snapshots.value
            fixture.runtime.submit(RuntimeCommand.SwitchGhost(1L, top, before.modeIdentity, "mismatch-new"))
            fixture.drain()
            fixture.awaitNativeWork()
            fixture.nativePort.requests.remove().complete(
                RuntimeResult.Success(TaggedShioriResponse(99L, response(204))),
            )
            fixture.drain()

            assertEquals(GhostRuntimePhase.SwitchPlayback, fixture.runtime.snapshots.value.phase)
            assertTrue(fixture.nativePort.unloads.isEmpty())
        }
    }

    @Test
    fun asynchronousNativeResponsesReduceInSubmissionOrder() {
        val root = File("build/runtime-snapshot/async-fifo").canonicalFile
        fixtureFor("async-fifo", root).use { fixture ->
            fixture.startAttached("async-fifo", root)
            fixture.runtime.enqueueScriptForTesting("\\_a[first]One\\_a\\_a[second]Two\\_a\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.dialogue.anchors.size == 2 }
            val anchors = fixture.runtime.snapshots.value.dialogue.anchors
            fixture.runtime.submit(RuntimeCommand.ActivateAnchor(anchors[0].key))
            fixture.runtime.submit(RuntimeCommand.ActivateAnchor(anchors[1].key))
            fixture.drain()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (fixture.nativePort.requests.size < 2 && System.nanoTime() < deadline) Thread.yield()
            val first = fixture.nativePort.requests.remove()
            val second = fixture.nativePort.requests.remove()

            second.complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(200, "\\hSECOND\\e"))))
            fixture.drain()
            assertFalse(fixture.runtime.snapshots.value.presentation.sakura.text.contains("SECOND"))
            first.complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(200, "\\hFIRST\\e"))))
            fixture.drain()
            fixture.runPlaybackUntil { it.presentation.sakura.text.contains("FIRST") }

            assertFalse(fixture.runtime.snapshots.value.presentation.sakura.text.contains("SECOND"))
        }
    }

    @Test
    fun presentationAndClockHousekeepingDoNotStalePublishedMode() {
        val root = File("build/runtime-snapshot/mode-stability").canonicalFile
        val elapsed = AtomicLong(60_000L)
        SnapshotRuntimeFixture(
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("mode-stability", root, null, null, File(root, "readme.txt")))
            },
            elapsedRealtimeMillis = elapsed::get,
        ).use { fixture ->
            fixture.startAttached("mode-stability", root)
            val top = fixture.makeTopHost(63L)
            fixture.runtime.enqueueScriptForTesting("\\hAB\\e")
            fixture.drain()
            val published = fixture.runtime.snapshots.value.modeIdentity
            fixture.scheduler.runNext(RuntimeScheduleKind.PLAYBACK)
            fixture.drain()
            fixture.scheduler.runNext(RuntimeScheduleKind.CLOCK)
            fixture.drain()
            assertEquals(published, fixture.runtime.snapshots.value.modeIdentity)

            fixture.runtime.submit(RuntimeCommand.Back(1L, top, published))
            fixture.drain()
            fixture.awaitNativeWork()
            assertTrue(fixture.nativePort.requests.any { it.intent.protocolText.contains("ID: OnClose\r\n") })
        }
    }

    // Mutation caught: Loading or Failed is treated as a proven empty catalog and starts preparation.
    @Test
    fun startupWaitsForProvenReadyAndReadyEmptyDoesNotSelectGhost() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val root = File("build/runtime-snapshot/catalog-gate").canonicalFile
        val scanner = RuntimeCatalogScanner {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            emptyList()
        }
        SnapshotRuntimeFixture(catalogScanner = scanner, awaitInitialCatalog = false).use { fixture ->
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            fixture.runtime.submit(RuntimeCommand.StartGhost("missing", root))
            fixture.drain()
            assertEquals(null, fixture.runtime.snapshots.value.generation)
            assertTrue(fixture.nativePort.loads.isEmpty())

            release.countDown()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (fixture.dispatcher.isEmpty()) {
                if (System.nanoTime() >= deadline) throw AssertionError("catalog result did not return")
                Thread.yield()
            }
            fixture.drain()
            assertTrue(fixture.runtime.snapshots.value.catalog is RuntimeCatalogState.Ready)

            fixture.runtime.submit(RuntimeCommand.StartGhost("missing", root))
            fixture.drain()
            assertEquals(null, fixture.runtime.snapshots.value.generation)
            assertTrue(fixture.nativePort.loads.isEmpty())
        }
    }

    @Test
    fun poisonedUnloadFencesNativeWorkAndBackOffersOneLocalExit() {
        val oldRoot = File("build/runtime-snapshot/poison-old").canonicalFile
        val newRoot = File("build/runtime-snapshot/poison-new").canonicalFile
        SnapshotRuntimeFixture(
            catalogScanner = RuntimeCatalogScanner {
                listOf(
                    InstalledGhostMetadata("poison-old", oldRoot, "Old", null, File(oldRoot, "readme.txt")),
                    InstalledGhostMetadata("poison-new", newRoot, "New", null, File(newRoot, "readme.txt")),
                )
            },
        ).use { fixture ->
            fixture.startAttached("poison-old", oldRoot)
            val top = fixture.makeTopHost(64L)
            var snapshot = fixture.runtime.snapshots.value
            fixture.runtime.submit(RuntimeCommand.SwitchGhost(1L, top, snapshot.modeIdentity, "poison-new"))
            fixture.drain()
            fixture.awaitNativeWork()
            fixture.nativePort.requests.remove().complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(204))))
            fixture.drain()
            fixture.awaitNativeWork()
            fixture.nativePort.unloads.remove().complete(
                RuntimeNativeLifecycleOutcome.Failed(RuntimeNoticeCode.NATIVE_UNLOAD_FAILED, ownershipCertain = false),
            )
            fixture.drain()
            snapshot = fixture.runtime.snapshots.value
            assertEquals(GhostRuntimePhase.Poisoned, snapshot.phase)
            assertFalse(snapshot.clockRunning)

            fixture.runtime.submit(RuntimeCommand.Back(1L, top, snapshot.modeIdentity))
            fixture.runtime.submit(RuntimeCommand.Back(1L, top, snapshot.modeIdentity))
            fixture.drain()
            assertEquals(0, fixture.nativePort.requests.size)
            assertTrue(fixture.runtime.snapshots.value.exit != null)
        }
    }

    @Test
    fun blockedPersistenceCannotDelayHostLoss() {
        val root = File("build/runtime-snapshot/io-tail").canonicalFile
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val persistence = object : GhostRuntimePersistence {
            override fun readLastRunGhostId(): String? = null
            override fun commitLastRunGhostId(ghostId: String) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            override fun readActivationCount(ghostId: String) = 0L
            override fun commitActivationCount(ghostId: String, count: Long) = Unit
        }
        SnapshotRuntimeFixture(
            persistence = persistence,
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("io-tail", root, null, null, File(root, "readme.txt")))
            },
        ).use { fixture ->
            fixture.runtime.submit(RuntimeCommand.StartGhost("io-tail", root))
            fixture.awaitNativeWork()
            fixture.nativePort.loads.remove().complete(RuntimeNativeLifecycleOutcome.Success)
            fixture.runtime.submit(RuntimeCommand.RegisterHost(RuntimeHostLease(RuntimeHostId(65L), 1L)))
            val drained = AtomicBoolean(false)
            val thread = Thread { fixture.drain(); drained.set(true) }
            thread.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            Thread.sleep(50L)
            assertTrue("coordination tail was blocked by persistence", drained.get())
            assertEquals("RegisterHost", fixture.runtime.snapshotCommandTraceForTesting().last())
            release.countDown()
            thread.join(5_000L)
        }
    }

    @Test
    fun nullableBackCannotOrphanNativeOwnedLoadDuringBlockedCommit() {
        val root = File("build/runtime-snapshot/native-owned-back").canonicalFile
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val persistence = blockingLastRunPersistence(entered, release)
        SnapshotRuntimeFixture(
            persistence = persistence,
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("native-owned-back", root, null, null, File(root, "readme.txt")))
            },
        ).use { fixture ->
            fixture.runtime.submit(RuntimeCommand.StartGhost("native-owned-back", root))
            fixture.awaitNativeWork()
            fixture.nativePort.loads.remove().complete(RuntimeNativeLifecycleOutcome.Success)
            fixture.drain()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val blocked = fixture.runtime.snapshots.value

            fixture.runtime.submit(
                RuntimeCommand.Back(null, RuntimeHostLease(RuntimeHostId(0L), 0L), blocked.modeIdentity),
            )
            fixture.drain()
            assertEquals(null, fixture.runtime.snapshots.value.exit)
            assertEquals(blocked.pending, fixture.runtime.snapshots.value.pending)

            release.countDown()
            fixture.drainUntil { fixture.runtime.snapshots.value.generation == 1L }
        }
    }

    @Test
    fun closeCleansNativeOwnedLoadWhileCommitIsBlocked() {
        val root = File("build/runtime-snapshot/native-owned-close").canonicalFile
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = SnapshotRuntimeFixture(
            persistence = blockingLastRunPersistence(entered, release),
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("native-owned-close", root, null, null, File(root, "readme.txt")))
            },
        )
        try {
            fixture.runtime.submit(RuntimeCommand.StartGhost("native-owned-close", root))
            fixture.awaitNativeWork()
            fixture.nativePort.loads.remove().complete(RuntimeNativeLifecycleOutcome.Success)
            fixture.drain()
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            fixture.close()
            val cleanup = fixture.nativePort.unloads.single()
            assertEquals(1L, cleanup.generation)
            assertTrue(cleanup.invocationThreadName.startsWith("GhostRuntime-SnapshotNative"))
        } finally {
            release.countDown()
            fixture.close()
        }
    }

    @Test
    fun blockingThrowingCancelSerializesWithCloseAndCannotKillCoordination() {
        val root = File("build/runtime-snapshot/cancel-close").canonicalFile
        val scheduler = BlockingThrowingCancelRuntimeScheduler()
        val fixture = SnapshotRuntimeFixture(
            scheduler = scheduler,
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("cancel-close", root, null, null, File(root, "readme.txt")))
            },
        )
        val coordinationFailure = AtomicReference<Throwable?>()
        val closeFailure = AtomicReference<Throwable?>()
        try {
            fixture.startAttached("cancel-close", root)
            val top = fixture.makeTopHost(66L)
            scheduler.armed.set(true)
            fixture.runtime.submit(RuntimeCommand.SetTopResumed(top.copy(hostEpoch = 4L), false))
            val coordination = Thread {
                runCatching { fixture.drain() }.exceptionOrNull()?.let(coordinationFailure::set)
            }
            coordination.start()
            assertTrue(scheduler.cancelEntered.await(5, TimeUnit.SECONDS))
            val closing = Thread {
                runCatching { fixture.close() }.exceptionOrNull()?.let(closeFailure::set)
            }
            closing.start()
            Thread.sleep(50L)
            val closeWaitedForCancel = closing.isAlive
            scheduler.cancelRelease.countDown()
            coordination.join(5_000L)
            closing.join(5_000L)

            assertTrue("close did not serialize behind in-flight cancel", closeWaitedForCancel)
            assertEquals(null, coordinationFailure.get())
            assertEquals(null, closeFailure.get())
        } finally {
            scheduler.cancelRelease.countDown()
            fixture.close()
        }
    }

    @Test
    fun synchronousNativeThrowCompletesItsSequenceBeforeLaterResponse() {
        val root = File("build/runtime-snapshot/native-throw").canonicalFile
        val port = object : RecordingRuntimeNativePort() {
            val throwNextPointer = AtomicBoolean(true)

            override fun request(
                token: com.cattailsw.nanidroid.runtime.RuntimeRequestToken,
                intent: ShioriRequestIntent,
                fallback: ShioriRequestIntent?,
                complete: (RuntimeResult<TaggedShioriResponse>) -> Unit,
            ) {
                if (intent.protocolText.contains("ID: OnMouseClick\r\n") && throwNextPointer.compareAndSet(true, false)) {
                    throw IllegalStateException("native request threw")
                }
                super.request(token, intent, fallback, complete)
            }
        }
        SnapshotRuntimeFixture(
            nativePort = port,
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("native-throw", root, null, null, File(root, "readme.txt")))
            },
        ).use { fixture ->
            fixture.startAttached("native-throw", root)
            val top = fixture.makeTopHost(67L)
            val effect = SurfaceInteractionEffect(
                PointerEventKind.CLICK,
                SurfaceSpeaker.SAKURA,
                IntOffset(1, 2),
                0,
                PointerSource.TOUCH,
                "head",
                null,
            )
            val surface = RuntimeSurfaceIdentity(1L, GhostSpeaker.SAKURA, "0", 0L)
            fixture.runtime.submit(RuntimeCommand.Pointer(1L, top, surface, effect))
            fixture.runtime.submit(RuntimeCommand.Pointer(1L, top, surface, effect))
            fixture.drain()
            fixture.awaitNativeWork()
            port.requests.remove().complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(204))))
            fixture.drain()

            assertEquals(GhostRuntimePhase.Poisoned, fixture.runtime.snapshots.value.phase)
            assertEquals(0, fixture.runtime.pendingSnapshotRequestCountForTesting())
            assertEquals("NativeResponseRejected", fixture.runtime.snapshotCommandTraceForTesting().last())
        }
    }

    @Test
    fun synchronousNativeLoadThrowReturnsFatalLifecycleCompletion() {
        val root = File("build/runtime-snapshot/load-throw").canonicalFile
        val port = object : RecordingRuntimeNativePort() {
            override fun load(
                operationId: Long,
                generation: Long,
                prepared: PreparedGhost,
                complete: (RuntimeNativeLifecycleOutcome) -> Unit,
            ) = throw IllegalStateException("native load threw")
        }
        SnapshotRuntimeFixture(
            nativePort = port,
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("load-throw", root, null, null, File(root, "readme.txt")))
            },
        ).use { fixture ->
            fixture.runtime.submit(RuntimeCommand.StartGhost("load-throw", root))
            fixture.drainUntil { fixture.runtime.snapshots.value.phase == GhostRuntimePhase.Poisoned }

            assertEquals(RuntimeNoticeCode.NATIVE_LOAD_FAILED, fixture.runtime.snapshots.value.notice?.code)
            assertEquals(0, fixture.runtime.pendingSnapshotRequestCountForTesting())
        }
    }

    @Test
    fun synchronousNativeUnloadThrowReturnsFatalLifecycleCompletion() {
        val oldRoot = File("build/runtime-snapshot/unload-throw-old").canonicalFile
        val newRoot = File("build/runtime-snapshot/unload-throw-new").canonicalFile
        val port = object : RecordingRuntimeNativePort() {
            val throwUnload = AtomicBoolean(false)

            override fun unload(
                operationId: Long,
                generation: Long,
                complete: (RuntimeNativeLifecycleOutcome) -> Unit,
            ) {
                if (throwUnload.get()) throw IllegalStateException("native unload threw")
                super.unload(operationId, generation, complete)
            }
        }
        SnapshotRuntimeFixture(
            nativePort = port,
            catalogScanner = RuntimeCatalogScanner {
                listOf(
                    InstalledGhostMetadata("unload-throw-old", oldRoot, "Old", null, File(oldRoot, "readme.txt")),
                    InstalledGhostMetadata("unload-throw-new", newRoot, "New", null, File(newRoot, "readme.txt")),
                )
            },
        ).use { fixture ->
            fixture.startAttached("unload-throw-old", oldRoot)
            val top = fixture.makeTopHost(68L)
            val before = fixture.runtime.snapshots.value
            port.throwUnload.set(true)
            fixture.runtime.submit(RuntimeCommand.SwitchGhost(1L, top, before.modeIdentity, "unload-throw-new"))
            fixture.drain()
            fixture.awaitNativeWork()
            port.requests.remove().complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(204))))
            fixture.drainUntil { fixture.runtime.snapshots.value.phase == GhostRuntimePhase.Poisoned }

            assertEquals(RuntimeNoticeCode.NATIVE_UNLOAD_FAILED, fixture.runtime.snapshots.value.notice?.code)
        }
    }

    @Test
    fun blockedCanonicalizationCannotDelayBackOrStartAfterExit() {
        val root = File("build/runtime-snapshot/canonical-tail")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        SnapshotRuntimeFixture(
            canonicalizeRoot = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                it.canonicalFile
            },
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("canonical-tail", root, null, null, File(root, "readme.txt")))
            },
        ).use { fixture ->
            fixture.runtime.submit(RuntimeCommand.StartGhost("canonical-tail", root))
            fixture.drain()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val snapshot = fixture.runtime.snapshots.value
            fixture.runtime.submit(
                RuntimeCommand.Back(null, RuntimeHostLease(RuntimeHostId(0L), 0L), snapshot.modeIdentity),
            )
            fixture.drain()
            assertTrue(fixture.runtime.snapshots.value.exit != null)

            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500L)
            while (System.nanoTime() < deadline && fixture.nativePort.loads.isEmpty()) {
                fixture.drain()
                Thread.yield()
            }
            assertTrue(fixture.nativePort.loads.isEmpty())
        }
    }

    @Test
    fun startupDecisionJoinsLoadingAndResumesOnceAfterReady() {
        val root = File("build/runtime-snapshot/startup-join").canonicalFile
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scanner = RuntimeCatalogScanner {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            listOf(InstalledGhostMetadata("startup-join", root, null, null, File(root, "readme.txt")))
        }
        SnapshotRuntimeFixture(catalogScanner = scanner, awaitInitialCatalog = false).use { fixture ->
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(2) { fixture.runtime.submit(RuntimeCommand.StartGhost("startup-join", root)) }
            fixture.drain()
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (fixture.dispatcher.isEmpty() && System.nanoTime() < deadline) Thread.yield()
            fixture.drain()
            fixture.awaitNativeWork()
            assertEquals(1, fixture.nativePort.loads.size)
        }
    }

    @Test
    fun startupDecisionSurvivesFailedCatalogUntilRetryIsReady() {
        val root = File("build/runtime-snapshot/startup-retry").canonicalFile
        val scans = AtomicLong()
        SnapshotRuntimeFixture(
            catalogScanner = RuntimeCatalogScanner {
                if (scans.incrementAndGet() == 1L) error("first scan fails")
                listOf(InstalledGhostMetadata("startup-retry", root, null, null, File(root, "readme.txt")))
            },
        ).use { fixture ->
            val failed = fixture.runtime.snapshots.value.catalog as RuntimeCatalogState.Failed
            repeat(2) { fixture.runtime.submit(RuntimeCommand.StartGhost("startup-retry", root)) }
            fixture.runtime.submit(RuntimeCommand.RetryCatalog(null, failed.epoch))
            fixture.drain()
            fixture.awaitNativeWork()
            assertEquals(1, fixture.nativePort.loads.size)
        }
    }

    @Test
    fun dispatcherAndLateNativeCompletionAreSafeAfterClose() {
        val dispatcher = com.cattailsw.nanidroid.runtime.SerializedRuntimeCommandDispatcher()
        dispatcher.close()
        dispatcher.dispatch { error("must not run") }

        val root = File("build/runtime-snapshot/close-race").canonicalFile
        val fixture = fixtureFor("close-race", root)
        fixture.runtime.submit(RuntimeCommand.StartGhost("close-race", root))
        fixture.awaitNativeWork()
        val load = fixture.nativePort.loads.remove()
        fixture.close()
        load.complete(RuntimeNativeLifecycleOutcome.Success)
    }

    // Mutation caught: initial scan failure is interpreted as Ready(empty) for bundled installation.
    @Test
    fun bundledInstallationEligibilityRequiresProvenReadyEmptyCatalog() {
        SnapshotRuntimeFixture(catalogScanner = RuntimeCatalogScanner { error("scan failed") }).use { failed ->
            assertTrue(failed.runtime.snapshots.value.catalog is RuntimeCatalogState.Failed)
            assertFalse(failed.runtime.shouldInstallBundledGhostForTesting(emptyArray()))
        }

        SnapshotRuntimeFixture(catalogScanner = RuntimeCatalogScanner { emptyList() }).use { ready ->
            assertTrue(ready.runtime.snapshots.value.catalog is RuntimeCatalogState.Ready)
            assertTrue(ready.runtime.shouldInstallBundledGhostForTesting(emptyArray()))
        }
    }

    // Mutation caught: a blocked native request stalls the coordination lane or its late response survives Back.
    @Test
    fun blockedChoiceDoesNotBlockBackAndLateResponseIsRejectedAtTail() {
        val root = File("build/runtime-snapshot/blocked-choice").canonicalFile
        val port = BlockingRecordingRuntimeNativePort("OnChoiceSelectEx")
        SnapshotRuntimeFixture(
            nativePort = port,
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("blocked-choice", root, null, null, File(root, "readme.txt")))
            },
        ).use { fixture ->
            fixture.startAttached("blocked-choice", root)
            val top = fixture.makeTopHost(71L)
            fixture.runtime.enqueueScriptForTesting("\\q[One,id]\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.dialogue.choices.size == 1 }
            val choice = fixture.runtime.snapshots.value.dialogue.choices.single()

            fixture.runtime.submit(RuntimeCommand.ActivateChoice(choice.key))
            fixture.drain()
            assertTrue(port.entered.await(5, TimeUnit.SECONDS))
            val beforeBack = fixture.runtime.snapshots.value
            fixture.runtime.submit(RuntimeCommand.Back(beforeBack.generation, top, beforeBack.modeIdentity))
            fixture.drain()
            assertEquals("Back", fixture.runtime.snapshotCommandTraceForTesting().last())

            port.release.countDown()
            fixture.awaitNativeWork()
            val staleChoice = port.requests.remove()
            staleChoice.complete(
                RuntimeResult.Success(TaggedShioriResponse(1L, response(200, "\\hSTALE\\e"))),
            )
            fixture.drain()

            assertEquals("NativeResponseRejected", fixture.runtime.snapshotCommandTraceForTesting().last())
            assertFalse(fixture.runtime.snapshots.value.presentation.sakura.text.contains("STALE"))
        }
    }

    // Mutation caught: the 65th active-host cue is dropped or playback continues beyond the 64-cue lease window.
    @Test
    fun activeHostCueBackpressurePausesAndContiguousAcknowledgementResumes() {
        val root = File("build/runtime-snapshot/cue-backpressure").canonicalFile
        fixtureFor("cue-backpressure", root).use { fixture ->
            fixture.startAttached("cue-backpressure", root)
            val top = fixture.makeTopHost(81L)
            val script = buildString {
                repeat(65) { append("\\i[1]") }
                append("\\e")
            }
            fixture.runtime.enqueueScriptForTesting(script)
            fixture.drain()
            var steps = 0
            while (fixture.runtime.snapshots.value.cues.size < 64 && steps < 200) {
                fixture.scheduler.runNext(RuntimeScheduleKind.PLAYBACK)
                fixture.drain()
                steps += 1
            }
            val full = fixture.runtime.snapshots.value.cues
            assertEquals(64, full.size)
            assertTrue(fixture.scheduler.scheduled().none { it.key.kind == RuntimeScheduleKind.PLAYBACK })

            fixture.runtime.submit(RuntimeCommand.AcknowledgeCues(top, full.last().cueId))
            fixture.drain()

            assertTrue(fixture.scheduler.scheduled().any { it.key.kind == RuntimeScheduleKind.PLAYBACK })
            fixture.scheduler.runNext(RuntimeScheduleKind.PLAYBACK)
            fixture.drain()
            assertEquals(1, fixture.runtime.snapshots.value.cues.size)
        }
    }

    @Test
    fun successfulSwitchRetiresOutgoingCueWindowAndAllowsAnotherSwitch() {
        val oldRoot = File("build/runtime-snapshot/cue-switch-old").canonicalFile
        val newRoot = File("build/runtime-snapshot/cue-switch-new").canonicalFile
        SnapshotRuntimeFixture(
            catalogScanner = RuntimeCatalogScanner {
                listOf(
                    InstalledGhostMetadata("cue-switch-old", oldRoot, "Old", null, File(oldRoot, "readme.txt")),
                    InstalledGhostMetadata("cue-switch-new", newRoot, "New", null, File(newRoot, "readme.txt")),
                )
            },
        ).use { fixture ->
            fixture.startAttached("cue-switch-old", oldRoot)
            val top = fixture.makeTopHost(83L)
            fixture.runtime.enqueueScriptForTesting(buildString { repeat(65) { append("\\i[1]") }; append("\\e") })
            fixture.drain()
            while (fixture.runtime.snapshots.value.cues.size < 64) {
                fixture.scheduler.runNext(RuntimeScheduleKind.PLAYBACK)
                fixture.drain()
            }
            val before = fixture.runtime.snapshots.value
            fixture.runtime.submit(RuntimeCommand.SwitchGhost(1L, top, before.modeIdentity, "cue-switch-new"))
            fixture.drain()
            fixture.awaitNativeWork()
            fixture.nativePort.requests.remove().complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(204))))
            fixture.drain()
            fixture.awaitNativeWork()
            fixture.nativePort.unloads.remove().complete(RuntimeNativeLifecycleOutcome.Success)
            fixture.drainUntil { fixture.nativePort.loads.isNotEmpty() }

            assertTrue(fixture.runtime.snapshots.value.cues.isEmpty())
            assertTrue(fixture.scheduler.scheduled().none { it.key.kind == RuntimeScheduleKind.PLAYBACK })
            fixture.nativePort.loads.remove().complete(RuntimeNativeLifecycleOutcome.Success)
            fixture.drainUntil { fixture.nativePort.requests.isNotEmpty() }
            fixture.nativePort.requests.remove().complete(RuntimeResult.Success(TaggedShioriResponse(2L, response(204))))
            fixture.drainUntil { fixture.runtime.snapshots.value.phase == GhostRuntimePhase.Attached }

            val replaced = fixture.runtime.snapshots.value
            fixture.runtime.submit(RuntimeCommand.SwitchGhost(2L, top, replaced.modeIdentity, "cue-switch-old"))
            fixture.drain()
            fixture.awaitNativeWork()
            assertTrue(fixture.nativePort.requests.remove().intent.protocolText.contains("ID: OnGhostChanging\r\n"))
        }
    }

    @Test
    fun pointerFailureUsesRequestIdentityAndFatalPoisonsRuntime() {
        val root = File("build/runtime-snapshot/pointer-failure").canonicalFile
        fixtureFor("pointer-failure", root).use { fixture ->
            fixture.startAttached("pointer-failure", root)
            val top = fixture.makeTopHost(84L)
            val effect = SurfaceInteractionEffect(
                PointerEventKind.CLICK,
                SurfaceSpeaker.SAKURA,
                IntOffset(1, 2),
                0,
                PointerSource.TOUCH,
                "head",
                null,
            )
            val surface = RuntimeSurfaceIdentity(1L, GhostSpeaker.SAKURA, "0", 0L)
            fixture.runtime.submit(RuntimeCommand.Pointer(1L, top, surface, effect))
            fixture.drain()
            fixture.awaitNativeWork()
            val replayable = fixture.nativePort.requests.remove()
            replayable.complete(RuntimeResult.Failure(RuntimeFailure.Replayable(IllegalStateException("retry"))))
            fixture.drain()
            assertEquals(replayable.token.requestId, fixture.runtime.snapshots.value.notice?.operationId)

            fixture.runtime.submit(RuntimeCommand.Pointer(1L, top, surface, effect))
            fixture.drain()
            fixture.awaitNativeWork()
            fixture.nativePort.requests.remove().complete(
                RuntimeResult.Failure(RuntimeFailure.Fatal(IllegalStateException("fatal"))),
            )
            fixture.drain()
            assertEquals(GhostRuntimePhase.Poisoned, fixture.runtime.snapshots.value.phase)
            fixture.runtime.submit(RuntimeCommand.Pointer(1L, top, surface, effect))
            fixture.drain()
            assertTrue(fixture.nativePort.requests.isEmpty())
        }
    }

    // Mutation caught: host loss resets cue IDs and lets a stale acknowledgement alias a replacement-host cue.
    @Test
    fun hostReplacementKeepsCueIdentityMonotonic() {
        val root = File("build/runtime-snapshot/cue-handoff").canonicalFile
        fixtureFor("cue-handoff", root).use { fixture ->
            fixture.startAttached("cue-handoff", root)
            val firstTop = fixture.makeTopHost(82L)
            fixture.runtime.enqueueScriptForTesting("\\i[1]\\i[2]\\e")
            fixture.drain()
            fixture.scheduler.runNext(RuntimeScheduleKind.PLAYBACK)
            fixture.drain()
            val oldCue = fixture.runtime.snapshots.value.cues.single()

            fixture.runtime.submit(RuntimeCommand.SetTopResumed(firstTop.copy(hostEpoch = 4L), false))
            val replacementTop = firstTop.copy(hostEpoch = 5L)
            fixture.runtime.submit(RuntimeCommand.SetTopResumed(replacementTop, true))
            fixture.drain()
            fixture.scheduler.runNext(RuntimeScheduleKind.PLAYBACK)
            fixture.drain()
            val replacementCue = fixture.runtime.snapshots.value.cues.single()

            assertTrue(replacementCue.cueId > oldCue.cueId)
            fixture.runtime.submit(RuntimeCommand.AcknowledgeCues(replacementTop, oldCue.cueId))
            fixture.drain()
            assertEquals(replacementCue, fixture.runtime.snapshots.value.cues.single())
        }
    }

    // Mutation caught: input timeout does not claim the exact action or omits the normal fallback request.
    @Test
    fun inputTimeoutClaimsOnceWithCancelFallbackContract() {
        val root = File("build/runtime-snapshot/input-timeout").canonicalFile
        val elapsed = AtomicLong(10_000L)
        SnapshotRuntimeFixture(
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("input-timeout", root, null, null, File(root, "readme.txt")))
            },
            elapsedRealtimeMillis = elapsed::get,
        ).use { fixture ->
            fixture.startAttached("input-timeout", root)
            fixture.runtime.enqueueScriptForTesting("\\![open,inputbox,name,500]\\e")
            fixture.drain()
            fixture.scheduler.runNext(RuntimeScheduleKind.PLAYBACK)
            fixture.drain()
            val input = requireNotNull(fixture.runtime.snapshots.value.dialogue.input)
            val timeout = fixture.scheduler.scheduled().single { it.key.kind == RuntimeScheduleKind.INPUT_TIMEOUT }
            assertEquals(500L, timeout.delayMillis)

            elapsed.set(10_500L)
            fixture.scheduler.run(timeout.key)
            fixture.drain()
            fixture.awaitNativeWork()
            val request = fixture.nativePort.requests.remove()

            assertEquals(null, fixture.runtime.snapshots.value.dialogue.input)
            assertTrue(request.intent.protocolText.contains("ID: OnUserInputCancel\r\n"))
            assertTrue(requireNotNull(request.fallback).protocolText.contains("ID: OnUserInput\r\n"))
            fixture.runtime.submit(RuntimeCommand.InputExpired(input.key, 11_000L))
            fixture.drain()
            assertTrue(fixture.nativePort.requests.isEmpty())
        }
    }

    // Mutation caught: pointer admission ignores the exact published surface epoch or omits SHIORI routing.
    @Test
    fun pointerRequestRequiresExactCurrentSurfaceIdentity() {
        val root = File("build/runtime-snapshot/pointer").canonicalFile
        fixtureFor("pointer", root).use { fixture ->
            fixture.startAttached("pointer", root)
            val top = fixture.makeTopHost(91L)
            val generation = requireNotNull(fixture.runtime.snapshots.value.generation)
            val effect = SurfaceInteractionEffect(
                kind = PointerEventKind.CLICK,
                speaker = SurfaceSpeaker.SAKURA,
                intrinsic = IntOffset(3, 4),
                button = 0,
                source = PointerSource.TOUCH,
                collisionIdentifier = "head",
                diagnosticCollisionId = null,
            )
            val current = RuntimeSurfaceIdentity(generation, GhostSpeaker.SAKURA, "0", 0L)

            fixture.runtime.submit(RuntimeCommand.Pointer(generation, top, current.copy(surfaceEpoch = 1L), effect))
            fixture.runtime.submit(RuntimeCommand.Pointer(generation, top, current, effect))
            fixture.drain()
            fixture.awaitNativeWork()
            val pointer = fixture.nativePort.requests.remove()

            assertTrue(pointer.intent.protocolText.contains("ID: OnMouseClick\r\n"))
            assertTrue(pointer.intent.protocolText.contains("Reference0: 3\r\n"))
            assertTrue(fixture.nativePort.requests.isEmpty())
        }
    }

    // Mutation caught: replacing a dialogue incarnation leaves its old native claim admissible.
    @Test
    fun dialogueReplacementRevokesLateClaimedResponse() {
        val root = File("build/runtime-snapshot/dialogue-replacement").canonicalFile
        fixtureFor("dialogue-replacement", root).use { fixture ->
            fixture.startAttached("dialogue-replacement", root)
            fixture.runtime.enqueueScriptForTesting("\\q[Old,id]\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.dialogue.choices.size == 1 }
            val old = fixture.runtime.snapshots.value.dialogue.choices.single()
            fixture.runtime.submit(RuntimeCommand.ActivateChoice(old.key))
            fixture.drain()
            fixture.awaitNativeWork()
            val request = fixture.nativePort.requests.remove()
            assertEquals(1, fixture.runtime.claimedDialogueCountForTesting())

            fixture.runtime.enqueueScriptForTesting("\\hNew\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.dialogue.state.incarnation > old.key.incarnation }
            assertEquals(0, fixture.runtime.claimedDialogueCountForTesting())

            request.complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(200, "\\hSTALE\\e"))))
            fixture.drain()
            assertEquals("NativeResponseRejected", fixture.runtime.snapshotCommandTraceForTesting().last())
        }
    }

    private fun fixtureFor(id: String, root: File): SnapshotRuntimeFixture = SnapshotRuntimeFixture(
        catalogScanner = RuntimeCatalogScanner {
            listOf(InstalledGhostMetadata(id, root, null, null, File(root, "readme.txt")))
        },
    )

    private fun blockingLastRunPersistence(
        entered: CountDownLatch,
        release: CountDownLatch,
    ): GhostRuntimePersistence = object : GhostRuntimePersistence {
        override fun readLastRunGhostId(): String? = null
        override fun commitLastRunGhostId(ghostId: String) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        override fun readActivationCount(ghostId: String) = 0L
        override fun commitActivationCount(ghostId: String, count: Long) = Unit
    }

    private fun response(status: Int, value: String? = null): ShioriResponse = ShioriResponse(
        "SHIORI/3.0 $status ${if (status == 200) "OK" else "No Content"}",
        Hashtable<String, String>().apply { if (value != null) put("Value", value) },
    )
}
