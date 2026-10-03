package com.cattailsw.nanidroid.engine

interface ShioriEngine {
    suspend fun request(event: ShioriEvent): ShioriReply
}
