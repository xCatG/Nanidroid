package com.cattailsw.nanidroid.engine

class UnsupportedShiori : ShioriEngine {
    override suspend fun request(event: ShioriEvent): ShioriReply = when (event.id) {
        "OnBoot", "OnFirstBoot", "OnGhostChanged" ->
            ShioriReply(200, "\\0\\s0This ghost uses a SHIORI Nanidroid does not support yet.\\e")
        "OnGhostChanging" ->
            ShioriReply(200, "\\0\\s0Changing ghost to ${event.references.firstOrNull().orEmpty()}\\e")
        "OnClose" -> ShioriReply(200, "\\0\\s0Thank you.\\e")
        else -> ShioriReply(204)
    }
}
