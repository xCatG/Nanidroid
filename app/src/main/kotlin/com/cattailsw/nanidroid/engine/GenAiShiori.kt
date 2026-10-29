package com.cattailsw.nanidroid.engine

import com.cattailsw.nanidroid.runtime.SakuraScriptText
import kotlinx.coroutines.CancellationException

/** Authored dialogue wins; generated plain text fills only missing user-facing replies. */
class GenAiShiori(
    private val base: ShioriEngine,
    private val generator: GhostTalkGenerator
) : ShioriEngine {
    private var isAvailable: () -> Boolean = { true }

    internal constructor(
        base: ShioriEngine, generator: GhostTalkGenerator, isAvailable: () -> Boolean
    ) : this(base, generator) {
        this.isAvailable = isAvailable
    }

    override suspend fun request(event: ShioriEvent): ShioriReply {
        val reply = base.request(event)
        val userEvent = event.id == "OnUserInput" ||
            (event.id == "OnCommunicate" && event.references.firstOrNull() == "user")
        if (!userEvent || !isAvailable()) return reply
        if (reply.status != 204 && (reply.status != 200 || !reply.value.isNullOrBlank())) return reply
        val userText = event.references.getOrNull(1) ?: return reply
        val text = try {
            generator.reply(userText)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return reply
        }
        return ShioriReply(200, "\\0\\s[0]${SakuraScriptText.escape(text)}\\e")
    }
}
