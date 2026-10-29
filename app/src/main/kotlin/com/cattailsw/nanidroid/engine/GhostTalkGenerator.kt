package com.cattailsw.nanidroid.engine

/** App-owned seam: generated output is plain text, never executable SakuraScript. */
fun interface GhostTalkGenerator {
    suspend fun reply(userText: String): String
}
