package com.cattailsw.nanidroid.ghost

import java.io.File

data class GhostDescriptor(
    val directoryId: String,
    val displayName: String,
    val sakuraName: String,
    val keroName: String,
    val masterPath: File,
    val surfaces: Map<Int, File>,
    val engineDeclaration: String?,
    val readmePath: File?,
    val sakuraName2: String = sakuraName,
)
