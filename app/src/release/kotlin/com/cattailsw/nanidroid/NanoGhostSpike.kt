package com.cattailsw.nanidroid

import android.content.Context
import com.cattailsw.nanidroid.engine.HelpClassification
import com.cattailsw.nanidroid.engine.ShioriReply
import com.cattailsw.nanidroid.ghost.BundledGhost
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val RELEASE_NANO_DOWNLOAD_STATE = MutableStateFlow<NanoDownloadState>(NanoDownloadState.Idle)

@Suppress("UNUSED_PARAMETER")
internal fun nanoGhostSpike(ghost: BundledGhost, locale: () -> Locale): (suspend () -> ShioriReply)? = null

@Suppress("UNUSED_PARAMETER")
internal fun ghostHelpClassifier(context: Context): (suspend (String) -> HelpClassification)? = null

internal fun sharedNanoDownloadState(): StateFlow<NanoDownloadState> = RELEASE_NANO_DOWNLOAD_STATE

internal fun refreshSharedNanoStatus() = Unit

internal fun closeSharedNanoFastClient() = Unit
