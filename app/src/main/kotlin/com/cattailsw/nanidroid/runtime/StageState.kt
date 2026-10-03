package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.ghost.GhostDescriptor
import java.io.File
import com.cattailsw.nanidroid.ghost.ShellCatalog

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
    ) : StageState
    data class Error(val message: String) : StageState
    data object Finished : StageState
}

data class SwitchPrompt(val directoryId: String, val displayName: String, val readme: String?)
