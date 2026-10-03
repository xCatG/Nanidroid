package com.cattailsw.nanidroid.install

import com.cattailsw.nanidroid.ghost.DescriptorReader
import java.io.File
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile

class NarArchiveReader(private val limits: ImportLimits = ImportLimits()) {
    fun inspect(archive: File, checkCancelled: () -> Unit = {}): ImportPackage = open(archive).use { zip ->
        checkCancelled()
        val items = scan(zip, checkCancelled, validateContent = false)
        val candidates = items.filter { !it.third &&
            (it.second.equals("install.txt", ignoreCase = true) ||
                it.second.count { c -> c == '/' } == 1 && it.second.substringAfter('/').equals("install.txt", ignoreCase = true)) }
        val selected = candidates.firstOrNull { it.second.equals("install.txt", ignoreCase = true) }
            ?: candidates.singleOrNull()
            ?: throw IllegalArgumentException("Missing or ambiguous install.txt")
        val root = selected.second.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        require(items.all { it.second.startsWith(root) ||
            (it.third && root.isNotEmpty() && it.second == root.removeSuffix("/")) }) {
            "Archive contains payload outside package root"
        }
        scan(zip, checkCancelled, validateContent = true, descriptorRoot = root)
        val bytes = readEntry(zip, selected.first, INSTALL_DESCRIPTOR_LIMIT.toLong(), checkCancelled)
        val values = try { DescriptorReader.read(bytes) } catch (e: Exception) {
            throw IllegalArgumentException("Invalid install.txt encoding", e)
        }
        require(values["type"]?.equals("ghost", ignoreCase = true) == true) { "Package is not a ghost" }
        val id = values["directory"] ?: throw IllegalArgumentException("Missing directory")
        require(id.isNotEmpty() && id == id.trim() && !id.startsWith('.') && !id.endsWith('.') &&
            '/' !in id && '\\' !in id &&
            ArchivePath.normalize(id) == id) { "Invalid directory" }
        ImportPackage(id, root)
    }

    fun extract(archive: File, metadata: ImportPackage, destination: File, checkCancelled: () -> Unit) {
        open(archive).use { zip ->
            val items = scan(zip, checkCancelled, validateContent = false, descriptorRoot = metadata.rootPrefix)
            require(items.all { it.second.startsWith(metadata.rootPrefix) ||
                (it.third && metadata.rootPrefix.isNotEmpty() && it.second == metadata.rootPrefix.removeSuffix("/")) }) {
                "Archive contains payload outside package root"
            }
            require(destination.isDirectory && destination.list()?.isEmpty() == true) { "Destination must be empty" }
            var total = 0L
            items.forEach { (entry, path, directory) ->
                checkCancelled()
                if (path == metadata.rootPrefix.removeSuffix("/")) return@forEach
                val relative = path.removePrefix(metadata.rootPrefix)
                if (relative.isEmpty()) return@forEach
                val target = File(destination, relative)
                if (directory) {
                    require(target.mkdirs() || target.isDirectory) { "Cannot create directory" }
                } else {
                    val parent = requireNotNull(target.parentFile)
                    require(parent.mkdirs() || parent.isDirectory) { "Cannot create parent" }
                    require(target.createNewFile()) { "Destination conflict" }
                    target.outputStream().use { output ->
                        val descriptor = isGhostDescriptor(path, metadata.rootPrefix)
                        val byteLimit = if (descriptor) minOf(limits.fileBytes, limits.ghostDescriptorBytes.toLong())
                            else limits.fileBytes
                        val limitMessage = if (descriptor && limits.ghostDescriptorBytes <= limits.fileBytes)
                            "Ghost descriptor byte limit exceeded" else "File byte limit exceeded"
                        streamEntry(zip, entry, byteLimit, checkCancelled, limitMessage) { data, count ->
                            require(total <= Long.MAX_VALUE - count) { "Expanded byte overflow" }
                            require(total <= limits.expandedBytes - count) { "Expanded byte limit exceeded" }
                            total += count
                            output.write(data, 0, count)
                        }
                    }
                }
            }
        }
    }

    private fun open(archive: File): ZipFile {
        require(archive.length() <= limits.archiveBytes) { "Archive byte limit exceeded" }
        CentralDirectoryEntryLimit.check(archive, limits.entries)
        return ZipFile.builder().setFile(archive).setCharset("windows-31j")
            .setUseUnicodeExtraFields(true).setMaxNumberOfDisks(1).get()
    }

    private fun scan(zip: ZipFile, checkCancelled: () -> Unit, validateContent: Boolean,
                     descriptorRoot: String? = null): List<Triple<ZipArchiveEntry, String, Boolean>> {
        val items = mutableListOf<Triple<ZipArchiveEntry, String, Boolean>>()
        val files = mutableSetOf<String>()
        val directories = mutableSetOf<String>()
        val explicitDirectories = mutableSetOf<String>()
        val directorySpelling = mutableMapOf<String, String>()
        var total = 0L
        var scannedEntries = 0
        var rootDirectorySeen = false
        val entries = zip.entries
        while (entries.hasMoreElements()) {
            checkCancelled()
            val entry = entries.nextElement()
            require(scannedEntries < limits.entries) { "Entry count limit exceeded" }
            scannedEntries++
            val normalizedName = entry.name.replace('\\', '/')
            val unixType = entry.unixMode and UnixStat.FILE_TYPE_FLAG
            val directory = normalizedName.endsWith('/') || unixType == UnixStat.DIR_FLAG
            require(entry.diskNumberStart == 0L && entry.isStreamContiguous) { "Split archive unsupported" }
            require(entry.method == ZipEntry.STORED || entry.method == ZipEntry.DEFLATED) { "ZIP method unsupported" }
            require(!entry.generalPurposeBit.usesEncryption()) { "Encrypted ZIP unsupported" }
            require(unixType == 0 || unixType == (if (directory) UnixStat.DIR_FLAG else UnixStat.FILE_FLAG)) {
                "ZIP entry is not a regular file or directory"
            }
            require(entry.size >= 0 && entry.crc >= 0 && entry.compressedSize >= 0) { "Unknown ZIP metadata" }
            // Commons exposes the corpus's raw "\\" root name as "/".
            if (entry.name == "\\" || entry.name == "/") {
                require(directory && (unixType == UnixStat.DIR_FLAG || entry.externalAttributes and 0x10L != 0L) &&
                    !rootDirectorySeen && entry.size == 0L) { "Unsafe root directory entry" }
                rootDirectorySeen = true
                streamEntry(zip, entry, 0, checkCancelled) { _, _ -> }
                continue
            }
            val path = ArchivePath.normalize(if (normalizedName.endsWith('/')) normalizedName.dropLast(1) else normalizedName)
            val folded = path.lowercase(Locale.ROOT)
            val components = folded.split('/')
            val originalComponents = path.split('/')
            components.dropLast(1).indices.forEach { index ->
                val parent = components.take(index + 1).joinToString("/")
                val spelling = originalComponents.take(index + 1).joinToString("/")
                require(directorySpelling.putIfAbsent(parent, spelling) in listOf(null, spelling)) {
                    "Directory case collision"
                }
                require(parent !in files) { "File and directory conflict" }
                directories.add(parent)
            }
            if (directory) {
                require(directorySpelling.putIfAbsent(folded, path) in listOf(null, path)) {
                    "Directory case collision"
                }
                require(folded !in files && explicitDirectories.add(folded)) { "Directory conflict" }
                directories.add(folded)
            } else {
                require(folded !in directories && files.add(folded)) { "Duplicate file or directory conflict" }
            }
            if (!directory) {
                require(entry.size <= limits.fileBytes) { "File byte limit exceeded" }
                val descriptor = descriptorRoot != null && isGhostDescriptor(path, descriptorRoot)
                if (descriptor) require(entry.size <= limits.ghostDescriptorBytes) {
                    "Ghost descriptor byte limit exceeded"
                }
                val byteLimit = if (descriptor) minOf(limits.fileBytes, limits.ghostDescriptorBytes.toLong())
                    else limits.fileBytes
                val limitMessage = if (descriptor && limits.ghostDescriptorBytes <= limits.fileBytes)
                    "Ghost descriptor byte limit exceeded" else "File byte limit exceeded"
                if (validateContent) streamEntry(zip, entry, byteLimit, checkCancelled, limitMessage) { _, count ->
                    require(total <= Long.MAX_VALUE - count) { "Expanded byte overflow" }
                    require(total <= limits.expandedBytes - count) { "Expanded byte limit exceeded" }
                    total += count
                }
            }
            items.add(Triple(entry, path, directory))
        }
        return items
    }

    private fun readEntry(zip: ZipFile, entry: ZipArchiveEntry, limit: Long, checkCancelled: () -> Unit): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        streamEntry(zip, entry, limit, checkCancelled) { data, count -> output.write(data, 0, count) }
        return output.toByteArray()
    }

    private fun streamEntry(zip: ZipFile, entry: ZipArchiveEntry, limit: Long,
                            checkCancelled: () -> Unit, limitMessage: String = "File byte limit exceeded",
                            consume: (ByteArray, Int) -> Unit): Long {
        val crc = CRC32()
        var count = 0L
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                checkCancelled()
                val n = input.read(buffer)
                if (n < 0) break
                require(count <= limit - n) { limitMessage }
                count += n
                crc.update(buffer, 0, n)
                consume(buffer, n)
            }
        }
        require(count == entry.size) { "ZIP entry size mismatch" }
        require(crc.value == entry.crc) { "ZIP entry CRC mismatch" }
        return count
    }

    private fun isGhostDescriptor(path: String, root: String): Boolean =
        path.equals("${root}ghost/master/descript.txt", ignoreCase = true)
}
