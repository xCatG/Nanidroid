package com.cattailsw.nanidroid.runtime.dialogue

enum class ActionOrigin { USER, SAKURA_SCRIPT, RECOVERY }

enum class GuardedAction {
    SWITCH_GHOST,
    MINIMIZE,
    EXIT,
    IMPORT_INSTALL,
    UNINSTALL,
    KEEP_WAITING,
    STOP_OPERATION,
}

class GhostActionGuard(private val runtimeMode: GhostRuntimeMode) {
    fun allows(action: GuardedAction, origin: ActionOrigin): Boolean = when (origin) {
        ActionOrigin.USER -> !runtimeMode.passive
        ActionOrigin.SAKURA_SCRIPT -> true
        ActionOrigin.RECOVERY -> action in setOf(GuardedAction.KEEP_WAITING, GuardedAction.STOP_OPERATION)
    }
}
