package com.cattailsw.nanidroid

class ProtocolVersion(
    val protocol: String,
    val major: Int,
    val minor: Int
) {
    override fun toString(): String {
        return "$protocol/$major.$minor"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProtocolVersion) return false
        return protocol == other.protocol && major == other.major && minor == other.minor
    }

    override fun hashCode(): Int {
        var result = protocol.hashCode()
        result = 31 * result + major
        result = 31 * result + minor
        return result
    }
}
