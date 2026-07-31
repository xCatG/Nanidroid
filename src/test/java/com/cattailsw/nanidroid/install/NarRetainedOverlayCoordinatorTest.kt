package com.cattailsw.nanidroid.install

import android.content.Context
import io.mockk.mockk

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import org.junit.Assert.*
import org.junit.Test

/** TDD contract for exact archive/baseline ownership binding. */
class NarRetainedOverlayCoordinatorTest {
    @Test fun bindsExactSourcesAndExposesOnlyImmutableFacts() {
        val archive = FakeArchive(); val io = FakeIo(); val session = session("ghost", io, archive)
        val backend = FakeBackend(); val claim = claim("ghost", backend)
        val bound = NarRetainedOverlayCoordinator.bind(session, claim)
        assertTrue(bound.detail(), bound.isSuccess()); assertNull(bound.error())
        val candidate = bound.candidate(); assertNotNull(candidate); candidate!!
        assertEquals(1, candidate.fileCount()); assertTrue(candidate.hasKnownTotalSize()); assertEquals(3, candidate.totalSize())
        val recipe = candidate.recipe(); assertSame(recipe, candidate.recipe()); assertEquals(1, recipe.entries().size)
        assertEquals(NarRetainedOverlayPolicy.Source.ARCHIVE, recipe.entries()[0].source())
        val fingerprint = candidate.baselineFingerprint(); fingerprint[0] = (fingerprint[0].toInt() xor 1).toByte()
        assertFalse(fingerprint.contentEquals(candidate.baselineFingerprint()))
        assertEquals("CONSUMED", session.state().name); assertEquals("CONSUMED", claim.state().name)
        assertNull(session.lease()); assertNull(claim.lease())
        candidate.cleanup(); candidate.cleanup(); assertTrue(candidate.isCleaned())
        assertEquals(1, archive.closeCount); assertEquals(1, io.deleteCount); assertEquals(1, backend.discards)
    }

    @Test fun policyRejectReleasesBothSourcesWithoutClaimingCleanup() {
        val archive = FakeArchive(); val io = FakeIo(); val session = session("other", io, archive)
        val backend = FakeBackend(); val claim = claim("ghost", backend)
        val rejected = NarRetainedOverlayCoordinator.bind(session, claim)
        assertFalse(rejected.isSuccess()); assertNull(rejected.candidate()); assertEquals("POLICY", rejected.error()!!.name)
        assertEquals("TARGET_MISMATCH", rejected.policyError()!!.name); assertFalse(rejected.detail().isEmpty())
        assertEquals("READY", session.state().name); assertEquals("READY", claim.state().name)
        assertEquals(0, archive.closeCount); assertEquals(0, io.deleteCount); assertEquals(0, backend.discards)
        session.close(); assertEquals(NarStagedTree.Error.OK, claim.discard())
    }

    @Test fun nullAndBusySourcesCannotLeakOrStrandLeases() {
        val archive = FakeArchive(); val io = FakeIo(); val session = session("ghost", io, archive)
        val backend = FakeBackend(); val claim = claim("ghost", backend)
        assertEquals("INPUT", NarRetainedOverlayCoordinator.bind(null, claim).error()!!.name)
        assertEquals("INPUT", NarRetainedOverlayCoordinator.bind(session, null).error()!!.name)
        val archiveBusy = session.lease(); assertEquals("BUSY", NarRetainedOverlayCoordinator.bind(session, claim).error()!!.name)
        assertEquals("BUSY", session.state().name); assertEquals("READY", claim.state().name); assertEquals("OK", session.release(archiveBusy).name)
        val claimBusy = claim.lease(); assertEquals("BUSY", NarRetainedOverlayCoordinator.bind(session, claim).error()!!.name)
        assertEquals("READY", session.state().name); assertEquals("BUSY", claim.state().name); assertEquals(NarStagedTree.Error.OK, claim.release(claimBusy))
        session.close(); assertEquals(NarStagedTree.Error.OK, claim.discard())
    }

    @Test fun candidateCleanupAttemptsBothSidesAndRetriesOnlyUnfinished() {
        val archive = FakeArchive(); val io = FakeIo(); io.deleteFailure = true
        val backend = FakeBackend(); backend.results = arrayOf(NarStagedTree.Error.PERMISSION, NarStagedTree.Error.OK)
        val candidate = success(NarRetainedOverlayCoordinator.bind(session("ghost", io, archive), claim("ghost", backend))).candidate()!!
        assertThrows(IOException::class.java) { candidate.cleanup() }
        assertEquals(1, archive.closeCount); assertEquals(1, io.deleteCount); assertEquals(1, backend.discards); assertFalse(candidate.isCleaned())
        io.deleteFailure = false; candidate.cleanup(); assertEquals(1, archive.closeCount); assertEquals(2, io.deleteCount); assertEquals(2, backend.discards); assertTrue(candidate.isCleaned())
        val fatalArchive = FakeArchive(); val archiveOome = OutOfMemoryError("archive"); fatalArchive.closeThrowable = archiveOome
        val fatalBackend = FakeBackend(); fatalBackend.throwable = OutOfMemoryError("tree")
        val fatal = success(NarRetainedOverlayCoordinator.bind(session("ghost", FakeIo(), fatalArchive), claim("ghost", fatalBackend))).candidate()!!
        assertSame(archiveOome, assertThrows(OutOfMemoryError::class.java) { fatal.cleanup() })
        assertEquals(1, fatalArchive.closeCount); assertEquals(1, fatalBackend.discards)
        fatalArchive.closeThrowable = null; fatalBackend.throwable = null; fatal.cleanup(); assertTrue(fatal.isCleaned())
    }

    @Test fun bindAndDirectSourceCleanupAreLinearized() {
        val archive = FakeArchive(); val io = FakeIo(); val session = session("ghost", io, archive)
        val backend = FakeBackend(); val claim = claim("ghost", backend)
        val bound = arrayOfNulls<NarRetainedOverlayCoordinator.Result>(1); val direct = arrayOfNulls<Throwable>(1)
        race({ bound[0] = NarRetainedOverlayCoordinator.bind(session, claim) }, { try { session.close(); claim.discard() } catch (failure: Throwable) { direct[0] = failure } })
        assertNotNull(bound[0]); if (bound[0]!!.isSuccess()) bound[0]!!.candidate()!!.cleanup()
        assertFalse(session.state().name == "BUSY"); assertFalse(claim.state().name == "BUSY")
        assertTrue(archive.closeCount <= 1); assertTrue(io.deleteCount <= 1); assertTrue(backend.discards <= 1)
    }

    @Test fun candidateSurfaceCannotRevealAuthority() {
        assertNotNull(NarRetainedOverlayCoordinator::class.java.getAnnotation(Metadata::class.java))
        assertMethods(NarRetainedOverlayCoordinator::class.java, "bind")
        assertMethods(NarRetainedOverlayCoordinator.Result::class.java, "candidate", "detail", "error", "isSuccess", "policyError")
        assertMethods(NarRetainedOverlayCoordinator.Candidate::class.java, "baselineFingerprint", "cleanup", "fileCount", "hasKnownTotalSize", "isCleaned", "recipe", "totalSize")
    }

    private fun success(result: NarRetainedOverlayCoordinator.Result): NarRetainedOverlayCoordinator.Result { assertTrue(result.detail(), result.isSuccess()); return result }
    private fun assertMethods(type: Class<*>, vararg expected: String) {
        val actual = ArrayList<String>(); for (method in type.declaredMethods) { if (method.isSynthetic) continue; actual.add(method.name); val name = method.name.lowercase(); for (forbidden in listOf("path", "stream", "handle", "token", "lease", "session", "claim", "publish", "materialize", "native", "backend")) assertFalse(name, name.contains(forbidden)); assertFalse(forbidden(method.returnType)); if (type != NarRetainedOverlayCoordinator::class.java) for (parameter in method.parameterTypes) assertFalse(forbidden(parameter)) }; actual.sort(); expected.sort(); assertEquals(expected.toList(), actual)
    }
    private fun forbidden(type: Class<*>): Boolean { val name = type.name; return name == "java.io.File" || name.startsWith("java.nio.file") || name.contains("InputStream") || name.contains("OutputStream") || name.contains("Handle") || name.contains("Lease") || name.contains("Session") || name.contains("Claim") || name.contains("Manifest") || name.contains("Inventory") }
    private fun session(target: String, io: FakeIo, archive: FakeArchive) = NarVerifiedInstallSession(io, File("staged.nar"), archive, emptyList<NarInstallPlanValidator.ArchiveEntry>(), plan(target))
    private fun claim(target: String, backend: FakeBackend): NarStagedTree.Claim { val session = NarStagedTree.Stager(backend).session(CONTEXT); return session.consume(success(session.stage(ROOT, target))).claim!! }
    private fun success(result: NarStagedTree.StageResult): NarStagedTree.Tree { assertTrue(result.detail, result.isSuccess()); return result.tree!! }
    private fun plan(target: String): NarInstallPlan { val metadata = linkedMapOf("type" to "ghost", "name" to "Ghost", "directory" to target, "refresh" to "0"); val descriptor = NarInstallDescriptor("ghost", "Ghost", target, target, null, metadata); val entry = NarArchiveInventory.Entry(0, "payload", "payload", "payload", false, 0, 8, 3, 3); return NarInstallPlan(0, ByteArray(32), NarArchiveInventory(listOf(entry), null, 0, 3), descriptor, File("ignored-root"), File("ignored-target")) }
    private fun empty() = NarStagedTreeInventory.Description(1, 2, emptyArray(), IntArray(0), LongArray(0), IntArray(0), ByteArray(0))
    private fun race(first: Runnable, second: Runnable) { val start = CountDownLatch(1); val one = Thread { await(start); first.run() }; val two = Thread { await(start); second.run() }; one.start(); two.start(); start.countDown(); one.join(5000); two.join(5000); assertFalse(one.isAlive); assertFalse(two.isAlive) }
    private fun await(latch: CountDownLatch) { try { latch.await() } catch (error: InterruptedException) { throw AssertionError(error) } }

    private class FakeIo : NarInstallPlanValidator.ArchiveIo { var deleteCount = 0; var deleteFailure = false; override fun length(file: File): Long = throw AssertionError(); override fun openSource(file: File): InputStream = throw AssertionError(); override fun preflight(file: File): Int = throw AssertionError(); override fun openArchive(file: File): NarInstallPlanValidator.OpenArchive = throw AssertionError(); override fun canonical(file: File): File = file; override fun delete(file: File): Boolean { deleteCount++; return !deleteFailure } }
    private class FakeArchive : NarInstallPlanValidator.OpenArchive { var closeCount = 0; var closeThrowable: Throwable? = null; override fun entries(limit: Int): List<out NarInstallPlanValidator.ArchiveEntry> = throw AssertionError(); override fun open(entry: NarInstallPlanValidator.ArchiveEntry): InputStream = throw AssertionError(); override fun close() { closeCount++; when (val throwable = closeThrowable) { is IOException -> throw throwable; is RuntimeException -> throw throwable; is Error -> throw throwable } } }
    private class FakeHandle : NarStagedTree.Handle
    private class FakeBackend : NarStagedTree.Backend { private val handle: NarStagedTree.Handle = FakeHandle(); var results = arrayOf(NarStagedTree.Error.OK); private var resultIndex = 0; var discards = 0; var throwable: Throwable? = null; override fun begin(context: Context, root: NarFilesystemInspector.TrustedRoot, target: CharSequence): NarStagedTree.BeginResult = NarStagedTree.BeginResult.present(handle); override fun describe(supplied: NarStagedTree.Handle): NarStagedTreeInventory.Description = NarStagedTreeInventory.Description(1, 2, emptyArray(), IntArray(0), LongArray(0), IntArray(0), ByteArray(0)); override fun discard(context: Context, supplied: NarStagedTree.Handle): NarStagedTree.Error { discards++; when (val failure = throwable) { is RuntimeException -> throw failure; is Error -> throw failure }; return results[minOf(resultIndex++, results.size - 1)] } }

    private companion object {
        // The fake staged-tree backend verifies identity only; no Context API
        // is invoked by this JVM characterization test.
        val CONTEXT: Context = mockk(relaxed = true)
        val ROOT = NarFilesystemInspector.TrustedRoot("/trusted")
    }
}
