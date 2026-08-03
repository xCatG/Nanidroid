package com.cattailsw.nanidroid.runtime.dialogue

enum class ActionOrigin { USER, SAKURA_SCRIPT, RECOVERY }

enum class GuardedAction { SWITCH_GHOST, MINIMIZE, EXIT, UPDATE, IMPORT_INSTALL, UNINSTALL }

class GhostActionGuard(private val runtimeMode: GhostRuntimeMode) {
    fun allows(action: GuardedAction, origin: ActionOrigin): Boolean =
        origin != ActionOrigin.USER || !runtimeMode.passive
}
