package com.cattailsw.nanidroid.install

import java.io.File

/** One-shot authority for a fresh app-private staged NAR snapshot. */
internal class NarStagedSource private constructor(private val file: File?) {
    private var claimed = false

    @Synchronized
    fun claim(): File? {
        if (claimed || file == null) return null
        claimed = true
        return file
    }

    interface StageIo {
        @Throws(java.io.IOException::class)
        fun canonical(file: File): File?
        fun isDirectory(directory: File): Boolean
        @Throws(java.io.IOException::class)
        fun createNew(file: File): Boolean
        @Throws(java.io.IOException::class)
        fun openSource(file: File): java.io.InputStream
        @Throws(java.io.IOException::class)
        fun openTarget(file: File): StageOutput
        fun delete(file: File): Boolean
    }

    interface StageOutput {
        @Throws(java.io.IOException::class)
        fun write(buffer: ByteArray, offset: Int, length: Int)
        @Throws(java.io.IOException::class)
        fun sync()
        @Throws(java.io.IOException::class)
        fun close()
    }

    fun interface NameSource { fun nextName(): String? }

    companion object {
        private const val MAX_ARCHIVE_BYTES = 544L * 1024L * 1024L
        private const val BUFFER_SIZE = 8192
        private const val NAME_ATTEMPTS = 16

        @JvmStatic
        fun copy(externalArchive: File?, trustedStagingRoot: File?): NarStagedSourceCopyResult =
            copy(externalArchive, trustedStagingRoot, FileStageIo(), RandomNameSource(), { false })

        @JvmStatic
        fun copy(
            externalArchive: File?,
            trustedStagingRoot: File?,
            isCancelled: () -> Boolean,
        ): NarStagedSourceCopyResult =
            copy(externalArchive, trustedStagingRoot, isCancelled) { }

        @JvmStatic
        fun copy(
            externalArchive: File?,
            trustedStagingRoot: File?,
            isCancelled: () -> Boolean,
            onProgress: (completed: Long) -> Unit,
        ): NarStagedSourceCopyResult =
            copy(
                externalArchive,
                trustedStagingRoot,
                FileStageIo(),
                RandomNameSource(),
                isCancelled,
                onProgress,
            )

        @JvmStatic
        fun copy(
            externalArchive: File?,
            trustedStagingRoot: File?,
            io: StageIo?,
            names: NameSource?,
            isCancelled: () -> Boolean = { false },
            onProgress: (completed: Long) -> Unit = { },
        ): NarStagedSourceCopyResult {
            if (externalArchive == null) return failure(NarStagedSourceCopyError.SOURCE_INVALID, "source is null")
            if (trustedStagingRoot == null || io == null) return failure(NarStagedSourceCopyError.STAGING_ROOT_INVALID, "staging root is null")
            val root = try { io.canonical(trustedStagingRoot) } catch (_: java.io.IOException) { return failure(NarStagedSourceCopyError.STAGING_ROOT_INVALID, "cannot canonicalize staging root") } catch (_: RuntimeException) { return failure(NarStagedSourceCopyError.STAGING_ROOT_INVALID, "cannot inspect staging root") }
            if (root == null || !io.isDirectory(root)) return failure(NarStagedSourceCopyError.STAGING_ROOT_INVALID, "staging root is not a directory")
            repeat(NAME_ATTEMPTS) {
                val name = try { names?.nextName() } catch (_: RuntimeException) { return failure(NarStagedSourceCopyError.STAGING_NAME_INVALID, "cannot generate staging name") }
                if (!isSafeName(name)) return failure(NarStagedSourceCopyError.STAGING_NAME_INVALID, "invalid staging name")
                val candidate = try { io.canonical(File(root, name!!)) } catch (_: java.io.IOException) { return failure(NarStagedSourceCopyError.STAGING_NAME_INVALID, "cannot canonicalize staging path") } catch (_: RuntimeException) { return failure(NarStagedSourceCopyError.STAGING_NAME_INVALID, "cannot inspect staging path") }
                if (candidate == null || candidate.parentFile != root) return failure(NarStagedSourceCopyError.STAGING_NAME_INVALID, "staging path escapes root")
                try { if (io.createNew(candidate)) return copyIntoCreated(externalArchive, candidate, io, isCancelled, onProgress) } catch (_: Exception) { return failure(NarStagedSourceCopyError.STAGING_CREATE_FAILED, "cannot create staging file") }
            }
            return failure(NarStagedSourceCopyError.STAGING_NAME_COLLISION_LIMIT, "staging name collision limit")
        }

        private fun isSafeName(name: String?): Boolean = name != null && name.isNotEmpty() && name.length <= 128 && name[0].isAsciiLetterOrDigit() && name.drop(1).all { it.isAsciiLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
        private fun failure(error: NarStagedSourceCopyError, detail: String) = NarStagedSourceCopyResult.failure(error, detail, ArrayList())

        private fun copyIntoCreated(sourceFile: File, staged: File, io: StageIo, isCancelled: () -> Boolean, onProgress: (Long) -> Unit): NarStagedSourceCopyResult {
            var source: java.io.InputStream? = null; var target: StageOutput? = null; var primary: NarStagedSourceCopyError? = null; var detail = ""; val cleanup = ArrayList<NarStagedSourceCopyError>()
            try { source = io.openSource(sourceFile) } catch (_: Exception) { primary = NarStagedSourceCopyError.SOURCE_OPEN_FAILED; detail = "cannot open source" }
            if (primary == null) try { target = io.openTarget(staged) } catch (_: Exception) { primary = NarStagedSourceCopyError.STAGING_OPEN_FAILED; detail = "cannot open staging writer" }
            if (primary == null) { val copied = copyBytes(source!!, target!!, isCancelled, onProgress); primary = copied.first; detail = copied.second }
            if (primary == null) try { target!!.sync() } catch (_: Exception) { primary = NarStagedSourceCopyError.STAGING_SYNC_FAILED; detail = "cannot sync staging writer" }
            try { target?.close() } catch (_: Exception) { primary = record(primary, NarStagedSourceCopyError.STAGING_CLOSE_FAILED, cleanup); if (detail.isEmpty()) detail = "cannot close staging writer" }
            try { source?.close() } catch (_: Exception) { primary = record(primary, NarStagedSourceCopyError.SOURCE_CLOSE_FAILED, cleanup); if (detail.isEmpty()) detail = "cannot close source" }
            if (primary != null) { if (!try { io.delete(staged) } catch (_: RuntimeException) { false }) cleanup.add(NarStagedSourceCopyError.STAGING_DELETE_FAILED); return NarStagedSourceCopyResult.failure(primary, detail, cleanup) }
            return NarStagedSourceCopyResult.success(NarStagedSource(staged))
        }
        private fun copyBytes(source: java.io.InputStream, target: StageOutput, isCancelled: () -> Boolean, onProgress: (Long) -> Unit): Pair<NarStagedSourceCopyError?, String> {
            val buffer = ByteArray(BUFFER_SIZE); var total = 0L
            while (true) { if (isCancelled()) return Pair(NarStagedSourceCopyError.CANCELLED, "copy cancelled"); val limit = minOf(buffer.size.toLong(), MAX_ARCHIVE_BYTES - total + 1).toInt(); val count = try { source.read(buffer, 0, limit) } catch (_: Exception) { return Pair(NarStagedSourceCopyError.SOURCE_READ_FAILED, "cannot read source") }
                if (count == -1) break; if (count < -1 || count > limit) return Pair(NarStagedSourceCopyError.SOURCE_READ_FAILED, "invalid source read count")
                val actual = if (count == 0) { val one = try { source.read() } catch (_: Exception) { return Pair(NarStagedSourceCopyError.SOURCE_READ_FAILED, "cannot read source") }; if (one == -1) break; if (one < -1 || one > 255) return Pair(NarStagedSourceCopyError.SOURCE_READ_FAILED, "invalid single-byte read"); buffer[0] = one.toByte(); 1 } else count
                try { target.write(buffer, 0, minOf(actual.toLong(), MAX_ARCHIVE_BYTES - total).toInt()) } catch (_: Exception) { return Pair(NarStagedSourceCopyError.STAGING_WRITE_FAILED, "cannot write staging file") }; total += actual; if (total > MAX_ARCHIVE_BYTES) return Pair(NarStagedSourceCopyError.ARCHIVE_SIZE_LIMIT, "archive exceeds 544 MiB"); onProgress(total) }
            return Pair(null, "")
        }
        private fun record(primary: NarStagedSourceCopyError?, next: NarStagedSourceCopyError, cleanup: MutableList<NarStagedSourceCopyError>) = if (primary == null) next else { cleanup.add(next); primary }
    }

    private class FileStageIo : StageIo { override fun canonical(file: File)=file.canonicalFile; override fun isDirectory(directory: File)=directory.isDirectory; override fun createNew(file: File)=file.createNewFile(); override fun openSource(file: File)=java.io.FileInputStream(file); override fun openTarget(file: File)=FileStageOutput(java.io.FileOutputStream(file)); override fun delete(file: File)=file.delete() }
    private class FileStageOutput(private val output: java.io.FileOutputStream) : StageOutput { override fun write(buffer: ByteArray, offset: Int, length: Int)=output.write(buffer, offset, length); override fun sync()=output.fd.sync(); override fun close()=output.close() }
    private class RandomNameSource : NameSource { private var random: java.security.SecureRandom? = null; override fun nextName()="staged-"+ByteArray(16).also{(random ?: java.security.SecureRandom().also { random = it }).nextBytes(it)}.joinToString(""){ "%02x".format(it) }+".nar" }
}
