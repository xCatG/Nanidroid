package com.cattailsw.nanidroid

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.engine.BuiltInShiori
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.ghost.BundledGhostRepository
import com.cattailsw.nanidroid.runtime.ScriptPlayer
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.tool
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Run on a local-testing bundle installed with bundletool, using -e liveLiteRt true. */
@RunWith(AndroidJUnit4::class)
class LiteRtGhostDeviceTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test
    fun onDemandPackModelDrivesOneShioriTurn(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(
            "Requires -e liveLiteRt true",
            InstrumentationRegistry.getArguments().getString("liveLiteRt") == "true"
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = fetchLiteRtModel(context)
        assertEquals(613_722_264L, model.length())
        val repository = BundledGhostRepository(
            openAsset = { context.assets.open("nanidroid.zip") },
            ghostRoot = files.newFolder("ghost")
        )
        val ghost = repository.load(repository.ensureInstalled(), Locale.ENGLISH)
        val engine = BuiltInShiori.bundled(ghost.content, debugToolsEnabled = true) {
            val start = SystemClock.elapsedRealtime()
            Engine(EngineConfig(modelPath = model.absolutePath, backend = Backend.CPU(), maxNumTokens = 1024))
                .use { modelEngine ->
                    modelEngine.initialize()
                    val configuration = ConversationConfig(
                        systemInstruction = Contents.of("Use the say_ghost_line tool to speak through Nanidroid."),
                        tools = listOf(tool(GhostLineTool)),
                        automaticToolCalling = false,
                        thinkingConfig = ThinkingConfig(enableThinking = false)
                    )
                    modelEngine.createConversation(configuration).use { conversation ->
                        val result = conversation.sendMessage(
                            "Have Sakura or Kero greet the user and suggest a topic in one short English line."
                        )
                        check(result.toolCalls.size <= 1) { "Model returned multiple tool calls" }
                        val call = result.toolCalls.singleOrNull()
                        val arguments = if (call != null) {
                            assertEquals("say_ghost_line", call.name)
                            call.arguments
                        } else {
                            // This Qwen checkpoint can return its tool call envelope as plain JSON text.
                            val json = JSONTokener(result.toString())
                            val envelope = json.nextValue() as JSONObject
                            assertEquals('\u0000', json.nextClean())
                            assertEquals("say_ghost_line", envelope.getString("name"))
                            val fields = envelope.getJSONObject("arguments")
                            mapOf("dialogue" to fields.opt("dialogue"), "speaker" to fields.opt("speaker"))
                        }
                        val line = checkNotNull(
                            compileNanoGhostLine(
                                arguments["dialogue"] as? String,
                                arguments["speaker"] as? String,
                                ghost.surfaces.keys
                            )
                        ) { "Model tool arguments failed validation: $arguments" }
                        Log.i(TAG, "CPU model and SHIORI reply ready in ${SystemClock.elapsedRealtime() - start} ms")
                        line
                    }
                }
        }
        val reply = engine.request(ShioriEvent(BuiltInShiori.NANO_SPIKE_EVENT))
        assertEquals(200, reply.status)
        val frame = ScriptPlayer(checkNotNull(reply.value)).advanceBy(15_000)
        val dialogue = listOf(frame.sakura, frame.kero).map { it.text }.single { it.isNotBlank() }
        assertTrue(frame.hostCommands.isEmpty())
        Log.i(TAG, "Played generated ghost line: $dialogue")
    }

    @Test
    fun onDemandPackModelClassifiesHelpQuestions(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(
            "Requires -e liveLiteRt true",
            InstrumentationRegistry.getArguments().getString("liveLiteRt") == "true"
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val classify = ghostHelpClassifier(context)
        val questions = mapOf(
            "How do I import a ghost?" to "IMPORT",
            "How do I tap a ghost to interact?" to "CONTROLS",
            "What is Nanidroid?" to "INTRO",
            "What is the weather in Seattle?" to "UNKNOWN",
            "Can I add a character from a NAR?" to "IMPORT",
            "How do I dismiss a balloon?" to "CONTROLS",
            "What are desktop ghosts?" to "INTRO",
            "Can you tell me a joke?" to "UNKNOWN"
        )
        val answers = questions.keys.associateWith { classify(it) }
        assertEquals(questions, answers)
    }

    private object GhostLineTool : OpenApiTool {
        override fun getToolDescriptionJsonString() = """
            {
              "name":"say_ghost_line",
              "description":"Say a short line as a Nanidroid ghost.",
              "parameters":{
                "type":"object",
                "properties":{
                  "speaker":{"type":"string","enum":["sakura","kero"]},
                  "dialogue":{"type":"string","description":"One friendly sentence to display"}
                },
                "required":["speaker","dialogue"]
              }
            }
        """.trimIndent()

        override fun execute(paramsJsonString: String): String = error("The spike dispatches tool calls manually")
    }

    private companion object {
        const val TAG = "LiteRtGhostSpike"
    }
}
