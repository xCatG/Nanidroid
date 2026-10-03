package com.cattailsw.nanidroid.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativeShioriHostTest {
    private val directory = File(".")

    @Test fun eventCountsSurviveDiagnosticHistoryRollover() = runBlocking {
        val host = NativeShioriHost.forTest { FakeBinding() }
        val lease = (host.load(NativeKind.SATORI, directory, "satori.dll") as NativeLoadResult.Loaded).lease
        val tracking = host.trackEventsForTest(setOf("OnBoot", "OnSecondChange", "OnUserInput"))
        host.request(lease, ShioriEvent("OnBoot"))
        repeat(5) { host.request(lease, ShioriEvent("OnSecondChange")) }
        host.request(lease, ShioriEvent("OnUserInput"))
        repeat(140) { host.request(lease, ShioriEvent("Unrelated$it")) }
        val before = host.observationForTest()
        assertEquals(128, before.events.size)
        assertEquals(1L, before.eventCount("OnBoot"))
        assertEquals(5L, before.eventCount("OnSecondChange"))
        assertEquals(1L, before.eventCount("OnUserInput"))
        host.request(lease, ShioriEvent("OnUserInput"))
        val after = host.observationForTest()
        assertEquals(1L, after.eventCount("OnUserInput") - before.eventCount("OnUserInput"))
        assertEquals(5L, after.eventCount("OnSecondChange"))
        assertEquals(setOf("OnBoot", "OnSecondChange", "OnUserInput"), after.eventCounts.keys)
        tracking.close()
        assertTrue(host.observationForTest().eventCounts.isEmpty())
        host.unload(lease)
        Unit
    }

    @Test fun shutdownNotifiesOnceThenUnloadsAndSharesResult() = runBlocking {
        for (status in listOf(200, 204)) {
            val fake = FakeBinding()
            fake.response = "SHIORI/3.0 $status OK\r\nValue: ignored\r\n\r\n".toByteArray()
            val host = NativeShioriHost.forTest { fake }
            val lease = (host.load(NativeKind.SATORI, directory, "satori.dll") as NativeLoadResult.Loaded).lease
            assertEquals(NativeShutdownResult.Completed, host.shutdown(lease))
            assertEquals(NativeShutdownResult.Completed, host.shutdown(lease))
            assertTrue(String(fake.lastRequest!!).startsWith("NOTIFY SHIORI/3.0\r\n"))
            assertTrue(String(fake.lastRequest!!).contains("ID: OnDestroy\r\n"))
            assertEquals(1, fake.requests)
            assertEquals(1, fake.unloads)
            assertEquals(NativeAvailability.Available, host.availability.value)
        }
    }

    @Test fun shutdownKeepsProtocolFailureAndUnloadsKnownOwner() = runBlocking {
        for (status in listOf(400, 500, 302)) {
            val fake = FakeBinding()
            fake.response = "SHIORI/3.0 $status Error\r\n\r\n".toByteArray()
            val host = NativeShioriHost.forTest { fake }
            val lease = (host.load(NativeKind.KAWARI, directory, "shiori.dll") as NativeLoadResult.Loaded).lease
            val result = host.shutdown(lease)
            assertTrue(result is NativeShutdownResult.Failed)
            assertTrue((result as NativeShutdownResult.Failed).message.contains("$status"))
            assertEquals(1, fake.unloads)
            assertEquals(NativeAvailability.Available, host.availability.value)
        }
    }

    @Test fun blockedNotificationTimesOutWithoutAdmittingUnloadAndNeverClearsQuarantine() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fake = FakeBinding()
        val host = NativeShioriHost.forTest { fake }
        val lease = (host.load(NativeKind.SATORI, directory, "satori.dll") as NativeLoadResult.Loaded).lease
        fake.onRequest = { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        try {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { host.shutdown(lease, timeoutMillis = 100) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(withTimeout(2_000) { pending.await() } is NativeShutdownResult.Unresolved)
            assertEquals(NativeAvailability.Quarantined, host.availability.value)
        } finally { release.countDown() }
        assertTrue(host.load(NativeKind.KAWARI, directory, "shiori.dll") is NativeLoadResult.Unresolved)
        assertEquals(0, fake.unloads)
        assertEquals(1, fake.loads)
        assertEquals(NativeAvailability.Quarantined, host.availability.value)
    }

    @Test fun blockedUnloadTimesOutAndLateReturnCannotRestoreAvailability() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fake = FakeBinding()
        val host = NativeShioriHost.forTest { fake }
        val lease = (host.load(NativeKind.SATORI, directory, "satori.dll") as NativeLoadResult.Loaded).lease
        fake.onUnload = { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        try {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { host.shutdown(lease, timeoutMillis = 100) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(withTimeout(2_000) { pending.await() } is NativeShutdownResult.Unresolved)
            assertEquals(NativeAvailability.Quarantined, host.availability.value)
        } finally { release.countDown() }
        assertTrue(host.load(NativeKind.KAWARI, directory, "shiori.dll") is NativeLoadResult.Unresolved)
        assertEquals(1, fake.unloads)
        assertEquals(NativeAvailability.Quarantined, host.availability.value)
    }

    @Test fun shutdownQueuedBehindRequestExpiresWithoutEnteringJni() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fake = FakeBinding()
        val host = NativeShioriHost.forTest { fake }
        val lease = (host.load(NativeKind.SATORI, directory, "satori.dll") as NativeLoadResult.Loaded).lease
        fake.onRequest = { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        try {
            val blocked = async(start = CoroutineStart.UNDISPATCHED) { host.request(lease, ShioriEvent("Block")) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val pending = async(start = CoroutineStart.UNDISPATCHED) { host.shutdown(lease, timeoutMillis = 100) }
            assertTrue(withTimeout(2_000) { pending.await() } is NativeShutdownResult.Unresolved)
            assertEquals(1, fake.requests)
            assertEquals(0, fake.unloads)
            release.countDown()
            blocked.await()
            assertEquals(NativeAvailability.Quarantined, host.availability.value)
            assertEquals(1, fake.requests)
        } finally { release.countDown() }
    }

    @Test fun cancellationOfWaiterDoesNotCancelShutdownOrSecondWaiter() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fake = FakeBinding()
        val host = NativeShioriHost.forTest { fake }
        val lease = (host.load(NativeKind.SATORI, directory, "satori.dll") as NativeLoadResult.Loaded).lease
        fake.onRequest = { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { host.shutdown(lease, timeoutMillis = 1_000) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val second = async(start = CoroutineStart.UNDISPATCHED) { host.shutdown(lease, timeoutMillis = 10) }
            first.cancel()
            release.countDown()
            assertEquals(NativeShutdownResult.Completed, withTimeout(2_000) { second.await() })
            first.join()
            assertEquals(1, fake.requests)
            assertEquals(1, fake.unloads)
        } finally { release.countDown() }
    }

    @Test fun notificationTransportFailureWithKnownOwnerStillUnloads() = runBlocking {
        val fake = FakeBinding()
        val host = NativeShioriHost.forTest { fake }
        val lease = (host.load(NativeKind.SATORI, directory, "satori.dll") as NativeLoadResult.Loaded).lease
        fake.onRequest = { throw NativeTransportException("wire error") }
        val result = host.shutdown(lease)
        assertTrue(result is NativeShutdownResult.Failed)
        assertTrue((result as NativeShutdownResult.Failed).message.contains("wire error"))
        assertEquals(1, fake.unloads)
        assertEquals(NativeAvailability.Available, host.availability.value)
    }

    @Test fun notificationJniFailureQuarantinesWithoutUnloading() = runBlocking {
        val fake = FakeBinding()
        val host = NativeShioriHost.forTest { fake }
        val lease = (host.load(NativeKind.SATORI, directory, "satori.dll") as NativeLoadResult.Loaded).lease
        fake.onRequest = { throw IllegalStateException("unknown owner") }
        assertTrue(host.shutdown(lease) is NativeShutdownResult.Unresolved)
        assertEquals(0, fake.unloads)
        assertEquals(NativeAvailability.Quarantined, host.availability.value)
    }

    @Test fun staleShutdownAndCandidateCleanupDoNotNotifyAnotherOwner() = runBlocking {
        val fake = FakeBinding()
        val host = NativeShioriHost.forTest { fake }
        val old = (host.load(NativeKind.SATORI, directory, "satori.dll") as NativeLoadResult.Loaded).lease
        assertEquals(NativeUnloadResult.Unloaded, host.unload(old))
        val current = (host.load(NativeKind.KAWARI, directory, "shiori.dll") as NativeLoadResult.Loaded).lease
        assertTrue(host.shutdown(old) is NativeShutdownResult.Failed)
        assertEquals(0, fake.requests)
        assertEquals(NativeShutdownResult.Completed, host.shutdown(current, notifyDestroy = false))
        assertEquals(0, fake.requests)
        assertEquals(2, fake.unloads)
    }

    @Test fun staleShutdownCannotQuarantineCurrentOwnerWhileLaneIsBlocked() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fake = FakeBinding()
        val host = NativeShioriHost.forTest { fake }
        val old = (host.load(NativeKind.SATORI, directory, "satori.dll") as NativeLoadResult.Loaded).lease
        assertEquals(NativeUnloadResult.Unloaded, host.unload(old))
        val current = (host.load(NativeKind.SATORI, directory, "satori.dll") as NativeLoadResult.Loaded).lease
        fake.onRequest = { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        try {
            val blocked = async(start = CoroutineStart.UNDISPATCHED) { host.request(current, ShioriEvent("Block")) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(host.shutdown(old, timeoutMillis = 10) is NativeShutdownResult.Failed)
            delay(30)
            assertEquals(NativeAvailability.Occupied, host.availability.value)
            release.countDown()
            blocked.await()
        } finally { release.countDown() }
        Unit
    }

    @Test fun lateOrdinaryUnloadCannotClearShutdownQuarantine() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fake = FakeBinding()
        val host = NativeShioriHost.forTest { fake }
        val lease = (host.load(NativeKind.SATORI, directory, "satori.dll") as NativeLoadResult.Loaded).lease
        fake.onUnload = { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        try {
            val ordinary = async(start = CoroutineStart.UNDISPATCHED) { host.unload(lease) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(host.shutdown(lease, timeoutMillis = 100) is NativeShutdownResult.Unresolved)
            release.countDown()
            assertTrue(ordinary.await() is NativeUnloadResult.Unresolved)
            assertEquals(NativeAvailability.Quarantined, host.availability.value)
            assertEquals(1, fake.unloads)
        } finally { release.countDown() }
    }

    @Test fun unloadCompletionRacingDeadlineHasOneCoherentTerminalState() = runBlocking {
        repeat(6) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val fake = FakeBinding()
            val host = NativeShioriHost.forTest { fake }
            val lease = (host.load(NativeKind.SATORI, directory, "satori.dll") as NativeLoadResult.Loaded).lease
            fake.onUnload = { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
            try {
                val pending = async(start = CoroutineStart.UNDISPATCHED) { host.shutdown(lease, timeoutMillis = 80) }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                delay(75)
                release.countDown()
                val result = withTimeout(2_000) { pending.await() }
                when (result) {
                    NativeShutdownResult.Completed -> assertEquals(NativeAvailability.Available, host.availability.value)
                    is NativeShutdownResult.Unresolved -> {
                        assertEquals(NativeAvailability.Quarantined, host.availability.value)
                        assertEquals(result, host.shutdown(lease))
                        assertTrue(host.load(NativeKind.KAWARI, directory, "shiori.dll") is NativeLoadResult.Unresolved)
                    }
                    is NativeShutdownResult.Failed -> fail("Unexpected shutdown failure: ${result.message}")
                }
            } finally { release.countDown() }
        }
    }

    @Test fun earlyDeadlineTimerWakeCannotQuarantineBeforeBudget() = runBlocking {
        val entered = CountDownLatch(1)
        val releaseUnload = CountDownLatch(1)
        val firstTimerWake = CompletableDeferred<Unit>()
        val secondTimerWait = CompletableDeferred<Unit>()
        val releaseTimer = CompletableDeferred<Unit>()
        var timerCalls = 0
        val fake = FakeBinding()
        val host = NativeShioriHost.forTest({ fake }, deadlinePause = {
            if (timerCalls++ == 0) firstTimerWake.complete(Unit)
            else {
                secondTimerWait.complete(Unit)
                releaseTimer.await()
            }
        })
        val lease = (host.load(NativeKind.SATORI, directory, "satori.dll") as NativeLoadResult.Loaded).lease
        fake.onUnload = { entered.countDown(); releaseUnload.await(5, TimeUnit.SECONDS) }
        try {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { host.shutdown(lease, timeoutMillis = 5_000) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            withTimeout(2_000) { firstTimerWake.await() }
            withTimeout(2_000) { secondTimerWait.await() }
            assertEquals(NativeAvailability.Occupied, host.availability.value)
            assertFalse(pending.isCompleted)
            releaseUnload.countDown()
            assertEquals(NativeShutdownResult.Completed, withTimeout(2_000) { pending.await() })
            assertEquals(NativeAvailability.Available, host.availability.value)
        } finally {
            releaseUnload.countDown()
            releaseTimer.complete(Unit)
        }
    }

    @Test fun successfulLeaseAndRepeatedEmptyUnload() = runBlocking {
        val fake = FakeBinding()
        val host = NativeShioriHost.forTest { fake }
        val loaded = host.load(NativeKind.SATORI, directory, "satori.dll") as NativeLoadResult.Loaded
        assertEquals(200, host.request(loaded.lease, ShioriEvent("OnBoot")).status)
        assertEquals(NativeUnloadResult.Unloaded, host.unload(loaded.lease))
        assertEquals(NativeUnloadResult.Empty, host.unload(loaded.lease))
        assertEquals(1, fake.unloads)
    }

    @Test fun ordinaryFailureDoesNotClaimOwnership() = runBlocking {
        val fake = FakeBinding(loadStatus = 0)
        val host = NativeShioriHost.forTest { fake }
        assertEquals(NativeLoadResult.Failed(0), host.load(NativeKind.KAWARI, directory, "shiori.dll"))
        assertEquals(NativeAvailability.Available, host.availability.value)
    }

    @Test fun existingOwnerAndFailedCleanupQuarantineWithoutUnload() = runBlocking {
        for (status in listOf(-1, -2)) {
            val fake = FakeBinding(loadStatus = status)
            val host = NativeShioriHost.forTest { fake }
            assertTrue(host.load(NativeKind.YAYA, directory, "yaya.dll") is NativeLoadResult.Unresolved)
            assertEquals(NativeAvailability.Quarantined, host.availability.value)
            assertTrue(host.load(NativeKind.YAYA, directory, "yaya.dll") is NativeLoadResult.Unresolved)
            assertEquals(1, fake.loads)
            assertEquals(0, fake.unloads)
        }
    }

    @Test fun failedUnloadQuarantines() = runBlocking {
        val fake = FakeBinding(unloadSuccess = false)
        val host = NativeShioriHost.forTest { fake }
        val lease = (host.load(NativeKind.SATORI, directory, "satori.dll") as NativeLoadResult.Loaded).lease
        assertTrue(host.unload(lease) is NativeUnloadResult.Unresolved)
        assertEquals(NativeAvailability.Quarantined, host.availability.value)
    }

    @Test fun queryWithoutOwnerAndThrownJniFailureAreVisible() = runBlocking {
        val fake = FakeBinding()
        val host = NativeShioriHost.forTest { fake }
        val other = NativeShioriHost.forTest { FakeBinding() }
        val lease = (other.load(NativeKind.YAYA, directory, "yaya.dll") as NativeLoadResult.Loaded).lease
        assertTrue(runCatching { host.request(lease, ShioriEvent("OnBoot")) }.exceptionOrNull() is NativeTransportException)
        fake.onRequest = { throw IllegalStateException("broken JNI") }
        val own = (host.load(NativeKind.YAYA, directory, "yaya.dll") as NativeLoadResult.Loaded).lease
        val error = runCatching { host.request(own, ShioriEvent("OnBoot")) }.exceptionOrNull()
        assertTrue(error is NativeTransportException)
        assertTrue(error!!.message!!.contains("broken JNI"))
        assertEquals(NativeAvailability.Quarantined, host.availability.value)
    }

    @Test fun yayaQueriesBeforeAndAfterAndUsesResponseCharset() = runBlocking {
        val fake = FakeBinding()
        fake.charsets = ArrayDeque(listOf("binary", "UTF-8"))
        fake.response = "SHIORI/3.0 200 OK\r\nCharset: UTF-8\r\nValue: 日本語\r\n\r\n".toByteArray(Charsets.UTF_8)
        val host = NativeShioriHost.forTest { fake }
        val lease = (host.load(NativeKind.YAYA, directory, "yaya.dll") as NativeLoadResult.Loaded).lease
        assertEquals("日本語", host.request(lease, ShioriEvent("OnBoot", listOf("é"))).value)
        assertEquals(2, fake.charsetQueries)
        assertTrue(fake.lastRequest!!.contains('é'.code.toByte()))
        assertEquals(1, fake.threads.distinct().size)
        assertTrue(fake.threads.distinct().single().startsWith("Nanidroid-NativeShiori"))
    }

    @Test fun invalidYayaBeforeCharsetIsVisibleWithoutSendingRequest() = runBlocking {
        val fake = FakeBinding()
        fake.charsets = ArrayDeque(listOf("invalid-charset-name-xyz"))
        val host = NativeShioriHost.forTest { fake }
        val lease = (host.load(NativeKind.YAYA, directory, "yaya.dll") as NativeLoadResult.Loaded).lease
        val error = runCatching { host.request(lease, ShioriEvent("OnBoot")) }.exceptionOrNull()
        assertTrue(error is NativeTransportException)
        assertTrue(error!!.message!!.contains("Invalid YAYA transport charset"))
        assertEquals(0, fake.requests)
    }

    @Test fun validResponseCharsetWinsOverInvalidAfterQuery() = runBlocking {
        val fake = FakeBinding()
        fake.charsets = ArrayDeque(listOf("UTF-8", "invalid-after-charset"))
        fake.response = "SHIORI/3.0 200 OK\r\nCharset: UTF-8\r\nValue: 日本語\r\n\r\n".toByteArray(Charsets.UTF_8)
        val host = NativeShioriHost.forTest { fake }
        val lease = (host.load(NativeKind.YAYA, directory, "yaya.dll") as NativeLoadResult.Loaded).lease
        assertEquals("日本語", host.request(lease, ShioriEvent("OnBoot")).value)
        assertEquals(2, fake.charsetQueries)
    }

    @Test fun cancelledCallerDuringSuccessfulLoadCleansNativeOwnerExactlyOnce() = runBlocking {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val fake = FakeBinding()
        fake.onLoad = { entered.countDown(); gate.await(5, TimeUnit.SECONDS) }
        val host = NativeShioriHost.forTest { fake }
        val loading = async(start = CoroutineStart.UNDISPATCHED) { host.load(NativeKind.SATORI, directory, "satori.dll") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        loading.cancel()
        gate.countDown()
        loading.join()
        // A barrier call proves the cancelled operation has finished on the host thread.
        assertEquals(NativeAvailability.Available, host.availability.value)
        assertEquals(1, fake.unloads)
        assertTrue(host.load(NativeKind.SATORI, directory, "satori.dll") is NativeLoadResult.Loaded)
    }

    @Test fun cancelledCallerWithFailedCleanupQuarantines() = runBlocking {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val fake = FakeBinding(unloadSuccess = false)
        fake.onLoad = { entered.countDown(); gate.await(5, TimeUnit.SECONDS) }
        val host = NativeShioriHost.forTest { fake }
        val loading = async(start = CoroutineStart.UNDISPATCHED) { host.load(NativeKind.SATORI, directory, "satori.dll") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        loading.cancel()
        gate.countDown()
        loading.join()
        assertEquals(NativeAvailability.Quarantined, host.availability.value)
        assertEquals(1, fake.unloads)
        assertTrue(host.load(NativeKind.SATORI, directory, "satori.dll") is NativeLoadResult.Unresolved)
    }

    @Test fun cancellationWhileSuccessfulLoadReturnIsQueuedStillCleansOwner() = runBlocking {
        for (unloadSuccess in listOf(true, false)) {
            val fake = FakeBinding(unloadSuccess = unloadSuccess)
            val host = NativeShioriHost.forTest { fake }
            val scheduler = TestCoroutineScheduler()
            val callerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
            val loading = callerScope.async { host.load(NativeKind.SATORI, directory, "satori.dll") }
            scheduler.runCurrent()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (host.availability.value != NativeAvailability.Occupied && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(NativeAvailability.Occupied, host.availability.value)
            // The native thread has completed, but the caller dispatcher has not delivered Loaded.
            loading.cancel()
            scheduler.runCurrent()
            while (fake.unloads == 0 && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(1, fake.unloads)
            assertEquals(if (unloadSuccess) NativeAvailability.Available else NativeAvailability.Quarantined,
                host.availability.value)
        }
    }

    @Test fun cancellationOfWaitingCallerDoesNotCancelRunningJni() = runBlocking {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val fake = FakeBinding()
        val host = NativeShioriHost.forTest { fake }
        val lease = (host.load(NativeKind.SATORI, directory, "satori.dll") as NativeLoadResult.Loaded).lease
        fake.onRequest = { entered.countDown(); gate.await(5, TimeUnit.SECONDS) }
        val running = async(start = CoroutineStart.UNDISPATCHED) { host.request(lease, ShioriEvent("OnBoot")) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        running.cancel()
        gate.countDown()
        running.join()
        assertEquals(1, fake.requests)
        assertEquals(NativeUnloadResult.Unloaded, host.unload(lease))
    }

    @Test fun cancelledQueuedOldLeaseCannotTouchReplacement() = runBlocking {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val fake = FakeBinding()
        val host = NativeShioriHost.forTest { fake }
        val a = (host.load(NativeKind.SATORI, directory, "satori.dll") as NativeLoadResult.Loaded).lease
        fake.onRequest = { entered.countDown(); gate.await(5, TimeUnit.SECONDS) }
        val blocked = async(start = CoroutineStart.UNDISPATCHED) { host.request(a, ShioriEvent("Block")) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val unloadA = async(start = CoroutineStart.UNDISPATCHED) { host.unload(a) }
        val loadB = async(start = CoroutineStart.UNDISPATCHED) { host.load(NativeKind.KAWARI, directory, "shiori.dll") }
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { host.request(a, ShioriEvent("Stale")) }
        cancelled.cancel()
        gate.countDown()
        blocked.await()
        assertEquals(NativeUnloadResult.Unloaded, unloadA.await())
        val b = (loadB.await() as NativeLoadResult.Loaded).lease
        cancelled.join()
        assertEquals(200, host.request(b, ShioriEvent("B")).status)
        assertEquals(2, fake.requests)
    }

    @Test fun cancelledWaitingUnloadStillFinishesNativeCleanup() = runBlocking {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val fake = FakeBinding()
        val host = NativeShioriHost.forTest { fake }
        val lease = (host.load(NativeKind.SATORI, directory, "satori.dll") as NativeLoadResult.Loaded).lease
        fake.onRequest = { entered.countDown(); gate.await(5, TimeUnit.SECONDS) }
        val blocked = async(start = CoroutineStart.UNDISPATCHED) { host.request(lease, ShioriEvent("Block")) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val unloading = async(start = CoroutineStart.UNDISPATCHED) { host.unload(lease) }
        unloading.cancel()
        gate.countDown()
        blocked.await()
        unloading.join()
        assertEquals(NativeAvailability.Available, host.availability.value)
        assertEquals(1, fake.unloads)
    }

    @Test fun staleOperationsQueuedAcrossSwitchNeverTouchNewOwner() = runBlocking {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val fake = FakeBinding()
        val host = NativeShioriHost.forTest { fake }
        val a = (host.load(NativeKind.SATORI, directory, "satori.dll") as NativeLoadResult.Loaded).lease
        fake.onRequest = { entered.countDown(); gate.await(5, TimeUnit.SECONDS) }
        val blocked = async(start = CoroutineStart.UNDISPATCHED) { host.request(a, ShioriEvent("Block")) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val unloadA = async(start = CoroutineStart.UNDISPATCHED) { host.unload(a) }
        val loadB = async(start = CoroutineStart.UNDISPATCHED) { host.load(NativeKind.KAWARI, directory, "shiori.dll") }
        val staleRequest = async(start = CoroutineStart.UNDISPATCHED) { runCatching { host.request(a, ShioriEvent("Stale")) }.exceptionOrNull() }
        val staleUnload = async(start = CoroutineStart.UNDISPATCHED) { host.unload(a) }
        gate.countDown()
        blocked.await()
        assertEquals(NativeUnloadResult.Unloaded, unloadA.await())
        val b = (loadB.await() as NativeLoadResult.Loaded).lease
        assertTrue(staleRequest.await() is NativeTransportException)
        assertEquals(NativeUnloadResult.Stale, staleUnload.await())
        assertEquals(200, host.request(b, ShioriEvent("B")).status)
        assertEquals(2, fake.requests)
        assertEquals(1, fake.unloads)
    }

    private class FakeBinding(
        var loadStatus: Int = 1,
        var unloadSuccess: Boolean = true,
    ) : NativeBinding {
        var loads = 0
        var unloads = 0
        var requests = 0
        var onRequest: (() -> Unit)? = null
        var onLoad: (() -> Unit)? = null
        var onUnload: (() -> Unit)? = null
        var response = "SHIORI/3.0 200 OK\r\nValue: ok\r\n\r\n".toByteArray()
        var charsets = ArrayDeque(listOf("UTF-8"))
        var charsetQueries = 0
        var lastRequest: ByteArray? = null
        val threads = mutableListOf<String>()
        override fun load(directory: File, libraryName: String): Int { threads += Thread.currentThread().name; loads++; onLoad?.invoke(); return loadStatus }
        override fun unload(): Boolean { threads += Thread.currentThread().name; unloads++; onUnload?.invoke(); return unloadSuccess }
        override fun request(bytes: ByteArray): ByteArray {
            threads += Thread.currentThread().name
            requests++
            lastRequest = bytes
            onRequest?.invoke()
            return response
        }
        override fun transportCharset(): String {
            threads += Thread.currentThread().name
            charsetQueries++
            return if (charsets.size > 1) charsets.removeFirst() else charsets.first()
        }
    }
}
