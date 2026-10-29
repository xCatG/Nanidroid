package com.cattailsw.nanidroid.engine

import com.cattailsw.nanidroid.runtime.SakuraScriptText
import kotlinx.coroutines.CancellationException

/** Spike: generates only user-input replies; runtime integration is intentionally separate. */
class GenAiShiori(
    private val base: ShioriEngine,
    private val generator: GhostTalkGenerator
) : ShioriEngine {
    override suspend fun request(event: ShioriEvent): ShioriReply {
        if (event.id != "OnUserInput") return base.request(event)
        val userText = event.references.getOrNull(1) ?: return base.request(event)
        val text = try {
            generator.reply(userText)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return base.request(event)
        }
        return ShioriReply(200, "\\0\\s[0]${SakuraScriptText.escape(text)}\\e")
    }
}
