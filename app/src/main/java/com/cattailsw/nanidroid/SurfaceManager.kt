package com.cattailsw.nanidroid

import android.content.res.Resources
import android.graphics.Rect
import android.graphics.drawable.Drawable
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap

class SurfaceManager(var ghostId: String?) {

    var surfaces: MutableMap<String, ShellSurface> = HashMap()
    var sakuraAliasTable: Map<String, Array<String>>? = null
    var keroAliasTable: Map<String, Array<String>>? = null

    companion object {
        private val nulSurface = ShellSurface()
    }

    fun addSurface(id: String, s: ShellSurface): Int {
        surfaces[id] = s
        return surfaces.size
    }

    fun containsSurface(id: String): Boolean {
        return surfaces.containsKey(id)
    }

    fun getTotalSurfaceCount(): Int {
        return surfaces.size
    }

    fun getSurfaceKeys(): Set<String> {
        return surfaces.keys
    }

    fun getSurface(id: String): ShellSurface? {
        return if (surfaces.containsKey(id)) surfaces[id] else null
    }

    fun getSakuraSurface(id: String): ShellSurface {
        return when {
            surfaces.containsKey(id) -> surfaces[id]!!
            surfaces.containsKey("0") -> surfaces["0"]!!
            else -> nulSurface
        }
    }

    fun getKeroSurface(id: String): ShellSurface {
        return when {
            surfaces.containsKey(id) -> surfaces[id]!!
            surfaces.containsKey("10") -> surfaces["10"]!!
            else -> nulSurface
        }
    }

    fun getSurfaceDrawable(id: String, res: Resources): Drawable? {
        return try {
            surfaces[id]?.getSurfaceDrawable(res)
        } catch (e: Exception) {
            null
        }
    }

    fun getSurfaceRect(id: String, res: Resources): Rect? {
        return try {
            surfaces[id]?.getSurfaceDim()
        } catch (e: Exception) {
            null
        }
    }

    fun dumpSurfaces(): String {
        val sb = StringBuilder()
        val kz = ArrayList(surfaces.keys)
        Collections.sort(kz)
        for (k in kz) {
            sb.append(surfaces[k]?.dumpSurfaceStat() ?: "")
        }
        return sb.toString()
    }
}
