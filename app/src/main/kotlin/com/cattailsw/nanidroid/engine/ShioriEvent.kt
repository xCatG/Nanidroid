package com.cattailsw.nanidroid.engine

enum class ShioriMethod { GET, NOTIFY }

data class ShioriEvent(
    val id: String,
    val references: List<String> = emptyList(),
    val method: ShioriMethod = ShioriMethod.GET,
)
