package com.cattailsw.nanidroid.install

/** Stable internal failures while minting a staged NAR snapshot. */
internal enum class NarStagedSourceCopyError {
    SOURCE_INVALID,
    STAGING_ROOT_INVALID,
    STAGING_NAME_INVALID,
    STAGING_NAME_COLLISION_LIMIT,
    STAGING_CREATE_FAILED,
    SOURCE_OPEN_FAILED,
    STAGING_OPEN_FAILED,
    SOURCE_READ_FAILED,
    CANCELLED,
    ARCHIVE_SIZE_LIMIT,
    STAGING_WRITE_FAILED,
    STAGING_SYNC_FAILED,
    STAGING_CLOSE_FAILED,
    SOURCE_CLOSE_FAILED,
    STAGING_DELETE_FAILED
}
