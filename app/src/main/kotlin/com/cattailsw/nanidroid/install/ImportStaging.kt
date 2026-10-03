package com.cattailsw.nanidroid.install

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Owns only private import-staging/attempt-* trees. */
internal class ImportStaging(
    filesDir: File,
    private val beforeCleanup: () -> Unit = {},
) {
    private val files = filesDir.toPath()
    private val root = files.resolve("import-staging")

    /** The parent of filesDir is the Android app-private root; higher ancestors are OS-owned. */
    fun validatePrivateRoot() {
        val appRoot = requireNotNull(files.parent) { "Private files directory has no parent" }
        require(safeDirectory(appRoot) && safeDirectory(files)) { "Private files path is linked or invalid" }
    }

    fun recover() {
        validatePrivateRoot()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        require(safeDirectory(root)) {
            "Import staging root is invalid"
        }
        Files.newDirectoryStream(root).use { children ->
            children.forEach { child ->
                if (child.fileName.toString().startsWith("attempt-")) deleteAttempt(child)
            }
        }
    }

    fun begin(): File {
        validatePrivateRoot()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(root)
        require(safeDirectory(root)) {
            "Import staging root is invalid"
        }
        return Files.createDirectory(root.resolve("attempt-${UUID.randomUUID()}")).toFile()
    }

    fun cleanup(attempt: File) = deleteAttempt(attempt.toPath())

    private fun deleteAttempt(attempt: Path) {
        require(attempt.parent == root && attempt.fileName.toString().startsWith("attempt-")) {
            "Not an owned import attempt"
        }
        if (!Files.exists(attempt, LinkOption.NOFOLLOW_LINKS)) return
        beforeCleanup()
        require(safeDirectory(attempt)) {
            "Import attempt is linked or invalid"
        }
        val paths = Files.walk(attempt).use { it.iterator().asSequence().toList() }
        require(paths.none { linkedOrSpecial(it) }) { "Import staging contains a link" }
        paths.sortedByDescending { it.nameCount }.forEach { Files.delete(it) }
    }

    private fun safeDirectory(path: Path): Boolean = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
        !linkedOrSpecial(path)

    private fun linkedOrSpecial(path: Path): Boolean {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        // Windows junctions are reported as "other" rather than symbolic links by Java.
        return attributes.isSymbolicLink || attributes.isOther
    }
}
