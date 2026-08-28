package com.cattailsw.nanidroid

import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.install.ArchiveInstallResult
import com.cattailsw.nanidroid.install.NarTransactionalInstaller
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShioriLifecycleInstrumentationTest {
    @Test
    fun realEnginesHaveSingleOwnerAndQueueConfinedLifecycle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = RealEngineAuditSupport.requireRunId(arguments)
        val runRoot = RealEngineAuditSupport.requireRunRoot(context, runId)
        val inputRoot = File(runRoot, "input").canonicalFile
        val installRoot = RealEngineAuditSupport.createOwnedChild(runRoot, "lifecycle-install")
        val report = RealEngineAuditSupport.baseReport(runId, "adapter-lifecycle")
        val engineCases = JSONArray()
        report.put("engineCases", engineCases)
        var failure: Throwable? = null

        try {
            val archives = listOf(
                EngineArchive(
                    expectedEngine = GhostEngine.Satori,
                    archive = RealEngineAuditSupport.requireArchive(
                        arguments,
                        inputRoot,
                        "satoriNarPath",
                        "satoriNarSha256",
                    ),
                ),
                EngineArchive(
                    expectedEngine = GhostEngine.Yaya,
                    archive = RealEngineAuditSupport.requireArchive(
                        arguments,
                        inputRoot,
                        "yayaNarPath",
                        "yayaNarSha256",
                    ),
                ),
                EngineArchive(
                    expectedEngine = GhostEngine.Kawari,
                    archive = RealEngineAuditSupport.requireArchive(
                        arguments,
                        inputRoot,
                        "kawariNarPath",
                        "kawariNarSha256",
                    ),
                ),
            )

            archives.forEachIndexed { index, engineArchive ->
                engineCases.put(runLifecycleCase(context, installRoot, index, engineArchive))
            }
            report.put("status", "passed")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed")
            report.put("error", error.stackTraceToString())
        } finally {
            RealEngineAuditSupport.deleteOwnedChild(installRoot, runRoot)
            report.put(
                "cleanup",
                JSONObject().put(
                    "remainingTestOwnedPaths",
                    JSONArray().also { remaining ->
                        if (installRoot.exists()) remaining.put(installRoot.absolutePath)
                    },
                ),
            )
            RealEngineAuditSupport.writeReport(context, runId, "lifecycle-trace.json", report)
        }

        failure?.let { throw it }
    }

    private fun runLifecycleCase(
        context: Context,
        installRoot: File,
        index: Int,
        engineArchive: EngineArchive,
    ): JSONObject {
        val engineName = RealEngineAuditSupport.engineName(engineArchive.expectedEngine)
        val targetId = "runtime-lifecycle-${index + 1}"
        val installedRoot = RealEngineAuditSupport.installArchive(
            engineArchive.archive,
            installRoot,
            targetId,
        )
        val prepared = GhostPreparer(context).prepare(index.toLong() + 1L, targetId, installedRoot)
        assertEquals("Host classification disagreed with runtime selection", engineArchive.expectedEngine, prepared.engine)

        val phases = mutableListOf<String>()
        val runtimeA = RealEngineAuditSupport.newRuntime(context)
        try {
            val handleA = runBlocking { runtimeA.startOrJoin(targetId, installedRoot) }
                .valueOrThrow("runtime A load")
            assertEquals(engineArchive.expectedEngine, handleA.ghost.engine)
            phases += "runtime-a-load:success"
            RealEngineAuditSupport.request(runtimeA, handleA, "runtime A request")
            phases += "runtime-a-request:success"

            val runtimeB = RealEngineAuditSupport.newRuntime(context)
            try {
                val ownerPresent = runBlocking { runtimeB.startOrJoin(targetId, installedRoot) }
                assertTrue(
                    "runtime B must fail fatally while runtime A owns $engineName",
                    ownerPresent is RuntimeResult.Failure && ownerPresent.failure is RuntimeFailure.Fatal,
                )
                val ownerFailure = (ownerPresent as RuntimeResult.Failure).failure as RuntimeFailure.Fatal
                assertTrue(
                    "runtime B fatal failure must retain owner-present evidence: ${ownerFailure.cause}",
                    ownerFailure.cause.message.orEmpty().contains("owner", ignoreCase = true),
                )
                phases += "runtime-b-owner-present:fatal"
            } finally {
                runtimeB.close()
                phases += "runtime-b-close:success"
            }

            RealEngineAuditSupport.request(runtimeA, handleA, "runtime A request after runtime B close")
            phases += "runtime-a-still-owner:request-success"
            runtimeA.unload(handleA.generation).valueOrThrow("runtime A unload")
            phases += "runtime-a-unload:success"
        } finally {
            runtimeA.close()
            phases += "runtime-a-close:success"
        }

        val runtimeC = RealEngineAuditSupport.newRuntime(context)
        try {
            val handleC = runBlocking { runtimeC.startOrJoin(targetId, installedRoot) }
                .valueOrThrow("runtime C load")
            assertEquals(engineArchive.expectedEngine, handleC.ghost.engine)
            phases += "runtime-c-load:success"
            RealEngineAuditSupport.request(runtimeC, handleC, "runtime C request")
            phases += "runtime-c-request:success"
            runtimeC.unload(handleC.generation).valueOrThrow("runtime C unload")
            phases += "runtime-c-unload:success"
            runtimeC.unload(handleC.generation).valueOrThrow("runtime C duplicate unload")
            phases += "runtime-c-duplicate-unload:success"
        } finally {
            runtimeC.close()
            phases += "runtime-c-close:success"
        }

        val invalidRoot = RealEngineAuditSupport.createOwnedChild(
            installRoot,
            "invalid-${engineName.lowercase(Locale.ROOT).replace(' ', '-')}",
        )
        val probeRuntime = RealEngineAuditSupport.newRuntime(context)
        val probe = try {
            probeRuntime.probeAdapterLifecycleForTesting(
                prepared = prepared,
                invalidPrepared = prepared.copy(canonicalRoot = invalidRoot),
            ).valueOrThrow("$engineName lifecycle probe")
        } finally {
            probeRuntime.close()
        }
        assertEquals(engineArchive.expectedEngine, probe.engine)
        assertEquals(RealEngineAuditSupport.expectedProbeSteps(engineArchive.expectedEngine), probe.steps)
        assertEquals(probe.steps.size, probe.commandThreadNames.size)
        assertTrue(
            "Every $engineName lifecycle probe command must use its runtime native thread",
            probe.commandThreadNames.all { it == probeRuntime.nativeThreadName },
        )
        RealEngineAuditSupport.deleteOwnedChild(invalidRoot, installRoot)

        return JSONObject()
            .put("engine", engineName)
            .put("archivePath", engineArchive.archive.file.absolutePath)
            .put("sha256", engineArchive.archive.sha256)
            .put("phases", phases.toJsonArray())
            .put("probeSteps", probe.steps.toJsonArray())
            .put("probeThreadNames", probe.commandThreadNames.toJsonArray())
            .put("nativeThreadName", probeRuntime.nativeThreadName)
    }

    private data class EngineArchive(
        val expectedEngine: GhostEngine,
        val archive: RealEngineAuditSupport.VerifiedArchive,
    )
}

internal object RealEngineAuditSupport {
    data class VerifiedArchive(
        val file: File,
        val sha256: String,
    )

    fun requireRunId(arguments: Bundle): String {
        val runId = requiredArgument(arguments, "runtimeAuditRunId")
        require(runId.matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
            "runtimeAuditRunId contains unsafe characters"
        }
        return runId
    }

    fun requireRunRoot(context: Context, runId: String): File {
        val parent = File(context.cacheDir, "cross-engine-runtime").canonicalFile
        val runRoot = File(parent, runId).canonicalFile
        require(runRoot.parentFile == parent && runRoot.isDirectory) {
            "Run-owned private root is unavailable: ${runRoot.absolutePath}"
        }
        return runRoot
    }

    fun requireArchive(
        arguments: Bundle,
        expectedInputRoot: File,
        pathKey: String,
        shaKey: String,
    ): VerifiedArchive {
        val expectedSha256 = requiredArgument(arguments, shaKey).lowercase(Locale.ROOT)
        require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "$shaKey is not a SHA-256 digest" }
        val archive = File(requiredArgument(arguments, pathKey)).canonicalFile
        require(archive.isFile && archive.canRead()) { "$pathKey is not a readable file: $archive" }
        require(archive.parentFile == expectedInputRoot.canonicalFile) {
            "$pathKey is outside the exact run-owned input root"
        }
        val actualSha256 = sha256(archive)
        require(actualSha256 == expectedSha256) {
            "$pathKey SHA-256 mismatch: expected=$expectedSha256 actual=$actualSha256"
        }
        return VerifiedArchive(archive, actualSha256)
    }

    fun installArchive(archive: VerifiedArchive, installRoot: File, targetId: String): File {
        val result = NarTransactionalInstaller.install(
            archive.file,
            installRoot,
            targetId,
            { false },
        )
        val installed = result as? ArchiveInstallResult.Installed
            ?: error("Transactional install failed for ${archive.file}: $result")
        require(installed.targetId == targetId) {
            "Installer published unexpected target ${installed.targetId}; expected $targetId"
        }
        return File(installed.installedPath).canonicalFile.also { installedRoot ->
            require(installedRoot.parentFile == installRoot.canonicalFile && installedRoot.isDirectory) {
                "Installed root escaped the run-owned install directory: $installedRoot"
            }
        }
    }

    fun newRuntime(context: Context): GhostRuntime = GhostRuntime.testRuntime(
        context = context,
        preparer = GhostPreparer(context),
        persistence = AuditPersistence(),
    )

    fun request(runtime: GhostRuntime, handle: GhostHandle, label: String) {
        val response = runtime.request(handle.generation, ShioriRequestIntent.event("OnBoot"))
            .valueOrThrow(label)
        require(response.generation == handle.generation) {
            "$label returned generation ${response.generation}; expected ${handle.generation}"
        }
    }

    fun engineName(engine: GhostEngine): String = when (engine) {
        GhostEngine.Satori -> "Satori"
        GhostEngine.Yaya -> "YAYA"
        GhostEngine.Kawari -> "Kawari 8"
        GhostEngine.Nanidroid -> "NanidroidShiori"
        GhostEngine.Unsupported -> "Unsupported"
    }

    fun expectedProbeSteps(engine: GhostEngine): List<String> = mutableListOf(
        "invalid-load:proven-empty",
        "load:success",
        "duplicate-load:owner-already-present",
        "request:success",
        "unload:success",
        "second-unload:success",
        "request-after-unload:rejected",
    ).apply {
        if (engine == GhostEngine.Yaya) add("charset-after-unload:rejected")
        add("reload:success")
        add("reload-unload:success")
    }

    fun createOwnedChild(parent: File, name: String): File {
        require(name.matches(Regex("[A-Za-z0-9._-]+"))) { "Unsafe owned child name: $name" }
        val canonicalParent = parent.canonicalFile
        require(canonicalParent.isDirectory) { "Owned parent is not a directory: $canonicalParent" }
        val child = File(canonicalParent, name).canonicalFile
        require(child.parentFile == canonicalParent && !child.exists()) {
            "Owned child must be absent under its exact parent: $child"
        }
        check(child.mkdir()) { "Could not create owned child: $child" }
        return child
    }

    fun deleteOwnedChild(child: File, expectedParent: File) {
        if (!child.exists()) return
        val owned = child.canonicalFile.toPath()
        val parent = expectedParent.canonicalFile.toPath()
        require(owned.parent == parent) { "Refusing cleanup outside exact parent $parent: $owned" }
        Files.walkFileTree(
            owned,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, error: java.io.IOException?): FileVisitResult {
                    error?.let { throw it }
                    Files.deleteIfExists(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    fun baseReport(runId: String, kind: String): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("runId", runId)
        .put("kind", kind)
        .put("status", "running")

    fun writeReport(context: Context, runId: String, name: String, report: JSONObject) {
        val externalRoot = requireNotNull(context.getExternalFilesDir("cross-engine-runtime")) {
            "External report directory is unavailable"
        }.canonicalFile
        val runRoot = File(externalRoot, runId).canonicalFile
        require(runRoot.parentFile == externalRoot) { "External report path escaped its root" }
        check(runRoot.isDirectory || runRoot.mkdirs()) { "Could not create report root: $runRoot" }
        File(runRoot, name).writeText(report.toString(2))
    }

    private fun requiredArgument(arguments: Bundle, key: String): String =
        arguments.getString(key)?.takeIf(String::isNotBlank)
            ?: error("Missing required instrumentation argument: $key")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class AuditPersistence : GhostRuntimePersistence {
        override fun readLastRunGhostId(): String? = null
        override fun commitLastRunGhostId(ghostId: String) = Unit
        override fun readActivationCount(ghostId: String): Long = 0L
        override fun commitActivationCount(ghostId: String, count: Long) = Unit
    }
}

internal fun <T> RuntimeResult<T>.valueOrThrow(label: String): T = when (this) {
    is RuntimeResult.Success -> value
    is RuntimeResult.Failure -> throw when (val typed = failure) {
        is RuntimeFailure.Fatal -> AssertionError("$label failed fatally", typed.cause)
        is RuntimeFailure.Replayable -> AssertionError("$label failed replayably", typed.cause)
        RuntimeFailure.Busy -> AssertionError("$label failed: runtime busy")
        RuntimeFailure.StaleGeneration -> AssertionError("$label failed: stale generation")
    }
}

internal fun Iterable<String>.toJsonArray(): JSONArray = JSONArray().also { values ->
    forEach(values::put)
}
