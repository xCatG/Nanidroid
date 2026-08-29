package com.cattailsw.nanidroid

import android.content.Context
import com.cattailsw.nanidroid.surface.CollisionShape
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale

internal data class InstalledGhostMetadata(
    val id: String,
    val canonicalRoot: File,
    val name: String?,
    val sakuraName: String?,
    val readme: File,
)

internal enum class GhostEngine { Satori, Yaya, Kawari, Nanidroid, Unsupported }

internal interface NanidroidContentPresence {
    val contentFilePresent: Boolean
}

private class FrozenNanidroidContent(
    private val contentValues: Map<String, String>,
    override val contentFilePresent: Boolean,
) : NanidroidContentPresence, Map<String, String> by contentValues

internal class SurfaceCatalog private constructor(
    private val definitions: Map<String, SurfaceDefinition>,
) {
    val keys: Set<String> get() = definitions.keys.toSet()

    fun definition(id: String): SurfaceDefinition? = definitions[id]

    fun sakuraDefinition(id: String): SurfaceDefinition? = definitions[id] ?: definitions["0"]

    fun keroDefinition(id: String): SurfaceDefinition? = definitions[id] ?: definitions["10"]

    internal fun frozenCopy(): SurfaceCatalog = freeze(definitions)

    internal fun definitionsForTesting(): Map<String, SurfaceDefinition> = definitions

    override fun equals(other: Any?): Boolean =
        other is SurfaceCatalog && definitions == other.definitions

    override fun hashCode(): Int = definitions.hashCode()

    companion object {
        fun freeze(source: Map<String, SurfaceDefinition>): SurfaceCatalog = SurfaceCatalog(
            Collections.unmodifiableMap(source.mapValues { (_, value) -> value.deepFrozenCopy() }.toMap()),
        )
    }
}

private fun SurfaceDefinition.deepFrozenCopy(): SurfaceDefinition = copy(
    collisions = Collections.unmodifiableList(collisions.map { collision ->
        val frozenShape = when (val shape = collision.shape) {
            is CollisionShape.Polygon -> CollisionShape.Polygon(
                Collections.unmodifiableList(shape.points.toList()),
            )
            else -> shape
        }
        collision.copy(shape = frozenShape)
    }),
    animations = Collections.unmodifiableList(animations.map { animation ->
        animation.copy(
            frames = Collections.unmodifiableList(animation.frames.toList()),
            alternativeAnimationIds = Collections.unmodifiableList(
                animation.alternativeAnimationIds.toList(),
            ),
        )
    }),
    elements = Collections.unmodifiableList(elements.toList()),
)

internal data class PreparedGhost(
    val operationId: Long,
    val id: String,
    val canonicalRoot: File,
    val name: String?,
    val shellName: String?,
    val crafterName: String?,
    val sakuraName: String?,
    val keroName: String?,
    val surfaces: SurfaceCatalog,
    val ghostDescriptor: Map<String, String>,
    val shellDescriptor: Map<String, String>?,
    val engine: GhostEngine,
    val nanidroidContent: Map<String, String>,
)

/** Reads installed ghost files into immutable data without constructing an active session. */
internal class GhostPreparer private constructor(
    @Suppress("UNUSED_PARAMETER") context: Context?,
    private val scriptedPreparation: ((Long, String, File) -> PreparedGhost)?,
) {
    constructor(context: Context?) : this(context, null)

    internal constructor(
        scriptedPreparation: (Long, String, File) -> PreparedGhost,
    ) : this(null, scriptedPreparation)

    fun prepare(operationId: Long, ghostId: String, canonicalRoot: File): PreparedGhost {
        scriptedPreparation?.let { return it(operationId, ghostId, canonicalRoot) }
        val root = canonicalRoot.canonicalFile
        require(root.isDirectory) { "Ghost root is not a directory: ${root.path}" }
        require(ghostId == root.name) { "Ghost ID $ghostId does not match root ${root.name}" }

        val ghostMaster = File(root, "ghost/master")
        val shellMaster = File(root, "shell/master")
        val ghostDescriptor = readRequiredDescriptor(File(ghostMaster, "descript.txt"))
        val shellDescriptor = readOptionalDescriptor(File(shellMaster, "descript.txt"))
        val surfaceManager = SurfaceManager(ghostId)
        SurfaceReader(
            surfaceManager,
            shellMaster.path,
            File(shellMaster, "surfaces.txt").path,
            SurfaceTransparencyPolicy.fromShellDescriptor(shellDescriptor),
        )
        val definitions = surfaceManager.getSurfaceKeys().associateWith { id ->
            requireNotNull(surfaceManager.getSurface(id)).toSurfaceDefinition()
        }
        val engine = selectEngine(ghostDescriptor, ghostMaster)

        return PreparedGhost(
            operationId = operationId,
            id = ghostId,
            canonicalRoot = root,
            name = ghostDescriptor["name"],
            shellName = shellDescriptor?.get("name") ?: if (shellDescriptor == null) "master" else null,
            crafterName = ghostDescriptor["craftmanw"] ?: ghostDescriptor["craftman"],
            sakuraName = ghostDescriptor["sakura.name"],
            keroName = ghostDescriptor["kero.name"],
            surfaces = SurfaceCatalog.freeze(definitions),
            ghostDescriptor = frozenMap(ghostDescriptor),
            shellDescriptor = shellDescriptor?.let(::frozenMap),
            engine = engine,
            nanidroidContent = if (engine == GhostEngine.Nanidroid) {
                readNanidroidContent(ghostMaster)
            } else {
                emptyMap()
            },
        )
    }

    private fun readRequiredDescriptor(file: File): Map<String, String> =
        DescReader(file.path).parse()

    private fun readOptionalDescriptor(file: File): Map<String, String>? =
        runCatching { readRequiredDescriptor(file) }.getOrNull()

    private fun selectEngine(descriptor: Map<String, String>, ghostMaster: File): GhostEngine =
        when (descriptor["shiori"]) {
            "Nanidroid" -> GhostEngine.Nanidroid
            "satori.dll" -> GhostEngine.Satori
            "yaya.dll" -> GhostEngine.Yaya
            null, "shiori.dll" -> if (File(ghostMaster, "kawarirc.kis").isFile) {
                GhostEngine.Kawari
            } else {
                GhostEngine.Unsupported
            }
            else -> GhostEngine.Unsupported
        }

    private fun readNanidroidContent(ghostMaster: File): Map<String, String> {
        var localeDirectory = File(ghostMaster, Locale.getDefault().language)
        if (!localeDirectory.exists()) localeDirectory = File(ghostMaster, "ja")
        val content = File(localeDirectory, "content.txt")
        if (!content.isFile) return frozenNanidroidContent(emptyMap(), contentFilePresent = false)
        val values = linkedMapOf<String, String>()
        content.forEachLine(StandardCharsets.UTF_8) { line ->
            if (line.startsWith(";")) return@forEachLine
            val separator = line.indexOf(',')
            if (separator != -1) values[line.substring(0, separator)] = line.substring(separator + 1)
        }
        return frozenNanidroidContent(values, contentFilePresent = true)
    }

    private fun frozenNanidroidContent(
        source: Map<String, String>,
        contentFilePresent: Boolean,
    ): Map<String, String> = FrozenNanidroidContent(
        Collections.unmodifiableMap(source.toMap()),
        contentFilePresent,
    )
}

internal object InstalledGhostCatalog {
    fun scan(context: Context): List<InstalledGhostMetadata> =
        File(context.getExternalFilesDir(null), "ghost").listFiles().orEmpty()
            .filter(File::isDirectory)
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .mapNotNull(::readMetadata)

    private fun readMetadata(root: File): InstalledGhostMetadata? = runCatching {
        val canonicalRoot = root.canonicalFile
        val descriptor = DescReader(File(canonicalRoot, "ghost/master/descript.txt").path).parse()
        InstalledGhostMetadata(
            id = canonicalRoot.name,
            canonicalRoot = canonicalRoot,
            name = descriptor["name"],
            sakuraName = descriptor["sakura.name"],
            readme = File(canonicalRoot, "readme.txt"),
        )
    }.getOrNull()
}

private fun frozenMap(source: Map<String, String>): Map<String, String> =
    Collections.unmodifiableMap(LinkedHashMap(source))
