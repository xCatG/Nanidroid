package com.cattailsw.nanidroid.install

import java.util.Collections

/**
 * Pure immutable adapter from native staged-tree facts to the reviewed policy.
 *
 * Fingerprint v1 authenticates target, storage-root identity, logical paths
 * and copied-byte digests, but intentionally excludes blob ordinals. The
 * immutable manifest and ordinal inventory must remain inseparable in the
 * later capability. D9b3 must treat process-death orphans as discard-only.
 */
internal object NarStagedTreeInventory {
    private const val MAX_ENTRIES = 10_000
    private const val DIGEST_BYTES = 32

    enum class Error { NATIVE, POLICY }

    class Description(
        internal val storageDevice: Long,
        internal val storageInode: Long,
        paths: Array<String>?,
        types: IntArray?,
        sizes: LongArray?,
        ordinals: IntArray?,
        digests: ByteArray?,
    ) {
        internal val paths = paths?.clone()
        internal val types = types?.clone()
        internal val sizes = sizes?.clone()
        internal val ordinals = ordinals?.clone()
        internal val digests = digests?.clone()
    }

    class Entry internal constructor(
        // This is deliberately nullable at the raw-facts boundary.  The
        // overlay validator—not this value object—maps malformed supplied
        // facts to MALFORMED_BASELINE, matching the Java implementation.
        private val pathValue: String?,
        private val typeValue: NarGhostTreePolicy.Type,
        private val sizeValue: Long,
        private val blobOrdinalValue: Int,
        sha256: ByteArray?,
    ) {
        private val sha256Value = sha256?.clone()

        fun path(): String? = pathValue

        fun type(): NarGhostTreePolicy.Type = typeValue

        fun size(): Long = sizeValue

        fun blobOrdinal(): Int = blobOrdinalValue

        fun sha256(): ByteArray? = sha256Value?.clone()
    }

    class Result private constructor(
        private val manifestValue: NarGhostTreePolicy.Manifest?,
        private val entriesValue: List<Entry>,
        private val errorValue: Error?,
        private val detailValue: String,
    ) {
        fun isSuccess(): Boolean = manifestValue != null

        fun manifest(): NarGhostTreePolicy.Manifest? = manifestValue

        fun entries(): List<Entry> = entriesValue

        fun error(): Error? = errorValue

        fun detail(): String = detailValue

        companion object {
            fun success(
                manifest: NarGhostTreePolicy.Manifest,
                entries: List<Entry>,
            ): Result = Result(
                manifest,
                Collections.unmodifiableList(ArrayList(entries)),
                null,
                "",
            )

            fun failure(error: Error, detail: String): Result =
                Result(null, Collections.emptyList(), error, detail)
        }
    }

    fun absent(target: String?, storageDevice: Long, storageInode: Long): Result =
        build(target, storageDevice, storageInode, NarGhostTreePolicy.State.ABSENT, null)

    fun present(target: String?, description: Description?): Result {
        if (description == null) return Result.failure(Error.NATIVE, "description")
        return build(
            target,
            description.storageDevice,
            description.storageInode,
            NarGhostTreePolicy.State.PRESENT,
            description,
        )
    }

    private fun build(
        target: String?,
        storageDevice: Long,
        storageInode: Long,
        state: NarGhostTreePolicy.State,
        description: Description?,
    ): Result = try {
        val prepared = if (state == NarGhostTreePolicy.State.ABSENT) {
            Prepared.absent()
        } else {
            prepare(description)
        }
        if (prepared == null) return Result.failure(Error.NATIVE, "inventory")
        val policy = NarGhostTreePolicy.build(
            target,
            identity(storageDevice, storageInode),
            state,
            prepared.policyEntries,
        )
        if (!policy.isSuccess()) {
            return Result.failure(Error.POLICY, policy.error!!.name)
        }
        val entries = ArrayList<Entry>()
        for (entry in policy.manifest!!.entries) {
            val item = prepared.byPath[entry.path]
                ?: return Result.failure(Error.NATIVE, "inventory mapping")
            entries.add(item)
        }
        Result.success(policy.manifest, entries)
    } catch (_: RuntimeException) {
        Result.failure(Error.NATIVE, "inventory")
    }

    private class Prepared(
        val policyEntries: List<NarGhostTreePolicy.InputEntry>,
        val byPath: Map<String, Entry>,
    ) {
        companion object {
            fun absent(): Prepared = Prepared(Collections.emptyList(), Collections.emptyMap())
        }
    }

    private fun prepare(value: Description?): Prepared? {
        if (value == null || value.paths == null || value.types == null ||
            value.sizes == null || value.ordinals == null || value.digests == null
        ) return null
        val paths = value.paths
        val types = value.types
        val sizes = value.sizes
        val ordinals = value.ordinals
        val digests = value.digests
        val count = paths.size
        if (count > MAX_ENTRIES || types.size != count || sizes.size != count ||
            ordinals.size != count || digests.size != count * DIGEST_BYTES
        ) return null

        val policy = ArrayList<NarGhostTreePolicy.InputEntry>(count)
        val inventory = HashMap<String, Entry>()
        val seen = BooleanArray(count)
        var files = 0
        for (index in 0 until count) {
            val path = paths[index]
            val normalized = NarRelativePathPolicy.normalize(path)
            if (!normalized.isSuccess()) return null
            when {
                types[index] == 2 -> {
                    if (sizes[index] != 0L || ordinals[index] != -1 || !zeroDigest(digests, index)) {
                        return null
                    }
                    val entry = Entry(
                        normalized.normalized!!,
                        NarGhostTreePolicy.Type.DIRECTORY,
                        0,
                        -1,
                        null,
                    )
                    inventory[entry.path()!!] = entry
                    policy.add(NarGhostTreePolicy.InputEntry.directory(path))
                }

                types[index] == 1 && sizes[index] >= 0 && ordinals[index] >= 0 &&
                    ordinals[index] < count && !seen[ordinals[index]] -> {
                    seen[ordinals[index]] = true
                    files++
                    val digest = digests.copyOfRange(
                        index * DIGEST_BYTES,
                        (index + 1) * DIGEST_BYTES,
                    )
                    val entry = Entry(
                        normalized.normalized!!,
                        NarGhostTreePolicy.Type.FILE,
                        sizes[index],
                        ordinals[index],
                        digest,
                    )
                    inventory[entry.path()!!] = entry
                    policy.add(NarGhostTreePolicy.InputEntry.file(path, sizes[index], digest))
                }

                else -> return null
            }
        }
        for (ordinal in 0 until files) {
            if (!seen[ordinal]) return null
        }
        for (ordinal in files until seen.size) {
            if (seen[ordinal]) return null
        }
        return Prepared(policy, inventory)
    }

    private fun zeroDigest(values: ByteArray, index: Int): Boolean {
        val start = index * DIGEST_BYTES
        for (offset in 0 until DIGEST_BYTES) {
            if (values[start + offset].toInt() != 0) return false
        }
        return true
    }

    private fun identity(device: Long, inode: Long): ByteArray = ByteArray(16).also {
        putLong(it, 0, device)
        putLong(it, 8, inode)
    }

    private fun putLong(target: ByteArray, offset: Int, initialValue: Long) {
        var value = initialValue
        for (index in 7 downTo 0) {
            target[offset + index] = value.toByte()
            value = value ushr 8
        }
    }
}
