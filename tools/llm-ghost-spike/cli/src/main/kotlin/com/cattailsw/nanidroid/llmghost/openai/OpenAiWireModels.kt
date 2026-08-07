package com.cattailsw.nanidroid.llmghost.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val stream: Boolean,
    val seed: Long? = null,
)

@Serializable
internal data class OpenAiMessage(
    val role: String,
    val content: String,
)

@Serializable
internal data class OpenAiChatResponse(
    val choices: List<OpenAiChatChoice> = emptyList(),
    val usage: OpenAiUsage? = null,
)

@Serializable
internal data class OpenAiChatChoice(
    val message: OpenAiMessage? = null,
)

@Serializable
internal data class OpenAiStreamChunk(
    val choices: List<OpenAiStreamChoice> = emptyList(),
    val usage: OpenAiUsage? = null,
)

@Serializable
internal data class OpenAiStreamChoice(
    val delta: OpenAiStreamDelta? = null,
)

@Serializable
internal data class OpenAiStreamDelta(
    val content: String? = null,
)

@Serializable
internal data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)
