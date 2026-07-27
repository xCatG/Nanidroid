package com.cattailsw.nanidroid.runtime

/** Receives state changes so a View Activity or a Compose screen can render them. */
fun interface InitializationObserver {
    fun onInitializationStateChanged(
        state: NanidroidCoordinator.InitializationState,
        failure: Throwable?,
    )
}

/**
 * Activity-independent owner of the first launch seam.
 *
 * It deliberately owns only observable initialization state and the script UI
 * callback lifecycle. Ghost creation, installation, view setup, and Android
 * scheduling remain in the legacy Activity until their own migrations.
 */
class NanidroidCoordinator(
    private val scriptInteractionGateway: ScriptInteractionGateway,
    private val initializationObserver: InitializationObserver,
) {
    enum class InitializationState {
        IDLE,
        STARTING,
        LOADING_GHOST,
        COMPLETE,
        FAILED,
    }

    var initializationState: InitializationState = InitializationState.IDLE
        private set

    var initializationFailure: Throwable? = null
        private set

    var isUiAttached: Boolean = false
        private set

    fun startInitialization() = transitionTo(InitializationState.STARTING)

    fun reportGhostLoading() = transitionTo(InitializationState.LOADING_GHOST)

    fun completeInitialization() = transitionTo(InitializationState.COMPLETE)

    fun failInitialization(failure: Throwable) {
        if (isTerminal()) return
        initializationFailure = failure
        initializationState = InitializationState.FAILED
        initializationObserver.onInitializationStateChanged(initializationState, failure)
    }

    fun attachUi(callback: ScriptInteractionCallback) {
        scriptInteractionGateway.attach(callback)
        isUiAttached = true
    }

    fun detachUi() {
        if (!isUiAttached) return
        scriptInteractionGateway.detach()
        isUiAttached = false
    }

    private fun transitionTo(nextState: InitializationState) {
        if (isTerminal()) return
        initializationFailure = null
        initializationState = nextState
        initializationObserver.onInitializationStateChanged(initializationState, null)
    }

    private fun isTerminal(): Boolean = initializationState == InitializationState.COMPLETE ||
        initializationState == InitializationState.FAILED
}
