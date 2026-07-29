package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.SScriptRunner

/** UI interactions emitted by a Sakura Script independently of an Activity. */
interface ScriptInteractionCallback {
    fun showUserInputBox(id: String)

    fun showUserSelection(labels: Array<String>, ids: Array<String>)
}

/** Lifecycle boundary between the script runner and whichever UI hosts it. */
interface ScriptInteractionGateway {
    fun attach(callback: ScriptInteractionCallback)

    fun detach()
}

/**
 * Gradle-only adapter that preserves [SScriptRunner.UICallback]'s historical
 * attach/null-detach semantics while removing that Android-era type from a
 * future Activity or Compose host.
 */
class SScriptRunnerInteractionGateway(
    private val runner: SScriptRunner,
) : ScriptInteractionGateway {
    private var callback: ScriptInteractionCallback? = null

    private val runnerCallback = object : SScriptRunner.UICallback {
        override fun showUserInputBox(id: String) {
            callback?.showUserInputBox(id)
        }

        override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) {
            callback?.showUserSelection(textlabel, ids)
        }
    }

    override fun attach(callback: ScriptInteractionCallback) {
        this.callback = callback
        runner.setUICallback(runnerCallback)
    }

    override fun detach() {
        callback = null
        runner.setUICallback(null)
    }
}
