package com.cattailsw.nanidroid.ghost

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

class BundledGhostRepository(
    private val openAsset: () -> InputStream,
    private val ghostRoot: File,
    private val expectedSha256: String = BUNDLED_SHA256,
) {
    fun ensureInstalled(): File = synchronized(INSTALL_LOCK) {
        val live = File(ghostRoot, "nanidroid")
        if (Files.exists(live.toPath(), NOFOLLOW_LINKS)) {
            refreshDictionaries(live)
            return live
        }
        require(ghostRoot.isDirectory || ghostRoot.mkdirs()) { "Cannot create ghost directory" }
        val staging = File(ghostRoot, ".nanidroid-staging")
        if (staging.exists()) deleteOwnedStaging(staging)
        require(staging.mkdir()) { "Cannot create staging directory" }
        try {
            val archive = verifiedArchive()
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

    fun load(root: File, language: String): BundledGhost = load(root, Locale.forLanguageTag(language))

    fun load(root: File, locale: Locale): BundledGhost {
        val install = DescriptorReader.read(File(root, "install.txt").readBytes())
        require(install["type"] == "ghost" && install["directory"] == "nanidroid")
        val descriptor = DescriptorReader.read(File(root, "ghost/master/descript.txt").readBytes())
        val selected = when {
            locale.script.equals("Hans", true) -> "ja"
            locale.language == "en" -> "en"
            locale.language == "zh" && (locale.script.equals("Hant", true) ||
                locale.country in setOf("TW", "HK", "MO")) -> "zh-TW"
            else -> "ja"
        }
        val requestedContent = File(root, "ghost/master/$selected/content.txt")
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
            content = boundedBytes(contentFile).toString(Charsets.UTF_8),
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
        require(File(root, "ghost/master/zh-TW/content.txt").isFile)
        require(File(root, "shell/master/surface0000.png").isFile)
        require(File(root, "shell/master/surface0010.png").isFile)
        for (path in MANAGED_PATHS) {
            val entries = boundedBytes(File(root, path)).toString(Charsets.UTF_8)
                .removePrefix("\uFEFF").lineSequence().mapNotNull { line ->
                    val comma = line.indexOf(',')
                    if (comma <= 0 || line.startsWith(';')) null else line.substring(0, comma)
                }.toSet()
            require(ARCHIVE_REQUIRED.all { it in entries }) { "Incomplete shipped dictionary" }
        }
    }

    private fun deleteOwnedStaging(staging: File) {
        Files.walk(staging.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun verifiedArchive(): ByteArray = openAsset().use { it.readBytes() }.also {
        require(sha256(it).equals(expectedSha256, ignoreCase = true)) { "Bundled ghost hash mismatch" }
    }

    private fun boundedBytes(file: File): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(CONTENT_LIMIT + 1)
        var count = 0
        while (count < buffer.size) {
            val next = input.read(buffer, count, buffer.size - count)
            if (next < 0) break
            count += next
        }
        require(count <= CONTENT_LIMIT) { "Bundled content exceeds 64 KiB" }
        buffer.copyOf(count)
    }

    /** Checks every fixed path component without following links, before any refresh writes. */
    private fun confined(root: File, relative: String): File {
        require(Files.readAttributes(ghostRoot.toPath(), BasicFileAttributes::class.java,
            NOFOLLOW_LINKS).isDirectory) { "Invalid ghost root" }
        val base = ghostRoot.canonicalFile.toPath()
        var file = root
        val parts = listOf("") + relative.split('/').filter { it.isNotEmpty() }
        for ((index, part) in parts.withIndex()) {
            if (part.isNotEmpty()) file = File(file, part)
            require(file.canonicalFile.toPath().startsWith(base)) { "Dictionary path escapes root" }
            if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(!attributes.isSymbolicLink && (if (index == parts.lastIndex)
                    attributes.isRegularFile else attributes.isDirectory)) { "Unsafe dictionary path" }
            }
        }
        return file
    }

    private fun installedIdentity(root: File): Boolean {
        val install = confined(root, "install.txt")
        val descriptor = confined(root, "ghost/master/descript.txt")
        if (!install.exists() || !descriptor.exists()) return false
        val manifest = DescriptorReader.read(boundedBytes(install))
        val fields = DescriptorReader.read(boundedBytes(descriptor))
        return manifest["type"] == "ghost" && manifest["directory"] == "nanidroid" &&
            fields["id"] == "nanidroid" && fields["shiori"] == "Nanidroid"
    }

    private fun hashFile(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun refreshDictionaries(root: File) {
        if (!installedIdentity(root)) return
        MANAGED_PATHS.forEach { confined(root, it) }
        val replacement = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(verifiedArchive())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name in MANAGED_PATHS) {
                    require(entry.name !in replacement) { "Duplicate managed dictionary" }
                    val bytes = ByteArray(CONTENT_LIMIT + 1)
                    var count = 0
                    while (count < bytes.size) {
                        val next = zip.read(bytes, count, bytes.size - count)
                        if (next < 0) break
                        count += next
                    }
                    require(count <= CONTENT_LIMIT) { "Bundled content exceeds 64 KiB" }
                    replacement[entry.name] = bytes.copyOf(count)
                }
                zip.closeEntry()
            }
        }
        require(replacement.keys == MANAGED_PATHS) { "Missing managed dictionary" }
        for (path in MANAGED_PATHS) {
            var target = confined(root, path)
            val before = target.takeIf { it.exists() }?.let(::hashFile)
            val bytes = replacement.getValue(path)
            val newHash = sha256(bytes)
            if (before == newHash || (before != null && before != PREVIOUS_HASHES[path])) continue
            val parent = requireNotNull(target.parentFile)
            require(parent.isDirectory || parent.mkdirs()) { "Cannot create dictionary parent" }
            target = confined(root, path)
            val temporary = Files.createTempFile(parent.toPath(), ".nanidroid-dictionary-", ".tmp")
            try {
                Files.newOutputStream(temporary).use { it.write(bytes) }
                check(installedIdentity(root)) { "Bundled identity changed during refresh" }
                val checked = confined(root, path)
                val current = checked.takeIf { it.exists() }?.let(::hashFile)
                if (current != before) continue // A newly customized dictionary remains authoritative.
                Files.move(temporary, checked.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    companion object {
        const val BUNDLED_SHA256 = "7ad4109c5362d3f068b1acf1ab642541b9dd8d2c5d9d95e5e7f2c9bf4eb9f46c"
        private const val CONTENT_LIMIT = 64 * 1024
        private val MANAGED_PATHS = linkedSetOf("ghost/master/en/content.txt",
            "ghost/master/ja/content.txt", "ghost/master/zh-TW/content.txt")
        private val ARCHIVE_REQUIRED = setOf("OnFirstBoot", "OnBoot", "OnClose",
            "OnGhostChanging", "OnGhostChanged", "OnMouseClick@r3=0", "OnMouseClick@r3=1",
            "OnMouseClick@r3=1@debug", "OnNanidroidHelp", "OnNanidroidIntro",
            "OnNanidroidImportHelp", "OnNanidroidInteractionHelp", "OnNanidroidTools",
            "OnNanidroidTools@debug", "OnNanidroidDismiss", "OnNanidroidOpenGhosts",
            "OnNanidroidOpenAbout", "OnNanidroidOpenDebug")
        private val PREVIOUS_HASHES = mapOf(
            "ghost/master/en/content.txt" to "b4621c0532d0027a539aecfadd85448b2167d9671f0f9c78239735b302b67f0c",
            "ghost/master/ja/content.txt" to "3b1fbeee59cb47e1e015fe41535218ec9a778444be2dc98d145b91df85b36002",
        )
        private val INSTALL_LOCK = Any()
    }
}
