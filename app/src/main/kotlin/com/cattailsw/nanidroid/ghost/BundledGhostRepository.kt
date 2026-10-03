package com.cattailsw.nanidroid.ghost

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class BundledGhostRepository(
    private val openAsset: () -> InputStream,
    private val ghostRoot: File,
    private val expectedSha256: String = BUNDLED_SHA256,
) {
    fun ensureInstalled(): File = synchronized(INSTALL_LOCK) {
        val live = File(ghostRoot, "nanidroid")
        if (live.exists()) return live
        require(ghostRoot.isDirectory || ghostRoot.mkdirs()) { "Cannot create ghost directory" }
        val staging = File(ghostRoot, ".nanidroid-staging")
        if (staging.exists()) deleteOwnedStaging(staging)
        require(staging.mkdir()) { "Cannot create staging directory" }
        try {
            val archive = openAsset().use { it.readBytes() }
            require(sha256(archive).equals(expectedSha256, ignoreCase = true)) {
                "Bundled ghost hash mismatch"
            }
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    require(name.isNotEmpty() && !name.startsWith('/') &&
                        !name.contains(':') && name.split('/').none { it == ".." || it == "." }) {
                        "Unsafe bundled entry"
                    }
                    val destination = File(staging, name).canonicalFile
                    require(destination.toPath().startsWith(staging.canonicalFile.toPath())) {
                        "Bundled entry escapes staging"
                    }
                    if (entry.isDirectory) {
                        require(destination.isDirectory || destination.mkdirs()) { "Cannot create directory" }
                    } else {
                        val parent = requireNotNull(destination.parentFile)
                        require(parent.isDirectory || parent.mkdirs()) {
                            "Cannot create entry parent"
                        }
                        require(!destination.exists()) { "Duplicate bundled entry" }
                        destination.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
            validate(staging)
            if (live.exists()) return live
            Files.move(staging.toPath(), live.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return live
        } finally {
            if (staging.exists()) deleteOwnedStaging(staging)
        }
    }

    fun load(root: File, language: String): BundledGhost {
        val install = DescriptorReader.read(File(root, "install.txt").readBytes())
        require(install["type"] == "ghost" && install["directory"] == "nanidroid")
        val descriptor = DescriptorReader.read(File(root, "ghost/master/descript.txt").readBytes())
        val locale = language.takeIf { it.matches(Regex("[A-Za-z]{2,8}")) } ?: "ja"
        val requestedContent = File(root, "ghost/master/$locale/content.txt")
        val contentFile = requestedContent.takeIf { it.isFile }
            ?: File(root, "ghost/master/ja/content.txt")
        val shell = File(root, "shell/master")
        val surfaces = shell.listFiles().orEmpty()
            .mapNotNull { file ->
                val id = SurfaceFileName.id(file.name)
                if (id != null && file.isFile) id to file else null
            }.toMap()
        return BundledGhost(
            directoryId = install.getValue("directory"),
            name = descriptor["name"].orEmpty(),
            sakuraName = descriptor["sakura.name"].orEmpty(),
            keroName = descriptor["kero.name"].orEmpty(),
            surfaces = surfaces,
            content = contentFile.readText(Charsets.UTF_8),
            shellDirectory = shell,
            sakuraName2 = DescriptorReader.sakuraName2(descriptor),
        )
    }

    private fun validate(root: File) {
        val install = DescriptorReader.read(File(root, "install.txt").readBytes())
        require(install["type"] == "ghost" && install["directory"] == "nanidroid") {
            "Bundled install descriptor mismatch"
        }
        require(File(root, "ghost/master/descript.txt").isFile)
        require(File(root, "ghost/master/en/content.txt").isFile)
        require(File(root, "ghost/master/ja/content.txt").isFile)
        require(File(root, "shell/master/surface0000.png").isFile)
        require(File(root, "shell/master/surface0010.png").isFile)
    }

    private fun deleteOwnedStaging(staging: File) {
        Files.walk(staging.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val BUNDLED_SHA256 = "2ebf24a2be8255011c5e004459a58dd1317eb7b12a55adcbe6b8b2977c89dc2d"
        private val INSTALL_LOCK = Any()
    }
}
