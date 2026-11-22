package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.engine.ShioriReply
import com.cattailsw.nanidroid.ghost.BundledGhost
import java.util.Locale

@Suppress("UNUSED_PARAMETER")
internal fun nanoGhostSpike(ghost: BundledGhost, locale: () -> Locale): (suspend () -> ShioriReply)? = null
