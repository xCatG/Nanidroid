package com.cattailsw.nanidroid.llmghost.openai

import com.cattailsw.nanidroid.llmghost.model.GenerationEvent
import com.cattailsw.nanidroid.llmghost.model.GenerationUsage
import com.cattailsw.nanidroid.llmghost.model.GhostGenerationRequest
import com.cattailsw.nanidroid.llmghost.model.GhostModelBackend
import com.cattailsw.nanidroid.llmghost.model.ModelCapabilities
import com.cattailsw.nanidroid.llmghost.model.ModelPreparation
import com.cattailsw.nanidroid.llmghost.prompt.GhostPromptRenderer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.UnresolvedAddressException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class OpenAiBackendConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String?,
    val streaming: Boolean,
    val connectTimeoutMillis: Long,
    val requestTimeoutMillis: Long,
    val seed: Long? = null,
)

internal fun createOpenAiHttpClient(): HttpClient = HttpClient(CIO) {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout)
    install(ContentNegotiation) {
        json(RESPONSE_JSON)
    }
}

internal class OpenAiCompatibleBackend(
    private val client: HttpClient,
    private val config: OpenAiBackendConfig,
    private val promptRenderer: GhostPromptRenderer = GhostPromptRenderer(),
) : GhostModelBackend {
    internal var retryCount: Int = 0
        private set

    override val capabilities = ModelCapabilities(
        streaming = config.streaming,
        structuredOutput = false,
    )

    override fun prepare(): Flow<ModelPreparation> = when {
        config.model.isBlank() -> flowOf(ModelPreparation.Failed("missing-model", "A model identifier is required."))
        config.connectTimeoutMillis <= 0 || config.requestTimeoutMillis <= 0 ->
            flowOf(ModelPreparation.Failed("invalid-timeout", "Finite positive HTTP timeouts are required."))
        else -> flowOf(ModelPreparation.Ready)
    }

    override fun generate(request: GhostGenerationRequest): Flow<GenerationEvent> = flow {
        retryCount = 0
        if (config.model.isBlank()) {
            emit(GenerationEvent.Failed("missing-model", "A model identifier is required."))
            return@flow
        }
        if (config.connectTimeoutMillis <= 0 || config.requestTimeoutMillis <= 0) {
            emit(GenerationEvent.Failed("invalid-timeout", "Finite positive HTTP timeouts are required."))
            return@flow
        }
        try {
            val prompt = promptRenderer.render(request)
            val wireRequest = OpenAiChatRequest(
                model = config.model,
                messages = listOf(
                    OpenAiMessage("system", prompt.system),
                    OpenAiMessage("user", prompt.user),
                ),
                stream = config.streaming,
                seed = config.seed,
            )
            while (true) {
                when (val result = execute(wireRequest) { emit(GenerationEvent.TextDelta(it)) }) {
                    is AttemptResult.Success -> {
                        result.text?.let { emit(GenerationEvent.TextDelta(it)) }
                        emit(GenerationEvent.Completed(result.usage))
                        return@flow
                    }

                    is AttemptResult.Failed -> {
                        emit(GenerationEvent.Failed(result.code, result.detail))
                        return@flow
                    }

                    is AttemptResult.Retryable -> {
                        if (retryCount >= 1) {
                            emit(
                                GenerationEvent.Failed(
                                    "service-unavailable",
                                    "The endpoint remained unavailable after one retry.",
                                ),
                            )
                            return@flow
                        }
                        retryCount++
                        delay(result.delayMillis)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpRequestTimeoutException) {
            emit(GenerationEvent.Failed("request-timeout", "The endpoint request timed out."))
        } catch (_: ConnectTimeoutException) {
            emit(GenerationEvent.Failed("request-timeout", "The endpoint request timed out."))
        } catch (_: SocketTimeoutException) {
            emit(GenerationEvent.Failed("request-timeout", "The endpoint request timed out."))
        } catch (_: ConnectException) {
            emit(GenerationEvent.Failed("connection-failed", "The endpoint connection failed."))
        } catch (_: UnresolvedAddressException) {
            emit(GenerationEvent.Failed("connection-failed", "The endpoint connection failed."))
        } catch (_: Exception) {
            emit(GenerationEvent.Failed("transport-error", "The endpoint request failed."))
        }
    }

    override suspend fun close() = Unit

    private fun endpoint(): String = "${config.baseUrl.trimEnd('/')}/chat/completions"

    private suspend fun execute(
        wireRequest: OpenAiChatRequest,
        emitDelta: suspend (String) -> Unit,
    ): AttemptResult = client.preparePost(endpoint()) {
        contentType(ContentType.Application.Json)
        config.apiKey?.takeIf { it.isNotBlank() }?.let { token ->
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        timeout {
            connectTimeoutMillis = config.connectTimeoutMillis
            requestTimeoutMillis = config.requestTimeoutMillis
            socketTimeoutMillis = config.requestTimeoutMillis
        }
        setBody(REQUEST_JSON.encodeToString(wireRequest))
    }.execute { response ->
        val status = response.status.value
        if (status !in 200..299) {
            if (status in RETRYABLE_STATUS_CODES) {
                return@execute AttemptResult.Retryable(retryDelayMillis(response.headers[HttpHeaders.RetryAfter]))
            }
            return@execute statusFailure(status)
        }
        if (config.streaming) {
            when (val stream = parseStream(response.bodyAsChannel(), emitDelta)) {
                is StreamResult.Completed -> AttemptResult.Success(text = null, usage = stream.usage)
                is StreamResult.Failed -> AttemptResult.Failed(stream.code, stream.detail)
            }
        } else {
            when (val body = readBounded(response.bodyAsChannel())) {
                is BoundedRead.TooLarge ->
                    AttemptResult.Failed("response-too-large", "The endpoint response exceeded the size limit.")
                is BoundedRead.InvalidUtf8 ->
                    AttemptResult.Failed("invalid-response", "The endpoint response was not valid UTF-8 JSON.")
                is BoundedRead.Success -> decodeNonStreaming(body.value)
            }
        }
    }

    private fun decodeNonStreaming(body: String): AttemptResult {
        val response = try {
            RESPONSE_JSON.decodeFromString<OpenAiChatResponse>(body)
        } catch (_: SerializationException) {
            return AttemptResult.Failed("invalid-response", "The endpoint response was malformed JSON.")
        } catch (_: IllegalArgumentException) {
            return AttemptResult.Failed("invalid-response", "The endpoint response was malformed JSON.")
        }
        val text = response.choices.firstOrNull()?.message?.content
            ?: return AttemptResult.Failed(
                "invalid-response",
                "The endpoint response did not contain assistant text.",
            )
        return AttemptResult.Success(text, response.usage?.toCommon())
    }

    private fun statusFailure(status: Int): AttemptResult.Failed = when (status) {
        in 300..399 -> AttemptResult.Failed("http-redirect", "The endpoint returned an HTTP redirect.")
        401, 403 -> AttemptResult.Failed("unauthorized", "The endpoint rejected authorization.")
        404 -> AttemptResult.Failed("model-not-found", "The endpoint or configured model was not found.")
        429 -> AttemptResult.Failed("rate-limited", "The endpoint rate limit was reached.")
        in 400..499 -> AttemptResult.Failed("http-client-error", "The endpoint rejected the request.")
        in 500..599 -> AttemptResult.Failed("server-error", "The endpoint reported a server error.")
        else -> AttemptResult.Failed("http-error", "The endpoint returned an unsuccessful HTTP status.")
    }

    private fun retryDelayMillis(retryAfter: String?): Long {
        val seconds = retryAfter?.trim()?.toLongOrNull()
        if (seconds != null) {
            return seconds.coerceIn(0, MAX_RETRY_DELAY_MILLIS / 1_000) * 1_000
        }
        val dateMillis = runCatching {
            ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()
        return dateMillis?.minus(System.currentTimeMillis())
            ?.coerceIn(0, MAX_RETRY_DELAY_MILLIS)
            ?: DEFAULT_RETRY_DELAY_MILLIS
    }

    private suspend fun parseStream(
        channel: ByteReadChannel,
        emitDelta: suspend (String) -> Unit,
    ): StreamResult {
        val line = ByteArrayOutputStream()
        val dataLines = mutableListOf<String>()
        var usage: GenerationUsage? = null
        var totalBytes = 0

        suspend fun dispatch(): StreamResult? {
            if (dataLines.isEmpty()) return null
            val data = dataLines.joinToString("\n")
            dataLines.clear()
            if (data == "[DONE]") return StreamResult.Completed(usage)
            val chunk = try {
                RESPONSE_JSON.decodeFromString<OpenAiStreamChunk>(data)
            } catch (_: SerializationException) {
                return StreamResult.Failed("invalid-stream-event", "The endpoint sent a malformed stream event.")
            } catch (_: IllegalArgumentException) {
                return StreamResult.Failed("invalid-stream-event", "The endpoint sent a malformed stream event.")
            }
            chunk.usage?.let { usage = it.toCommon() }
            chunk.choices.forEach { choice ->
                choice.delta?.content?.takeIf { it.isNotEmpty() }?.let { emitDelta(it) }
            }
            return null
        }

        try {
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = channel.readAvailable(buffer, 0, buffer.size)
                if (count == -1) break
                if (count == 0) continue
                totalBytes += count
                if (totalBytes > MAX_RESPONSE_BYTES) {
                    return StreamResult.Failed("response-too-large", "The endpoint response exceeded the size limit.")
                }
                for (index in 0 until count) {
                    val byte = buffer[index]
                    if (byte == '\n'.code.toByte()) {
                        val bytes = line.toByteArray().let {
                            if (it.lastOrNull() == '\r'.code.toByte()) it.copyOf(it.size - 1) else it
                        }
                        line.reset()
                        val decoded = decodeUtf8(bytes)
                            ?: return StreamResult.Failed("invalid-stream-event", "The endpoint sent invalid UTF-8 stream data.")
                        when {
                            decoded.isEmpty() -> dispatch()?.let { return it }
                            decoded.startsWith(":") -> Unit
                            decoded == "data" -> dataLines += ""
                            decoded.startsWith("data:") -> dataLines += decoded.removePrefix("data:").removePrefix(" ")
                        }
                    } else {
                        line.write(byte.toInt())
                    }
                }
            }
            if (line.size() > 0) {
                val decoded = decodeUtf8(line.toByteArray())
                    ?: return StreamResult.Failed("invalid-stream-event", "The endpoint sent invalid UTF-8 stream data.")
                if (decoded.startsWith("data:")) dataLines += decoded.removePrefix("data:").removePrefix(" ")
            }
            dispatch()?.let { if (it is StreamResult.Failed) return it }
            return StreamResult.Failed("incomplete-stream", "The endpoint stream ended before its completion marker.")
        } finally {
            channel.cancel(CancellationException("Response channel closed."))
        }
    }

    private suspend fun readBounded(channel: ByteReadChannel): BoundedRead {
        val output = ByteArrayOutputStream()
        return try {
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = channel.readAvailable(buffer, 0, buffer.size)
                if (count == -1) break
                if (count == 0) continue
                if (output.size() + count > MAX_RESPONSE_BYTES) return BoundedRead.TooLarge
                output.write(buffer, 0, count)
            }
            decodeUtf8(output.toByteArray())?.let(BoundedRead::Success) ?: BoundedRead.InvalidUtf8
        } finally {
            channel.cancel(CancellationException("Response channel closed."))
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        null
    }

    private sealed interface StreamResult {
        data class Completed(val usage: GenerationUsage?) : StreamResult
        data class Failed(val code: String, val detail: String) : StreamResult
    }

    private sealed interface BoundedRead {
        data class Success(val value: String) : BoundedRead
        data object TooLarge : BoundedRead
        data object InvalidUtf8 : BoundedRead
    }

    private sealed interface AttemptResult {
        data class Success(val text: String?, val usage: GenerationUsage?) : AttemptResult
        data class Failed(val code: String, val detail: String) : AttemptResult
        data class Retryable(val delayMillis: Long) : AttemptResult
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val DEFAULT_RETRY_DELAY_MILLIS = 100L
        const val MAX_RETRY_DELAY_MILLIS = 5_000L
        val RETRYABLE_STATUS_CODES = setOf(502, 503, 504)
    }
}

private fun OpenAiUsage.toCommon() = GenerationUsage(promptTokens, completionTokens, totalTokens)

private val REQUEST_JSON = Json {
    explicitNulls = false
    encodeDefaults = true
}

private val RESPONSE_JSON = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
