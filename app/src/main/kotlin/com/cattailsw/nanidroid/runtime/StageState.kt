package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.ghost.GhostDescriptor
import com.cattailsw.nanidroid.ghost.ShellCatalog
import java.io.File

sealed interface StageState {
    data object Loading : StageState
    data class Ready(
        val frame: PlaybackFrame,
        val ghostName: String,
        val sakuraName: String,
        val keroName: String,
        val surfaces: Map<Int, File>,
        val installedGhosts: List<GhostDescriptor> = emptyList(),
        val switchPrompt: SwitchPrompt? = null,
        val activationError: String? = null,
        val shell: ShellCatalog? = null,
        val choices: Map<Int, List<PresentedChoice>> = emptyMap(),
        val input: PresentedInput? = null,
        val dialogueToken: DialogueToken? = null,
        val hostUiRequest: HostUiRequest? = null,
        val genAiSupported: Boolean = false,
        val genAiEnabled: Boolean = false,
        val talkAvailable: Boolean = false,
        val talkInput: PresentedInput? = null
    ) : StageState
    data class Error(val message: String) : StageState
    data object Finished : StageState
}

data class SwitchPrompt(val directoryId: String, val displayName: String, val readme: String?)
