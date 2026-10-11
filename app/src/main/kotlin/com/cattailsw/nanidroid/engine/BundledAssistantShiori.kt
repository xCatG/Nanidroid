package com.cattailsw.nanidroid.engine

import com.cattailsw.nanidroid.ghost.BundledAssistantContent

class BundledAssistantShiori(
    private val delegate: ShioriEngine,
    private val content: BundledAssistantContent,
    private val debugToolsEnabled: Boolean,
) : ShioriEngine {
    override suspend fun request(event: ShioriEvent): ShioriReply {
        if (event.id == "OnFirstBoot" || event.id == "OnBoot") return script(event.id)

        if (event.id == "OnMouseClick") {
            val references = event.references
            if (references.size < 4 || references.take(3).any { it.toIntOrNull() == null }) {
                return ShioriReply(204)
            }
            return when (references[3]) {
                "0" -> script("OnNanidroidHelp")
                "1" -> tools()
                else -> ShioriReply(204)
            }
        }

        val action = ACTIONS[event.id]
        if (action != null) {
            if (event.references.isNotEmpty() ||
                (action == BuiltInAppAction.DebugTools && !debugToolsEnabled)) return ShioriReply(204)
            return ShioriReply(200, appAction = action)
        }

        return when (event.id) {
            "OnNanidroidHelp", "OnNanidroidIntro", "OnNanidroidImportHelp",
            "OnNanidroidInteractionHelp", "OnNanidroidDismiss" ->
                if (event.references.isEmpty()) script(event.id) else ShioriReply(204)
            "OnNanidroidTools" -> if (event.references.isEmpty()) tools() else ShioriReply(204)
            else -> delegate.request(event)
        }
    }

    private fun script(eventId: String) = ShioriReply(200, requireNotNull(content.script(eventId)))

    private fun tools() = script(if (debugToolsEnabled) "OnNanidroidToolsDebug" else "OnNanidroidTools")

    companion object {
        private val ACTIONS = mapOf(
            "OnNanidroidOpenGhosts" to BuiltInAppAction.Ghosts,
            "OnNanidroidOpenAbout" to BuiltInAppAction.About,
            "OnNanidroidOpenDebug" to BuiltInAppAction.DebugTools,
        )
    }
}
