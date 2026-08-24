package com.cattailsw.nanidroid.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OwnedStagingRecoveryTest {
    @Test
    fun importRecoveryDeletesOnlyMatchingRegularFiles() {
        val parent = File("/import-parent")
        val root = File(parent, "nar-import-v1")
        val owned = File(root, "nar-import-0123456789abcdef01234567.zip")
        val unmatched = File(root, "keep.txt")
        val files = FakeFileSystem().apply {
            directory(parent)
            directory(root)
            regularFile(owned)
            regularFile(unmatched)
        }

        val result = OwnedStagingRecovery.reconcile(
            root = root,
            expectedParent = parent,
            entryPattern = Regex("^nar-import-[0-9a-f]{24}\\.zip$"),
            entryKind = OwnedStagingEntryKind.REGULAR_FILE,
            files = files,
        )

        assertEquals(OwnedStagingRecoveryResult.Cleaned, result)
        assertFalse(files.exists(owned))
        assertTrue(files.exists(unmatched))
    }

    @Test
    fun installRecoveryDeletesMatchingCandidateDirectoryTreeAndInnerSymlinkWithoutTraversal() {
        val parent = File("/install-parent")
        val root = File(parent, ".nanidroid-install-staging")
        val candidate = File(root, "candidate-0123456789abcdef0123456789abcdef")
        val tree = File(candidate, "tree")
        val child = File(tree, "file.txt")
        val innerLink = File(tree, "outside")
        val files = FakeFileSystem().apply {
            directory(parent)
            directory(root)
            directory(candidate)
            directory(tree)
            regularFile(child)
            symbolicLink(innerLink)
        }

        val result = OwnedStagingRecovery.reconcile(
            root = root,
            expectedParent = parent,
            entryPattern = Regex("^candidate-[0-9a-f]{32}$"),
            entryKind = OwnedStagingEntryKind.DIRECTORY_TREE,
            files = files,
        )

        assertEquals(OwnedStagingRecoveryResult.Cleaned, result)
        assertFalse(files.exists(candidate))
        assertFalse(files.exists(innerLink))
    }

    @Test
    fun matchingSymlinkIsRejectedWithoutFollowingOrDeletingIt() {
        val parent = File("/link-parent")
        val root = File(parent, "nar-import-v1")
        val ownedLink = File(root, "nar-import-0123456789abcdef01234567.zip")
        val files = FakeFileSystem().apply {
            directory(parent)
            directory(root)
            symbolicLink(ownedLink)
        }

        val result = OwnedStagingRecovery.reconcile(
            root = root,
            expectedParent = parent,
            entryPattern = Regex("^nar-import-[0-9a-f]{24}\\.zip$"),
            entryKind = OwnedStagingEntryKind.REGULAR_FILE,
            files = files,
        )

        assertTrue(result is OwnedStagingRecoveryResult.Failed)
        assertTrue(files.exists(ownedLink))
    }

    @Test
    fun symlinkRootIsRejectedWithoutFollowingIt() {
        val parent = File("/root-link-parent")
        val root = File(parent, "nar-import-v1")
        val target = File(parent, "other-directory")
        val files = FakeFileSystem().apply {
            directory(parent)
            symbolicLink(root)
            directory(target)
            canonical(root, target)
        }

        val result = OwnedStagingRecovery.reconcile(
            root = root,
            expectedParent = parent,
            entryPattern = Regex(".*"),
            entryKind = OwnedStagingEntryKind.REGULAR_FILE,
            files = files,
        )

        assertTrue(result is OwnedStagingRecoveryResult.Failed)
        assertTrue(files.exists(root))
        assertFalse(files.wasListed(target))
    }

    @Test
    fun matchingEntryWithWrongKindIsRejectedAndUnmatchedSiblingIsPreserved() {
        val parent = File("/kind-parent")
        val root = File(parent, "nar-import-v1")
        val wrongKind = File(root, "nar-import-0123456789abcdef01234567.zip")
        val unmatched = File(root, "keep-dir")
        val files = FakeFileSystem().apply {
            directory(parent)
            directory(root)
            directory(wrongKind)
            directory(unmatched)
        }

        val result = OwnedStagingRecovery.reconcile(
            root = root,
            expectedParent = parent,
            entryPattern = Regex("^nar-import-[0-9a-f]{24}\\.zip$"),
            entryKind = OwnedStagingEntryKind.REGULAR_FILE,
            files = files,
        )

        assertTrue(result is OwnedStagingRecoveryResult.Failed)
        assertTrue(files.exists(wrongKind))
        assertTrue(files.exists(unmatched))
    }

    @Test
    fun canonicalParentMismatchIsRejectedWithoutEnumeratingRoot() {
        val parent = File("/expected-parent")
        val root = File(parent, "nar-import-v1")
        val actualParent = File("/other-parent")
        val canonicalRoot = File(actualParent, "nar-import-v1")
        val files = FakeFileSystem().apply {
            directory(parent)
            directory(root)
            canonical(root, canonicalRoot)
        }

        val result = OwnedStagingRecovery.reconcile(
            root = root,
            expectedParent = parent,
            entryPattern = Regex(".*"),
            entryKind = OwnedStagingEntryKind.REGULAR_FILE,
            files = files,
        )

        assertTrue(result is OwnedStagingRecoveryResult.Failed)
        assertFalse(files.wasListed(root))
    }

    @Test
    fun absentCorrectlyLocatedRootIsCleanWithoutCreatingIt() {
        val parent = File("/absent-parent")
        val root = File(parent, "nar-import-v1")
        val files = FakeFileSystem().apply { directory(parent) }

        val result = OwnedStagingRecovery.reconcile(
            root = root,
            expectedParent = parent,
            entryPattern = Regex(".*"),
            entryKind = OwnedStagingEntryKind.REGULAR_FILE,
            files = files,
        )

        assertEquals(OwnedStagingRecoveryResult.Clean, result)
        assertFalse(files.exists(root))
    }

    @Test
    fun enumerationFailureIsReportedWithoutDeletingMatchingEntry() {
        val parent = File("/enumeration-parent")
        val root = File(parent, "nar-import-v1")
        val owned = File(root, "nar-import-0123456789abcdef01234567.zip")
        val files = FakeFileSystem().apply {
            directory(parent)
            directory(root)
            regularFile(owned)
            failListing(root)
        }

        val result = OwnedStagingRecovery.reconcile(
            root = root,
            expectedParent = parent,
            entryPattern = Regex("^nar-import-[0-9a-f]{24}\\.zip$"),
            entryKind = OwnedStagingEntryKind.REGULAR_FILE,
            files = files,
        )

        assertTrue(result is OwnedStagingRecoveryResult.Failed)
        assertTrue(files.exists(owned))
    }

    @Test
    fun filesystemExceptionIsReportedWithoutDeletingMatchingEntry() {
        val parent = File("/exception-parent")
        val root = File(parent, "nar-import-v1")
        val owned = File(root, "nar-import-0123456789abcdef01234567.zip")
        val files = FakeFileSystem().apply {
            directory(parent)
            directory(root)
            regularFile(owned)
            throwWhenListing(root)
        }

        val result = OwnedStagingRecovery.reconcile(
            root = root,
            expectedParent = parent,
            entryPattern = Regex("^nar-import-[0-9a-f]{24}\\.zip$"),
            entryKind = OwnedStagingEntryKind.REGULAR_FILE,
            files = files,
        )

        assertTrue(result is OwnedStagingRecoveryResult.Failed)
        assertTrue(files.exists(owned))
    }

    @Test
    fun deleteFailureIsReportedAndOwnedEntryRemains() {
        val parent = File("/delete-parent")
        val root = File(parent, "nar-import-v1")
        val owned = File(root, "nar-import-0123456789abcdef01234567.zip")
        val files = FakeFileSystem().apply {
            directory(parent)
            directory(root)
            regularFile(owned)
            failDeletion(owned)
        }

        val result = OwnedStagingRecovery.reconcile(
            root = root,
            expectedParent = parent,
            entryPattern = Regex("^nar-import-[0-9a-f]{24}\\.zip$"),
            entryKind = OwnedStagingEntryKind.REGULAR_FILE,
            files = files,
        )

        assertTrue(result is OwnedStagingRecoveryResult.Failed)
        assertTrue(files.exists(owned))
    }

    private class FakeFileSystem : OwnedStagingFileSystem {
        private enum class Kind { FILE, DIRECTORY, LINK }

        private val nodes = mutableMapOf<File, Kind>()
        private val canonical = mutableMapOf<File, File>()
        private val listFailures = mutableSetOf<File>()
        private val listExceptions = mutableSetOf<File>()
        private val deleteFailures = mutableSetOf<File>()
        private val listed = mutableSetOf<File>()

        fun regularFile(file: File) { nodes[file] = Kind.FILE }
        fun directory(file: File) { nodes[file] = Kind.DIRECTORY }
        fun symbolicLink(file: File) { nodes[file] = Kind.LINK }
        fun canonical(file: File, target: File) { canonical[file] = target }
        fun failListing(file: File) { listFailures += file }
        fun throwWhenListing(file: File) { listExceptions += file }
        fun failDeletion(file: File) { deleteFailures += file }
        fun exists(file: File) = file in nodes
        fun wasListed(file: File) = file in listed

        override fun canonical(file: File): File = canonical[file] ?: file
        override fun existsNoFollow(file: File): Boolean = exists(file)
        override fun isRegularFileNoFollow(file: File): Boolean = nodes[file] == Kind.FILE
        override fun isDirectoryNoFollow(file: File): Boolean = nodes[file] == Kind.DIRECTORY
        override fun isSymbolicLink(file: File): Boolean = nodes[file] == Kind.LINK
        override fun list(file: File): List<File>? {
            listed += file
            if (file in listExceptions) throw IllegalStateException("listing failed")
            if (file in listFailures) return null
            return nodes.keys.filter { it.parentFile == file }
        }

        override fun delete(file: File): Boolean {
            if (file in deleteFailures) return false
            val descendants = nodes.keys.filter { it == file || it.path.startsWith(file.path + File.separator) }
            if (descendants.any { it != file }) return false
            return nodes.remove(file) != null
        }
    }
}
