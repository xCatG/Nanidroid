package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.data.GenAiSettingsStore
import com.cattailsw.nanidroid.engine.GenAiAvailability
import com.cattailsw.nanidroid.engine.ManagedGhostTalkGenerator
import com.cattailsw.nanidroid.ghost.BundledGhost

class GenAiRuntimeDependencies(
    val settings: GenAiSettingsStore,
    val availability: GenAiAvailability,
    val createGenerator: (sessionId: Long, ghost: BundledGhost) -> ManagedGhostTalkGenerator
)
