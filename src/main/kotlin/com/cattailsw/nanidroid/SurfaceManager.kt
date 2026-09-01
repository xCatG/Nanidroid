package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.surface.ParsedSurfaceEntry

/**
 * Kotlin domain catalog for the shell surfaces installed for one ghost.
 *
 * It owns catalog and speaker-default selection. Parsed surface data crosses
 * from the shell parser to the Compose rendering pipeline through this catalog.
 */
class SurfaceManager(@Suppress("UNUSED_PARAMETER") ghostid: String) {
    private val surfaces = linkedMapOf<String, ShellSurface>()
    private val parsedEntries = linkedMapOf<String, List<ParsedSurfaceEntry>>()

    internal fun addSurface(id: String, surface: ShellSurface): Int {
        surfaces[id] = surface
        parsedEntries.remove(id)
        return surfaces.size
    }

    internal fun addParsedSurface(
        id: String,
        surface: ShellSurface,
        entries: List<ParsedSurfaceEntry>,
    ): Int {
        surfaces[id] = surface
        parsedEntries[id] = entries.toList()
        return surfaces.size
    }

    fun getParsedSurfaceEntries(id: String): List<ParsedSurfaceEntry> =
        parsedEntries[id].orEmpty()

    fun containsSurface(id: String): Boolean = id in surfaces

    fun getTotalSurfaceCount(): Int = surfaces.size

    fun getSurfaceKeys(): Set<String> = surfaces.keys

    /** Returns null for an unknown exact surface id. */
    fun getSurface(id: String): ShellSurface? = surfaces[id]

    fun getSakuraSurface(id: String): ShellSurface =
        surfaces[id] ?: surfaces[SAKURA_DEFAULT_ID] ?: nullSurface

    fun getKeroSurface(id: String): ShellSurface =
        surfaces[id] ?: surfaces[KERO_DEFAULT_ID] ?: nullSurface

    private companion object {
        const val SAKURA_DEFAULT_ID = "0"
        const val KERO_DEFAULT_ID = "10"
        val nullSurface = ShellSurface()
    }
}
