package com.cattailsw.nanidroid.install

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes

internal enum class OwnedStagingEntryKind { REGULAR_FILE, DIRECTORY_TREE }

internal sealed interface OwnedStagingRecoveryResult {
    data object Clean : OwnedStagingRecoveryResult
    data object Cleaned : OwnedStagingRecoveryResult
    data class Failed(val message: String) : OwnedStagingRecoveryResult
}

internal interface OwnedStagingFileSystem {
    fun canonical(file: File): File
    fun existsNoFollow(file: File): Boolean
    fun isRegularFileNoFollow(file: File): Boolean
    fun isDirectoryNoFollow(file: File): Boolean
    fun isSymbolicLink(file: File): Boolean
    fun list(file: File): List<File>?
    fun delete(file: File): Boolean
}

internal object RealOwnedStagingFileSystem : OwnedStagingFileSystem {
    override fun canonical(file: File): File = file.canonicalFile

    override fun existsNoFollow(file: File): Boolean = Files.exists(file.toPath(), NOFOLLOW_LINKS)

    override fun isRegularFileNoFollow(file: File): Boolean = attributes(file).isRegularFile

    override fun isDirectoryNoFollow(file: File): Boolean = attributes(file).isDirectory

    override fun isSymbolicLink(file: File): Boolean = attributes(file).isSymbolicLink

    override fun list(file: File): List<File>? = Files.newDirectoryStream(file.toPath()).use { entries ->
        entries.map { it.toFile() }
    }

    override fun delete(file: File): Boolean = Files.deleteIfExists(file.toPath())

    private fun attributes(file: File): BasicFileAttributes = Files.readAttributes(
        file.toPath(),
        BasicFileAttributes::class.java,
        NOFOLLOW_LINKS,
    )
}

internal object OwnedStagingRecovery {
    private const val FAILURE_MESSAGE = "Nanidroid could not reconcile its private import staging."

    fun reconcile(
        root: File?,
        expectedParent: File?,
        entryPattern: Regex,
        entryKind: OwnedStagingEntryKind,
        files: OwnedStagingFileSystem = RealOwnedStagingFileSystem,
    ): OwnedStagingRecoveryResult = try {
        if (root == null || expectedParent == null) return failed()
        if (files.existsNoFollow(root) && files.isSymbolicLink(root)) return failed()
        val canonicalRoot = files.canonical(root)
        val canonicalParent = files.canonical(expectedParent)
        if (canonicalRoot.parentFile != canonicalParent) return failed()
        if (!files.existsNoFollow(canonicalRoot)) return OwnedStagingRecoveryResult.Clean
        if (files.isSymbolicLink(canonicalRoot) || !files.isDirectoryNoFollow(canonicalRoot)) return failed()
        val entries = files.list(canonicalRoot) ?: return failed()
        var cleaned = false
        for (entry in entries) {
            if (!entry.name.matches(entryPattern)) continue
            if (entry.parentFile != canonicalRoot || files.isSymbolicLink(entry)) return failed()
            val deleted = when (entryKind) {
                OwnedStagingEntryKind.REGULAR_FILE ->
                    files.isRegularFileNoFollow(entry) && files.delete(entry)
                OwnedStagingEntryKind.DIRECTORY_TREE ->
                    files.isDirectoryNoFollow(entry) && deleteVerifiedTree(entry, files)
            }
            if (!deleted) return failed()
            cleaned = true
        }
        if (cleaned) OwnedStagingRecoveryResult.Cleaned else OwnedStagingRecoveryResult.Clean
    } catch (_: Exception) {
        failed()
    }

    private fun deleteVerifiedTree(
        directory: File,
        files: OwnedStagingFileSystem,
    ): Boolean {
        if (files.isSymbolicLink(directory)) return files.delete(directory)
        if (files.isRegularFileNoFollow(directory)) return files.delete(directory)
        if (!files.isDirectoryNoFollow(directory)) return false
        val entries = files.list(directory) ?: return false
        for (entry in entries) {
            if (entry.parentFile != directory || !deleteVerifiedTree(entry, files)) return false
        }
        return files.delete(directory)
    }

    private fun failed() = OwnedStagingRecoveryResult.Failed(FAILURE_MESSAGE)
}
