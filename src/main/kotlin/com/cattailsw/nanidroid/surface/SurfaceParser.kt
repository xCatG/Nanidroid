package com.cattailsw.nanidroid.surface

import java.util.Locale

data class SurfaceParseSeed(val pngSurfaceIds: Set<Int>)

enum class CollisionSort { ASCEND, DESCEND, NONE }

data class SurfaceFileDirectives(val collisionSort: CollisionSort = CollisionSort.NONE)

data class ParsedSurfaceEntry(
    val source: SourceLine,
    val fileDirectives: SurfaceFileDirectives,
    val authoredOrder: Long,
)

data class ParsedSurfaceBlock(
    val selection: SurfaceSelection,
    val mode: SurfaceBlockMode,
    val entries: List<ParsedSurfaceEntry>,
)

data class SurfaceParseResult(
    val surfaces: Map<Int, List<ParsedSurfaceEntry>>,
    val diagnostics: List<SurfaceParseDiagnostic>,
)

class SurfaceParser(
    private val selector: SurfaceSelector = SurfaceSelector(),
) {
    fun parse(files: List<SurfaceSourceFile>, seed: SurfaceParseSeed): SurfaceParseResult {
        val diagnostics = BoundedDiagnostics()
        val surfaces = linkedMapOf<Int, MutableList<ParsedSurfaceEntry>>()
        val existing = seed.pngSurfaceIds.toMutableSet()
        var authoredOrder = 0L

        files.forEach { file ->
            val directives = scanDirectives(file, diagnostics)
            var index = 0
            while (index < file.lines.size) {
                val source = file.source(index)
                val cleaned = cleanTopLevel(source.text)
                if (cleaned.isEmpty()) {
                    index++
                    continue
                }
                if (isDescript(cleaned)) {
                    index = skipBlock(file, index)
                    continue
                }
                if (!isSelector(cleaned)) {
                    index++
                    continue
                }

                val compactBrace = cleaned.contains('{')
                val selectorResult = selector.parse(source.copy(text = cleaned.substringBefore('{').trim()))
                diagnostics.addAll(selectorResult.diagnostics)
                var bodyIndex = index + 1
                if (compactBrace) {
                    diagnostics.add(
                        SurfaceParseDiagnostic(
                            file.name,
                            index + 1,
                            source.text,
                            SurfaceDiagnosticReason.MISSING_BRACE,
                        ),
                    )
                } else {
                    bodyIndex = nextContentLine(file, bodyIndex)
                    if (bodyIndex >= file.lines.size || cleanTopLevel(file.lines[bodyIndex]) != "{") {
                        diagnostics.add(
                            SurfaceParseDiagnostic(
                                file.name,
                                index + 1,
                                source.text,
                                SurfaceDiagnosticReason.MISSING_BRACE,
                            ),
                        )
                        index = if (bodyIndex < file.lines.size && isSelector(cleanTopLevel(file.lines[bodyIndex]))) {
                            bodyIndex
                        } else {
                            index + 1
                        }
                        continue
                    }
                    bodyIndex++
                }

                val entries = mutableListOf<ParsedSurfaceEntry>()
                var closed = false
                var cursor = bodyIndex
                while (cursor < file.lines.size) {
                    val entrySource = file.source(cursor)
                    val entry = entrySource.text.trim()
                    when {
                        entry.isEmpty() || entry.startsWith("//") -> Unit
                        entry.startsWith('}') -> {
                            closed = true
                            cursor++
                            break
                        }
                        isSelector(entry) -> break
                        ',' !in entry -> diagnostics.add(
                            SurfaceParseDiagnostic(
                                file.name,
                                cursor + 1,
                                entrySource.text,
                                SurfaceDiagnosticReason.ENTRY,
                            ),
                        )
                        else -> {
                            entries += ParsedSurfaceEntry(
                                entrySource.copy(text = entry),
                                directives,
                                authoredOrder++,
                            )
                        }
                    }
                    cursor++
                }

                if (!closed) {
                    diagnostics.add(
                        SurfaceParseDiagnostic(
                            file.name,
                            index + 1,
                            source.text,
                            SurfaceDiagnosticReason.MISSING_BRACE,
                        ),
                    )
                    index = cursor
                    continue
                }

                applyBlock(
                    ParsedSurfaceBlock(selectorResult.selection, selectorResult.mode, entries),
                    existing,
                    surfaces,
                )
                index = cursor
            }
        }

        return SurfaceParseResult(
            surfaces.mapValues { (_, entries) -> entries.toList() },
            diagnostics.values,
        )
    }

    private fun applyBlock(
        block: ParsedSurfaceBlock,
        existing: MutableSet<Int>,
        surfaces: MutableMap<Int, MutableList<ParsedSurfaceEntry>>,
    ) {
        block.selection.included.forEach { id ->
            when (block.mode) {
                SurfaceBlockMode.DEFINE -> {
                    existing += id
                    surfaces.getOrPut(id) { mutableListOf() }.addAll(block.entries)
                }
                SurfaceBlockMode.APPEND_EXISTING -> if (id in existing) {
                    surfaces.getOrPut(id) { mutableListOf() }.addAll(block.entries)
                }
            }
        }
    }

    private fun scanDirectives(
        file: SurfaceSourceFile,
        diagnostics: BoundedDiagnostics,
    ): SurfaceFileDirectives {
        var collisionSort = CollisionSort.NONE
        var index = 0
        while (index < file.lines.size) {
            val declaration = cleanTopLevel(file.lines[index])
            if (!isDescript(declaration)) {
                index++
                continue
            }
            var cursor = if (declaration.contains('{')) index + 1 else nextContentLine(file, index + 1)
            if (!declaration.contains('{')) {
                if (cursor >= file.lines.size || cleanTopLevel(file.lines[cursor]) != "{") {
                    diagnostics.add(
                        SurfaceParseDiagnostic(
                            file.name,
                            index + 1,
                            file.lines[index],
                            SurfaceDiagnosticReason.MISSING_BRACE,
                        ),
                    )
                    index++
                    continue
                }
                cursor++
            }
            var closed = false
            var candidate = collisionSort
            while (cursor < file.lines.size) {
                val entry = file.lines[cursor].trim()
                if (entry.startsWith("//") || entry.isEmpty()) {
                    cursor++
                    continue
                }
                if (entry.startsWith('}')) {
                    closed = true
                    break
                }
                if (isSelector(cleanTopLevel(entry))) break
                val fields = entry.split(',').map { it.trim() }
                if (fields.size >= 2 && fields[0].equals("collision-sort", ignoreCase = true)) {
                    candidate = when (fields[1].lowercase(Locale.ROOT)) {
                        "ascend" -> CollisionSort.ASCEND
                        "descend" -> CollisionSort.DESCEND
                        "none" -> CollisionSort.NONE
                        else -> {
                            diagnostics.add(
                                SurfaceParseDiagnostic(
                                    file.name,
                                    cursor + 1,
                                    file.lines[cursor],
                                    SurfaceDiagnosticReason.UNSUPPORTED,
                                ),
                            )
                            candidate
                        }
                    }
                }
                cursor++
            }
            if (closed) {
                collisionSort = candidate
                index = cursor + 1
            } else {
                diagnostics.add(
                    SurfaceParseDiagnostic(
                        file.name,
                        index + 1,
                        file.lines[index],
                        SurfaceDiagnosticReason.MISSING_BRACE,
                    ),
                )
                index = cursor
            }
        }
        return SurfaceFileDirectives(collisionSort)
    }

    private fun skipBlock(file: SurfaceSourceFile, declarationIndex: Int): Int {
        val declaration = cleanTopLevel(file.lines[declarationIndex])
        var cursor = if (declaration.contains('{')) declarationIndex + 1 else nextContentLine(file, declarationIndex + 1)
        if (!declaration.contains('{') && cursor < file.lines.size && cleanTopLevel(file.lines[cursor]) == "{") cursor++
        while (cursor < file.lines.size) {
            val line = cleanTopLevel(file.lines[cursor])
            if (line.startsWith('}')) return cursor + 1
            if (isSelector(line)) return cursor
            cursor++
        }
        return file.lines.size
    }

    private fun nextContentLine(file: SurfaceSourceFile, start: Int): Int {
        var cursor = start
        while (cursor < file.lines.size && cleanTopLevel(file.lines[cursor]).isEmpty()) cursor++
        return cursor
    }

    private fun SurfaceSourceFile.source(index: Int) = SourceLine(name, index + 1, lines[index])

    private fun cleanTopLevel(line: String): String = line.substringBefore("//").trim()

    private fun isDescript(line: String): Boolean =
        line.substringBefore('{').trim().equals("descript", ignoreCase = true)

    private fun isSelector(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        return lower.startsWith("surface") && !lower.startsWith("surface.alias")
    }

    private class BoundedDiagnostics {
        private val mutableValues = mutableListOf<SurfaceParseDiagnostic>()
        val values: List<SurfaceParseDiagnostic> get() = mutableValues

        fun add(value: SurfaceParseDiagnostic) {
            if (mutableValues.size < MAX_DIAGNOSTICS) mutableValues += value
        }

        fun addAll(values: List<SurfaceParseDiagnostic>) = values.forEach(::add)
    }

    private companion object {
        const val MAX_DIAGNOSTICS = 256
    }
}
