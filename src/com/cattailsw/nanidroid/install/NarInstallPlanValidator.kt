package com.cattailsw.nanidroid.install

import java.io.File
import java.util.Arrays

/** Kotlin transcription baseline for the identity-bound NAR plan validator. */
@Suppress("EXPOSED_PARAMETER_TYPE")
class NarInstallPlanValidator(private val io: ArchiveIo) {
    constructor() : this(FileArchiveIo())

    fun validate(archive: File?, installRoot: File?, forcedId: String?): NarInstallPlanResult = try {
        validateArguments(archive, installRoot)
        val source = archive!!
        val before = readIdentity(source)
        requireCleanClose(before)
        val plan = planArchive(source, installRoot!!, forcedId, before.identity, false).plan
        val after = readIdentity(source)
        requireSameIdentity(before.identity, after.identity)
        requireCleanClose(after)
        NarInstallPlanResult.success(plan)
    } catch (failure: Failure) { result(failure) }

    fun verify(archive: File?, plan: NarInstallPlan?): NarInstallPlanResult = try {
        if (archive == null || plan == null) fail(NarInstallError.ARCHIVE_IDENTITY_MISMATCH, "missing identity")
        val before = readIdentity(archive)
        requirePlanIdentity(plan, before.identity)
        requireCleanClose(before)
        verifyCentral(archive, plan, false)
        val after = readIdentity(archive)
        requirePlanIdentity(plan, after.identity)
        requireCleanClose(after)
        NarInstallPlanResult.success(plan)
    } catch (failure: Failure) { result(failure) }

    @Suppress("EXPOSED_PARAMETER_TYPE")
    fun validateStaged(staged: NarStagedSource?, installRoot: File?, forcedId: String?): NarInstallPlanResult {
        val archive = staged?.claim() ?: return NarInstallPlanResult.failure(NarInstallError.STAGED_SOURCE_INVALID, "staged source already claimed")
        var retained: RetainedArchive? = null; var transferred = false
        return try { validateArguments(archive, installRoot); val before = readIdentity(archive); requireCleanClose(before)
            retained = planArchive(archive, installRoot!!, forcedId, before.identity, true); val after = readIdentity(archive); requireSameIdentity(before.identity, after.identity); requireCleanClose(after)
            val session = NarVerifiedInstallSession(io, archive, retained.archive!!, retained.entries!!, retained.plan); transferred = true; NarInstallPlanResult.stagedSuccess(retained.plan, session)
        } catch (failure: Failure) { result(failure) } catch (_: RuntimeException) { result(archiveRead("staged validation")) }
        finally { if (!transferred) cleanup(retained, archive) }
    }

    @Suppress("EXPOSED_PARAMETER_TYPE")
    fun verifyStaged(staged: NarStagedSource?, plan: NarInstallPlan?): NarInstallPlanResult {
        val archive = staged?.claim() ?: return NarInstallPlanResult.failure(NarInstallError.STAGED_SOURCE_INVALID, "staged source already claimed")
        var retained: RetainedArchive? = null; var transferred = false
        return try { if (plan == null) fail(NarInstallError.ARCHIVE_IDENTITY_MISMATCH, "missing plan")
            val before = readIdentity(archive); requirePlanIdentity(plan, before.identity); requireCleanClose(before)
            retained = verifyCentral(archive, plan, true); val after = readIdentity(archive); requirePlanIdentity(plan, after.identity); requireCleanClose(after)
            val session = NarVerifiedInstallSession(io, archive, retained.archive!!, retained.entries!!, plan); transferred = true; NarInstallPlanResult.stagedSuccess(plan, session)
        } catch (failure: Failure) { result(failure) } catch (_: RuntimeException) { result(archiveRead("staged verification")) }
        finally { if (!transferred) cleanup(retained, archive) }
    }

    private class Failure(val error: NarInstallError, detail: String) : Exception(detail)
    private class SourceIdentity(val length: Long, val digest: ByteArray) {
        fun same(other: SourceIdentity): Boolean =
            length == other.length && Arrays.equals(digest, other.digest)
    }
    private class IdentityRead(val identity: SourceIdentity, val closeFailed: Boolean)
    private class DescriptorRead(val bytes: ByteArray, val closeFailed: Boolean)
    private class DescriptorFailure(val failure: Failure) : Exception()

    private fun buildPlan(
        installRoot: File,
        identity: SourceIdentity,
        inventory: NarArchiveInventory,
        descriptor: NarInstallDescriptor,
    ): NarInstallPlan {
        val root = try { io.canonical(installRoot) }
        catch (_: Exception) { throw Failure(NarInstallError.INSTALL_ROOT_INVALID, "install root") }
            ?: fail(NarInstallError.INSTALL_ROOT_INVALID, "null canonical root")
        val target = File(root, descriptor.getTargetId())
        if (root != target.parentFile) fail(NarInstallError.INVALID_TARGET_ID, "target parent")
        return NarInstallPlan(identity.length, identity.digest, inventory, descriptor, root, target)
    }

    private fun inspectBeforeZip(archive: File): Int {
        val count = try { io.preflight(archive) }
        catch (_: Exception) { throw archiveRead("central preflight") }
        if (count < 0) throw archiveRead("negative central count")
        if (count > ENTRY_LIMIT) fail(NarInstallError.ENTRY_COUNT_LIMIT, "entry count exceeds 10000")
        return count
    }

    private fun planArchive(archive: File, installRoot: File, forcedId: String?, identity: SourceIdentity, retain: Boolean): RetainedArchive {
        val preflight = inspectBeforeZip(archive); var zip: OpenArchive? = null; var entries: List<out ArchiveEntry>? = null; var failure: Failure? = null; var plan: NarInstallPlan? = null
        try { zip = io.openArchive(archive); entries = zip.entries(ENTRY_LIMIT_PLUS_ONE); failure = validateEnumeration(preflight, entries)
            if (failure == null) { val inventoryResult = NarArchiveInventoryValidator().validate(entries)
                if (!inventoryResult.isSuccess()) failure = Failure(inventoryResult.getError()!!, inventoryResult.getDetail())
                else { val inventory = inventoryResult.getInventory()!!; val read = readDescriptor(zip, entries[inventory.getDescriptorOrdinal()]); val descriptor = NarDescriptorParser().parse(read.bytes, forcedId)
                    if (!descriptor.isSuccess()) failure = Failure(descriptor.getError()!!, descriptor.getDetail())
                    else if (read.closeFailed) failure = Failure(NarInstallError.DESCRIPTOR_READ_FAILED, "descriptor close")
                    else plan = buildPlan(installRoot, identity, inventory, descriptor.getDescriptor()!!) } }
        } catch (error: DescriptorFailure) { failure = error.failure }
        catch (error: Failure) { failure = error }
        catch (_: java.io.IOException) { failure = archiveRead("archive read") }
        catch (_: RuntimeException) { failure = archiveRead("archive runtime") }
        finally { if (!retain || failure != null) failure = closeArchive(zip, failure) }
        if (failure != null) throw failure
        return RetainedArchive(plan!!, if (retain) zip else null, if (retain) entries else null)
    }

    private fun verifyCentral(archive: File, plan: NarInstallPlan, retain: Boolean): RetainedArchive {
        val preflight = inspectBeforeZip(archive); var zip: OpenArchive? = null; var actual: List<out ArchiveEntry>? = null; var failure: Failure? = null
        try { zip = io.openArchive(archive); actual = zip.entries(ENTRY_LIMIT_PLUS_ONE); failure = validateEnumeration(preflight, actual)
            if (failure == null) { val expected = plan.entries; if (actual.size != expected.size) failure = identityMismatch("central count")
                else for (index in actual.indices) if (!expected[index].sameCentral(actual[index])) { failure = identityMismatch("central record"); break } }
        } catch (_: java.io.IOException) { failure = archiveRead("archive read") }
        catch (_: RuntimeException) { failure = archiveRead("central metadata") }
        finally { if (!retain || failure != null) failure = closeArchive(zip, failure) }
        if (failure != null) throw failure
        return RetainedArchive(plan, if (retain) zip else null, if (retain) actual else null)
    }

    private fun readIdentity(archive: File): IdentityRead {
        try { if (io.length(archive) > MAX_ARCHIVE_BYTES) fail(NarInstallError.ARCHIVE_SIZE_LIMIT, "archive exceeds 544 MiB") }
        catch (_: java.io.IOException) { fail(NarInstallError.ARCHIVE_READ_FAILED, "archive length") }
        catch (_: RuntimeException) { fail(NarInstallError.ARCHIVE_READ_FAILED, "archive length") }
        var input: java.io.InputStream? = null; var failure: Failure? = null; var identity: SourceIdentity? = null; var closeFailed = false
        try { input = io.openSource(archive); val digest = java.security.MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(BUFFER_SIZE); var total = 0L
            while (true) { val limit = minOf(buffer.size.toLong(), MAX_ARCHIVE_BYTES - total + 1).toInt(); var count = input.read(buffer, 0, limit)
                if (count == 0) { val one = input.read(); if (one < 0) break; buffer[0] = one.toByte(); count = 1 } else if (count < 0) break else if (count > limit) throw java.io.IOException("invalid read count")
                total += count; if (total > MAX_ARCHIVE_BYTES) { failure = Failure(NarInstallError.ARCHIVE_SIZE_LIMIT, "archive exceeds 544 MiB"); break }; digest.update(buffer, 0, count) }
            if (failure == null) identity = SourceIdentity(total, digest.digest())
        } catch (_: java.io.IOException) { failure = archiveRead("archive stream") }
        catch (_: java.security.NoSuchAlgorithmException) { failure = archiveRead("SHA-256") }
        catch (_: RuntimeException) { failure = archiveRead("archive stream") }
        finally { try { input?.close() } catch (_: Exception) { closeFailed = true } }
        if (failure != null) throw failure; return IdentityRead(identity!!, closeFailed)
    }

    private fun readDescriptor(zip: OpenArchive, entry: ArchiveEntry): DescriptorRead {
        var input: java.io.InputStream? = null; var failure: Failure? = null; var bytes: ByteArray? = null; var closeFailed = false
        try { input = zip.open(entry); val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(BUFFER_SIZE); var total = 0
            while (true) { val limit = minOf(buffer.size, MAX_DESCRIPTOR_BYTES - total + 1); var count = input.read(buffer, 0, limit)
                if (count == 0) { val one = input.read(); if (one < 0) break; buffer[0] = one.toByte(); count = 1 } else if (count < 0) break else if (count > limit) throw java.io.IOException("invalid read count")
                total += count; if (total > MAX_DESCRIPTOR_BYTES) { failure = Failure(NarInstallError.INSTALL_DESCRIPTOR_LIMIT, "actual descriptor exceeds 64 KiB"); break }; output.write(buffer, 0, count) }
            if (failure == null) bytes = output.toByteArray()
        } catch (_: java.io.IOException) { failure = Failure(NarInstallError.DESCRIPTOR_READ_FAILED, "descriptor read") }
        catch (_: RuntimeException) { failure = Failure(NarInstallError.DESCRIPTOR_READ_FAILED, "descriptor runtime") }
        finally { try { input?.close() } catch (_: Exception) { closeFailed = true } }
        if (failure != null) throw DescriptorFailure(failure); return DescriptorRead(bytes!!, closeFailed)
    }

    private fun closeArchive(archive: OpenArchive?, failure: Failure?): Failure? {
        if (archive == null) return failure
        return try { archive.close(); failure } catch (_: Exception) { failure ?: archiveRead("archive close") }
    }

    private fun cleanup(retained: RetainedArchive?, stagedFile: File) {
        if (retained != null) closeArchive(retained.archive, null)
        try { io.delete(stagedFile) } catch (_: RuntimeException) { }
    }

    private class RetainedArchive(val plan: NarInstallPlan, val archive: OpenArchive?, val entries: List<out ArchiveEntry>?)

    companion object {
        private const val MAX_ARCHIVE_BYTES = 544L * 1024L * 1024L
        private const val MAX_DESCRIPTOR_BYTES = 64 * 1024
        private const val ENTRY_LIMIT = NarZipCentralPreflight.MAX_ENTRIES
        private const val ENTRY_LIMIT_PLUS_ONE = ENTRY_LIMIT + 1
        private const val BUFFER_SIZE = 8192

        private fun result(failure: Failure): NarInstallPlanResult =
            NarInstallPlanResult.failure(failure.error, failure.message)
        private fun validateArguments(archive: File?, installRoot: File?) {
            if (archive == null) fail(NarInstallError.ARCHIVE_READ_FAILED, "null archive")
            if (installRoot == null) fail(NarInstallError.INSTALL_ROOT_INVALID, "null install root")
        }
        private fun validateEnumeration(preflightCount: Int, entries: List<out ArchiveEntry>): Failure? = when {
            entries.size > ENTRY_LIMIT -> Failure(NarInstallError.ENTRY_COUNT_LIMIT, "entry count exceeds 10000")
            entries.size != preflightCount -> identityMismatch("preflight count")
            else -> null
        }
        private fun requireSameIdentity(expected: SourceIdentity, actual: SourceIdentity) {
            if (!expected.same(actual)) throw identityMismatch("source bytes changed")
        }
        private fun requirePlanIdentity(plan: NarInstallPlan, actual: SourceIdentity) {
            if (actual.length != plan.sourceLength || !Arrays.equals(actual.digest, plan.getSourceSha256()))
                throw identityMismatch("source bytes changed")
        }
        private fun requireCleanClose(read: IdentityRead) {
            if (read.closeFailed) throw archiveRead("source close")
        }
        private fun archiveRead(detail: String) = Failure(NarInstallError.ARCHIVE_READ_FAILED, detail)
        private fun identityMismatch(detail: String) = Failure(NarInstallError.ARCHIVE_IDENTITY_MISMATCH, detail)
        private fun fail(error: NarInstallError, detail: String): Nothing = throw Failure(error, detail)
    }

    interface ArchiveIo {
        @Throws(java.io.IOException::class)
        fun length(file: File): Long
        @Throws(java.io.IOException::class)
        fun openSource(file: File): java.io.InputStream
        @Throws(java.io.IOException::class)
        fun preflight(file: File): Int
        @Throws(java.io.IOException::class)
        fun openArchive(file: File): OpenArchive
        @Throws(java.io.IOException::class)
        fun canonical(file: File): File
        fun delete(file: File): Boolean
    }
    interface OpenArchive {
        @Throws(java.io.IOException::class)
        fun entries(limit: Int): List<@JvmWildcard ArchiveEntry>
        @Throws(java.io.IOException::class)
        fun open(entry: ArchiveEntry): java.io.InputStream
        @Throws(java.io.IOException::class)
        fun close()
    }
    interface ArchiveEntry : NarArchiveInventoryValidator.CentralEntry
    private class FileArchiveIo : ArchiveIo {
        override fun length(file: File) = file.length()
        override fun openSource(file: File) = java.io.FileInputStream(file)
        override fun preflight(file: File) = NarZipCentralPreflight.inspect(file).getEntryCount()
        override fun openArchive(file: File): OpenArchive = ZipArchive(file)
        override fun canonical(file: File) = file.canonicalFile
        override fun delete(file: File) = file.delete()
    }

    private class ZipArchive(file: File) : OpenArchive {
        private val zip = java.util.zip.ZipFile(file)
        override fun entries(limit: Int): List<@JvmWildcard ArchiveEntry> {
            val entries = ArrayList<ArchiveEntry>(); val source = zip.entries(); var ordinal = 0
            while (source.hasMoreElements() && entries.size < limit) entries.add(ZipArchiveEntry(this, ordinal++, source.nextElement()))
            return entries
        }
        override fun open(entry: ArchiveEntry): java.io.InputStream {
            if (entry !is ZipArchiveEntry || entry.owner !== this) throw java.io.IOException("foreign ZIP entry")
            return zip.getInputStream(entry.entry)
        }
        override fun close() { zip.close() }
    }

    private class ZipArchiveEntry(val owner: ZipArchive, private val ordinal: Int, val entry: java.util.zip.ZipEntry) : ArchiveEntry {
        override fun getOrdinal() = ordinal
        override fun getRawName() = entry.name
        override fun isDirectory() = entry.isDirectory
        override fun getCrc() = entry.crc
        override fun getMethod() = entry.method
        override fun getDeclaredSize() = entry.size
        override fun getCompressedSize() = entry.compressedSize
    }
}
