package com.cattailsw.nanidroid

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrossEngineRuntimeInstrumentationTest {
    @Test
    fun satoriYayaKawariSatoriUsesOneRuntimeAuthority() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        RealEngineAuditSupport.assumeAuditConfigured(arguments)
        val runId = RealEngineAuditSupport.requireRunId(arguments)
        val runRoot = RealEngineAuditSupport.requireRunRoot(context, runId)
        val inputRoot = File(runRoot, "input").canonicalFile
        val installRoot = RealEngineAuditSupport.createOwnedChild(runRoot, "transition-install")
        val report = RealEngineAuditSupport.baseReport(runId, "cross-engine-transition")
        val trace = mutableListOf<String>()
        val generationTrace = mutableListOf<Long>()
        var failure: Throwable? = null

        try {
            val transitions = listOf(
                TransitionInput(
                    engine = GhostEngine.Satori,
                    targetId = "transition-satori-1",
                    archive = RealEngineAuditSupport.requireArchive(
                        arguments,
                        inputRoot,
                        "satoriNarPath",
                        "satoriNarSha256",
                    ),
                ),
                TransitionInput(
                    engine = GhostEngine.Yaya,
                    targetId = "transition-yaya",
                    archive = RealEngineAuditSupport.requireArchive(
                        arguments,
                        inputRoot,
                        "yayaNarPath",
                        "yayaNarSha256",
                    ),
                ),
                TransitionInput(
                    engine = GhostEngine.Kawari,
                    targetId = "transition-kawari",
                    archive = RealEngineAuditSupport.requireArchive(
                        arguments,
                        inputRoot,
                        "kawariNarPath",
                        "kawariNarSha256",
                    ),
                ),
                TransitionInput(
                    engine = GhostEngine.Satori,
                    targetId = "transition-satori-2",
                    archive = RealEngineAuditSupport.requireArchive(
                        arguments,
                        inputRoot,
                        "satoriReloadNarPath",
                        "satoriReloadNarSha256",
                    ),
                ),
            )
            assertEquals(
                "The two run-owned Satori copies must retain the selected archive hash",
                transitions.first().archive.sha256,
                transitions.last().archive.sha256,
            )

            val installed = transitions.mapIndexed { index, transition ->
                val root = RealEngineAuditSupport.installArchive(
                    transition.archive,
                    installRoot,
                    transition.targetId,
                )
                val prepared = GhostPreparer(context).prepare(
                    index.toLong() + 1L,
                    transition.targetId,
                    root,
                )
                assertEquals(
                    "Host classification disagreed with runtime engine selection for ${transition.targetId}",
                    transition.engine,
                    prepared.engine,
                )
                InstalledTransition(transition, root)
            }

            val runtime = RealEngineAuditSupport.newRuntime(context)
            try {
                installed.forEach { transition ->
                    executeTransition(runtime, transition, trace, generationTrace)
                }
            } finally {
                runtime.close()
            }

            assertEquals(EXACT_TRANSITION_TRACE, trace)
            assertEquals(listOf(1L, 2L, 3L, 4L), generationTrace)
            report
                .put("status", "passed")
                .put("trace", trace.toJsonArray())
                .put("generations", generationTrace.toJsonArray())
                .put(
                    "archives",
                    JSONArray().also { archives ->
                        transitions.forEach { transition ->
                            archives.put(
                                JSONObject()
                                    .put("engine", RealEngineAuditSupport.engineName(transition.engine))
                                    .put("targetId", transition.targetId)
                                    .put("path", transition.archive.file.absolutePath)
                                    .put("sha256", transition.archive.sha256),
                            )
                        }
                    },
                )
        } catch (error: Throwable) {
            failure = error
            report
                .put("status", "failed")
                .put("trace", trace.toJsonArray())
                .put("generations", generationTrace.toJsonArray())
                .put("error", error.stackTraceToString())
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
            RealEngineAuditSupport.writeReport(context, runId, "transition-trace.json", report)
        }

        failure?.let { throw it }
    }

    private fun executeTransition(
        runtime: GhostRuntime,
        installed: InstalledTransition,
        trace: MutableList<String>,
        generationTrace: MutableList<Long>,
    ) {
        val engineName = RealEngineAuditSupport.engineName(installed.input.engine)
        val handle = runBlocking {
            runtime.startOrJoin(installed.input.targetId, installed.root)
        }.valueOrThrow("load $engineName")
        assertEquals(installed.input.engine, handle.ghost.engine)
        trace += "load:$engineName"
        generationTrace += handle.generation

        RealEngineAuditSupport.request(runtime, handle, "request $engineName")
        trace += "request:$engineName"

        runtime.unload(handle.generation).valueOrThrow("unload $engineName")
        trace += "unload:$engineName"
    }

    private data class TransitionInput(
        val engine: GhostEngine,
        val targetId: String,
        val archive: RealEngineAuditSupport.VerifiedArchive,
    )

    private data class InstalledTransition(
        val input: TransitionInput,
        val root: File,
    )

    private companion object {
        val EXACT_TRANSITION_TRACE = listOf(
            "load:Satori",
            "request:Satori",
            "unload:Satori",
            "load:YAYA",
            "request:YAYA",
            "unload:YAYA",
            "load:Kawari 8",
            "request:Kawari 8",
            "unload:Kawari 8",
            "load:Satori",
            "request:Satori",
            "unload:Satori",
        )
    }
}

internal fun Iterable<Long>.toJsonArray(): JSONArray = JSONArray().also { values ->
    forEach(values::put)
}
