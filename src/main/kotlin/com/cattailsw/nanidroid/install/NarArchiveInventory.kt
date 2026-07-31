package com.cattailsw.nanidroid.install

import java.util.ArrayList
import java.util.Collections

/** Immutable, normalized view of every supplied central-directory entry. */
class NarArchiveInventory internal constructor(
    entries: List<Entry>,
    private val wrapperDirectory: String?,
    private val descriptorOrdinal: Int,
    private val declaredTotalSize: Long,
) {
    // Kotlin's toList() is not a Java immutability guarantee: callers can
    // still receive a mutable ArrayList through getEntries(). Keep the same
    // unmodifiable defensive copy the Java model exposed.
    private val entries: List<Entry> = Collections.unmodifiableList(ArrayList(entries))

    fun getEntries(): List<Entry> = entries
    fun getWrapperDirectory(): String? = wrapperDirectory
    fun getDescriptorOrdinal(): Int = descriptorOrdinal
    fun getDeclaredTotalSize(): Long = declaredTotalSize

    /** Central identity plus safe normalized output mapping for one entry. */
    class Entry internal constructor(
        private val ordinal: Int,
        private val rawName: String,
        private val normalizedArchivePath: String,
        private val relativePath: String?,
        private val directory: Boolean,
        private val crc: Long,
        private val method: Int,
        private val declaredSize: Long,
        private val compressedSize: Long,
    ) {
        fun getOrdinal(): Int = ordinal
        fun getRawName(): String = rawName
        fun getNormalizedArchivePath(): String = normalizedArchivePath
        fun getRelativePath(): String? = relativePath
        fun isInstallEntry(): Boolean = relativePath != null
        fun isDirectory(): Boolean = directory
        fun getCrc(): Long = crc
        fun getMethod(): Int = method
        fun getDeclaredSize(): Long = declaredSize
        fun getCompressedSize(): Long = compressedSize
    }
}
