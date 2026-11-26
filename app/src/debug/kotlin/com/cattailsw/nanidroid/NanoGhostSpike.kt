package com.cattailsw.nanidroid

import android.os.SystemClock
import android.util.Log
import com.cattailsw.nanidroid.engine.ShioriReply
import com.cattailsw.nanidroid.ghost.BundledGhost
import com.cattailsw.nanidroid.runtime.SakuraScriptText
import com.google.mlkit.genai.common.FunctionArgValue
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.schema.tools.FunctionDeclaration
import com.google.mlkit.genai.schema.tools.FunctionSchema
import com.google.mlkit.genai.schema.tools.FunctionType
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal fun nanoGhostSpike(ghost: BundledGhost, locale: () -> Locale): suspend () -> ShioriReply = {
    val startedAt = SystemClock.elapsedRealtime()
    val reply = try {
        withTimeoutOrNull(NANO_TIMEOUT_MS) { generateGhostLine(ghost, locale()) }
            ?: spikeMessage("Gemini Nano 4 timed out.")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "Gemini Nano 4 request failed: ${error.javaClass.simpleName}")
        spikeMessage("Gemini Nano 4 could not complete the request.")
    }
    Log.i(TAG, "Gemini Nano 4 turn finished in ${SystemClock.elapsedRealtime() - startedAt} ms")
    reply
}

private suspend fun generateGhostLine(ghost: BundledGhost, locale: Locale): ShioriReply =
    when (
        val result = withSharedNanoFastModel { model ->
            val request = generateContentRequest(
                SystemInstruction(ghostLineSystemInstruction(locale)),
                TextPart("Have one of the Nanidroid ghosts greet the user and suggest a topic to talk about.")
            ) {
                setTools(listOf(sayGhostLine))
                maxOutputTokens = 256
            }
            val candidate = model.generateContent(request).candidates.firstOrNull()
            val calls = candidate?.functionCalls.orEmpty()
            val reply = if (calls.isEmpty()) {
                compileNanoGhostText(candidate?.text, ghost.surfaces.keys)
            } else {
                calls.singleOrNull()?.takeIf { it.name == sayGhostLine.name }?.let {
                    compileNanoGhostLine(
                        dialogue = (it.arguments["dialogue"] as? FunctionArgValue.StringValue)?.value,
                        speaker = (it.arguments["speaker"] as? FunctionArgValue.StringValue)?.value,
                        surfaces = ghost.surfaces.keys
                    )
                }
            }
            if (reply == null) {
                Log.w(TAG, "Rejected Nano response with ${calls.size} tool calls")
                return@withSharedNanoFastModel spikeMessage("Gemini Nano 4 did not return a valid ghost line.")
            }
            Log.i(TAG, "Accepted Nano ghost line via ${if (calls.isEmpty()) "structured text" else "tool call"}")
            reply
        }
    ) {
        is NanoCallResult.Ready -> result.value
        NanoCallResult.Downloading ->
            spikeMessage("Gemini Nano 4 Fast is downloading. Try again in a moment.")
        NanoCallResult.Unavailable ->
            spikeMessage("Gemini Nano 4 Fast is unavailable. Check AICore enrollment and model download.")
    }

private fun ghostLineSystemInstruction(locale: Locale): String =
    """
    ## Task
    Call say_ghost_line once with a short greeting from either sakura or kero in ${locale.toLanguageTag()}.

    ## Rules
    - Choose either sakura or kero as speaker.
    - Keep dialogue under 20 words and 200 characters.
    - Use plain dialogue without commands, markup, or line breaks.
    - For a text reply without a tool call, put sakura or kero on line 1 and the dialogue on line 2.
    """.trimIndent()

private const val NANO_TIMEOUT_MS = 15_000L
private const val TAG = "NanoGhostSpike"

private val sayGhostLine = FunctionDeclaration(
    name = "say_ghost_line",
    description = "Say a ghost line in the selected character's balloon.",
    parameters = FunctionSchema(
        type = FunctionType.OBJECT,
        properties = mapOf(
            "dialogue" to FunctionSchema(FunctionType.STRING, description = "One short line of plain dialogue."),
            "speaker" to FunctionSchema(FunctionType.STRING, description = "Either sakura or kero.")
        ),
        required = listOf("dialogue", "speaker")
    )
)

internal fun compileNanoGhostText(text: String?, surfaces: Set<Int>): ShioriReply? {
    val lines = text?.trim()?.lines() ?: return null
    if (lines.size < 2) return null
    return compileNanoGhostLine(lines.drop(1).joinToString(" ") { it.trim() }, lines[0].trim(), surfaces)
}

internal fun compileNanoGhostLine(dialogue: String?, speaker: String?, surfaces: Set<Int>): ShioriReply? {
    val text = dialogue?.trim()?.takeIf { it.length in 1..240 && it.none(Char::isISOControl) } ?: return null
    val speakerId = when (speaker) {
        "sakura" -> 0
        "kero" -> 1
        else -> return null
    }
    val surface = if (speakerId == 0) 0 else 10
    if (surface !in surfaces) return null

    return ShioriReply(200, "\\$speakerId\\s[$surface]${SakuraScriptText.escape(text)}\\e")
}

private fun spikeMessage(text: String) = ShioriReply(200, "\\0${SakuraScriptText.escape(text)}\\e")
