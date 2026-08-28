package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.surface.SurfaceDiagnosticReason
import com.cattailsw.nanidroid.surface.SurfaceParseDiagnostic
import com.cattailsw.nanidroid.surface.SurfaceParseSeed
import com.cattailsw.nanidroid.surface.SurfaceParser
import com.cattailsw.nanidroid.surface.SurfaceSourceDecoder
import com.cattailsw.nanidroid.surface.SurfaceSourceInput
import com.cattailsw.nanidroid.surface.CollisionGeometryParser
import com.cattailsw.nanidroid.surface.CollisionShape
import com.cattailsw.nanidroid.surface.ParsedCollision
import com.cattailsw.nanidroid.surface.ParsedSurfaceEntry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.IdentityHashMap

/** Reads every surface source into the mutable builder used before immutable publication. */
class SurfaceReader {
    @JvmField
    var error = false

    val diagnostics: List<SurfaceParseDiagnostic>
        get() = mutableDiagnostics.toList()

    private var rootPath: String? = null
    private var manager: SurfaceManager? = null
    private var parseTime = 0L
    private var transparencyPolicy = SurfaceTransparencyPolicy.LEGACY_COLOR_KEY
    private val mutableDiagnostics = mutableListOf<SurfaceParseDiagnostic>()
    private val diagnosedPngPaths = mutableSetOf<String>()
    private val parsedCollisionCache = IdentityHashMap<ParsedSurfaceEntry, ParsedCollision>()
    private val diagnosedCollisionEntries = java.util.Collections.newSetFromMap(IdentityHashMap<ParsedSurfaceEntry, Boolean>())

    constructor(manager: SurfaceManager) {
        this.manager = manager
    }

    constructor()

    constructor(
        manager: SurfaceManager,
        shellRoot: String,
        descriptorPath: String,
        transparencyPolicy: SurfaceTransparencyPolicy = SurfaceTransparencyPolicy.LEGACY_COLOR_KEY,
    ) {
        rootPath = shellRoot
        this.manager = manager
        this.transparencyPolicy = transparencyPolicy
        loadShell(File(shellRoot))
    }

    constructor(file: File) {
        rootPath = file.parent
    }

    private fun loadShell(root: File) {
        val started = LegacyPlatform.uptimeMillis()
        val catalog = manager ?: return
        val rootDirectory = when {
            root.isDirectory -> root
            root.parentFile?.isDirectory == true -> root.parentFile
            else -> {
                error = true
                return
            }
        }

        val pngIds = linkedSetOf<Int>()
        val pngById = linkedMapOf<Int, File>()
        discoverPngFiles(rootDirectory).forEach { file ->
            val id = PNG_NAME.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            if (id !in pngById && pngById.size >= MAX_PNG_SURFACES) {
                addDiagnostic(
                    SurfaceParseDiagnostic(
                        file.name,
                        1,
                        file.absolutePath,
                        SurfaceDiagnosticReason.DECODE,
                    ),
                )
                return@forEach
            }
            pngIds += id
            pngById[id] = file
        }
        pngById.forEach { (id, file) ->
            catalog.addSurface(
                id.toString(),
                pngSurface(rootDirectory, file, id),
            )
        }

        val decodeSession = SurfaceSourceDecoder.newSession()
        discoverSourceFiles(rootDirectory).forEach { file ->
            if (!decodeSession.begin(file.name)) return@forEach
            if (file.length() > SurfaceSourceDecoder.MAX_SOURCE_BYTES) {
                decodeSession.rejectOversizedUnopened(file.name)
                return@forEach
            }
            val read = readBounded(file, decodeSession.maxReadBytes())
            if (read.failed) {
                decodeSession.rejectStarted(file.name, read.bytes.size)
            } else {
                decodeSession.decodeStarted(SurfaceSourceInput(file.name, read.bytes))
            }
        }
        val decoded = decodeSession.result()
        decoded.diagnostics.forEach(::addDiagnostic)
        val parsed = SurfaceParser().parse(decoded.files, SurfaceParseSeed(pngIds))
        parsed.diagnostics.forEach(::addDiagnostic)

        parsed.surfaces.forEach { (id, entries) ->
            val surface = parsedSurface(rootDirectory, pngById[id], id, entries)
            catalog.addParsedSurface(id.toString(), surface, entries)
        }

        parseTime = LegacyPlatform.uptimeMillis() - started
        LegacyPlatform.debug(TAG, "parse time:${parseTime}ms")
    }

    private fun discoverSourceFiles(root: File): List<File> =
        root.listFiles().orEmpty().filter { file ->
            file.isFile && SOURCE_NAME.matches(file.name)
        }.sortedWith(compareBy<File> { it.name.lowercase(Locale.ROOT) }.thenBy { it.name })

    private fun discoverPngFiles(root: File): List<File> =
        root.listFiles().orEmpty().filter { file ->
            file.isFile && PNG_NAME.matches(file.name)
        }.sortedWith(compareBy<File> { it.name.lowercase(Locale.ROOT) }.thenBy { it.name })

    private fun addDiagnostic(diagnostic: SurfaceParseDiagnostic) {
        if (mutableDiagnostics.size < MAX_DIAGNOSTICS) mutableDiagnostics += diagnostic
    }

    private fun readBounded(file: File, limit: Int): BoundedRead {
        val output = ByteArrayOutputStream(minOf(READ_BUFFER_SIZE, limit))
        val buffer = ByteArray(READ_BUFFER_SIZE)
        return try {
            FileInputStream(file).use { input ->
                while (output.size() < limit) {
                    val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            BoundedRead(output.toByteArray(), failed = false)
        } catch (_: IOException) {
            BoundedRead(output.toByteArray(), failed = true)
        }
    }

    private fun pngSurface(root: File, file: File, id: Int): ShellSurface =
        materializeSurface(root, file, id, null)

    private fun parsedSurface(
        root: File,
        png: File?,
        id: Int,
        entries: List<ParsedSurfaceEntry>,
    ): ShellSurface {
        return materializeSurface(root, png, id, entries).also { surface ->
            surface.setCanonicalCollisions(materializeCollisions(entries))
        }
    }

    private fun materializeSurface(
        root: File,
        png: File?,
        id: Int,
        entries: List<ParsedSurfaceEntry>?,
    ): ShellSurface {
        val path = withSeparator(root)
        val selfName = png?.name
        val surface = try {
            val loaded = if (entries == null) ShellSurface(path, selfName, id, null)
            else ShellSurface(path, selfName, id, entries, probeBitmap = true, preserveProvenance = Unit)
            if (png != null && (loaded.origW <= 0 || loaded.origH <= 0)) {
                addPngDiagnostic(png)
            }
            loaded
        } catch (_: Exception) {
            if (png != null) addPngDiagnostic(png)
            if (entries == null) ShellSurface(path, selfName, id, null, probeBitmap = false)
            else ShellSurface(path, selfName, id, entries, probeBitmap = false, preserveProvenance = Unit)
        }
        if (png != null) surface.selfFilename = png.absolutePath
        surface.bp2 = File(root, "surface%04d.png".format(id)).absolutePath
        surface.transparencyPolicy = transparencyPolicy
        return surface
    }

    private fun addPngDiagnostic(file: File) {
        if (!diagnosedPngPaths.add(file.absolutePath)) return
        addDiagnostic(
            SurfaceParseDiagnostic(
                file.name,
                1,
                file.absolutePath,
                SurfaceDiagnosticReason.DECODE,
            ),
        )
    }

    private fun materializeCollisions(entries: List<ParsedSurfaceEntry>): List<SurfaceCollision> {
        val firstById = linkedMapOf<Int, SurfaceCollision>()
        var polygonVertexWork = 0
        entries.forEach { entry ->
            when (val parsed = parsedCollisionCache.getOrPut(entry) {
                CollisionGeometryParser.parse(entry.source.text, entry.authoredOrder.toInt())
            }) {
                ParsedCollision.NotCollision -> Unit
                is ParsedCollision.Invalid -> diagnoseCollision(entry, parsed.reason)
                is ParsedCollision.Valid -> {
                    val vertexCount = (parsed.collision.shape as? CollisionShape.Polygon)?.points?.size ?: 0
                    if (firstById.containsKey(parsed.collision.id) ||
                        firstById.size >= MAX_COLLISIONS_PER_SURFACE ||
                        polygonVertexWork + vertexCount > MAX_POLYGON_VERTICES_PER_SURFACE
                    ) {
                        diagnoseCollision(entry, SurfaceDiagnosticReason.ENTRY)
                    } else {
                        firstById[parsed.collision.id] = parsed.collision
                        polygonVertexWork += vertexCount
                    }
                }
            }
        }
        return orderCollisions(entries, firstById.values)
    }

    private fun orderCollisions(
        entries: List<ParsedSurfaceEntry>,
        accepted: Collection<SurfaceCollision>,
    ): List<SurfaceCollision> {
        val collisions = accepted.associateBy { it.authoredOrder }
        return entries
            .groupBy { it.source.file }
            .values
            .flatMap { fileEntries ->
                val fileCollisions = fileEntries.mapNotNull { collisions[it.authoredOrder.toInt()] }
                when (fileEntries.first().fileDirectives.collisionSort) {
                    com.cattailsw.nanidroid.surface.CollisionSort.ASCEND -> fileCollisions.sortedBy { it.id }
                    com.cattailsw.nanidroid.surface.CollisionSort.DESCEND -> fileCollisions.sortedByDescending { it.id }
                    com.cattailsw.nanidroid.surface.CollisionSort.NONE -> fileCollisions
                }
            }
    }

    private fun diagnoseCollision(entry: ParsedSurfaceEntry, reason: SurfaceDiagnosticReason) {
        if (!diagnosedCollisionEntries.add(entry)) return
        addDiagnostic(
            SurfaceParseDiagnostic(entry.source.file, entry.source.number, entry.source.text, reason),
        )
    }

    private fun withSeparator(root: File): String = root.absolutePath + File.separator

    private companion object {
        const val TAG = "SurfaceReader"
        const val MAX_DIAGNOSTICS = 256
        const val MAX_PNG_SURFACES = 4_096
        const val MAX_COLLISIONS_PER_SURFACE = 256
        /** Limits a pointer miss to sixteen maximum-size polygons. */
        const val MAX_POLYGON_VERTICES_PER_SURFACE = 4_096
        const val READ_BUFFER_SIZE = 8_192
        val SOURCE_NAME = Regex("^surfaces.*\\.txt$", RegexOption.IGNORE_CASE)
        val PNG_NAME = Regex("^surface(\\d+)\\.png$", RegexOption.IGNORE_CASE)
    }

    private data class BoundedRead(val bytes: ByteArray, val failed: Boolean)
}
