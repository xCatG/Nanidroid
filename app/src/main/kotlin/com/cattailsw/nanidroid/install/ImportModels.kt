package com.cattailsw.nanidroid.install

import com.cattailsw.nanidroid.ghost.GHOST_DESCRIPTOR_LIMIT

data class ImportLimits(
    val archiveBytes: Long = 256L * 1024 * 1024,
    val expandedBytes: Long = 1024L * 1024 * 1024,
    val fileBytes: Long = 128L * 1024 * 1024,
    val ghostDescriptorBytes: Int = GHOST_DESCRIPTOR_LIMIT,
    val entries: Int = 20_000,
)

data class ImportPackage(val directoryId: String, val rootPrefix: String)

sealed interface ImportOutcome {
    data class Installed(val directoryId: String) : ImportOutcome
    data class Refused(val directoryId: String) : ImportOutcome
    data class Failed(val message: String) : ImportOutcome
    data object Cancelled : ImportOutcome
}

internal const val INSTALL_DESCRIPTOR_LIMIT = 64 * 1024
internal const val ARCHIVE_PATH_LIMIT = 1024
internal const val ARCHIVE_COMPONENT_LIMIT = 32
