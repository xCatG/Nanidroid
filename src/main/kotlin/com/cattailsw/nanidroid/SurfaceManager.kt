package com.cattailsw.nanidroid

import android.content.res.Resources
import android.graphics.Rect
import android.graphics.drawable.Drawable
import com.cattailsw.nanidroid.surface.ParsedSurfaceEntry

/**
 * Kotlin domain catalog for the shell surfaces installed for one ghost.
 *
 * It deliberately owns catalog and speaker-default selection only. Parsing a
 * shell and composing Android drawables remain separate concerns, so a future
 * Compose renderer can consume surface selection without taking ownership of
 * the legacy parser or ImageView implementation.
 */
class SurfaceManager(@Suppress("UNUSED_PARAMETER") ghostid: String) {
    @JvmField
    var ghostId: String? = null

    private val surfaces = linkedMapOf<String, ShellSurface>()
    private val parsedEntries = linkedMapOf<String, List<ParsedSurfaceEntry>>()

    fun addSurface(id: String, surface: ShellSurface): Int {
        surfaces[id] = surface
        parsedEntries.remove(id)
        return surfaces.size
    }

    fun addParsedSurface(
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

    fun getSurfaceDrawable(id: String, resources: Resources): Drawable? =
        try {
            surfaces[id]?.getSurfaceDrawable(resources)
        } catch (_: Exception) {
            null
        }

    fun getSurfaceRect(id: String, resources: Resources): Rect? =
        try {
            surfaces[id]?.getSurfaceDim()
        } catch (_: Exception) {
            null
        }

    fun dumpSurfaces(): String = buildString {
        surfaces.keys.sorted().forEach { id -> append(surfaces.getValue(id).dumpSurfaceStat()) }
    }

    private companion object {
        const val SAKURA_DEFAULT_ID = "0"
        const val KERO_DEFAULT_ID = "10"
        val nullSurface = ShellSurface()
    }
}
