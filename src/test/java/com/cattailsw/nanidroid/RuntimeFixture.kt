package com.cattailsw.nanidroid

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Closeable runtime-owned replacement for the former fake-[Ghost] test subclasses. */
internal class RuntimeFixture(
    val id: String = "recording",
    val root: File = File("build/runtime-fixtures/${id}-${fixtureIds.incrementAndGet()}"),
    val trace: RecordingShioriTrace = RecordingShioriTrace(),
    val persistence: InMemoryGhostRuntimePersistence = InMemoryGhostRuntimePersistence(),
    response: (String) -> String = { NO_CONTENT_RESPONSE },
    bootstrapResponse: ((String) -> String)? = null,
    preparedFactory: (Long, String, File) -> PreparedGhost = ::preparedGhost,
    runnerConfiguration: SScriptRunnerConfiguration? = null,
    autoStart: Boolean = true,
    autoAttach: Boolean = autoStart,
) : AutoCloseable {
    val runtime = GhostRuntime.testRuntime(
        context = null,
        preparer = GhostPreparer(preparedFactory),
        adapterFactory = { prepared -> RecordingShiori(trace, prepared.id) },
        persistence = persistence,
        runnerConfiguration = runnerConfiguration,
    )
    val runner: SScriptRunner = runtime.runner
    var handle: GhostHandle? = null
        private set

    init {
        if (bootstrapResponse != null) {
            trace.requestHandler.set(bootstrapResponse)
        } else if (!autoAttach) {
            trace.requestHandler.set(response)
        }
        if (autoStart) {
            handle = runBlocking {
                val result = runtime.startOrJoin(id, root)
                assertTrue("runtime start failed: $result", result is RuntimeResult.Success)
                (result as RuntimeResult.Success).value
            }
        }
        if (autoAttach) {
            runBlocking {
                val result = runtime.attachHost(requireHandle().generation)
                assertTrue("runtime attachment failed: $result", result is RuntimeResult.Success)
            }
            runner.clearMsgQueue()
            trace.requests.clear()
            trace.ownedRequests.clear()
            trace.requestHandler.set(response)
        }
    }

    fun requireHandle(): GhostHandle = requireNotNull(handle) { "fixture was created without startup" }

    override fun close() = runtime.close()

    private companion object {
        val fixtureIds = AtomicLong()
        const val NO_CONTENT_RESPONSE = "SHIORI/3.0 204 No Content\r\n\r\n"
    }
}

class RuntimeFixtureRegistry : TestRule {
    private val fixtures = mutableListOf<RuntimeFixture>()

    internal fun create(
        id: String = "recording",
        root: File = File("build/runtime-fixtures/$id-${registryFixtureIds.incrementAndGet()}"),
        trace: RecordingShioriTrace = RecordingShioriTrace(),
        persistence: InMemoryGhostRuntimePersistence = InMemoryGhostRuntimePersistence(),
        response: (String) -> String = { "SHIORI/3.0 204 No Content\r\n\r\n" },
        bootstrapResponse: ((String) -> String)? = null,
        preparedFactory: (Long, String, File) -> PreparedGhost = ::preparedGhost,
        runnerConfiguration: SScriptRunnerConfiguration? = null,
        autoStart: Boolean = true,
        autoAttach: Boolean = autoStart,
    ): RuntimeFixture = RuntimeFixture(
        id = id,
        root = root,
        trace = trace,
        persistence = persistence,
        response = response,
        bootstrapResponse = bootstrapResponse,
        preparedFactory = preparedFactory,
        runnerConfiguration = runnerConfiguration,
        autoStart = autoStart,
        autoAttach = autoAttach,
    ).also(fixtures::add)

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            try {
                base.evaluate()
            } finally {
                fixtures.asReversed().forEach(RuntimeFixture::close)
                fixtures.clear()
            }
        }
    }

    private companion object {
        val registryFixtureIds = AtomicLong()
    }
}
