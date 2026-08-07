package com.cattailsw.nanidroid.llmghost.openai

import com.cattailsw.nanidroid.llmghost.model.CanonicalTalk
import com.cattailsw.nanidroid.llmghost.model.CanonicalTurn
import com.cattailsw.nanidroid.llmghost.model.GenerationEvent
import com.cattailsw.nanidroid.llmghost.model.GenerationScenario
import com.cattailsw.nanidroid.llmghost.model.GenerationUsage
import com.cattailsw.nanidroid.llmghost.model.GhostGenerationRequest
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId
import com.cattailsw.nanidroid.llmghost.model.OutputLanguage
import com.cattailsw.nanidroid.llmghost.model.ScenarioKind
import com.cattailsw.nanidroid.llmghost.model.TalkCategory
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAiCompatibleBackendTest {
    @Test
    fun postsRenderedMessagesAndMapsNonStreamingTextAndUsage() = withServer { server ->
        val requests = LinkedBlockingQueue<CapturedRequest>()
        server.createContext("/v1/chat/completions") { exchange ->
            requests += CapturedRequest(
                method = exchange.requestMethod,
                contentType = exchange.requestHeaders.getFirst("Content-Type"),
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8),
            )
            exchange.respondJson(
                200,
                """{"id":"response-id","choices":[{"message":{"role":"assistant","content":"{\"turns\":[]}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":11,"completion_tokens":5,"total_tokens":16},"provider_extension":true}""",
            )
        }
        val config = OpenAiBackendConfig(
            baseUrl = "${server.baseUrl()}/v1/",
            model = "nemotron-fixture",
            apiKey = "fixture-bearer-token",
            streaming = false,
            connectTimeoutMillis = 1_000,
            requestTimeoutMillis = 2_000,
            seed = 73,
        )
        val client = createOpenAiHttpClient()
        try {
            val events = runBlocking {
                OpenAiCompatibleBackend(client, config).generate(request()).toList()
            }

            assertEquals(
                listOf(
                    GenerationEvent.TextDelta("{\"turns\":[]}"),
                    GenerationEvent.Completed(GenerationUsage(11, 5, 16)),
                ),
                events,
            )
            val captured = assertNotNull(requests.poll())
            assertEquals("POST", captured.method)
            assertTrue(captured.contentType.orEmpty().startsWith("application/json"))
            assertEquals("Bearer fixture-bearer-token", captured.authorization)
            val json = Json.parseToJsonElement(captured.body).jsonObject
            assertEquals("nemotron-fixture", json.getValue("model").jsonPrimitive.content)
            assertFalse(json.getValue("stream").jsonPrimitive.boolean)
            assertEquals(73, json.getValue("seed").jsonPrimitive.content.toInt())
            val messages = json.getValue("messages").jsonArray
            assertEquals(listOf("system", "user"), messages.map { it.jsonObject.getValue("role").jsonPrimitive.content })
            assertTrue(messages.all { it.jsonObject.getValue("content").jsonPrimitive.content.isNotBlank() })
        } finally {
            client.close()
        }
    }

    @Test
    fun incrementallyParsesCrLfCommentsMultilineDataAndTerminalUsage() = withServer { server ->
        server.createContext("/v1/chat/completions") { exchange ->
            assertEquals("true", Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString())
                .jsonObject.getValue("stream").jsonPrimitive.content)
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val payload = buildString {
                append(": keepalive\r\n\r\n")
                append("data: {\"choices\":[\r\n")
                append("data: {\"delta\":{\"content\":\"hello \"}}]}\r\n\r\n")
                append("event: message\r\n")
                append("data: {\"choices\":[{\"delta\":{\"content\":\"世界\"}}]}\r\n\r\n")
                append("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2,\"total_tokens\":9},\"unknown\":true}\r\n\r\n")
                append("data: [DONE]\r\n\r\n")
            }.toByteArray(StandardCharsets.UTF_8)
            exchange.responseBody.use { output ->
                payload.forEach { byte ->
                    output.write(byte.toInt())
                    output.flush()
                }
            }
        }
        val client = createOpenAiHttpClient()
        try {
            val events = runBlocking {
                OpenAiCompatibleBackend(client, config(server, streaming = true)).generate(request()).toList()
            }

            assertEquals(
                listOf(
                    GenerationEvent.TextDelta("hello "),
                    GenerationEvent.TextDelta("世界"),
                    GenerationEvent.Completed(GenerationUsage(7, 2, 9)),
                ),
                events,
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun malformedSseDataBecomesATerminalFailure() = withServer { server ->
        server.sse("data: {not-json}\n\n")
        val client = createOpenAiHttpClient()
        try {
            val events = runBlocking {
                OpenAiCompatibleBackend(client, config(server, streaming = true)).generate(request()).toList()
            }

            assertEquals(1, events.size)
            assertFailure(events.single(), "invalid-stream-event")
        } finally {
            client.close()
        }
    }

    @Test
    fun rejectsOversizedStreamingResponseBeforeEof() = withServer { server ->
        server.sse("x".repeat(2 * 1024 * 1024 + 1))
        val client = createOpenAiHttpClient()
        try {
            val events = runBlocking {
                OpenAiCompatibleBackend(client, config(server, streaming = true)).generate(request()).toList()
            }

            assertFailure(events.single(), "response-too-large")
        } finally {
            client.close()
        }
    }

    @Test
    fun eofBeforeDonePreservesDeltaThenFailsWithoutRetry() = withServer { server ->
        val attempts = AtomicInteger()
        server.createContext("/v1/chat/completions") { exchange ->
            attempts.incrementAndGet()
            exchange.respondSse("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n")
        }
        val client = createOpenAiHttpClient()
        try {
            val events = runBlocking {
                OpenAiCompatibleBackend(client, config(server, streaming = true)).generate(request()).toList()
            }

            assertEquals(GenerationEvent.TextDelta("partial"), events.first())
            assertFailure(events.last(), "incomplete-stream")
            assertEquals(1, attempts.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun downstreamCancellationClosesStreamingResponse() = withServer { server ->
        val firstChunkSent = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val peerClosed = AtomicBoolean()
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.write("data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n".encodeToByteArray())
                exchange.responseBody.flush()
                firstChunkSent.countDown()
                releaseServer.await(2, TimeUnit.SECONDS)
                repeat(512) {
                    exchange.responseBody.write(ByteArray(256 * 1024))
                    exchange.responseBody.flush()
                }
            } catch (_: Exception) {
                peerClosed.set(true)
            } finally {
                exchange.responseBody.close()
            }
        }
        val client = createOpenAiHttpClient()
        try {
            val first = runBlocking {
                OpenAiCompatibleBackend(client, config(server, streaming = true)).generate(request()).first()
            }
            assertEquals(GenerationEvent.TextDelta("first"), first)
            assertTrue(firstChunkSent.await(1, TimeUnit.SECONDS))
            releaseServer.countDown()
            for (attempt in 0 until 120) {
                if (peerClosed.get()) break
                Thread.sleep(25)
            }
            assertTrue(peerClosed.get())
        } finally {
            releaseServer.countDown()
            client.close()
        }
    }

    @Test
    fun omitsOptionalAuthorizationAndSeed() = withServer { server ->
        val requests = LinkedBlockingQueue<CapturedRequest>()
        server.createContext("/v1/chat/completions") { exchange ->
            requests += CapturedRequest(
                exchange.requestMethod,
                exchange.requestHeaders.getFirst("Content-Type"),
                exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestBody.readAllBytes().decodeToString(),
            )
            exchange.respondJson(200, SUCCESS_JSON)
        }
        val client = createOpenAiHttpClient()
        try {
            runBlocking { OpenAiCompatibleBackend(client, config(server)).generate(request()).toList() }

            val captured = assertNotNull(requests.poll())
            assertEquals(null, captured.authorization)
            assertFalse(Json.parseToJsonElement(captured.body).jsonObject.containsKey("seed"))
        } finally {
            client.close()
        }
    }

    @Test
    fun doesNotFollowRedirects() = withServer { server ->
        val redirectedRequests = AtomicInteger()
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.responseHeaders.set("Location", "/redirected")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.createContext("/redirected") { exchange ->
            redirectedRequests.incrementAndGet()
            exchange.respondJson(200, SUCCESS_JSON)
        }
        val client = createOpenAiHttpClient()
        try {
            val events = runBlocking {
                OpenAiCompatibleBackend(client, config(server)).generate(request()).toList()
            }

            assertFailure(events.single(), "http-redirect")
            assertEquals(0, redirectedRequests.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun classifiesHttpFailuresWithoutBodyOrBearerLeakage() {
        val cases = listOf(
            400 to "http-client-error",
            401 to "unauthorized",
            404 to "model-not-found",
            429 to "rate-limited",
            500 to "server-error",
        )
        cases.forEach { (status, expectedCode) ->
            withServer { server ->
                val attempts = AtomicInteger()
                server.createContext("/v1/chat/completions") { exchange ->
                    attempts.incrementAndGet()
                    exchange.respondJson(status, "{\"error\":\"private-body-secret fixture-bearer-token\"}")
                }
                val client = createOpenAiHttpClient()
                try {
                    val events = runBlocking {
                        val configured = config(server).copy(apiKey = "fixture-bearer-token")
                        OpenAiCompatibleBackend(client, configured).generate(request()).toList()
                    }

                    assertFailure(events.single(), expectedCode, "private-body-secret", "fixture-bearer-token")
                    assertEquals(1, attempts.get())
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun rejectsBlankModelBeforeOpeningAConnection() = withServer { server ->
        val attempts = AtomicInteger()
        server.createContext("/v1/chat/completions") { exchange ->
            attempts.incrementAndGet()
            exchange.respondJson(200, SUCCESS_JSON)
        }
        val client = createOpenAiHttpClient()
        try {
            val events = runBlocking {
                OpenAiCompatibleBackend(client, config(server).copy(model = " ")).generate(request()).toList()
            }

            assertFailure(events.single(), "missing-model")
            assertEquals(0, attempts.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun invalidJsonAndOversizedResponsesAreDistinctSchemaFailures() = withServer { server ->
        val responseNumber = AtomicInteger()
        server.createContext("/v1/chat/completions") { exchange ->
            if (responseNumber.getAndIncrement() == 0) {
                exchange.respondJson(200, "{not-json}")
            } else {
                exchange.respondJson(200, "x".repeat(2 * 1024 * 1024 + 1))
            }
        }
        val client = createOpenAiHttpClient()
        try {
            val backend = OpenAiCompatibleBackend(client, config(server))
            val malformed = runBlocking { backend.generate(request()).toList() }
            val oversized = runBlocking { backend.generate(request()).toList() }

            assertFailure(malformed.single(), "invalid-response")
            assertFailure(oversized.single(), "response-too-large")
            assertEquals(2, responseNumber.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun timeoutAndRefusedConnectionHaveStableFailureCodes() {
        withServer { server ->
            server.createContext("/v1/chat/completions") { exchange ->
                Thread.sleep(1_000)
                runCatching { exchange.respondJson(200, SUCCESS_JSON) }
            }
            val client = createOpenAiHttpClient()
            try {
                val events = runBlocking {
                    val configured = config(server).copy(requestTimeoutMillis = 100)
                    OpenAiCompatibleBackend(client, configured).generate(request()).toList()
                }
                assertFailure(events.single(), "request-timeout")
            } finally {
                client.close()
            }
        }

        val unusedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val client = createOpenAiHttpClient()
        try {
            val configured = OpenAiBackendConfig(
                baseUrl = "http://127.0.0.1:$unusedPort/v1",
                model = "nemotron-fixture",
                apiKey = null,
                streaming = false,
                connectTimeoutMillis = 200,
                requestTimeoutMillis = 500,
            )
            val events = runBlocking { OpenAiCompatibleBackend(client, configured).generate(request()).toList() }
            assertFailure(events.single(), "connection-failed")
        } finally {
            client.close()
        }
    }

    @Test
    fun retriesOneTransientStatusOnceAndRecordsIt() = withServer { server ->
        val attempts = AtomicInteger()
        server.createContext("/v1/chat/completions") { exchange ->
            if (attempts.incrementAndGet() == 1) {
                exchange.responseHeaders.set("Retry-After", "0")
                exchange.respondJson(503, "private-body-secret")
            } else {
                exchange.respondJson(200, SUCCESS_JSON)
            }
        }
        val client = createOpenAiHttpClient()
        try {
            val backend = OpenAiCompatibleBackend(client, config(server))
            val events = runBlocking { backend.generate(request()).toList() }

            assertEquals(listOf(GenerationEvent.TextDelta("ok"), GenerationEvent.Completed(null)), events)
            assertEquals(2, attempts.get())
            assertEquals(1, backend.retryCount)
        } finally {
            client.close()
        }
    }

    @Test
    fun capsTransientRetryAtOneForEachEligibleStatus() {
        listOf(502, 503, 504).forEach { status ->
            withServer { server ->
                val attempts = AtomicInteger()
                server.createContext("/v1/chat/completions") { exchange ->
                    attempts.incrementAndGet()
                    exchange.responseHeaders.set("Retry-After", "0")
                    exchange.respondJson(status, "private-body-secret")
                }
                val client = createOpenAiHttpClient()
                try {
                    val backend = OpenAiCompatibleBackend(client, config(server))
                    val events = runBlocking { backend.generate(request()).toList() }

                    assertFailure(events.single(), "service-unavailable", "private-body-secret")
                    assertEquals(2, attempts.get())
                    assertEquals(1, backend.retryCount)
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun cancellationDuringRetryAfterStopsBeforeSecondRequest() = withServer { server ->
        val attempts = AtomicInteger()
        val firstResponse = CountDownLatch(1)
        server.createContext("/v1/chat/completions") { exchange ->
            attempts.incrementAndGet()
            exchange.responseHeaders.set("Retry-After", Long.MAX_VALUE.toString())
            exchange.respondJson(503, "private-body-secret")
            firstResponse.countDown()
        }
        val client = createOpenAiHttpClient()
        try {
            runBlocking {
                val backend = OpenAiCompatibleBackend(client, config(server).copy(requestTimeoutMillis = 10_000))
                val collection = async(Dispatchers.Default) { backend.generate(request()).toList() }
                assertTrue(firstResponse.await(1, TimeUnit.SECONDS))
                delay(50)
                collection.cancel()
                assertFailsWith<CancellationException> { collection.await() }
                assertEquals(1, attempts.get())
            }
        } finally {
            client.close()
        }
    }

    private fun request() = GhostGenerationRequest(
        scenario = GenerationScenario(ScenarioKind.IDLE, topic = "rain"),
        language = OutputLanguage.ENGLISH,
        examples = listOf(
            CanonicalTalk(
                id = "talk-1",
                sourcePath = "ghost/master/dic.txt",
                sourceLine = 1,
                heading = "idle",
                category = TalkCategory.IDLE,
                turns = listOf(CanonicalTurn(GhostSpeakerId.SAKURA, 0, "Hello")),
            ),
        ),
        validSurfaces = mapOf(
            GhostSpeakerId.SAKURA to setOf(0),
            GhostSpeakerId.KERO to setOf(10),
        ),
    )

    private fun config(server: HttpServer, streaming: Boolean = false) = OpenAiBackendConfig(
        baseUrl = "${server.baseUrl()}/v1",
        model = "nemotron-fixture",
        apiKey = null,
        streaming = streaming,
        connectTimeoutMillis = 1_000,
        requestTimeoutMillis = 2_000,
    )

    private fun withServer(block: (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.start()
        try {
            block(server)
        } finally {
            server.stop(0)
        }
    }

    private fun HttpServer.baseUrl(): String = "http://${address.address.hostAddress}:${address.port}"

    private fun com.sun.net.httpserver.HttpExchange.respondJson(status: Int, body: String) {
        responseHeaders.set("Content-Type", "application/json")
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpServer.sse(body: String) {
        createContext("/v1/chat/completions") { exchange -> exchange.respondSse(body) }
    }

    private fun com.sun.net.httpserver.HttpExchange.respondSse(body: String) {
        responseHeaders.set("Content-Type", "text/event-stream")
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun assertFailure(event: GenerationEvent, code: String, vararg forbidden: String) {
        val failure = assertIs<GenerationEvent.Failed>(event)
        assertEquals(code, failure.code)
        assertTrue(failure.detail.isNotBlank())
        forbidden.forEach { assertFalse(failure.detail.contains(it)) }
    }

    private data class CapturedRequest(
        val method: String,
        val contentType: String?,
        val authorization: String?,
        val body: String,
    )

    private companion object {
        const val SUCCESS_JSON = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}"
    }
}
