package com.cattailsw.nanidroid

/** Immutable SHIORI wire-protocol version; replaces Apache's HTTP-only type. */
class ShioriProtocolVersion(
    private val protocol: String,
    private val major: Int,
    private val minor: Int,
) {
    override fun toString(): String = "$protocol/$major.$minor"
}
