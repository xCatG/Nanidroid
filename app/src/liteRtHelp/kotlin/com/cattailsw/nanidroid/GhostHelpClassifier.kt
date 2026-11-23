package com.cattailsw.nanidroid

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.android.play.core.aipacks.AiPackManagerFactory
import com.google.android.play.core.aipacks.AiPackStateUpdateListener
import com.google.android.play.core.aipacks.model.AiPackStatus
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal fun ghostHelpClassifier(context: Context): suspend (String) -> String? = { question ->
    try {
        val model = fetchLiteRtModel(context)
        withContext(Dispatchers.IO) {
            modelLock.withLock { classify(model, question) }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "Help topic selection failed: ${error.javaClass.simpleName}")
        null
    }
}

private fun classify(model: File, question: String): String {
    val start = SystemClock.elapsedRealtime()
    val engineConfig = EngineConfig(modelPath = model.absolutePath, backend = Backend.CPU(), maxNumTokens = 1024)
    val conversationConfig = ConversationConfig(
        systemInstruction = Contents.of(CLASSIFIER_INSTRUCTION),
        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0),
        thinkingConfig = ThinkingConfig(enableThinking = false),
        enableResponseFormat = true
    )
    val topic = Engine(engineConfig).use { engine ->
        engine.initialize()
        engine.createConversation(conversationConfig).use { conversation ->
            conversation.sendMessage(
                question,
                maxOutputToken = 10,
                responseFormat = ResponseFormat.regex("(IMPORT|CONTROLS|INTRO|UNKNOWN)")
            ).toString().trim()
        }
    }
    Log.i(TAG, "LiteRT CPU help classification finished in ${SystemClock.elapsedRealtime() - start} ms")
    return topic
}

internal suspend fun fetchLiteRtModel(context: Context): File {
    val packs = AiPackManagerFactory.getInstance(context)
    fun modelFile(): File? = packs.getPackLocation(PACK)?.assetsPath()?.let { File(it, MODEL).takeIf(File::isFile) }

    modelFile()?.let { return it }
    val completed = withTimeoutOrNull(120_000) {
        callbackFlow {
            val listener = AiPackStateUpdateListener { state ->
                if (state.name() == PACK) {
                    when (state.status()) {
                        AiPackStatus.COMPLETED -> trySend(Unit)
                        AiPackStatus.FAILED, AiPackStatus.CANCELED ->
                            close(IllegalStateException("AI pack stopped with status ${state.status()}"))
                    }
                }
            }
            packs.registerListener(listener)
            if (modelFile() != null) {
                trySend(Unit)
            } else {
                packs.fetch(listOf(PACK)).addOnFailureListener { close(it) }
            }
            awaitClose { packs.unregisterListener(listener) }
        }.first()
    }
    checkNotNull(completed) { "Model pack download timed out" }
    return checkNotNull(modelFile()) { "Downloaded pack contains no model path" }
}

private const val TAG = "LiteRtGhostHelp"
private const val PACK = "qwen_ghost_ai"
private const val MODEL = "Qwen3-0.6B.litertlm"
private val modelLock = Mutex()

private const val CLASSIFIER_INSTRUCTION = "Classify Nanidroid app help questions. " +
    "IMPORT = install, import, add, or switch ghosts, characters and NAR archives. " +
    "CONTROLS = tap characters, click options, type replies or dismiss speech balloons. " +
    "INTRO = explain what Nanidroid or desktop ghosts are. UNKNOWN = any unrelated question. " +
    "For example, adding a character is IMPORT, opening its menu is CONTROLS, " +
    "and asking what a desktop ghost is is INTRO. Return one label."
