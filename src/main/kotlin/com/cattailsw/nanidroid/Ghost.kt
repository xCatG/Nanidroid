package com.cattailsw.nanidroid

import java.io.File

/** Immutable prepared/display data for one installed ghost generation. */
internal class Ghost internal constructor(prepared: PreparedGhost) {
    val id: String = prepared.id
    val canonicalRoot: File = prepared.canonicalRoot
    val name: String? = prepared.name
    val shellName: String? = prepared.shellName
    val crafterName: String? = prepared.crafterName
    val sakuraName: String? = prepared.sakuraName
    val keroName: String? = prepared.keroName
    val surfaces: SurfaceCatalog = prepared.surfaces
    val ghostDescriptor: Map<String, String> = prepared.ghostDescriptor
    val shellDescriptor: Map<String, String>? = prepared.shellDescriptor
    val engine: GhostEngine = prepared.engine
    val username: String = "User"

    init {
        LegacyPlatform.debug(TAG, "gdname=$id")
    }

    private companion object {
        const val TAG = "Ghost"
    }
}
