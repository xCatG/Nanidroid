package com.cattailsw.nanidroid.install

import com.cattailsw.nanidroid.DescReader
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes

internal interface NarDiscoverabilityFileSystem {
    fun canonical(file: File): File
    fun isRegularFile(file: File): Boolean
    fun parseDescriptor(file: File)
}

internal object RealNarDiscoverabilityFileSystem : NarDiscoverabilityFileSystem {
    override fun canonical(file: File) = file.canonicalFile

    override fun isRegularFile(file: File): Boolean = Files.readAttributes(
        file.toPath(),
        BasicFileAttributes::class.java,
        NOFOLLOW_LINKS,
    ).isRegularFile

    override fun parseDescriptor(file: File) {
        DescReader(file.path).parse()
    }
}

internal object NarGhostDiscoverabilityValidator {
    fun validate(
        candidateRoot: File,
        files: NarDiscoverabilityFileSystem = RealNarDiscoverabilityFileSystem,
    ): Boolean = try {
        val root = files.canonical(candidateRoot)
        val ghostPath = File(root, "ghost")
        val masterPath = File(ghostPath, "master")
        val descriptorPath = File(masterPath, "descript.txt")
        if (!files.isRegularFile(descriptorPath)) return false
        val ghost = files.canonical(ghostPath)
        val master = files.canonical(masterPath)
        val descriptor = files.canonical(descriptorPath)
        if (ghost != ghostPath || master != masterPath || descriptor != descriptorPath ||
            ghost.parentFile != root || master.parentFile != ghost || descriptor.parentFile != master
        ) {
            return false
        }
        files.parseDescriptor(descriptor)
        true
    } catch (_: Exception) {
        false
    }
}
