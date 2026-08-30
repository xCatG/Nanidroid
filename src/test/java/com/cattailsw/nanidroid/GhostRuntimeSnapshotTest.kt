package com.cattailsw.nanidroid

import androidx.compose.ui.unit.IntOffset
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.RuntimeCatalogScanOutcome
import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeGhostMetadata
import com.cattailsw.nanidroid.runtime.RuntimeHostId
import com.cattailsw.nanidroid.runtime.RuntimeHostLease
import com.cattailsw.nanidroid.runtime.RuntimeNativeLifecycleOutcome
import com.cattailsw.nanidroid.runtime.RuntimeNativeLoadOutcome
import com.cattailsw.nanidroid.runtime.RuntimeNoticeCode
import com.cattailsw.nanidroid.runtime.RuntimeScheduleKind
import com.cattailsw.nanidroid.runtime.RuntimeSurfaceIdentity
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventKind
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource
import com.cattailsw.nanidroid.runtime.dialogue.Support
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import com.cattailsw.nanidroid.runtime.CatalogPublicationToken
import com.cattailsw.nanidroid.runtime.RuntimeCatalogScanner
import com.cattailsw.nanidroid.runtime.RuntimeCatalogState
import java.io.File
import java.lang.reflect.Modifier
import java.util.Hashtable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostRuntimeSnapshotTest {
    // Mutation caught: construction leaves snapshots inactive behind an ownership-mode branch.
    @Test
    fun constructorUsesSnapshotAuthorityUnconditionally() {
        SnapshotRuntimeFixture().use { fixture ->
            val before = fixture.runtime.snapshots.value
            fixture.runtime.submit(RuntimeCommand.RegisterHost(RuntimeHostLease(RuntimeHostId(1L), 1L)))
            fixture.runtime.submit(RuntimeCommand.SetResumed(RuntimeHostLease(RuntimeHostId(1L), 2L), true))
            fixture.runtime.submit(RuntimeCommand.SetTopResumed(RuntimeHostLease(RuntimeHostId(1L), 3L), true))
            fixture.drain()
            assertTrue(fixture.runtime.snapshots.value.revision > before.revision)
            assertEquals(RuntimeHostLease(RuntimeHostId(1L), 3L), fixture.runtime.snapshots.value.foregroundHost)
        }
    }

    @Test
    fun runtimeInstancesCannotConsumeEachOthersPlayerQueues() {
        val firstRoot = File("build/runtime-snapshot/authority-first").canonicalFile
        val secondRoot = File("build/runtime-snapshot/authority-second").canonicalFile
        fixtureFor("authority-first", firstRoot).use { first ->
            fixtureFor("authority-second", secondRoot).use { second ->
                first.startAttached("authority-first", firstRoot)
                second.startAttached("authority-second", secondRoot)
                first.runtime.enqueueScriptForTesting("\\hfirst\\e")
                first.drain()

                assertTrue(first.runtime.snapshots.value.mode.playingTalk)
                assertFalse(second.runtime.snapshots.value.mode.playingTalk)

                second.scheduler.runAll()
                second.drain()
                assertTrue(first.runtime.snapshots.value.mode.playingTalk)
                assertFalse(second.runtime.snapshots.value.mode.playingTalk)
            }
        }
    }

    @Test
    fun runtimeHasNoStaticMutableQueuePlayerHostOrCatalogState() {
        val authorityNames = setOf("playerState", "hostState", "catalogOwner", "queuedNativeRequests")
        val fields = GhostRuntime::class.java.declaredFields.associateBy { it.name }

        authorityNames.forEach { name ->
            assertTrue("missing runtime authority $name", fields.containsKey(name))
            assertFalse("static runtime authority $name", Modifier.isStatic(requireNotNull(fields[name]).modifiers))
        }
        assertTrue(fields.keys.none { it.contains("legacy", ignoreCase = true) || it.contains("runner", ignoreCase = true) })
    }

    @Test
    fun snapshotAndCuesPreserveLegacyEffectOrder() {
        presentationAndClockHousekeepingDoNotStalePublishedMode()
        activeHostCueBackpressurePausesAndContiguousAcknowledgementResumes()
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

            load.complete(RuntimeNativeLoadOutcome.Loaded(com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities()))
            fixture.runtime.submit(RuntimeCommand.RegisterHost(RuntimeHostLease(RuntimeHostId(9L), 1L)))

            assertEquals("PreparationCompleted", fixture.runtime.snapshotCommandTraceForTesting().last())
            fixture.drain()
            assertEquals(
                listOf("NativeLoadCompleted", "RegisterHost"),
                fixture.runtime.snapshotCommandTraceForTesting().takeLast(2),
            )
            fixture.drainUntil { fixture.runtime.snapshots.value.generation == 1L }
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
            fixture.nativePort.loads.remove().complete(RuntimeNativeLoadOutcome.Loaded(com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities()))
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
            val top = fixture.makeTopHost(11L)
            fixture.runtime.enqueueScriptForTesting("\\q[One,id1]\\q[Two,id2]\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.dialogue.choices.size == 2 }
            val first = fixture.runtime.snapshots.value.dialogue.choices[0]
            val sibling = fixture.runtime.snapshots.value.dialogue.choices[1]

            fixture.runtime.submit(RuntimeCommand.ActivateChoice(first.key, top))
            fixture.runtime.submit(RuntimeCommand.ActivateChoice(sibling.key, top))
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
            val top = fixture.makeTopHost(12L)
            fixture.runtime.enqueueScriptForTesting("\\q[Remote,id]\\q[Local,script:\\hDone\\e]\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.dialogue.choices.size == 2 }
            val local = fixture.runtime.snapshots.value.dialogue.choices.last()

            fixture.runtime.submit(RuntimeCommand.ActivateChoice(local.key, top))
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
            val top = fixture.makeTopHost(13L)
            fixture.runtime.enqueueScriptForTesting("\\_a[id,tail]Link\\_a\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.dialogue.anchors.size == 1 }
            val anchor = fixture.runtime.snapshots.value.dialogue.anchors.single()

            repeat(2) {
                fixture.runtime.submit(RuntimeCommand.ActivateAnchor(anchor.key, top))
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
            fixture.nativePort.loads.remove().complete(RuntimeNativeLoadOutcome.Loaded(com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities()))
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

    // Mutation caught: application onboarding is claimed after, or submitted as, a second native attachment request.
    @Test
    fun localOnboardingIsClaimedBeforeTheSingleNativeAttachmentRequest() {
        val root = File("build/runtime-snapshot/onboarding-order").canonicalFile
        val events = mutableListOf<String>()
        val nativePort = object : RecordingRuntimeNativePort() {
            override fun request(
                token: com.cattailsw.nanidroid.runtime.RuntimeRequestToken,
                intent: ShioriRequestIntent,
                fallback: ShioriRequestIntent?,
                complete: (RuntimeResult<TaggedShioriResponse>) -> Unit,
            ) {
                events += "request"
                super.request(token, intent, fallback, complete)
            }
        }
        SnapshotRuntimeFixture(
            nativePort = nativePort,
            applicationOnboardingProvider = ApplicationOnboardingProvider {
                events += "claim"
                listOf("\\hLOCAL\\e")
            },
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("onboarding-order", root, null, null, File(root, "readme.txt")))
            },
        ).use { fixture ->
            fixture.runtime.submit(RuntimeCommand.StartGhost("onboarding-order", root))
            fixture.awaitNativeWork()
            assertTrue(events.isEmpty())
            fixture.nativePort.loads.remove().complete(RuntimeNativeLoadOutcome.Loaded(PointerEventCapabilities()))
            fixture.drain()
            fixture.awaitNativeWork()

            assertEquals(listOf("claim", "request"), events)
            assertEquals(1, fixture.nativePort.requests.size)
            assertTrue(fixture.nativePort.requests.single().intent.protocolText.contains("ID: OnFirstBoot\r\n"))
        }
    }

    // Mutations caught: attaching schedules hidden onboarding playback or starts timer-native work.
    @Test
    fun unresolvedAttachmentDefersOnboardingPlaybackAndClockUntilAttached() {
        val root = File("build/runtime-snapshot/onboarding-attachment-fence").canonicalFile
        SnapshotRuntimeFixture(
            applicationOnboardingProvider = ApplicationOnboardingProvider { listOf("\\hLOCAL\\e") },
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("onboarding-attachment-fence", root, null, null, File(root, "readme.txt")))
            },
        ).use { fixture ->
            fixture.makeTopHost(17L)
            fixture.startLoaded("onboarding-attachment-fence", root)
            fixture.awaitNativeWork()

            assertEquals(1, fixture.nativePort.requests.size)
            val attachment = fixture.nativePort.requests.remove()
            assertEquals(GhostRuntimePhase.Attaching, fixture.runtime.snapshots.value.phase)
            assertFalse(fixture.runtime.snapshots.value.clockRunning)
            assertTrue(fixture.scheduler.scheduled().isEmpty())
            fixture.scheduler.runAll()
            fixture.drain()
            assertEquals("", fixture.runtime.snapshots.value.presentation.sakura.text)
            assertTrue(fixture.runtime.snapshots.value.dialogue.state.contents.isEmpty())
            assertTrue(fixture.runtime.snapshots.value.cues.isEmpty())
            assertTrue(fixture.nativePort.requests.isEmpty())

            attachment.complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(204))))
            fixture.drainUntil { fixture.runtime.snapshots.value.phase == GhostRuntimePhase.Attached }
            assertEquals("", fixture.runtime.snapshots.value.presentation.sakura.text)
            assertTrue(fixture.runtime.snapshots.value.clockRunning)

            fixture.runPlaybackUntil { it.presentation.sakura.text == "LOCAL" }
            assertEquals("LOCAL", fixture.runtime.snapshots.value.presentation.sakura.text)
            assertTrue(fixture.nativePort.requests.isEmpty())
        }
    }

    // Mutation caught: a 204 OnFirstBoot discards the already-queued local onboarding script.
    @Test
    fun noContentOnFirstBootStillLeavesLocalOnboardingPlayable() {
        val root = File("build/runtime-snapshot/onboarding-no-content").canonicalFile
        SnapshotRuntimeFixture(
            applicationOnboardingProvider = ApplicationOnboardingProvider { listOf("\\hLOCAL\\e") },
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("onboarding-no-content", root, null, null, File(root, "readme.txt")))
            },
        ).use { fixture ->
            fixture.startLoaded("onboarding-no-content", root)
            fixture.awaitNativeWork()
            val attachment = fixture.nativePort.requests.remove()
            attachment.complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(204))))
            fixture.drainUntil { fixture.runtime.snapshots.value.phase == GhostRuntimePhase.Attached }

            fixture.runPlaybackUntil { it.presentation.sakura.text == "LOCAL" }

            assertEquals("LOCAL", fixture.runtime.snapshots.value.presentation.sakura.text)
            assertTrue(fixture.nativePort.requests.isEmpty())
        }
    }

    // Mutation caught: 200 attachment content jumps ahead of the already-queued local onboarding.
    @Test
    fun attachmentContentPlaysAfterLocalOnboarding() {
        val root = File("build/runtime-snapshot/onboarding-before-attachment-content").canonicalFile
        SnapshotRuntimeFixture(
            applicationOnboardingProvider = ApplicationOnboardingProvider { listOf("\\hLOCAL\\e") },
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("onboarding-before-attachment-content", root, null, null, File(root, "readme.txt")))
            },
        ).use { fixture ->
            fixture.startLoaded("onboarding-before-attachment-content", root)
            fixture.awaitNativeWork()
            fixture.nativePort.requests.remove().complete(
                RuntimeResult.Success(TaggedShioriResponse(1L, response(200, "\\hGHOST\\e"))),
            )
            fixture.drainUntil { fixture.runtime.snapshots.value.phase == GhostRuntimePhase.Attached }

            fixture.runPlaybackUntil { it.presentation.sakura.text == "LOCAL" }
            assertEquals("LOCAL", fixture.runtime.snapshots.value.presentation.sakura.text)
            fixture.runPlaybackUntil { it.presentation.sakura.text == "GHOST" }
            assertEquals("GHOST", fixture.runtime.snapshots.value.presentation.sakura.text)
        }
    }

    // Mutation caught: runtime reconstruction reclaims and requeues application onboarding.
    @Test
    fun secondRuntimeSharingClaimedOnboardingProviderDoesNotRequeue() {
        val root = File("build/runtime-snapshot/onboarding-once").canonicalFile
        var claimed = false
        val persistence = InMemoryGhostRuntimePersistence()
        val provider = ApplicationOnboardingProvider {
            if (claimed) emptyList() else listOf("\\hONCE\\e").also { claimed = true }
        }
        fun fixture() = SnapshotRuntimeFixture(
            persistence = persistence,
            applicationOnboardingProvider = provider,
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("onboarding-once", root, null, null, File(root, "readme.txt")))
            },
        )

        fixture().use { first ->
            first.startLoaded("onboarding-once", root)
            first.awaitNativeWork()
            assertTrue(first.scheduler.scheduled().none {
                it.key.kind == com.cattailsw.nanidroid.runtime.RuntimeScheduleKind.PLAYBACK
            })
            assertEquals(1, first.nativePort.requests.size)
            first.nativePort.requests.remove().complete(
                RuntimeResult.Success(TaggedShioriResponse(1L, response(204))),
            )
            first.drainUntil { first.runtime.snapshots.value.phase == GhostRuntimePhase.Attached }
            assertTrue(first.scheduler.scheduled().any {
                it.key.kind == com.cattailsw.nanidroid.runtime.RuntimeScheduleKind.PLAYBACK
            })
        }
        fixture().use { second ->
            second.startLoaded("onboarding-once", root)
            second.awaitNativeWork()
            assertTrue(second.scheduler.scheduled().none {
                it.key.kind == com.cattailsw.nanidroid.runtime.RuntimeScheduleKind.PLAYBACK
            })
            assertEquals(1, second.nativePort.requests.size)
            assertTrue(second.nativePort.requests.single().intent.protocolText.contains("ID: OnBoot\r\n"))
        }
    }

    @Test
    fun activationIsCommittedBeforeBootAndReplayableFailureDoesNotRepeatFirstBoot() {
        val root = File("build/runtime-snapshot/activation-before-boot").canonicalFile
        val persistence = InMemoryGhostRuntimePersistence()
        SnapshotRuntimeFixture(
            persistence = persistence,
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("activation-before-boot", root, null, null, File(root, "readme.txt")))
            },
        ).use { first ->
            first.startLoaded("activation-before-boot", root)
            first.awaitNativeWork()
            val firstBoot = first.nativePort.requests.remove()

            assertEquals(listOf("activation-before-boot" to 1L), persistence.activationWrites)
            assertTrue(firstBoot.intent.protocolText.contains("ID: OnFirstBoot\r\n"))
            firstBoot.complete(RuntimeResult.Failure(RuntimeFailure.Replayable(IllegalStateException("boot failed"))))
            first.drainUntil { first.runtime.snapshots.value.phase == GhostRuntimePhase.Attached }
        }

        SnapshotRuntimeFixture(
            persistence = persistence,
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("activation-before-boot", root, null, null, File(root, "readme.txt")))
            },
        ).use { restarted ->
            restarted.startLoaded("activation-before-boot", root)
            restarted.awaitNativeWork()
            val nextBoot = restarted.nativePort.requests.remove()

            assertTrue(nextBoot.intent.protocolText.contains("ID: OnBoot\r\n"))
            nextBoot.complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(204))))
            restarted.drainUntil { restarted.runtime.snapshots.value.phase == GhostRuntimePhase.Attached }
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
            fixture.awaitNativeWork()
            val second = fixture.nativePort.requests.remove()
            assertTrue(second.intent.protocolText.contains("ID: OnSecondChange\r\n"))
            assertTrue(fixture.nativePort.requests.isEmpty())
            second.complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(204))))
            fixture.drain()
            fixture.awaitNativeWork()
            val minute = fixture.nativePort.requests.remove()
            assertTrue(minute.intent.protocolText.contains("ID: OnMinuteChange\r\n"))
            minute.complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(204))))
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

    // Mutations caught: timers omit legacy references, use a later clock read, or send NOTIFY while idle.
    @Test
    fun idleTimerRequestsUseGetWithAdmittedBucketReferencesAndPlayTheirResponse() {
        val root = File("build/runtime-snapshot/timer-idle-protocol").canonicalFile
        val elapsed = AtomicLong(14_400_000L)
        SnapshotRuntimeFixture(
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("timer-idle-protocol", root, null, null, File(root, "readme.txt")))
            },
            elapsedRealtimeMillis = elapsed::get,
        ).use { fixture ->
            fixture.startAttached("timer-idle-protocol", root)
            fixture.makeTopHost(43L)

            fixture.scheduler.runNext(RuntimeScheduleKind.CLOCK)
            fixture.drain()
            fixture.awaitNativeWork()
            val second = fixture.nativePort.requests.remove()
            assertEquals(
                "GET SHIORI/3.0\r\nSender: Nanidroid\r\nSecurityLevel: local\r\n" +
                    "ID: OnSecondChange\r\nReference0: 4\r\nReference1: 0\r\n" +
                    "Reference2: 0\r\nReference3: 1\r\n\r\n",
                second.intent.protocolText,
            )
            second.complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(200, "\\hIDLE-TIMER\\e"))))
            fixture.drain()
            fixture.awaitNativeWork()
            val minute = fixture.nativePort.requests.remove()
            assertEquals(
                "GET SHIORI/3.0\r\nSender: Nanidroid\r\nSecurityLevel: local\r\n" +
                    "ID: OnMinuteChange\r\nReference0: 4\r\nReference1: 0\r\n" +
                    "Reference2: 0\r\nReference3: 1\r\n\r\n",
                minute.intent.protocolText,
            )
            minute.complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(204))))
            fixture.drain()
            fixture.runPlaybackUntil { it.presentation.sakura.text.contains("IDLE-TIMER") }

            assertEquals(0, fixture.runtime.pendingSnapshotRequestCountForTesting())
        }
    }

    // Mutations caught: a busy timer sends GET or enqueues a successful NOTIFY Value.
    @Test
    fun busyTimerRequestsUseNotifyAndSuppressValuesAcrossEveryTalkBlocker() {
        data class BusyCase(
            val name: String,
            val script: String,
            val admitted: (com.cattailsw.nanidroid.runtime.RuntimeSnapshot) -> Boolean,
        )

        val cases = listOf(
            BusyCase("active-talk", "\\hACTIVE\\_w[50]\\e") { it.mode.playingTalk },
            BusyCase("pending-choice", "\\q[Choice,id]\\e") {
                it.dialogue.choices.size == 1 && !it.mode.playingTalk
            },
            BusyCase("pending-input", "\\![open,inputbox,name,0]\\e") {
                it.dialogue.input != null
            },
            BusyCase("passive", "\\![enter,passivemode]\\e") {
                it.mode.passive && !it.mode.playingTalk
            },
        )

        cases.forEachIndexed { index, case ->
            val root = File("build/runtime-snapshot/timer-${case.name}").canonicalFile
            val elapsed = AtomicLong(18_000_000L)
            SnapshotRuntimeFixture(
                catalogScanner = RuntimeCatalogScanner {
                    listOf(InstalledGhostMetadata("timer-${case.name}", root, null, null, File(root, "readme.txt")))
                },
                elapsedRealtimeMillis = elapsed::get,
            ).use { fixture ->
                fixture.startAttached("timer-${case.name}", root)
                fixture.makeTopHost(44L + index)
                fixture.runtime.enqueueScriptForTesting(case.script)
                fixture.drain()
                fixture.runPlaybackUntil(case.admitted)
                val before = fixture.runtime.snapshots.value

                fixture.scheduler.runNext(RuntimeScheduleKind.CLOCK)
                fixture.drain()
                fixture.awaitNativeWork()
                val second = fixture.nativePort.requests.remove()
                assertEquals(
                    "NOTIFY SHIORI/3.0\r\nSender: Nanidroid\r\nSecurityLevel: local\r\n" +
                        "ID: OnSecondChange\r\nReference0: 5\r\nReference1: 0\r\n" +
                        "Reference2: 0\r\nReference3: 0\r\n\r\n",
                    second.intent.protocolText,
                )
                second.complete(
                    RuntimeResult.Success(TaggedShioriResponse(1L, response(200, "\\hSUPPRESSED-SECOND\\e"))),
                )
                fixture.drain()
                fixture.awaitNativeWork()
                val minute = fixture.nativePort.requests.remove()
                assertEquals(
                    "NOTIFY SHIORI/3.0\r\nSender: Nanidroid\r\nSecurityLevel: local\r\n" +
                        "ID: OnMinuteChange\r\nReference0: 5\r\nReference1: 0\r\n" +
                        "Reference2: 0\r\nReference3: 0\r\n\r\n",
                    minute.intent.protocolText,
                )
                minute.complete(
                    RuntimeResult.Success(TaggedShioriResponse(1L, response(200, "\\hSUPPRESSED-MINUTE\\e"))),
                )
                fixture.drain()

                if (case.name == "active-talk") {
                    fixture.runPlaybackUntil { !it.mode.playingTalk }
                    val text = fixture.runtime.snapshots.value.presentation.sakura.text
                    assertFalse(text.contains("SUPPRESSED-SECOND"))
                    assertFalse(text.contains("SUPPRESSED-MINUTE"))
                } else {
                    assertEquals(before.mode, fixture.runtime.snapshots.value.mode)
                    assertEquals(before.presentation, fixture.runtime.snapshots.value.presentation)
                }
                assertEquals(0, fixture.runtime.pendingSnapshotRequestCountForTesting())
            }
        }
    }

    // Mutation caught: suppressing NOTIFY Value also suppresses fatal native ownership evidence.
    @Test
    fun busyTimerNotifyStillSettlesFatalFailure() {
        val root = File("build/runtime-snapshot/timer-notify-fatal").canonicalFile
        val elapsed = AtomicLong(21_600_000L)
        SnapshotRuntimeFixture(
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("timer-notify-fatal", root, null, null, File(root, "readme.txt")))
            },
            elapsedRealtimeMillis = elapsed::get,
        ).use { fixture ->
            fixture.startAttached("timer-notify-fatal", root)
            val top = fixture.makeTopHost(48L)
            fixture.runtime.enqueueScriptForTesting("\\![enter,passivemode]\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.mode.passive && !it.mode.playingTalk }

            fixture.scheduler.runNext(RuntimeScheduleKind.CLOCK)
            fixture.drain()
            fixture.awaitNativeWork()
            val timer = fixture.nativePort.requests.remove()
            timer.complete(
                RuntimeResult.Failure(RuntimeFailure.Fatal(IllegalStateException("timer ownership lost"))),
            )
            fixture.drain()

            val poisoned = fixture.runtime.snapshots.value
            assertEquals(GhostRuntimePhase.Poisoned, poisoned.phase)
            assertTrue(poisoned.mode.passive)
            assertEquals(0, fixture.runtime.pendingSnapshotRequestCountForTesting())

            val back = RuntimeCommand.Back(poisoned.generation, top, poisoned.modeIdentity)
            fixture.runtime.submit(back)
            fixture.drain()
            val offered = requireNotNull(fixture.runtime.snapshots.value.exit?.offeredLease)
            assertEquals(top, offered.hostLease)
            assertTrue(fixture.nativePort.requests.isEmpty())

            fixture.runtime.submit(back)
            fixture.drain()
            assertEquals(offered, fixture.runtime.snapshots.value.exit?.offeredLease)
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

    // Mutation caught: Back leaves completed dialogue published and accepts its exact stale key during OnClose.
    @Test
    fun backRetiresCompletedDialogueAndRejectsItsExactKeyDuringParentOperation() {
        val root = File("build/runtime-snapshot/back-dialogue-retirement").canonicalFile
        fixtureFor("back-dialogue-retirement", root).use { fixture ->
            fixture.startAttached("back-dialogue-retirement", root)
            val top = fixture.makeTopHost(101L)
            fixture.runtime.enqueueScriptForTesting("\\q[Choice,choice-id]\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.dialogue.choices.size == 1 }
            while (fixture.scheduler.scheduled().any { it.key.kind == RuntimeScheduleKind.PLAYBACK }) {
                fixture.scheduler.runNext(RuntimeScheduleKind.PLAYBACK)
                fixture.drain()
            }
            val before = fixture.runtime.snapshots.value
            val oldKey = before.dialogue.choices.single().key

            fixture.runtime.submit(RuntimeCommand.Back(before.generation, top, before.modeIdentity))
            fixture.runtime.submit(RuntimeCommand.ActivateChoice(oldKey, top))
            fixture.drain()
            fixture.awaitNativeWork()

            assertTrue(fixture.runtime.snapshots.value.dialogue.choices.isEmpty())
            assertEquals(1, fixture.nativePort.requests.size)
            assertTrue(fixture.nativePort.requests.single().intent.protocolText.contains("ID: OnClose\r\n"))
        }
    }

    // Mutation caught: switch leaves completed dialogue published and accepts its stale key beside OnGhostChanging.
    @Test
    fun switchRetiresCompletedDialogueAndRejectsItsExactKeyDuringParentOperation() {
        val oldRoot = File("build/runtime-snapshot/switch-dialogue-old").canonicalFile
        val newRoot = File("build/runtime-snapshot/switch-dialogue-new").canonicalFile
        SnapshotRuntimeFixture(
            catalogScanner = RuntimeCatalogScanner {
                listOf(
                    InstalledGhostMetadata("switch-dialogue-old", oldRoot, "Old", null, File(oldRoot, "readme.txt")),
                    InstalledGhostMetadata("switch-dialogue-new", newRoot, "New", null, File(newRoot, "readme.txt")),
                )
            },
        ).use { fixture ->
            fixture.startAttached("switch-dialogue-old", oldRoot)
            val top = fixture.makeTopHost(102L)
            fixture.runtime.enqueueScriptForTesting("\\_a[anchor-id]Anchor\\_a\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.dialogue.anchors.size == 1 }
            while (fixture.scheduler.scheduled().any { it.key.kind == RuntimeScheduleKind.PLAYBACK }) {
                fixture.scheduler.runNext(RuntimeScheduleKind.PLAYBACK)
                fixture.drain()
            }
            val before = fixture.runtime.snapshots.value
            val oldKey = before.dialogue.anchors.single().key

            fixture.runtime.submit(RuntimeCommand.SwitchGhost(1L, top, before.modeIdentity, "switch-dialogue-new"))
            fixture.runtime.submit(RuntimeCommand.ActivateAnchor(oldKey, top))
            fixture.drain()
            fixture.awaitNativeWork()

            assertTrue(fixture.runtime.snapshots.value.dialogue.anchors.isEmpty())
            assertEquals(1, fixture.nativePort.requests.size)
            assertTrue(fixture.nativePort.requests.single().intent.protocolText.contains("ID: OnGhostChanging\r\n"))
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
            replacement.complete(RuntimeNativeLoadOutcome.Loaded(com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities()))
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
    fun failedPostUnloadTargetCanStartAnotherInstalledSelection() {
        val oldRoot = File("build/runtime-snapshot/recover-switch-old").canonicalFile
        val badRoot = File("build/runtime-snapshot/recover-switch-bad").canonicalFile
        val goodRoot = File("build/runtime-snapshot/recover-switch-good").canonicalFile
        SnapshotRuntimeFixture(
            catalogScanner = RuntimeCatalogScanner {
                listOf(
                    InstalledGhostMetadata("recover-switch-old", oldRoot, "Old", null, File(oldRoot, "readme.txt")),
                    InstalledGhostMetadata("recover-switch-bad", badRoot, "Bad", null, File(badRoot, "readme.txt")),
                    InstalledGhostMetadata("recover-switch-good", goodRoot, "Good", null, File(goodRoot, "readme.txt")),
                )
            },
        ).use { fixture ->
            fixture.startAttached("recover-switch-old", oldRoot)
            val top = fixture.makeTopHost(103L)
            val before = fixture.runtime.snapshots.value
            fixture.runtime.submit(RuntimeCommand.SwitchGhost(1L, top, before.modeIdentity, "recover-switch-bad"))
            fixture.drain()
            fixture.awaitNativeWork()
            fixture.nativePort.requests.remove().complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(204))))
            fixture.drain()
            fixture.awaitNativeWork()
            fixture.nativePort.unloads.remove().complete(RuntimeNativeLifecycleOutcome.Success)
            fixture.awaitNativeWork()
            fixture.nativePort.loads.remove().complete(
                RuntimeNativeLoadOutcome.Failed(RuntimeNoticeCode.NATIVE_LOAD_FAILED, ownershipCertain = true),
            )
            fixture.drainUntil { fixture.runtime.snapshots.value.phase == GhostRuntimePhase.Idle }

            val failed = fixture.runtime.snapshots.value
            assertEquals(null, failed.generation)
            fixture.runtime.submit(requireNotNull(ghostSelectionCommand(failed, top, "recover-switch-good")))
            fixture.drain()
            fixture.awaitNativeWork()

            assertEquals("recover-switch-good", fixture.nativePort.loads.single().prepared.id)
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
    fun nativeRequestsRemainFifoThroughAsynchronousSettlement() {
        val root = File("build/runtime-snapshot/async-fifo").canonicalFile
        fixtureFor("async-fifo", root).use { fixture ->
            fixture.startAttached("async-fifo", root)
            val top = fixture.makeTopHost(14L)
            fixture.runtime.enqueueScriptForTesting("\\_a[first]One\\_a\\_a[second]Two\\_a\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.dialogue.anchors.size == 2 }
            val anchors = fixture.runtime.snapshots.value.dialogue.anchors
            fixture.runtime.submit(RuntimeCommand.ActivateAnchor(anchors[0].key, top))
            fixture.runtime.submit(RuntimeCommand.ActivateAnchor(anchors[1].key, top))
            fixture.drain()
            fixture.awaitNativeWork()
            val first = fixture.nativePort.requests.remove()
            assertTrue(fixture.nativePort.requests.isEmpty())

            first.complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(200, "\\hFIRST\\e"))))
            fixture.drain()
            fixture.awaitNativeWork()
            val second = fixture.nativePort.requests.remove()
            second.complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(200, "\\hSECOND\\e"))))
            fixture.drain()
            fixture.runPlaybackUntil { it.presentation.sakura.text.contains("FIRST") }

            assertFalse(fixture.runtime.snapshots.value.presentation.sakura.text.contains("SECOND"))
        }
    }

    // Mutation caught: fatal settlement launches an already queued native request before poison admission.
    @Test
    fun fatalNativeSettlementFencesQueuedSuccessorBeforePortInvocation() {
        val root = File("build/runtime-snapshot/fatal-fifo-fence").canonicalFile
        fixtureFor("fatal-fifo-fence", root).use { fixture ->
            fixture.startAttached("fatal-fifo-fence", root)
            val top = fixture.makeTopHost(15L)
            fixture.runtime.enqueueScriptForTesting("\\_a[first]One\\_a\\_a[second]Two\\_a\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.dialogue.anchors.size == 2 }
            val anchors = fixture.runtime.snapshots.value.dialogue.anchors
            fixture.runtime.submit(RuntimeCommand.ActivateAnchor(anchors[0].key, top))
            fixture.runtime.submit(RuntimeCommand.ActivateAnchor(anchors[1].key, top))
            fixture.drain()
            fixture.awaitNativeWork()
            val first = fixture.nativePort.requests.remove()
            assertTrue(fixture.nativePort.requests.isEmpty())

            fixture.runtime.submit(RuntimeCommand.ActivateAnchor(anchors[0].key, top))
            first.complete(RuntimeResult.Failure(RuntimeFailure.Fatal(IllegalStateException("fatal"))))
            fixture.drain()

            assertEquals(GhostRuntimePhase.Poisoned, fixture.runtime.snapshots.value.phase)
            assertTrue("queued successor entered the native port after fatal settlement", fixture.nativePort.requests.isEmpty())
        }
    }

    // Mutation caught: retired-token fatal evidence is rejected and its canceled OnClose sequence wedges exit.
    @Test
    fun retiredChoiceFatalPoisonsAndSettlesCanceledExitRequestSequence() {
        val root = File("build/runtime-snapshot/retired-fatal-exit").canonicalFile
        val port = BlockingRecordingRuntimeNativePort("OnChoiceSelectEx")
        SnapshotRuntimeFixture(
            nativePort = port,
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("retired-fatal-exit", root, null, null, File(root, "readme.txt")))
            },
        ).use { fixture ->
            fixture.startAttached("retired-fatal-exit", root)
            val top = fixture.makeTopHost(75L)
            fixture.runtime.enqueueScriptForTesting("\\q[One,id]\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.dialogue.choices.size == 1 }
            val choice = fixture.runtime.snapshots.value.dialogue.choices.single()
            fixture.runtime.submit(RuntimeCommand.ActivateChoice(choice.key, top))
            fixture.drain()
            assertTrue(port.entered.await(5, TimeUnit.SECONDS))

            val afterChoice = fixture.runtime.snapshots.value
            fixture.runtime.submit(RuntimeCommand.Back(1L, top, afterChoice.modeIdentity))
            fixture.drain()
            val nativeResponsesBeforeFatal = fixture.runtime.snapshotCommandTraceForTesting().count { it == "NativeResponse" }
            port.release.countDown()
            fixture.awaitNativeWork()
            port.requests.remove().complete(
                RuntimeResult.Failure(RuntimeFailure.Fatal(IllegalStateException("retired choice fatal"))),
            )
            fixture.drainUntil { fixture.runtime.snapshots.value.phase == GhostRuntimePhase.Poisoned }

            assertTrue(fixture.runtime.snapshots.value.exit != null)
            assertEquals(0, fixture.runtime.pendingSnapshotRequestCountForTesting())
            assertTrue(port.requests.isEmpty())
            assertEquals(
                nativeResponsesBeforeFatal + 2,
                fixture.runtime.snapshotCommandTraceForTesting().count { it == "NativeResponse" },
            )
        }
    }

    // Mutation caught: a parent-active timer enters the native lane and prevents switch unload admission.
    @Test
    fun switchParentFencesQueuedTimerBeforeSuccessfulUnload() {
        val oldRoot = File("build/runtime-snapshot/parent-timer-old").canonicalFile
        val newRoot = File("build/runtime-snapshot/parent-timer-new").canonicalFile
        SnapshotRuntimeFixture(
            catalogScanner = RuntimeCatalogScanner {
                listOf(
                    InstalledGhostMetadata("parent-timer-old", oldRoot, "Old", null, File(oldRoot, "readme.txt")),
                    InstalledGhostMetadata("parent-timer-new", newRoot, "New", null, File(newRoot, "readme.txt")),
                )
            },
        ).use { fixture ->
            fixture.startAttached("parent-timer-old", oldRoot)
            val top = fixture.makeTopHost(73L)
            val before = fixture.runtime.snapshots.value
            fixture.runtime.submit(RuntimeCommand.SwitchGhost(1L, top, before.modeIdentity, "parent-timer-new"))
            fixture.drain()
            fixture.awaitNativeWork()
            val parent = fixture.nativePort.requests.remove()
            fixture.scheduler.runNext(RuntimeScheduleKind.CLOCK)
            fixture.drain()

            parent.complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(204))))
            fixture.drain()
            fixture.awaitNativeWork()

            assertTrue("parent-active timer entered the native port", fixture.nativePort.requests.isEmpty())
            assertEquals(1, fixture.nativePort.unloads.size)
            assertEquals(GhostRuntimePhase.Replacing, fixture.runtime.snapshots.value.phase)
        }
    }

    // Mutation caught: rejecting a parent-active clock tick permanently stops the retained outgoing ghost's clock.
    @Test
    fun failedSwitchReschedulesClockAfterParentTimerWasFenced() {
        val oldRoot = File("build/runtime-snapshot/parent-timer-failure-old").canonicalFile
        val newRoot = File("build/runtime-snapshot/parent-timer-failure-new").canonicalFile
        SnapshotRuntimeFixture(
            catalogScanner = RuntimeCatalogScanner {
                listOf(
                    InstalledGhostMetadata("parent-timer-failure-old", oldRoot, "Old", null, File(oldRoot, "readme.txt")),
                    InstalledGhostMetadata("parent-timer-failure-new", newRoot, "New", null, File(newRoot, "readme.txt")),
                )
            },
        ).use { fixture ->
            fixture.startAttached("parent-timer-failure-old", oldRoot)
            val top = fixture.makeTopHost(74L)
            val before = fixture.runtime.snapshots.value
            fixture.runtime.submit(RuntimeCommand.SwitchGhost(1L, top, before.modeIdentity, "parent-timer-failure-new"))
            fixture.drain()
            fixture.awaitNativeWork()
            val parent = fixture.nativePort.requests.remove()
            fixture.scheduler.runNext(RuntimeScheduleKind.CLOCK)
            fixture.drain()

            parent.complete(
                RuntimeResult.Failure(RuntimeFailure.Replayable(IllegalStateException("switch request failed"))),
            )
            fixture.drain()

            assertEquals(GhostRuntimePhase.Attached, fixture.runtime.snapshots.value.phase)
            assertTrue(fixture.nativePort.requests.isEmpty())
            assertTrue(fixture.scheduler.scheduled().any { it.key.kind == RuntimeScheduleKind.CLOCK })
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
            repeat(2) {
                fixture.awaitNativeWork()
                fixture.nativePort.requests.remove().complete(
                    RuntimeResult.Success(TaggedShioriResponse(1L, response(204))),
                )
                fixture.drain()
            }
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

    // Mutation caught: a stale no-generation selection starts a ghost while an Idle exit parent is offered.
    @Test
    fun startGhostIsRejectedWhileNoGenerationExitParentIsInFlight() {
        val root = File("build/runtime-snapshot/start-during-idle-exit").canonicalFile
        fixtureFor("start-during-idle-exit", root).use { fixture ->
            val idle = fixture.runtime.snapshots.value
            fixture.runtime.submit(
                RuntimeCommand.Back(
                    null,
                    RuntimeHostLease(RuntimeHostId(0L), 0L),
                    idle.modeIdentity,
                ),
            )
            fixture.drain()
            assertTrue(fixture.runtime.snapshots.value.exit != null)

            fixture.runtime.submit(RuntimeCommand.StartGhost("start-during-idle-exit", root))
            fixture.drain()

            assertEquals(GhostRuntimePhase.Idle, fixture.runtime.snapshots.value.phase)
            assertEquals(null, fixture.runtime.snapshots.value.generation)
            assertTrue(fixture.nativePort.loads.isEmpty())
            assertTrue(fixture.runtime.snapshots.value.exit != null)
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
            fixture.nativePort.loads.remove().complete(RuntimeNativeLoadOutcome.Loaded(com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities()))
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
            fixture.nativePort.loads.remove().complete(RuntimeNativeLoadOutcome.Loaded(com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities()))
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
            fixture.nativePort.loads.remove().complete(RuntimeNativeLoadOutcome.Loaded(com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities()))
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
    fun queuedLoadReservationFencesNullableBackBeforeNativeInvocation() {
        val root = File("build/runtime-snapshot/load-reservation").canonicalFile
        val laneEntered = CountDownLatch(1)
        val laneRelease = CountDownLatch(1)
        SnapshotRuntimeFixture(
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("load-reservation", root, null, null, File(root, "readme.txt")))
            },
        ).use { fixture ->
            fixture.runtime.blockSnapshotNativeLaneForTesting {
                laneEntered.countDown()
                laneRelease.await()
            }
            assertTrue(laneEntered.await(5, TimeUnit.SECONDS))
            fixture.runtime.submit(RuntimeCommand.StartGhost("load-reservation", root))
            fixture.drainUntil {
                fixture.runtime.snapshotCommandTraceForTesting().lastOrNull() == "PreparationCompleted"
            }
            val reserved = fixture.runtime.snapshots.value

            fixture.runtime.submit(
                RuntimeCommand.Back(null, RuntimeHostLease(RuntimeHostId(0L), 0L), reserved.modeIdentity),
            )
            fixture.drain()
            val exitWhileQueued = fixture.runtime.snapshots.value.exit
            val pendingWhileQueued = fixture.runtime.snapshots.value.pending
            laneRelease.countDown()
            fixture.awaitNativeWork()
            fixture.nativePort.loads.remove().complete(
                RuntimeNativeLoadOutcome.Failed(RuntimeNoticeCode.NATIVE_LOAD_FAILED, ownershipCertain = true),
            )
            fixture.drain()

            assertEquals(null, exitWhileQueued)
            assertEquals(reserved.pending, pendingWhileQueued)
        }
    }

    @Test
    fun closeDuringAsyncLoadWaitsForSuccessThenUnloadsExactlyOnce() {
        val root = File("build/runtime-snapshot/async-load-close").canonicalFile
        val fixture = fixtureFor("async-load-close", root)
        val closeFailure = AtomicReference<Throwable?>()
        try {
            fixture.runtime.submit(RuntimeCommand.StartGhost("async-load-close", root))
            fixture.awaitNativeWork()
            val load = fixture.nativePort.loads.remove()
            val closing = Thread {
                runCatching { fixture.close() }.exceptionOrNull()?.let(closeFailure::set)
            }
            closing.start()
            Thread.sleep(50L)
            val unloadBeforeLoadSuccess = fixture.nativePort.unloads.size

            load.complete(RuntimeNativeLoadOutcome.Loaded(com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities()))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (fixture.nativePort.unloads.isEmpty() && System.nanoTime() < deadline) Thread.yield()
            val cleanup = fixture.nativePort.unloads.single()
            cleanup.complete(RuntimeNativeLifecycleOutcome.Success)
            closing.join(6_000L)

            assertEquals(0, unloadBeforeLoadSuccess)
            assertEquals(1, fixture.nativePort.unloads.size)
            assertEquals(null, closeFailure.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun closeJoinsSwitchUnloadWithoutIssuingSecondUnload() {
        val oldRoot = File("build/runtime-snapshot/join-unload-old").canonicalFile
        val newRoot = File("build/runtime-snapshot/join-unload-new").canonicalFile
        val fixture = SnapshotRuntimeFixture(
            catalogScanner = RuntimeCatalogScanner {
                listOf(
                    InstalledGhostMetadata("join-unload-old", oldRoot, "Old", null, File(oldRoot, "readme.txt")),
                    InstalledGhostMetadata("join-unload-new", newRoot, "New", null, File(newRoot, "readme.txt")),
                )
            },
        )
        try {
            fixture.startAttached("join-unload-old", oldRoot)
            val top = fixture.makeTopHost(69L)
            val before = fixture.runtime.snapshots.value
            fixture.runtime.submit(RuntimeCommand.SwitchGhost(1L, top, before.modeIdentity, "join-unload-new"))
            fixture.drain()
            fixture.awaitNativeWork()
            fixture.nativePort.requests.remove().complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(204))))
            fixture.drain()
            fixture.awaitNativeWork()
            val unload = fixture.nativePort.unloads.remove()
            val closing = Thread(fixture::close)
            closing.start()
            Thread.sleep(50L)
            val callsBeforeSettlement = fixture.nativePort.unloads.size + 1

            unload.complete(RuntimeNativeLifecycleOutcome.Success)
            closing.join(6_000L)

            assertEquals(1, callsBeforeSettlement)
            assertTrue(fixture.nativePort.unloads.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun neverReturningRequestKeepsCloseBoundedAndDefersSameLaneCleanup() {
        val root = File("build/runtime-snapshot/blocked-close-cleanup").canonicalFile
        val port = IndefinitelyBlockingRecordingRuntimeNativePort("OnMouseDoubleClick")
        val fixture = SnapshotRuntimeFixture(
            nativePort = port,
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("blocked-close-cleanup", root, null, null, File(root, "readme.txt")))
            },
        )
        try {
            fixture.startAttached("blocked-close-cleanup", root)
            val top = fixture.makeTopHost(70L)
            val effect = SurfaceInteractionEffect(
                PointerEventKind.CLICK,
                SurfaceSpeaker.SAKURA,
                IntOffset(1, 2),
                0,
                PointerSource.TOUCH,
                "head",
                null,
            )
            fixture.runtime.submit(
                RuntimeCommand.Pointer(
                    1L,
                    top,
                    RuntimeSurfaceIdentity(1L, GhostSpeaker.SAKURA, "0", 0L),
                    effect,
                ),
            )
            fixture.drain()
            assertTrue(port.entered.await(5, TimeUnit.SECONDS))
            val closing = Thread(fixture::close)
            closing.start()
            closing.join(6_000L)
            val closeReturnedWithinBound = !closing.isAlive
            val unloadWhileRequestBlocked = port.unloads.size

            port.release.countDown()
            val requestDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (port.requests.isEmpty() && System.nanoTime() < requestDeadline) Thread.yield()
            port.requests.poll()?.complete(RuntimeResult.Success(TaggedShioriResponse(1L, response(204))))
            val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (port.unloads.isEmpty() && System.nanoTime() < cleanupDeadline) Thread.yield()
            val cleanupAfterSettlement = port.unloads.poll()
            cleanupAfterSettlement?.complete(RuntimeNativeLifecycleOutcome.Success)

            assertTrue(closeReturnedWithinBound)
            assertEquals(0, unloadWhileRequestBlocked)
            assertTrue("close cleanup was discarded at the timeout", cleanupAfterSettlement != null)
        } finally {
            port.release.countDown()
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
            assertTrue(scheduler.closeEntered.await(5, TimeUnit.SECONDS))
            val snapshotAtCloseLinearization = fixture.runtime.snapshots.value
            coordination.join(5_000L)
            val snapshotAfterCoordination = fixture.runtime.snapshots.value
            scheduler.closeRelease.countDown()
            closing.join(5_000L)
            val snapshotAfterCloseComplete = fixture.runtime.snapshots.value

            assertTrue("close did not serialize behind in-flight cancel", closeWaitedForCancel)
            assertFalse("coordination thread did not terminate", coordination.isAlive)
            assertFalse("closing thread did not terminate", closing.isAlive)
            assertEquals(null, coordinationFailure.get())
            assertEquals(null, closeFailure.get())
            assertEquals(
                "an in-flight admission published after close linearized",
                snapshotAtCloseLinearization,
                snapshotAfterCoordination,
            )
            assertEquals(
                "close completion changed the linearized snapshot",
                snapshotAtCloseLinearization,
                snapshotAfterCloseComplete,
            )
        } finally {
            scheduler.cancelRelease.countDown()
            scheduler.closeRelease.countDown()
            fixture.close()
        }
    }

    // Mutation caught: StateFlow publication resumes an unconfined collector while a runtime lock is held.
    @Test
    fun unconfinedSnapshotCollectorCanJoinCloseDuringPublication() {
        val root = File("build/runtime-snapshot/collector-close").canonicalFile
        val fixture = fixtureFor("collector-close", root)
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val collectorFinished = CountDownLatch(1)
        val closeReturnedInsideCollector = AtomicBoolean(false)
        try {
            fixture.startAttached("collector-close", root)
            collectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.runtime.snapshots.drop(1).take(1).collect {
                    val closing = Thread(fixture::close)
                    closing.start()
                    closing.join(2_000L)
                    closeReturnedInsideCollector.set(!closing.isAlive)
                    collectorFinished.countDown()
                }
            }

            fixture.runtime.enqueueScriptForTesting("\\hpublish\\e")
            fixture.drain()

            assertTrue(collectorFinished.await(5, TimeUnit.SECONDS))
            assertTrue("close was blocked by a runtime lock held across StateFlow publication", closeReturnedInsideCollector.get())
        } finally {
            collectorScope.cancel()
            fixture.close()
        }
    }

    @Test
    fun synchronousNativeThrowCompletesItsSequenceAndFencesLaterRequest() {
        val root = File("build/runtime-snapshot/native-throw").canonicalFile
        val port = object : RecordingRuntimeNativePort() {
            val throwNextPointer = AtomicBoolean(true)

            override fun request(
                token: com.cattailsw.nanidroid.runtime.RuntimeRequestToken,
                intent: ShioriRequestIntent,
                fallback: ShioriRequestIntent?,
                complete: (RuntimeResult<TaggedShioriResponse>) -> Unit,
            ) {
                if (intent.protocolText.contains("ID: OnMouseDoubleClick\r\n") && throwNextPointer.compareAndSet(true, false)) {
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
            fixture.drainUntil { fixture.runtime.snapshots.value.phase == GhostRuntimePhase.Poisoned }

            assertEquals(GhostRuntimePhase.Poisoned, fixture.runtime.snapshots.value.phase)
            assertEquals(0, fixture.runtime.pendingSnapshotRequestCountForTesting())
            assertTrue(port.requests.isEmpty())
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
                complete: (RuntimeNativeLoadOutcome) -> Unit,
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
        load.complete(RuntimeNativeLoadOutcome.Loaded(com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities()))
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

            fixture.runtime.submit(RuntimeCommand.ActivateChoice(choice.key, top))
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

    // Mutation caught: the second cue in a two-cue transition is dropped when the lease starts at 63 cues.
    @Test
    fun twoCueTransitionAtCapacityBoundaryDeliversBothInOrderWithoutDuplication() {
        val root = File("build/runtime-snapshot/cue-two-effect-boundary").canonicalFile
        fixtureFor("cue-two-effect-boundary", root).use { fixture ->
            fixture.startAttached("cue-two-effect-boundary", root)
            val top = fixture.makeTopHost(82L)
            fixture.runtime.enqueueScriptForTesting(
                buildString {
                    repeat(63) { append("\\i[one-shot]") }
                    repeat(7) { append("\\w1") }
                    append("\\h\\_sA\\e")
                },
            )
            fixture.drain()
            while (fixture.runtime.snapshots.value.cues.size < 63) {
                fixture.scheduler.runNext(RuntimeScheduleKind.PLAYBACK)
                fixture.drain()
            }

            while (
                fixture.runtime.snapshots.value.cues.size == 63 &&
                fixture.scheduler.scheduled().any { it.key.kind == RuntimeScheduleKind.PLAYBACK }
            ) {
                fixture.scheduler.runNext(RuntimeScheduleKind.PLAYBACK)
                fixture.drain()
            }
            val firstWindow = fixture.runtime.snapshots.value.cues
            assertEquals(64, firstWindow.size)
            assertEquals(GhostSpeaker.SAKURA, firstWindow.last().target.speaker)
            assertEquals(com.cattailsw.nanidroid.runtime.RuntimeCueKind.TALKING, firstWindow.last().kind)
            assertTrue(fixture.scheduler.scheduled().none { it.key.kind == RuntimeScheduleKind.PLAYBACK })

            fixture.runtime.submit(RuntimeCommand.AcknowledgeCues(top, firstWindow.last().cueId))
            fixture.drain()
            val overflow = fixture.runtime.snapshots.value.cues.single()
            assertEquals(GhostSpeaker.KERO, overflow.target.speaker)
            assertEquals(com.cattailsw.nanidroid.runtime.RuntimeCueKind.TALKING, overflow.kind)
            assertEquals(firstWindow.last().cueId + 1L, overflow.cueId)

            fixture.runtime.submit(RuntimeCommand.AcknowledgeCues(top, overflow.cueId))
            fixture.drain()
            assertTrue(fixture.runtime.snapshots.value.cues.isEmpty())
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
            fixture.nativePort.loads.remove().complete(RuntimeNativeLoadOutcome.Loaded(com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities()))
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

    @Test
    fun replayableAuthoredSurfaceFailureResumesVisibleRemainderWithGenerationNotice() {
        val root = File("build/runtime-snapshot/replayable-authored-surface").canonicalFile
        fixtureFor("replayable-authored-surface", root).use { fixture ->
            fixture.startAttached("replayable-authored-surface", root)
            fixture.makeTopHost(184L)
            fixture.runtime.enqueueScriptForTesting("\\hBefore\\q[Choice,id]\\s[42]After\\e")
            fixture.drain()
            fixture.runPlaybackUntil {
                it.presentation.sakura.text == "BeforeChoice" && it.dialogue.choices.size == 1
            }
            fixture.awaitNativeWork()
            val request = fixture.nativePort.requests.remove()
            val before = fixture.runtime.snapshots.value
            val choice = before.dialogue.choices.single()
            val generation = requireNotNull(before.generation)
            assertTrue(request.intent.protocolText.contains("ID: OnSurfaceChange\r\n"))

            request.complete(RuntimeResult.Failure(RuntimeFailure.Replayable(IllegalStateException("retry"))))
            fixture.drain()

            val resumed = fixture.runtime.snapshots.value
            assertEquals(GhostRuntimePhase.Attached, resumed.phase)
            assertEquals(generation, resumed.notice?.operationId)
            assertEquals(RuntimeNoticeCode.REQUEST_FAILED, resumed.notice?.code)
            assertEquals(before.presentation, resumed.presentation)
            assertEquals(before.dialogue, resumed.dialogue)
            assertEquals(0, fixture.runtime.pendingSnapshotRequestCountForTesting())

            fixture.runPlaybackUntil { it.presentation.sakura.text == "BeforeChoiceAfter" }
            val completedRemainder = fixture.runtime.snapshots.value
            assertEquals("BeforeChoiceAfter", completedRemainder.presentation.sakura.text)
            assertEquals(choice, completedRemainder.dialogue.choices.single())
            assertEquals(generation, completedRemainder.notice?.operationId)
            assertEquals(RuntimeNoticeCode.REQUEST_FAILED, completedRemainder.notice?.code)
            assertEquals(0, fixture.runtime.pendingSnapshotRequestCountForTesting())
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

            assertTrue(pointer.intent.protocolText.contains("ID: OnMouseDoubleClick\r\n"))
            assertTrue(pointer.intent.protocolText.contains("Reference0: 3\r\n"))
            assertTrue(fixture.nativePort.requests.isEmpty())
        }
    }

    @Test
    fun generationOwnedPointerCapabilitiesSelectAndSuppressRequests() {
        val supportedRoot = File("build/runtime-snapshot/pointer-capability-supported").canonicalFile
        fixtureFor("pointer-capability-supported", supportedRoot).use { fixture ->
            fixture.startAttached(
                "pointer-capability-supported",
                supportedRoot,
                PointerEventCapabilities(click = Support.SUPPORTED, doubleClick = Support.UNSUPPORTED),
            )
            val top = fixture.makeTopHost(94L)
            val generation = requireNotNull(fixture.runtime.snapshots.value.generation)
            fixture.runtime.submit(
                RuntimeCommand.Pointer(
                    generation,
                    top,
                    RuntimeSurfaceIdentity(generation, GhostSpeaker.SAKURA, "0", 0L),
                    pointerEffect(SurfaceSpeaker.SAKURA),
                ),
            )
            fixture.drain()
            fixture.awaitNativeWork()

            assertTrue(fixture.nativePort.requests.remove().intent.protocolText.contains("ID: OnMouseClick\r\n"))
        }

        val unsupportedRoot = File("build/runtime-snapshot/pointer-capability-unsupported").canonicalFile
        fixtureFor("pointer-capability-unsupported", unsupportedRoot).use { fixture ->
            fixture.startAttached(
                "pointer-capability-unsupported",
                unsupportedRoot,
                PointerEventCapabilities(click = Support.UNSUPPORTED, doubleClick = Support.UNSUPPORTED),
            )
            val top = fixture.makeTopHost(95L)
            val generation = requireNotNull(fixture.runtime.snapshots.value.generation)
            fixture.runtime.submit(
                RuntimeCommand.Pointer(
                    generation,
                    top,
                    RuntimeSurfaceIdentity(generation, GhostSpeaker.SAKURA, "0", 0L),
                    pointerEffect(SurfaceSpeaker.SAKURA),
                ),
            )
            fixture.drain()

            assertTrue(fixture.nativePort.requests.isEmpty())
        }
    }

    @Test
    fun passivePointerResponseIsDiscardedWithoutStartingTalk() {
        val root = File("build/runtime-snapshot/passive-pointer").canonicalFile
        fixtureFor("passive-pointer", root).use { fixture ->
            fixture.startAttached("passive-pointer", root)
            val top = fixture.makeTopHost(92L)
            fixture.runtime.enqueueScriptForTesting("\\![enter,passivemode]\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.mode.passive }
            val generation = requireNotNull(fixture.runtime.snapshots.value.generation)
            val surface = RuntimeSurfaceIdentity(generation, GhostSpeaker.SAKURA, "0", 0L)
            fixture.runtime.submit(RuntimeCommand.Pointer(generation, top, surface, pointerEffect(SurfaceSpeaker.SAKURA)))
            fixture.drain()
            fixture.awaitNativeWork()

            fixture.nativePort.requests.remove().complete(
                RuntimeResult.Success(TaggedShioriResponse(generation, response(200, "\\hUNWANTED\\e"))),
            )
            fixture.drain()

            assertFalse(fixture.runtime.snapshots.value.presentation.sakura.text.contains("UNWANTED"))
        }
    }

    // Mutation caught: stale UI commands bypass passive mode and start terminal/switch parent operations.
    @Test
    fun passiveRuntimeRejectsUserBackAndSwitchThenAllowsThemAfterLeave() {
        val oldRoot = File("build/runtime-snapshot/passive-command-old").canonicalFile
        val newRoot = File("build/runtime-snapshot/passive-command-new").canonicalFile
        SnapshotRuntimeFixture(
            catalogScanner = RuntimeCatalogScanner {
                listOf(
                    InstalledGhostMetadata("passive-command-old", oldRoot, "Old", null, File(oldRoot, "readme.txt")),
                    InstalledGhostMetadata("passive-command-new", newRoot, "New", null, File(newRoot, "readme.txt")),
                )
            },
        ).use { fixture ->
            fixture.startAttached("passive-command-old", oldRoot)
            val top = fixture.makeTopHost(99L)
            fixture.runtime.enqueueScriptForTesting("\\![enter,passivemode]\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.mode.passive }
            val passive = fixture.runtime.snapshots.value

            fixture.runtime.submit(RuntimeCommand.Back(1L, top, passive.modeIdentity))
            fixture.runtime.submit(RuntimeCommand.SwitchGhost(1L, top, passive.modeIdentity, "passive-command-new"))
            fixture.drain()

            assertEquals(GhostRuntimePhase.Attached, fixture.runtime.snapshots.value.phase)
            assertEquals(null, fixture.runtime.snapshots.value.modeIdentity.parentOperationId)
            assertTrue(fixture.nativePort.requests.isEmpty())

            fixture.runtime.enqueueScriptForTesting("\\![leave,passivemode]\\e")
            fixture.drain()
            fixture.runPlaybackUntil { !it.mode.passive }
            val active = fixture.runtime.snapshots.value
            fixture.runtime.submit(RuntimeCommand.SwitchGhost(1L, top, active.modeIdentity, "passive-command-new"))
            fixture.drain()
            fixture.awaitNativeWork()
            assertTrue(fixture.nativePort.requests.single().intent.protocolText.contains("ID: OnGhostChanging\r\n"))
        }
    }

    @Test
    fun fatalPassivePointerResponsePoisonsAfterSettlingItsToken() {
        val root = File("build/runtime-snapshot/passive-pointer-fatal").canonicalFile
        fixtureFor("passive-pointer-fatal", root).use { fixture ->
            fixture.startAttached("passive-pointer-fatal", root)
            val top = fixture.makeTopHost(96L)
            fixture.runtime.enqueueScriptForTesting("\\![enter,passivemode]\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.mode.passive }
            val generation = requireNotNull(fixture.runtime.snapshots.value.generation)
            val surface = RuntimeSurfaceIdentity(generation, GhostSpeaker.SAKURA, "0", 0L)
            fixture.runtime.submit(RuntimeCommand.Pointer(generation, top, surface, pointerEffect(SurfaceSpeaker.SAKURA)))
            fixture.drain()
            fixture.awaitNativeWork()
            val pointer = fixture.nativePort.requests.remove()

            pointer.complete(RuntimeResult.Failure(RuntimeFailure.Fatal(IllegalStateException("ownership lost"))))
            fixture.drain()

            val poisoned = fixture.runtime.snapshots.value
            assertEquals(GhostRuntimePhase.Poisoned, poisoned.phase)
            assertTrue(poisoned.mode.passive)
            assertEquals(0, fixture.runtime.pendingSnapshotRequestCountForTesting())

            fixture.runtime.submit(RuntimeCommand.SetTopResumed(top, false))
            val replacementTop = fixture.makeTopHost(196L)
            val handedOff = fixture.runtime.snapshots.value
            assertEquals(poisoned.generation, handedOff.generation)
            assertEquals(poisoned.modeIdentity, handedOff.modeIdentity)

            fixture.runtime.submit(RuntimeCommand.Back(poisoned.generation, top, poisoned.modeIdentity))
            fixture.drain()
            assertEquals(null, fixture.runtime.snapshots.value.exit)
            assertTrue(fixture.nativePort.requests.isEmpty())

            val replacementBack = RuntimeCommand.Back(
                handedOff.generation,
                replacementTop,
                handedOff.modeIdentity,
            )
            fixture.runtime.submit(replacementBack)
            fixture.drain()
            val offered = requireNotNull(fixture.runtime.snapshots.value.exit?.offeredLease)
            assertEquals(replacementTop, offered.hostLease)
            assertTrue(fixture.nativePort.requests.isEmpty())

            fixture.runtime.submit(replacementBack)
            fixture.drain()
            assertEquals(offered, fixture.runtime.snapshots.value.exit?.offeredLease)
            assertTrue(fixture.nativePort.requests.isEmpty())
        }
    }

    @Test
    fun replayablePassivePointerFailureSettlesWithTypedNotice() {
        val root = File("build/runtime-snapshot/passive-pointer-replayable").canonicalFile
        fixtureFor("passive-pointer-replayable", root).use { fixture ->
            fixture.startAttached("passive-pointer-replayable", root)
            val top = fixture.makeTopHost(97L)
            fixture.runtime.enqueueScriptForTesting("\\![enter,passivemode]\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.mode.passive }
            val generation = requireNotNull(fixture.runtime.snapshots.value.generation)
            val surface = RuntimeSurfaceIdentity(generation, GhostSpeaker.SAKURA, "0", 0L)
            fixture.runtime.submit(RuntimeCommand.Pointer(generation, top, surface, pointerEffect(SurfaceSpeaker.SAKURA)))
            fixture.drain()
            fixture.awaitNativeWork()
            val pointer = fixture.nativePort.requests.remove()

            pointer.complete(RuntimeResult.Failure(RuntimeFailure.Replayable(IllegalStateException("retry"))))
            fixture.drain()

            assertEquals(GhostRuntimePhase.Attached, fixture.runtime.snapshots.value.phase)
            assertEquals(pointer.token.requestId, fixture.runtime.snapshots.value.notice?.operationId)
            assertEquals(RuntimeNoticeCode.REQUEST_FAILED, fixture.runtime.snapshots.value.notice?.code)
            assertEquals(0, fixture.runtime.pendingSnapshotRequestCountForTesting())
        }
    }

    @Test
    fun pointerSuccessIsDiscardedWhenPlayerBecomesPassiveBeforeResponse() {
        val root = File("build/runtime-snapshot/pointer-passive-before-response").canonicalFile
        fixtureFor("pointer-passive-before-response", root).use { fixture ->
            fixture.startAttached("pointer-passive-before-response", root)
            val top = fixture.makeTopHost(98L)
            val generation = requireNotNull(fixture.runtime.snapshots.value.generation)
            fixture.runtime.enqueueScriptForTesting("\\hA\\![enter,passivemode]\\e")
            fixture.drain()
            fixture.runPlaybackUntil {
                it.presentation.sakura.text.contains("A") && !it.mode.passive
            }
            val presentation = fixture.runtime.snapshots.value.presentation.sakura
            val surface = RuntimeSurfaceIdentity(
                generation,
                GhostSpeaker.SAKURA,
                presentation.surfaceId,
                presentation.surfaceEpoch,
            )
            fixture.runtime.submit(RuntimeCommand.Pointer(generation, top, surface, pointerEffect(SurfaceSpeaker.SAKURA)))
            fixture.drain()
            fixture.awaitNativeWork()
            val pointer = fixture.nativePort.requests.remove()
            fixture.runPlaybackUntil { it.mode.passive }
            val responseTraceStart = fixture.runtime.snapshotCommandTraceForTesting().size

            pointer.complete(RuntimeResult.Success(TaggedShioriResponse(generation, response(200, "\\hUNWANTED\\e"))))
            fixture.drain()
            assertTrue(
                fixture.runtime.snapshotCommandTraceForTesting()
                    .drop(responseTraceStart)
                    .none { it == "NativeResponseRejected" },
            )
            fixture.scheduler.runNext(RuntimeScheduleKind.PLAYBACK)
            fixture.drain()

            assertFalse(fixture.runtime.snapshots.value.mode.playingTalk)
            assertFalse(fixture.runtime.snapshots.value.presentation.sakura.text.contains("UNWANTED"))
            assertEquals(0, fixture.runtime.pendingSnapshotRequestCountForTesting())
        }
    }

    @Test
    fun nonPassiveKeroPointerClearsStalePlaybackBeforeItsResponse() {
        val root = File("build/runtime-snapshot/kero-pointer-interrupt").canonicalFile
        fixtureFor("kero-pointer-interrupt", root).use { fixture ->
            fixture.startAttached("kero-pointer-interrupt", root)
            val top = fixture.makeTopHost(93L)
            fixture.runtime.enqueueScriptForTesting("\\hOLD\\w9QUEUED\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.presentation.sakura.text.contains("OLD") }
            val generation = requireNotNull(fixture.runtime.snapshots.value.generation)
            val surface = RuntimeSurfaceIdentity(generation, GhostSpeaker.KERO, "10", 0L)

            fixture.runtime.submit(RuntimeCommand.Pointer(generation, top, surface, pointerEffect(SurfaceSpeaker.KERO)))
            fixture.drain()
            fixture.awaitNativeWork()

            assertEquals("", fixture.runtime.snapshots.value.presentation.sakura.text)
            assertFalse(fixture.runtime.snapshots.value.mode.playingTalk)
            fixture.nativePort.requests.remove().complete(
                RuntimeResult.Success(TaggedShioriResponse(generation, response(200, "\\1NEW\\e"))),
            )
            fixture.drain()
            fixture.runPlaybackUntil { it.presentation.kero.text.contains("NEW") }
            assertFalse(fixture.runtime.snapshots.value.presentation.sakura.text.contains("OLD"))
        }
    }

    // Mutation caught: replacing a dialogue incarnation leaves its old native claim admissible.
    @Test
    fun dialogueReplacementRevokesLateClaimedResponse() {
        val root = File("build/runtime-snapshot/dialogue-replacement").canonicalFile
        fixtureFor("dialogue-replacement", root).use { fixture ->
            fixture.startAttached("dialogue-replacement", root)
            val top = fixture.makeTopHost(16L)
            fixture.runtime.enqueueScriptForTesting("\\q[Old,id]\\e")
            fixture.drain()
            fixture.runPlaybackUntil { it.dialogue.choices.size == 1 }
            val old = fixture.runtime.snapshots.value.dialogue.choices.single()
            fixture.runtime.submit(RuntimeCommand.ActivateChoice(old.key, top))
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

    private fun pointerEffect(speaker: SurfaceSpeaker) = SurfaceInteractionEffect(
        kind = PointerEventKind.CLICK,
        speaker = speaker,
        intrinsic = IntOffset(3, 4),
        button = 0,
        source = PointerSource.TOUCH,
        collisionIdentifier = "head",
        diagnosticCollisionId = null,
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
