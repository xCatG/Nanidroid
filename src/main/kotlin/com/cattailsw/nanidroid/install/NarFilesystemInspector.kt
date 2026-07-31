package com.cattailsw.nanidroid.install

import java.util.Collections

/** Package-private boundary for inspecting a trusted NAR filesystem tree. */
internal class NarFilesystemInspector(private val loader: Loader, private val backend: Backend) {
    enum class State { ERROR, ABSENT, PRESENT }

    enum class Type { FILE, DIRECTORY }

    enum class Error {
        OK, INVALID_OPTIONS, INVALID_TARGET, ROOT_TYPE, TARGET_TYPE, SYMLINK,
        SPECIAL_TYPE, INVALID_NAME, COMPONENT_LIMIT, PATH_LIMIT, DEPTH_LIMIT,
        ENTRY_COUNT_LIMIT, FILE_SIZE_LIMIT, TOTAL_SIZE_LIMIT, CYCLE,
        TREE_CHANGED, PERMISSION, RESOURCE, IO, VISITOR, CLOSE,
        INPUT, NATIVE, LINKAGE, SECURITY;

        companion object {
            @JvmStatic
            fun fromCode(code: Int): Error = when {
                code in 0..20 -> entries[code]
                code == 100 -> INPUT
                code == 101 -> NATIVE
                code == 102 -> LINKAGE
                code == 103 -> SECURITY
                else -> NATIVE
            }
        }
    }

    class TrustedRoot(value: String?) {
        private val value: String = value ?: throw NullPointerException("trusted root")

        companion object {
            @JvmStatic
            fun valueOf(root: TrustedRoot?): String =
                (root ?: throw NullPointerException("trusted root")).value
        }
    }

    class Entry(
        private val path: String,
        private val type: Type,
        private val size: Long,
        private val device: Long,
        private val inode: Long,
    ) {
        fun path(): String = path
        fun type(): Type = type
        fun size(): Long = size
        fun device(): Long = device
        fun inode(): Long = inode
    }

    class Result(
        private val state: State,
        private val error: Error,
        private val cleanupError: Error,
        private val entryCount: Int,
        private val totalFileSize: Long,
        entries: List<Entry>,
    ) {
        private val entries: List<Entry> = Collections.unmodifiableList(ArrayList(entries))

        fun state(): State = state
        fun error(): Error = error
        fun cleanupError(): Error = cleanupError
        fun entryCount(): Int = entryCount
        fun totalFileSize(): Long = totalFileSize
        fun entries(): List<Entry> = entries
    }

    fun interface Loader { fun load() }

    fun interface Backend { fun inspect(trustedRoot: String, target: String): Result? }

    private var loaded = false

    constructor() : this(
        Loader { System.loadLibrary("narfs") },
        Backend { trustedRoot, target -> nativeInspect(trustedRoot, target) },
    )

    @Synchronized
    private fun ensureLoaded() {
        if (!loaded) {
            loader.load()
            loaded = true
        }
    }

    fun inspect(root: TrustedRoot?, target: String?): Result {
        if (root == null || target == null) return failure(Error.INPUT)
        return try {
            ensureLoaded()
            backend.inspect(TrustedRoot.valueOf(root), target) ?: failure(Error.NATIVE)
        } catch (_: LinkageError) {
            failure(Error.LINKAGE)
        } catch (_: SecurityException) {
            failure(Error.SECURITY)
        }
    }

    companion object {
        @JvmStatic
        fun sourceRootValue(root: TrustedRoot?): String = TrustedRoot.valueOf(root)

        @JvmStatic
        fun fromNative(
            stateCode: Int,
            errorCode: Int,
            cleanupCode: Int,
            count: Int,
            total: Long,
            paths: Array<String>?,
            types: IntArray?,
            facts: LongArray?,
        ): Result {
            if (paths == null || types == null || facts == null || count !in 0..10000 ||
                paths.size != count || types.size != count || facts.size != count * 3
            ) return failure(Error.NATIVE)

            val entries = ArrayList<Entry>(count)
            for (index in 0 until count) {
                val type = when (types[index]) {
                    1 -> Type.FILE
                    2 -> Type.DIRECTORY
                    else -> return failure(Error.NATIVE)
                }
                val path = java.lang.reflect.Array.get(paths, index) as? String
                    ?: return failure(Error.NATIVE)
                val fact = index * 3
                entries.add(Entry(path, type, facts[fact], facts[fact + 1], facts[fact + 2]))
            }
            val state = when (stateCode) {
                1 -> State.ABSENT
                2 -> State.PRESENT
                else -> State.ERROR
            }
            return Result(state, Error.fromCode(errorCode), Error.fromCode(cleanupCode), count, total, entries)
        }

        private fun failure(error: Error): Result =
            Result(State.ERROR, error, Error.OK, 0, 0, emptyList())

        @JvmStatic
        private external fun nativeInspect(trustedRoot: String, target: String): Result
    }
}
