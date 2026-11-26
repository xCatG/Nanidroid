package com.cattailsw.nanidroid

import android.content.Context
import com.cattailsw.nanidroid.engine.ShioriReply
import com.cattailsw.nanidroid.ghost.BundledGhost
import java.util.Locale

@Suppress("UNUSED_PARAMETER")
internal fun nanoGhostSpike(ghost: BundledGhost, locale: () -> Locale): (suspend () -> ShioriReply)? = null

@Suppress("UNUSED_PARAMETER")
internal fun ghostHelpClassifier(context: Context): (suspend (String) -> String?)? = null

internal fun closeSharedNanoFastClient() = Unit
