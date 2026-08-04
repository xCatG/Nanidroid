package com.cattailsw.nanidroid.install

/** Pure structural policy over caller-supplied central-directory records. */
class NarArchiveInventoryValidator {
    fun validate(centralEntries: List<out CentralEntry?>?): NarArchiveInventoryResult = try {
        NarArchiveInventoryResult.success(inspect(centralEntries))
    } catch (rejected: Rejected) {
        NarArchiveInventoryResult.failure(rejected.error, rejected.message!!)
    }

    @Throws(Rejected::class)
    private fun inspect(centralEntries: List<out CentralEntry?>?): NarArchiveInventory {
        if (centralEntries == null) {
            reject(NarInstallError.INVALID_ENTRY_METADATA, "null inventory")
        }
        if (centralEntries.size > MAX_ENTRIES) {
            reject(NarInstallError.ENTRY_COUNT_LIMIT, "too many entries")
        }

        val items = ArrayList<Item>()
        val entriesByKey = HashMap<String, Item>()
        val directorySpellings = HashMap<String, String>()
        val implicitDirectories = HashSet<String>()
        for (index in centralEntries.indices) {
            val source = snapshot(centralEntries[index])
            if (source.ordinal != index || source.declaredSize < -1 ||
                source.compressedSize < -1 || source.crc < -1 ||
                source.crc > 0xffffffffL ||
                (source.method != -1 && source.method != 0 && source.method != 8)
            ) {
                reject(NarInstallError.INVALID_ENTRY_METADATA, "central metadata")
            }
            val path = normalize(source)
            val item = Item(source, path)
            val previous = entriesByKey[path.key]
            if (previous != null) {
                if (previous.source.directory != source.directory) {
                    reject(NarInstallError.FILE_DIRECTORY_COLLISION, path.normalized)
                }
                reject(
                    if (previous.path.original == path.original) NarInstallError.DUPLICATE_ENTRY
                    else NarInstallError.NORMALIZED_COLLISION,
                    path.normalized,
                )
            }

            var ancestor = path.key
            var slash = ancestor.lastIndexOf('/')
            while (slash >= 0) {
                ancestor = ancestor.substring(0, slash)
                val parent = entriesByKey[ancestor]
                if (parent != null && !parent.source.directory) {
                    reject(NarInstallError.FILE_DIRECTORY_COLLISION, path.normalized)
                }
                implicitDirectories.add(ancestor)
                slash = ancestor.lastIndexOf('/')
            }
            if (!source.directory && implicitDirectories.contains(path.key)) {
                reject(NarInstallError.FILE_DIRECTORY_COLLISION, path.normalized)
            }
            recordDirectorySpellings(item, directorySpellings)
            entriesByKey[path.key] = item
            items.add(item)
        }

        val layout = layout(items)
        if (layout.descriptor.source.declaredSize > MAX_DESCRIPTOR_SIZE) {
            reject(NarInstallError.INSTALL_DESCRIPTOR_LIMIT, layout.descriptor.path.normalized)
        }
        var totalSize = 0L
        var totalCompressed = 0L
        var totalSizeKnown = true
        var totalRatioKnown = true
        for (item in items) {
            val size = item.source.declaredSize
            val compressed = item.source.compressedSize
            if (size > MAX_ENTRY_SIZE) {
                reject(NarInstallError.DECLARED_ENTRY_SIZE_LIMIT, item.path.normalized)
            }
            if (size >= 0) {
                if (size > MAX_TOTAL_SIZE - totalSize) {
                    reject(NarInstallError.DECLARED_TOTAL_SIZE_LIMIT, item.path.normalized)
                }
                totalSize += size
            }
            if (size < 0 || compressed < 0) {
                if (size < 0) totalSizeKnown = false
                totalRatioKnown = false
            } else if (ratioTooHigh(size, compressed)) {
                reject(NarInstallError.DECLARED_RATIO_LIMIT, item.path.normalized)
            } else if (compressed > Long.MAX_VALUE - totalCompressed) {
                totalRatioKnown = false
            } else {
                totalCompressed += compressed
            }
        }
        if (totalRatioKnown && ratioTooHigh(totalSize, totalCompressed)) {
            reject(NarInstallError.DECLARED_RATIO_LIMIT, "archive total")
        }

        val output = ArrayList<NarArchiveInventory.Entry>()
        for (item in items) {
            var relative: String? = item.path.normalized
            if (layout.wrapper != null) {
                relative = if (relative == layout.wrapper) null
                else relative!!.substring(layout.wrapper.length + 1)
            }
            val source = item.source
            output.add(
                NarArchiveInventory.Entry(
                    source.ordinal,
                    // normalize() rejects null names before an Item exists.
                    source.rawName!!,
                    item.path.normalized,
                    relative,
                    source.directory,
                    source.crc,
                    source.method,
                    source.declaredSize,
                    source.compressedSize,
                ),
            )
        }
        return NarArchiveInventory(
            output,
            layout.wrapper,
            layout.descriptor.source.ordinal,
            if (totalSizeKnown) totalSize else -1,
        )
    }

    @Throws(Rejected::class)
    private fun snapshot(entry: CentralEntry?): Snapshot {
        if (entry == null) {
            reject(NarInstallError.INVALID_ENTRY_METADATA, "null entry")
        }
        return try {
            val rawName = entry.getRawName()
            Snapshot(
                entry.getOrdinal(),
                rawName,
                // Java's ZIP reader does not mark Windows-style `directory\\`
                // entries as directories. Treat that spelling as structural
                // metadata, then validate the normalized path below.
                entry.isDirectory() || rawName?.endsWith("\\") == true,
                entry.getCrc(),
                entry.getMethod(),
                entry.getDeclaredSize(),
                entry.getCompressedSize(),
            )
        } catch (_: RuntimeException) {
            reject(NarInstallError.INVALID_ENTRY_METADATA, "central getter")
        }
    }

    @Throws(Rejected::class)
    private fun normalize(entry: Snapshot): Path {
        val raw = entry.rawName
        if (raw == null) {
            reject(NarInstallError.INVALID_PATH, "null name")
        }
        if (raw.length > MAX_RAW_NAME_CHARS) {
            reject(NarInstallError.RAW_NAME_LENGTH_LIMIT, "raw name")
        }
        val archiveName = raw.replace('\\', '/')
        if (entry.directory != archiveName.endsWith("/")) {
            reject(NarInstallError.INVALID_ENTRY_METADATA, raw)
        }
        val original = if (entry.directory) archiveName.substring(0, archiveName.length - 1) else archiveName
        val path = NarRelativePathPolicy.normalize(original)
        if (!path.isSuccess()) {
            when (path.error) {
                NarRelativePathPolicy.Error.PATH_DEPTH_LIMIT ->
                    reject(NarInstallError.PATH_DEPTH_LIMIT, raw)
                NarRelativePathPolicy.Error.PATH_LENGTH_LIMIT ->
                    reject(NarInstallError.PATH_LENGTH_LIMIT, raw)
                NarRelativePathPolicy.Error.COMPONENT_LENGTH_LIMIT ->
                    reject(NarInstallError.COMPONENT_LENGTH_LIMIT, raw)
                else -> reject(NarInstallError.INVALID_PATH, raw)
            }
        }
        return Path(original, path.normalized!!, path.key!!)
    }

    @Throws(Rejected::class)
    private fun recordDirectorySpellings(item: Item, spellings: MutableMap<String, String>) {
        val raw = item.path.original.split("/")
        val normalized = item.path.normalized.split("/")
        val count = if (item.source.directory) raw.size else raw.size - 1
        var rawPrefix = ""
        var normalizedPrefix = ""
        for (index in 0 until count) {
            rawPrefix += (if (index == 0) "" else "/") + raw[index]
            normalizedPrefix += (if (index == 0) "" else "/") + normalized[index]
            val key = collisionKey(normalizedPrefix)
            val previous = spellings[key]
            if (previous != null && previous != rawPrefix) {
                reject(NarInstallError.NORMALIZED_COLLISION, normalizedPrefix)
            }
            spellings[key] = rawPrefix
        }
    }

    @Throws(Rejected::class)
    private fun layout(items: List<Item>): Layout {
        var rootDescriptor: Item? = null
        val wrapperDescriptors = ArrayList<Item>()
        var deep = false
        for (item in items) {
            val components = item.path.normalized.split("/")
            val descriptor = !item.source.directory && components.last() == "install.txt"
            if (!descriptor) continue
            if (components.size == 1 && item.path.normalized == "install.txt") {
                rootDescriptor = item
            } else if (components.size == 2) {
                wrapperDescriptors.add(item)
            } else {
                deep = true
            }
        }
        if (rootDescriptor != null) {
            return Layout(rootDescriptor, null)
        }
        if (wrapperDescriptors.isEmpty()) {
            if (deep) {
                reject(NarInstallError.INVALID_LAYOUT, "deep install.txt")
            }
            reject(NarInstallError.MISSING_INSTALL_DESCRIPTOR, "no supported install.txt")
        }
        if (wrapperDescriptors.size > 1) {
            reject(NarInstallError.AMBIGUOUS_LAYOUT, "multiple install.txt")
        }
        val descriptor = wrapperDescriptors[0]
        val slash = descriptor.path.normalized.indexOf('/')
        val wrapper = if (slash < 0) null else descriptor.path.normalized.substring(0, slash)
        if (wrapper != null) {
            for (item in items) {
                if (item.path.normalized != wrapper && !item.path.normalized.startsWith("$wrapper/")) {
                    reject(NarInstallError.MIXED_LAYOUT, item.path.normalized)
                }
            }
        }
        return Layout(descriptor, wrapper)
    }

    private fun ratioTooHigh(size: Long, compressed: Long): Boolean =
        size > 0 && (compressed <= 0 ||
            (compressed <= Long.MAX_VALUE / MAX_RATIO && size > compressed * MAX_RATIO))

    private fun collisionKey(value: String): String = NarRelativePathPolicy.collisionKey(value)

    @Throws(Rejected::class)
    private fun reject(error: NarInstallError, detail: String): Nothing = throw Rejected(error, detail)

    interface CentralEntry {
        fun getOrdinal(): Int
        fun getRawName(): String?
        fun isDirectory(): Boolean
        fun getCrc(): Long
        fun getMethod(): Int
        fun getDeclaredSize(): Long
        fun getCompressedSize(): Long
    }

    private class Item(val source: Snapshot, val path: Path)

    private class Snapshot(
        val ordinal: Int,
        val rawName: String?,
        val directory: Boolean,
        val crc: Long,
        val method: Int,
        val declaredSize: Long,
        val compressedSize: Long,
    )

    private class Path(val original: String, val normalized: String, val key: String)

    private class Layout(val descriptor: Item, val wrapper: String?)

    private class Rejected(val error: NarInstallError, detail: String) : Exception(detail)

    private companion object {
        const val MAX_ENTRIES = 10_000
        const val MAX_RAW_NAME_CHARS = 4096
        const val MAX_DESCRIPTOR_SIZE = 64L * 1024L
        const val MAX_ENTRY_SIZE = 128L * 1024L * 1024L
        const val MAX_TOTAL_SIZE = 512L * 1024L * 1024L
        const val MAX_RATIO = 1000L
    }
}
