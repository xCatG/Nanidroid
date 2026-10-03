package com.cattailsw.nanidroid.ghost

import java.io.File
import java.nio.charset.Charset

data class SurfaceLayer(val surfaceId: Int, val method: String, val x: Int, val y: Int)

/** Element and animation methods drawn as layers over a base image. */
val LAYER_METHODS = setOf("overlay", "overlayfast", "interpolate")

class SurfaceDefinitions private constructor(
    private val byDefinition: Map<Int, SurfaceDefinition>,
) {
    fun definition(id: Int): SurfaceDefinition? = byDefinition[id]
    fun ids(): Set<Int> = byDefinition.keys

    companion object {
        private val selectorPrefix = Regex("^surface\\s*", RegexOption.IGNORE_CASE)
        private val selectorPart = Regex("(!?)\\s*(?:surface\\s*)?(\\d+)(?:\\s*-\\s*(\\d+))?", RegexOption.IGNORE_CASE)
        private val interval = Regex("(?:animation)?(\\d+)(?:\\.)?interval", RegexOption.IGNORE_CASE)
        private val pattern = Regex("(?:animation)?(\\d+)(?:\\.)?pattern(\\d+)", RegexOption.IGNORE_CASE)
        private val element = Regex("element(\\d+)", RegexOption.IGNORE_CASE)
        private val collision = Regex("collision(\\d+)", RegexOption.IGNORE_CASE)
        private val charsetLine = Regex("(?im)^\\s*charset\\s*,\\s*([^\\s,;]+)")
        private val waitValue = Regex("\\d+(?:\\s*-\\s*\\d+)?")
        private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        private val elementMethods = LAYER_METHODS + "base"
        private val patternMethods = elementMethods + "move"

        private fun surfaceIds(header: String): List<Int> {
            val prefix = selectorPrefix.find(header) ?: return emptyList()
            val included = linkedSetOf<Int>()
            val excluded = mutableSetOf<Int>()
            for (part in header.substring(prefix.range.last + 1).split(',')) {
                val match = selectorPart.matchEntire(part.trim()) ?: return emptyList()
                val first = match.groupValues[2].toIntOrNull() ?: return emptyList()
                val last = match.groupValues[3].takeIf(String::isNotEmpty)?.toIntOrNull() ?: first
                if (last < first || last.toLong() - first > 4096) return emptyList()
                val target = if (match.groupValues[1] == "!") excluded else included
                for (id in first..last) {
                    target += id
                    if (included.size + excluded.size > 4096) return emptyList()
                }
            }
            return included.filterNot { it in excluded }
        }

        fun read(file: File): SurfaceDefinitions {
            val raw = ShellFiles.readAtMost(file, 4 * 1024 * 1024, "surfaces.txt exceeds 4 MiB")
            val hasBom = raw.size >= 3 && raw.copyOfRange(0, 3).contentEquals(utf8Bom)
            val bytes = if (hasBom) raw.copyOfRange(3, raw.size) else raw
            val fallback = if (hasBom) Charsets.UTF_8 else Charset.forName("Shift_JIS")
            val declared = charsetLine.find(bytes.toString(Charsets.ISO_8859_1))?.groupValues?.get(1)
            val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: fallback
            return parse(bytes.toString(charset))
        }

        fun parse(text: String): SurfaceDefinitions {
            require(text.length <= 4 * 1024 * 1024) { "surfaces.txt exceeds parsing limit" }
            val definitions = mutableMapOf<Int, SurfaceDefinition>()
            var selected = emptyList<Int>()
            var inBlock = false
            var pending = ""
            val intervals = mutableMapOf<Int, String>()
            val authoredElements = mutableMapOf<Int, SurfaceElement>()
            val authoredCollisions = mutableListOf<SurfaceCollision>()
            val authoredPatterns = mutableMapOf<Int, MutableMap<Int, SurfacePattern>>()
            var records = 0
            fun finish() {
                val animations = (intervals.keys + authoredPatterns.keys).distinct().sorted().map { id ->
                    SurfaceAnimation(id, intervals[id].orEmpty(), authoredPatterns[id].orEmpty().toSortedMap().values.toList())
                }
                selected.forEach { id ->
                    val old = definitions[id]
                    definitions[id] = SurfaceDefinition(id,
                        (old?.elements.orEmpty() + authoredElements.values).distinctBy { it.id }.sortedBy { it.id },
                        old?.collisions.orEmpty() + authoredCollisions,
                        (old?.animations.orEmpty() + animations).distinctBy { it.id }.sortedBy { it.id })
                }
                intervals.clear()
                authoredElements.clear()
                authoredCollisions.clear()
                authoredPatterns.clear()
            }
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("//") || line.startsWith(";")) continue
                if (line == "{" || (!inBlock && line.endsWith("{"))) {
                    selected = surfaceIds(if (line == "{") pending else line.dropLast(1).trim())
                    if (definitions.size + selected.count { it !in definitions } > 4096) selected = emptyList()
                    inBlock = selected.isNotEmpty()
                    pending = ""
                    continue
                }
                if (line == "}") {
                    if (inBlock) finish()
                    inBlock = false
                    continue
                }
                if (!inBlock) {
                    pending = line
                    continue
                }
                if (records + selected.size > 65_536) break
                records += selected.size
                val fields = line.split(',').map(String::trim)
                val key = fields[0]
                element.matchEntire(key)?.let { match ->
                    val id = match.groupValues[1].toIntOrNull()
                    val method = fields.getOrNull(1)?.lowercase()
                    val fileName = fields.getOrNull(2).orEmpty()
                    val x = fields.getOrNull(3)?.toIntOrNull()
                    val y = fields.getOrNull(4)?.toIntOrNull()
                    if (id != null && method in elementMethods &&
                        fileName.isNotBlank() && x != null && y != null)
                        authoredElements[id] = SurfaceElement(id, method!!, fileName, x, y)
                    continue
                }
                collision.matchEntire(key)?.let { match ->
                    val id = match.groupValues[1].toIntOrNull()
                    val bounds = (1..4).map { fields.getOrNull(it)?.toIntOrNull() }
                    val name = fields.drop(5).joinToString(",")
                    if (id != null && bounds.all { it != null } && name.isNotBlank())
                        authoredCollisions += SurfaceCollision(id, bounds[0]!!, bounds[1]!!, bounds[2]!!, bounds[3]!!, name)
                    continue
                }
                interval.matchEntire(key)?.let { match ->
                    val id = match.groupValues[1].toIntOrNull()
                    if (id != null) intervals[id] = fields.getOrNull(1)?.lowercase().orEmpty()
                    continue
                }
                val match = pattern.matchEntire(key) ?: continue
                val id = match.groupValues[1].toIntOrNull() ?: continue
                val frame = match.groupValues[2].toIntOrNull() ?: continue
                val modern = key.startsWith("animation", ignoreCase = true)
                val surfaceId = fields.getOrNull(if (modern) 2 else 1)?.toIntOrNull()
                val method = fields.getOrNull(if (modern) 1 else 3)?.lowercase()
                val wait = fields.getOrNull(if (modern) 3 else 2)
                val x = fields.getOrNull(4)?.toIntOrNull()
                val y = fields.getOrNull(5)?.toIntOrNull()
                val parsedWait = waitValue.matchEntire(wait.orEmpty())
                val multiplier = if (modern) 1L else 10L
                val min = parsedWait?.groupValues?.get(0)?.substringBefore('-')?.trim()?.toLongOrNull()
                    ?.let { runCatching { Math.multiplyExact(it, multiplier) }.getOrNull() }
                val max = parsedWait?.groupValues?.get(0)?.substringAfter('-', "")?.trim()
                    ?.takeIf { it.isNotEmpty() }?.toLongOrNull()
                    ?.let { runCatching { Math.multiplyExact(it, multiplier) }.getOrNull() } ?: min
                val parsed = if (method == "alternativestart" ||
                    fields.getOrNull(1)?.equals("alternativestart", true) == true) {
                    val targetStart = if (!modern && method == "alternativestart") 4 else 2
                    val rawTargets = fields.drop(targetStart).joinToString(",")
                    val contents = when {
                        rawTargets.startsWith('[') && rawTargets.endsWith(']') -> rawTargets.drop(1).dropLast(1)
                        rawTargets.startsWith('(') && rawTargets.endsWith(')') -> rawTargets.drop(1).dropLast(1)
                        else -> rawTargets
                    }
                    // Authored lists vary in separators; keep every valid ID rather than the pattern.
                    val targets = contents.split('.', ',', ' ', ':', '\t')
                        .mapNotNull { it.trim().toIntOrNull() }
                    if (targets.isNotEmpty()) SurfacePattern.Alternative(targets) else null
                } else if (surfaceId != null && surfaceId >= -1 && method in patternMethods &&
                    min != null && max != null && max >= min && max <= Int.MAX_VALUE && x != null && y != null) {
                    SurfacePattern.Image(surfaceId, method!!, min, max, x, y)
                } else null
                if (parsed != null) authoredPatterns.getOrPut(id) { mutableMapOf() }[frame] = parsed
            }
            if (inBlock) finish()
            return SurfaceDefinitions(definitions)
        }
    }
}
