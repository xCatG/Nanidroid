package com.cattailsw.nanidroid.ghost

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/** Reads already installed ghosts from the application's private ghost directory. */
class InstalledGhostRepository(
    private val ghostRoot: File,
    private val descriptorByteLimit: Int = GHOST_DESCRIPTOR_LIMIT,
) {
    fun list(): List<GhostDescriptor> {
        if (!ghostRoot.isDirectory || Files.isSymbolicLink(ghostRoot.toPath())) return emptyList()
        return ghostRoot.listFiles().orEmpty()
            .sortedBy { it.name }
            .mapNotNull { candidate -> runCatching { validate(candidate.name) }.getOrNull() }
    }

    fun validate(directoryId: String): GhostDescriptor {
        require(descriptorByteLimit in 1 until Int.MAX_VALUE) { "Invalid ghost descriptor byte limit" }
        require(directoryId.isNotBlank() && directoryId != "." && directoryId != ".." &&
            directoryId.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }) {
            "Unsafe ghost directory ID"
        }
        require(ghostRoot.isDirectory && !Files.isSymbolicLink(ghostRoot.toPath())) {
            "Private ghost root is unavailable"
        }
        val root = ghostRoot.canonicalFile
        val directory = safeChild(root, directoryId)
        require(directory.isDirectory) { "Ghost directory missing" }
        val master = safeChild(directory, "ghost", "master")
        val descriptorFile = safeChild(master, "descript.txt")
        val shell = safeChild(directory, "shell", "master")
        require(master.isDirectory && descriptorFile.isFile && shell.isDirectory) {
            "Ghost requires a master descriptor and shell"
        }
        require(descriptorFile.length() <= descriptorByteLimit) { "Ghost descriptor byte limit exceeded" }
        val fields = DescriptorReader.read(
            ShellFiles.readAtMost(descriptorFile, descriptorByteLimit, "Ghost descriptor byte limit exceeded"))
        require(fields.isNotEmpty()) { "Ghost descriptor is malformed" }
        val surfaces = shell.listFiles().orEmpty().sortedBy { it.name }
            .mapNotNull { file ->
                val id = SurfaceFileName.id(file.name)
                if (id != null && safeChild(shell, file.name).isFile) id to file else null
            }.toMap()
        val readme = safeChild(directory, "readme.txt").takeIf { it.isFile }
        return GhostDescriptor(
            directoryId = directoryId,
            displayName = fields["name"]?.takeIf { it.isNotBlank() } ?: directoryId,
            sakuraName = fields["sakura.name"].orEmpty(),
            keroName = fields["kero.name"].orEmpty(),
            masterPath = master,
            surfaces = surfaces,
            engineDeclaration = fields["shiori"],
            readmePath = readme,
            sakuraName2 = DescriptorReader.sakuraName2(fields),
        )
    }

    private fun safeChild(parent: File, vararg names: String): File {
        var child = parent
        for (name in names) {
            child = File(child, name)
            require(!Files.isSymbolicLink(child.toPath())) { "Ghost contains a symbolic link" }
            require(child.canonicalFile.toPath().startsWith(ghostRoot.canonicalFile.toPath())) {
                "Ghost path escapes private root"
            }
        }
        return child
    }

}
