package com.cattailsw.nanidroid.surface

import com.cattailsw.nanidroid.ShellSurface
import java.util.Locale

data class SurfaceParseSeed(val pngSurfaceIds: Set<Int>)

enum class CollisionSort { ASCEND, DESCEND, NONE }

data class SurfaceFileDirectives(val collisionSort: CollisionSort = CollisionSort.NONE)

data class ParsedSurfaceEntry(
    val source: SourceLine,
    val fileDirectives: SurfaceFileDirectives,
    val authoredOrder: Long,
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
        val materialized = seed.pngSurfaceIds.toMutableSet()
        val budget = ParseBudget(seed.pngSurfaceIds.size.toLong())
        var authoredOrder = 0L

        files.forEach { file ->
            budget.beginFile()
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

                val blockCharge = budget.chargeBlock()
                if (!blockCharge.accepted) {
                    if (blockCharge.report) diagnostics.add(source.unsupported())
                    index = skipBlock(file, index)
                    continue
                }

                val compactBrace = cleaned.contains('{')
                val selectorResult = selector.parse(
                    source.copy(text = cleaned.substringBefore('{').trim()),
                    budget,
                )
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

                val entrySources = mutableListOf<SourceLine>()
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
                            val normalized = entrySource.copy(text = entry)
                            if (hasInvalidAnimationPatternId(entry)) {
                                diagnostics.add(
                                    SurfaceParseDiagnostic(
                                        file.name,
                                        cursor + 1,
                                        entrySource.text,
                                        SurfaceDiagnosticReason.ENTRY,
                                    ),
                                )
                            } else {
                                entrySources += normalized
                            }
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

                if (selectorResult.exhausted) {
                    index = cursor
                    continue
                }

                val applicableIds = when (selectorResult.mode) {
                    SurfaceBlockMode.DEFINE -> selectorResult.selection.included
                    SurfaceBlockMode.APPEND_EXISTING ->
                        selectorResult.selection.included.filterTo(linkedSetOf()) { it in existing }
                }
                val newTargets = applicableIds.count { it !in materialized }.toLong()
                val associations = applicableIds.size.toLong() * entrySources.size.toLong()
                val applyCharge = budget.chargeApplication(newTargets, associations)
                if (!applyCharge.accepted) {
                    if (applyCharge.report) diagnostics.add(source.unsupported())
                    index = cursor
                    continue
                }
                val entries = entrySources.map { entrySource ->
                    ParsedSurfaceEntry(entrySource, directives, authoredOrder++)
                }
                if (selectorResult.mode == SurfaceBlockMode.DEFINE) existing += applicableIds
                materialized += applicableIds
                applicableIds.forEach { id ->
                    surfaces.getOrPut(id) { mutableListOf() }.addAll(entries)
                }
                index = cursor
            }
        }

        return SurfaceParseResult(
            surfaces.mapValues { (_, entries) -> entries.toList() },
            diagnostics.values,
        )
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

    private fun SourceLine.unsupported() =
        SurfaceParseDiagnostic(file, number, text, SurfaceDiagnosticReason.UNSUPPORTED)

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

    private fun hasInvalidAnimationPatternId(line: String): Boolean {
        val token = LEGACY_PATTERN_ID.find(line)?.groupValues?.get(1)
            ?: MODERN_PATTERN_ID.find(line)?.groupValues?.get(1)
            ?: return false
        val id = token.toIntOrNull() ?: return true
        return id !in 0..ShellSurface.MAX_ANIMATION_PATTERN_ID
    }

    private class ParseBudget(seedTargets: Long) : SurfaceSelectorWorkBudget {
        private var fileBlocks = 0L
        private var wholeBlocks = 0L
        private var fileSelectorWork = 0L
        private var wholeSelectorWork = 0L
        private var fileTargets = 0L
        private var wholeTargets = seedTargets
        private var fileAssociations = 0L
        private var wholeAssociations = 0L
        private var blockReported = false
        private var selectorReported = false
        private var targetReported = false
        private var associationReported = false

        fun beginFile() {
            fileBlocks = 0L
            fileSelectorWork = 0L
            fileTargets = 0L
            fileAssociations = 0L
        }

        fun chargeBlock(): SurfaceBudgetCharge {
            if (fileBlocks + 1L > MAX_BLOCKS_PER_FILE || wholeBlocks + 1L > MAX_BLOCKS_TOTAL) {
                val report = !blockReported
                blockReported = true
                return SurfaceBudgetCharge(accepted = false, report = report)
            }
            fileBlocks++
            wholeBlocks++
            return SurfaceBudgetCharge(accepted = true)
        }

        override fun charge(amount: Long): SurfaceBudgetCharge {
            if (amount < 0L ||
                fileSelectorWork + amount > MAX_SELECTOR_WORK_PER_FILE ||
                wholeSelectorWork + amount > MAX_SELECTOR_WORK_TOTAL
            ) {
                val report = !selectorReported
                selectorReported = true
                return SurfaceBudgetCharge(accepted = false, report = report)
            }
            fileSelectorWork += amount
            wholeSelectorWork += amount
            return SurfaceBudgetCharge(accepted = true)
        }

        fun chargeApplication(targets: Long, associations: Long): SurfaceBudgetCharge {
            val targetRejected = targets < 0L ||
                fileTargets + targets > MAX_TARGETS_PER_FILE ||
                wholeTargets + targets > MAX_TARGETS_TOTAL
            val associationRejected = associations < 0L ||
                fileAssociations + associations > MAX_ASSOCIATIONS_PER_FILE ||
                wholeAssociations + associations > MAX_ASSOCIATIONS_TOTAL
            if (targetRejected || associationRejected) {
                val report = (targetRejected && !targetReported) ||
                    (associationRejected && !associationReported)
                if (targetRejected) targetReported = true
                if (associationRejected) associationReported = true
                return SurfaceBudgetCharge(accepted = false, report = report)
            }
            fileTargets += targets
            wholeTargets += targets
            fileAssociations += associations
            wholeAssociations += associations
            return SurfaceBudgetCharge(accepted = true)
        }
    }

    private companion object {
        const val MAX_DIAGNOSTICS = 256
        const val MAX_BLOCKS_PER_FILE = 2_048L
        const val MAX_BLOCKS_TOTAL = 4_096L
        const val MAX_SELECTOR_WORK_PER_FILE = 20_000L
        const val MAX_SELECTOR_WORK_TOTAL = 50_000L
        const val MAX_TARGETS_PER_FILE = 4_096L
        const val MAX_TARGETS_TOTAL = 8_192L
        const val MAX_ASSOCIATIONS_PER_FILE = 50_000L
        const val MAX_ASSOCIATIONS_TOTAL = 100_000L
        val LEGACY_PATTERN_ID = Regex("^\\d+pattern([+-]?\\d+),", RegexOption.IGNORE_CASE)
        val MODERN_PATTERN_ID = Regex("^animation\\d+\\.pattern([+-]?\\d+),", RegexOption.IGNORE_CASE)
    }
}
