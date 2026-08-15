package com.cattailsw.nanidroid.install

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Collections

/**
 * Pure immutable baseline policy for a live ghost tree.
 *
 * Hard links are deliberately flattened: a future filesystem walker copies
 * and hashes each relative file path independently before calling this policy.
 */
internal object NarGhostTreePolicy {
    private val DOMAIN = "Nanidroid/GhostTreeFingerprint".toByteArray(StandardCharsets.UTF_8)
    private const val FINGERPRINT_VERSION = 1
    private const val MAX_ENTRIES = 10_000
    private const val MAX_FILE_BYTES = 128L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L

    fun build(
        validatedTargetId: String?,
        storageRootIdentity: ByteArray?,
        state: State?,
        suppliedEntries: List<InputEntry>?,
    ): Result = try {
        inspect(validatedTargetId, storageRootIdentity, state, suppliedEntries)
    } catch (_: RuntimeException) {
        Result.failure(Error.ENTRY_INVALID, "input")
    }

    private fun inspect(
        validatedTargetId: String?,
        storageRootIdentity: ByteArray?,
        state: State?,
        suppliedEntries: List<InputEntry>?,
    ): Result {
        val target = NarRelativePathPolicy.normalize(validatedTargetId)
        if (!target.isSuccess() || target.normalized!!.indexOf('/') >= 0 ||
            target.normalized != validatedTargetId
        ) {
            return Result.failure(Error.TARGET_ID_INVALID, "target id")
        }
        if (storageRootIdentity == null) {
            return Result.failure(Error.STORAGE_ROOT_ID_INVALID, "storage root identity")
        }
        if (state == null || suppliedEntries == null) {
            return Result.failure(Error.STATE_INVALID, "baseline state")
        }

        val snapshot = ArrayList<InputEntry?>(MAX_ENTRIES)
        val iterator = suppliedEntries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (snapshot.size == MAX_ENTRIES) {
                return Result.failure(Error.ENTRY_COUNT_LIMIT, "entry count")
            }
            snapshot.add(entry)
        }
        if (state == State.ABSENT && snapshot.isNotEmpty()) {
            return Result.failure(Error.STATE_INVALID, "absent baseline has entries")
        }

        val entries = ArrayList<Entry>()
        val byKey = HashMap<String, Entry>()
        var total = 0L
        for (supplied in snapshot) {
            if (supplied == null || supplied.type == null) {
                return Result.failure(Error.ENTRY_INVALID, "null entry")
            }
            val path = NarRelativePathPolicy.normalize(supplied.path)
            if (!path.isSuccess()) {
                return Result.failure(pathError(path.error!!), supplied.path)
            }
            if (supplied.type == Type.FILE) {
                if (supplied.length < 0 || supplied.length > MAX_FILE_BYTES) {
                    return Result.failure(Error.FILE_SIZE_LIMIT, path.normalized!!)
                }
                val contentDigest = supplied.contentDigest
                if (contentDigest == null || contentDigest.size != 32) {
                    return Result.failure(Error.CONTENT_DIGEST_INVALID, path.normalized!!)
                }
                if (total > MAX_TOTAL_BYTES - supplied.length) {
                    return Result.failure(Error.TOTAL_SIZE_LIMIT, path.normalized!!)
                }
                total += supplied.length
            }
            val entry = Entry(path.normalized!!, supplied.type, supplied.length, supplied.contentDigest)
            val previous = byKey.put(path.key!!, entry)
            if (previous != null) {
                return Result.failure(
                    if (previous.type == entry.type) Error.NORMALIZED_COLLISION
                    else Error.FILE_DIRECTORY_COLLISION,
                    path.normalized,
                )
            }
            entries.add(entry)
        }

        entries.sortBy { it.path }
        for (entry in entries) {
            val slash = entry.path.lastIndexOf('/')
            if (slash < 0) continue
            val parent = entry.path.substring(0, slash)
            val owner = byKey[NarRelativePathPolicy.collisionKey(parent)]
                ?: return Result.failure(Error.MISSING_DIRECTORY, parent)
            if (owner.path != parent) {
                return Result.failure(Error.NORMALIZED_COLLISION, parent)
            }
            if (owner.type != Type.DIRECTORY) {
                return Result.failure(Error.FILE_DIRECTORY_COLLISION, parent)
            }
        }

        return try {
            val root = storageRootIdentity.clone()
            val targetId = requireNotNull(validatedTargetId)
            val fingerprint = fingerprint(targetId, root, state, entries)
            Result.success(Manifest(targetId, root, state, entries, fingerprint))
        } catch (_: NoSuchAlgorithmException) {
            Result.failure(Error.FINGERPRINT_FAILED, "SHA-256")
        } catch (_: RuntimeException) {
            Result.failure(Error.FINGERPRINT_FAILED, "fingerprint")
        }
    }

    private fun pathError(error: NarRelativePathPolicy.Error): Error = when (error) {
        NarRelativePathPolicy.Error.PATH_DEPTH_LIMIT -> Error.PATH_DEPTH_LIMIT
        NarRelativePathPolicy.Error.PATH_LENGTH_LIMIT -> Error.PATH_LENGTH_LIMIT
        NarRelativePathPolicy.Error.COMPONENT_LENGTH_LIMIT -> Error.COMPONENT_LENGTH_LIMIT
        else -> Error.INVALID_PATH
    }

    @Throws(NoSuchAlgorithmException::class)
    private fun fingerprint(target: String, root: ByteArray, state: State, entries: List<Entry>): ByteArray {
        val encoder = Encoder(MessageDigest.getInstance("SHA-256"))
        encoder.bytes(DOMAIN)
        encoder.integer(FINGERPRINT_VERSION)
        encoder.bytes(target.toByteArray(StandardCharsets.UTF_8))
        encoder.bytes(root)
        encoder.one(if (state == State.ABSENT) 0 else 1)
        encoder.integer(entries.size)
        for (entry in entries) {
            encoder.bytes(entry.path.toByteArray(StandardCharsets.UTF_8))
            encoder.one(if (entry.type == Type.DIRECTORY) 1 else 2)
            if (entry.type == Type.FILE) {
                encoder.longValue(entry.length)
                encoder.bytes(entry.contentDigest!!)
            }
        }
        return encoder.finish()
    }

    enum class State { ABSENT, PRESENT }
    enum class Type { FILE, DIRECTORY }

    enum class Error {
        TARGET_ID_INVALID,
        STORAGE_ROOT_ID_INVALID,
        STATE_INVALID,
        ENTRY_INVALID,
        INVALID_PATH,
        NORMALIZED_COLLISION,
        FILE_DIRECTORY_COLLISION,
        MISSING_DIRECTORY,
        PATH_DEPTH_LIMIT,
        COMPONENT_LENGTH_LIMIT,
        PATH_LENGTH_LIMIT,
        ENTRY_COUNT_LIMIT,
        FILE_SIZE_LIMIT,
        TOTAL_SIZE_LIMIT,
        CONTENT_DIGEST_INVALID,
        FINGERPRINT_FAILED,
    }

    class InputEntry private constructor(
        val path: String?,
        val type: Type?,
        val length: Long,
        contentDigest: ByteArray?,
    ) {
        private val contentDigestValue = contentDigest?.clone()
        val contentDigest: ByteArray? get() = contentDigestValue?.clone()

        companion object {
            fun directory(path: String?): InputEntry = InputEntry(path, Type.DIRECTORY, 0, null)

            fun file(path: String?, length: Long, digest: ByteArray?): InputEntry =
                InputEntry(path, Type.FILE, length, digest)
        }
    }

    class Entry internal constructor(
        val path: String,
        val type: Type,
        val length: Long,
        contentDigest: ByteArray?,
    ) {
        private val contentDigestValue = contentDigest?.clone()
        val contentDigest: ByteArray? get() = contentDigestValue?.clone()
    }

    class Manifest internal constructor(
        val targetId: String,
        storageRootIdentity: ByteArray,
        val state: State,
        entries: List<Entry>,
        fingerprint: ByteArray,
    ) {
        private val storageRootIdentityValue = storageRootIdentity.clone()
        private val entriesValue = Collections.unmodifiableList(ArrayList(entries))
        private val fingerprintValue = fingerprint.clone()

        val storageRootIdentity: ByteArray get() = storageRootIdentityValue.clone()
        val entries: List<Entry> get() = entriesValue
        val fingerprintVersion: Int get() = FINGERPRINT_VERSION
        val fingerprint: ByteArray get() = fingerprintValue.clone()
    }

    class Result private constructor(
        val manifest: Manifest?,
        val error: Error?,
        val detail: String?,
    ) {
        fun isSuccess(): Boolean = manifest != null

        companion object {
            fun success(manifest: Manifest): Result = Result(manifest, null, "")
            fun failure(error: Error, detail: String?): Result = Result(null, error, detail)
        }
    }

    private class Encoder(private val digest: MessageDigest) {
        fun one(value: Int) {
            digest.update(value.toByte())
        }

        fun integer(value: Int) {
            one(value ushr 24)
            one(value ushr 16)
            one(value ushr 8)
            one(value)
        }

        fun longValue(value: Long) {
            one((value ushr 56).toInt())
            one((value ushr 48).toInt())
            one((value ushr 40).toInt())
            one((value ushr 32).toInt())
            one((value ushr 24).toInt())
            one((value ushr 16).toInt())
            one((value ushr 8).toInt())
            one(value.toInt())
        }

        fun bytes(value: ByteArray) {
            integer(value.size)
            digest.update(value)
        }

        fun finish(): ByteArray = digest.digest()
    }
}
