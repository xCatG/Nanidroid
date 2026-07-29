package com.cattailsw.nanidroid.install

import java.io.File
import java.util.Collections

/** Immutable identity-bound diagnostic plan that does not authorize extraction. */
class NarInstallPlan internal constructor(
    val sourceLength: Long,
    sourceSha256: ByteArray,
    inventory: NarArchiveInventory,
    val descriptor: NarInstallDescriptor,
    val installRoot: File,
    val targetDirectory: File,
) {
    private val sourceSha256 = sourceSha256.clone()
    val entries: List<Entry> = Collections.unmodifiableList(
        inventory.getEntries().map { Entry(it) },
    )
    val wrapperDirectory = inventory.getWrapperDirectory()

    fun getSourceSha256(): ByteArray = sourceSha256.clone()

    /** Exact central identity plus normalized diagnostic mapping. */
    class Entry internal constructor(entry: NarArchiveInventory.Entry) {
        val ordinal = entry.getOrdinal()
        val rawName = entry.getRawName()
        val normalizedArchivePath = entry.getNormalizedArchivePath()
        val relativePath = entry.getRelativePath()
        val isDirectory = entry.isDirectory()
        val crc = entry.getCrc()
        val method = entry.getMethod()
        val declaredSize = entry.getDeclaredSize()
        val compressedSize = entry.getCompressedSize()

        val isInstallEntry: Boolean get() = relativePath != null

        fun sameCentral(other: NarArchiveInventoryValidator.CentralEntry): Boolean =
            ordinal == other.getOrdinal() && rawName == other.getRawName() &&
                isDirectory == other.isDirectory() && crc == other.getCrc() &&
                method == other.getMethod() && declaredSize == other.getDeclaredSize() &&
                compressedSize == other.getCompressedSize()
    }
}
