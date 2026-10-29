package com.cattailsw.nanidroid.engine

import com.google.mlkit.genai.agent.Agent
import com.google.mlkit.genai.agent.AgentRunner
import com.google.mlkit.genai.agent.textTokens
import com.google.mlkit.genai.prompt.Content
import kotlinx.coroutines.flow.collect

/** Own one instance per ghost session and close it when that session ends. */
class MlKitGhostTalkGenerator(
    private val sessionId: String,
    private val instructions: String
) : ManagedGhostTalkGenerator {
    private val runner = lazy {
        AgentRunner(
            Agent.builder()
                .setName("ghost")
                .setInstructions("Reply using plain text only. $instructions")
                .enableAppFunctions(false)
                .build()
        )
    }
    private val session = lazy { runner.value.getOrCreateSession(sessionId) }

    override suspend fun reply(userText: String): String {
        val text = StringBuilder()
        session.value.runAsync(Content.builder().text(userText).build())
            .textTokens()
            .collect { text.append(it) }
        return text.toString()
    }

    override fun close() {
        try {
            if (session.isInitialized()) session.value.close()
        } finally {
            if (runner.isInitialized()) runner.value.close()
        }
    }
}
