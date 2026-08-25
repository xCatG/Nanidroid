package com.cattailsw.nanidroid

import android.content.Context
import java.io.File

internal class GhostRuntime(
    context: Context?,
    private val sessionCoordinator: GhostSessionCoordinator = GhostSessionCoordinator(),
) {
    val runner = SScriptRunner(context, sessionCoordinator)

    fun beginGhostConstruction(
        ghostId: String,
        ghostRoot: File,
    ): GhostConstructionReservation = sessionCoordinator.beginConstruction(ghostId, ghostRoot)

    fun reuseActiveGhost(
        ghostId: String,
        ghostRoot: File,
    ): ReservedGhost? = sessionCoordinator.reuseActive(ghostId, ghostRoot)
}
