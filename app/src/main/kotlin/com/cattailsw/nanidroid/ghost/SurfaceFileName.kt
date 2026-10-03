package com.cattailsw.nanidroid.ghost

/** Matches numbered PNG surface filenames used by both repositories and the composer. */
internal object SurfaceFileName {
    private val pattern = Regex("surface0*([0-9]+)\\.png", RegexOption.IGNORE_CASE)

    fun id(name: String): Int? = pattern.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
}
