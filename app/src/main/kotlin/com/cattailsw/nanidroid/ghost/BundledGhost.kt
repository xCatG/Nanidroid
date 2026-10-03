package com.cattailsw.nanidroid.ghost

import java.io.File

data class BundledGhost(
    val directoryId: String,
    val name: String,
    val sakuraName: String,
    val keroName: String,
    val surfaces: Map<Int, File>,
    val content: String,
    val shellDirectory: File? = null,
    val sakuraName2: String = sakuraName,
)
