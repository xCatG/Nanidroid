package com.cattailsw.nanidroid.runtime

data class InteractionToken(val sessionId: Long, val dialogueId: Long, val itemKey: Long)

data class DialogueToken(val sessionId: Long, val dialogueId: Long)

data class PresentedChoice(val token: InteractionToken, val label: String, val textOffset: Int)

data class PresentedInput(val token: InteractionToken, val boxId: String)
