package com.cattailsw.nanidroid.surface

import com.cattailsw.nanidroid.HostAndroidStubRule
import com.cattailsw.nanidroid.SurfaceManager
import com.cattailsw.nanidroid.SurfaceReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

class SurfaceParserRecoveryTest {
    @get:Rule
    val androidStubs = HostAndroidStubRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val parser = SurfaceParser()

    @Test
    fun invalid_selector_token_is_skipped_and_never_aliases_surface_zero() {
        val result = parser.parse(
            listOf(source("surface0,broken,2 {\ncollision0,0,0,1,1,Face\n}")),
            SurfaceParseSeed(emptySet()),
        )

        assertEquals(setOf(0, 2), result.surfaces.keys)
        assertEquals(1, result.diagnostics.count { it.reason == SurfaceDiagnosticReason.SELECTOR })
    }

    @Test
    fun normal_blocks_accumulate_entries_in_authored_order() {
        val result = parser.parse(
            listOf(
                source(
                    """
                    surface1
                    {
                    collision0,0,0,1,1,First
                    }
                    surface1
                    {
                    collision1,1,1,2,2,Second
                    }
                    """.trimIndent(),
                ),
            ),
            SurfaceParseSeed(emptySet()),
        )

        assertEquals(listOf("First", "Second"), result.surfaces.getValue(1).map { it.source.text.substringAfterLast(',') })
        assertEquals(listOf(0L, 1L), result.surfaces.getValue(1).map { it.authoredOrder })
    }

    @Test
    fun entry_text_retains_double_slashes_that_are_authored_data() {
        val result = parser.parse(
            listOf(source("surface1\n{\ncollision0,0,0,1,1,http://example\n}")),
            SurfaceParseSeed(emptySet()),
        )

        assertEquals("collision0,0,0,1,1,http://example", result.surfaces.getValue(1).single().source.text)
    }

    @Test
    fun append_is_existing_only_for_prior_normal_or_png_seed_and_is_not_retroactive() {
        val result = parser.parse(
            listOf(
                source(
                    """
                    surface.append1,2,3
                    {
                    collision0,0,0,1,1,Before
                    }
                    surface1
                    {
                    collision1,0,0,1,1,Defined
                    }
                    surface.append1,2,3
                    {
                    collision2,0,0,1,1,After
                    }
                    """.trimIndent(),
                ),
            ),
            SurfaceParseSeed(setOf(2)),
        )

        assertEquals(setOf(1, 2), result.surfaces.keys)
        assertEquals(listOf("Defined", "After"), names(result, 1))
        assertEquals(listOf("Before", "After"), names(result, 2))
    }

    @Test
    fun empty_normal_definition_establishes_target_for_a_later_append() {
        val result = parser.parse(
            listOf(
                source(
                    """
                    surface8
                    {
                    }
                    surface.append8
                    {
                    collision0,0,0,1,1,Added
                    }
                    """.trimIndent(),
                ),
            ),
            SurfaceParseSeed(emptySet()),
        )

        assertEquals(listOf("Added"), names(result, 8))
    }

    @Test
    fun descript_after_surface_applies_per_file_provenance_to_every_entry() {
        val result = parser.parse(
            listOf(
                source("surface0\n{\ncollision0,0,0,1,1,A\n}\ndescript\n{\ncollision-sort,ascend\n}", "surfaces.txt"),
                source("descript\n{\ncollision-sort,descend\n}\nsurface0\n{\ncollision1,0,0,1,1,B\n}", "surfaces2.txt"),
                source("surface0\n{\ncollision2,0,0,1,1,C\n}", "surfaces3.txt"),
            ),
            SurfaceParseSeed(emptySet()),
        )

        assertEquals(
            listOf(CollisionSort.ASCEND, CollisionSort.DESCEND, CollisionSort.NONE),
            result.surfaces.getValue(0).map { it.fileDirectives.collisionSort },
        )
        assertEquals(
            listOf("surfaces.txt", "surfaces2.txt", "surfaces3.txt"),
            result.surfaces.getValue(0).map { it.source.file },
        )
    }

    @Test
    fun indentation_comments_trailing_comments_and_compact_braces_recover_with_bounded_diagnostics() {
        val result = parser.parse(
            listOf(
                source(
                    """
                      surface4 // selector note
                      // comment before brace
                      {
                        collision0,0,0,1,1,Canonical
                      }
                    surface5{
                    collision0,0,0,1,1,Compact
                    }
                    surface6 {
                    collision0,0,0,1,1,CompactSpace
                    }
                    """.trimIndent(),
                ),
            ),
            SurfaceParseSeed(emptySet()),
        )

        assertEquals(setOf(4, 5, 6), result.surfaces.keys)
        assertEquals(2, result.diagnostics.count { it.reason == SurfaceDiagnosticReason.MISSING_BRACE })
    }

    @Test
    fun missing_braces_and_malformed_entries_resynchronize_at_later_selectors_and_files() {
        val result = parser.parse(
            listOf(
                source("surface0\n{\nnot-an-entry\nsurface1\n{\ncollision0,0,0,1,1,Recovered\n}", "surfaces.txt"),
                source("surface2\n{\ncollision0,0,0,1,1,LaterFile\n}", "surfaces2.txt"),
            ),
            SurfaceParseSeed(emptySet()),
        )

        assertEquals(setOf(1, 2), result.surfaces.keys)
        assertTrue(result.diagnostics.any { it.reason == SurfaceDiagnosticReason.MISSING_BRACE })
        assertTrue(result.diagnostics.any { it.reason == SurfaceDiagnosticReason.ENTRY })
    }

    @Test
    fun unclosed_descript_resynchronizes_at_a_later_surface_selector() {
        val result = parser.parse(
            listOf(
                source(
                    """
                    descript
                    {
                    collision-sort,ascend
                    surface11
                    {
                    collision0,0,0,1,1,Recovered
                    }
                    """.trimIndent(),
                ),
            ),
            SurfaceParseSeed(emptySet()),
        )

        assertEquals(listOf("Recovered"), names(result, 11))
        assertTrue(result.diagnostics.any { it.reason == SurfaceDiagnosticReason.MISSING_BRACE })
    }

    @Test
    fun diagnostics_are_capped_while_a_later_valid_block_still_recovers() {
        val malformed = (0 until 300).joinToString("\n") { "surfacebroken$it" }
        val result = parser.parse(
            listOf(source("$malformed\nsurface9\n{\ncollision0,0,0,1,1,Recovered\n}")),
            SurfaceParseSeed(emptySet()),
        )

        assertEquals(256, result.diagnostics.size)
        assertEquals(listOf("Recovered"), names(result, 9))
    }

    @Test
    fun oversized_selector_range_is_rejected_and_a_later_block_recovers() {
        val result = parser.parse(
            listOf(
                source(
                    """
                    surface0-10000
                    {
                    collision0,0,0,1,1,Rejected
                    }
                    surface13
                    {
                    collision0,0,0,1,1,Recovered
                    }
                    """.trimIndent(),
                ),
            ),
            SurfaceParseSeed(emptySet()),
        )

        assertEquals(setOf(13), result.surfaces.keys)
        assertEquals(listOf("Recovered"), names(result, 13))
        assertTrue(result.diagnostics.any { it.reason == SurfaceDiagnosticReason.SELECTOR })
    }

    @Test
    fun shared_selector_exhaustion_after_a_valid_define_token_rejects_the_whole_block() {
        val tenThousand = List(10_000) { "0" }.joinToString(",")
        val nineThousandNineHundredNinetyNine = List(9_999) { "1" }.joinToString(",")
        val result = parser.parse(
            listOf(
                source(
                    "surface$tenThousand\n{\n}\n" +
                        "surface$nineThousandNineHundredNinetyNine\n{\n}\n" +
                        "surface2000,2001\n{\ncollision0,0,0,1,1,Rejected\n}\n",
                    "surfaces1.txt",
                ),
                source(
                    "surface3\n{\ncollision0,0,0,1,1,Recovered\n}\n",
                    "surfaces2.txt",
                ),
            ),
            SurfaceParseSeed(emptySet()),
        )

        assertEquals(setOf(0, 1, 3), result.surfaces.keys)
        assertTrue(2000 !in result.surfaces)
        assertEquals(listOf("Recovered"), names(result, 3))
        assertEquals(1, result.diagnostics.count { it.reason == SurfaceDiagnosticReason.SELECTOR })
    }

    @Test
    fun shared_selector_exhaustion_after_a_valid_append_token_rejects_the_whole_block() {
        val tenThousand = List(10_000) { "0" }.joinToString(",")
        val nineThousandNineHundredNinetyNine = List(9_999) { "0" }.joinToString(",")
        val result = parser.parse(
            listOf(
                source(
                    "surface.append$tenThousand\n{\n}\n" +
                        "surface.append$nineThousandNineHundredNinetyNine\n{\n}\n" +
                        "surface.append0,1\n{\ncollision0,0,0,1,1,Rejected\n}\n",
                    "surfaces1.txt",
                ),
                source(
                    "surface.append0\n{\ncollision1,0,0,1,1,Recovered\n}\n",
                    "surfaces2.txt",
                ),
            ),
            SurfaceParseSeed(setOf(0)),
        )

        assertEquals(listOf("Recovered"), names(result, 0))
        assertEquals(1, result.diagnostics.count { it.reason == SurfaceDiagnosticReason.SELECTOR })
    }

    @Test
    fun many_small_selectors_share_one_file_work_budget_and_later_file_recovers() {
        val repeated = buildString {
            repeat(21) {
                append("surface0-999\n{\n}\n")
            }
        }
        val result = parser.parse(
            listOf(
                source(repeated, "surfaces.txt"),
                source("surface2000\n{\n}\n", "surfaces2.txt"),
            ),
            SurfaceParseSeed(emptySet()),
        )

        assertEquals(1_001, result.surfaces.size)
        assertTrue(2000 in result.surfaces)
        assertEquals(1, result.diagnostics.count { it.reason == SurfaceDiagnosticReason.SELECTOR })
    }

    @Test
    fun selector_work_budget_is_shared_across_individually_valid_files() {
        fun selectorBlocks(file: Int): String = buildString {
            repeat(15) { block ->
                append("surface0-999\n{\ncollision0,0,0,1,1,F")
                append(file)
                append("B")
                append(block)
                append("\n}\n")
            }
        }
        val result = parser.parse(
            (1..4).map { file -> source(selectorBlocks(file), "surfaces$file.txt") },
            SurfaceParseSeed(emptySet()),
        )

        assertEquals(50, result.surfaces.getValue(0).size)
        assertEquals(1, result.diagnostics.count { it.reason == SurfaceDiagnosticReason.SELECTOR })
    }

    @Test
    fun many_small_blocks_share_per_file_and_whole_parse_block_budgets() {
        fun blocks(count: Int, fileOffset: Int): String = buildString {
            repeat(count) { index ->
                append("surface0\n{\ncollision0,0,0,1,1,B")
                append(fileOffset + index)
                append("\n}\n")
            }
        }
        val result = parser.parse(
            listOf(
                source(blocks(1_500, 0), "surfaces1.txt"),
                source(blocks(1_500, 1_500), "surfaces2.txt"),
                source(blocks(1_500, 3_000), "surfaces3.txt"),
            ),
            SurfaceParseSeed(emptySet()),
        )

        assertEquals(4_096, result.surfaces.getValue(0).size)
        assertEquals(1, result.diagnostics.count { it.reason == SurfaceDiagnosticReason.UNSUPPORTED })
    }

    @Test
    fun per_file_block_budget_rejects_excess_and_later_file_recovers() {
        val blocks = buildString {
            repeat(2_050) { index ->
                append("surface0\n{\ncollision0,0,0,1,1,B")
                append(index)
                append("\n}\n")
            }
        }
        val result = parser.parse(
            listOf(
                source(blocks, "surfaces1.txt"),
                source("surface1\n{\ncollision0,0,0,1,1,Recovered\n}\n", "surfaces2.txt"),
            ),
            SurfaceParseSeed(emptySet()),
        )

        assertEquals(2_048, result.surfaces.getValue(0).size)
        assertEquals(listOf("Recovered"), names(result, 1))
        assertEquals(1, result.diagnostics.count { it.reason == SurfaceDiagnosticReason.UNSUPPORTED })
    }

    @Test
    fun association_budget_rejects_a_whole_block_before_materializing_targets() {
        val entries = buildString {
            repeat(51) { index ->
                append("collision")
                append(index)
                append(",0,0,1,1,Area\n")
            }
        }
        val result = parser.parse(
            listOf(
                source("surface0-999\n{\n$entries}\n", "surfaces1.txt"),
                source("surface2000\n{\ncollision0,0,0,1,1,Recovered\n}\n", "surfaces2.txt"),
            ),
            SurfaceParseSeed(emptySet()),
        )

        assertEquals(setOf(2000), result.surfaces.keys)
        assertEquals(listOf("Recovered"), names(result, 2000))
        assertEquals(1, result.diagnostics.count { it.reason == SurfaceDiagnosticReason.UNSUPPORTED })
    }

    @Test
    fun append_to_many_png_seeds_uses_the_same_atomic_association_budget() {
        val entries = buildString {
            repeat(51) { index ->
                append("collision")
                append(index)
                append(",0,0,1,1,Area\n")
            }
        }
        val result = parser.parse(
            listOf(
                source("surface.append0-999\n{\n$entries}\n", "surfaces1.txt"),
                source("surface2000\n{\ncollision0,0,0,1,1,Recovered\n}\n", "surfaces2.txt"),
            ),
            SurfaceParseSeed((0..999).toSet()),
        )

        assertEquals(setOf(2000), result.surfaces.keys)
        assertEquals(listOf("Recovered"), names(result, 2000))
        assertEquals(1, result.diagnostics.count { it.reason == SurfaceDiagnosticReason.UNSUPPORTED })
    }

    @Test
    fun materialization_budget_cannot_be_bypassed_with_many_small_blocks() {
        val blocks = buildString {
            repeat(5) { block ->
                val first = block * 1_000
                append("surface$first-${first + 999}\n{\n}\n")
            }
        }
        val result = parser.parse(
            listOf(
                source(blocks, "surfaces1.txt"),
                source("surface6000\n{\n}\n", "surfaces2.txt"),
            ),
            SurfaceParseSeed(emptySet()),
        )

        assertEquals(4_001, result.surfaces.size)
        assertTrue(3999 in result.surfaces)
        assertTrue(4000 !in result.surfaces)
        assertTrue(6000 in result.surfaces)
        assertEquals(1, result.diagnostics.count { it.reason == SurfaceDiagnosticReason.UNSUPPORTED })
    }

    @Test
    fun whole_parse_target_budget_is_shared_across_individually_valid_files() {
        val result = parser.parse(
            listOf(
                source("surface0-3999\n{\n}\n", "surfaces1.txt"),
                source("surface4000-7999\n{\n}\n", "surfaces2.txt"),
                source(
                    "surface8000-8999\n{\n}\nsurface9000\n{\n}\n",
                    "surfaces3.txt",
                ),
            ),
            SurfaceParseSeed(emptySet()),
        )

        assertEquals(8_001, result.surfaces.size)
        assertTrue(7999 in result.surfaces)
        assertTrue(8000 !in result.surfaces)
        assertTrue(9000 in result.surfaces)
        assertEquals(1, result.diagnostics.count { it.reason == SurfaceDiagnosticReason.UNSUPPORTED })
    }

    @Test
    fun append_to_nonexistent_ids_counts_zero_actual_associations() {
        val entries = buildString {
            repeat(100) { index -> append("collision$index,0,0,1,1,Area\n") }
        }
        val result = parser.parse(
            listOf(source("surface.append0-999\n{\n$entries}\n")),
            SurfaceParseSeed(emptySet()),
        )

        assertTrue(result.surfaces.isEmpty())
        assertTrue(result.diagnostics.none { it.reason == SurfaceDiagnosticReason.UNSUPPORTED })
    }

    @Test
    fun repeated_append_to_existing_targets_accumulates_association_work() {
        val firstAppend = buildString {
            append("surface.append0-99\n{\n")
            repeat(500) { index -> append("collision$index,0,0,1,1,Area\n") }
            append("}\n")
        }
        val result = parser.parse(
            listOf(
                source(
                    "surface0-99\n{\n}\n" +
                        firstAppend +
                        "surface.append0-99\n{\ncollision999,0,0,1,1,Rejected\n}\n",
                ),
            ),
            SurfaceParseSeed(emptySet()),
        )

        assertEquals(500, result.surfaces.getValue(0).size)
        assertEquals(1, result.diagnostics.count { it.reason == SurfaceDiagnosticReason.UNSUPPORTED })
    }

    @Test
    fun whole_parse_association_budget_cannot_be_bypassed_across_files() {
        fun associatedBlock(first: Int, entryCount: Int): String = buildString {
            append("surface$first-${first + 999}\n{\n")
            repeat(entryCount) { index ->
                append("collision$index,0,0,1,1,Area\n")
            }
            append("}\n")
        }
        val result = parser.parse(
            listOf(
                source(associatedBlock(0, 40), "surfaces1.txt"),
                source(associatedBlock(1_000, 40), "surfaces2.txt"),
                source(
                    associatedBlock(2_000, 30) +
                        "surface4000\n{\ncollision0,0,0,1,1,Recovered\n}\n",
                    "surfaces3.txt",
                ),
            ),
            SurfaceParseSeed(emptySet()),
        )

        assertTrue(1999 in result.surfaces)
        assertTrue(2000 !in result.surfaces)
        assertTrue(4000 in result.surfaces)
        assertEquals(listOf("Recovered"), names(result, 4000))
        assertEquals(1, result.diagnostics.count { it.reason == SurfaceDiagnosticReason.UNSUPPORTED })
    }

    @Test
    fun png_seed_targets_debit_the_whole_parse_materialization_budget() {
        val result = parser.parse(
            listOf(source("surface9000\n{\n}\n", "surfaces.txt")),
            SurfaceParseSeed((0 until 8_192).toSet()),
        )

        assertTrue(result.surfaces.isEmpty())
        assertEquals(1, result.diagnostics.count { it.reason == SurfaceDiagnosticReason.UNSUPPORTED })
    }

    @Test
    fun short_audited_fixture_grammar_remains_supported() {
        val files = listOf("snake-otacon", "nanika-atsume").mapIndexed { index, ghost ->
            val text = requireNotNull(javaClass.classLoader?.getResource("ghost-fixtures/$ghost/surfaces.txt")).readText()
            source(text, "surfaces$index.txt")
        }

        val result = parser.parse(files, SurfaceParseSeed(setOf(10)))

        assertTrue(result.surfaces.keys.containsAll(listOf(0, 1, 3, 10)))
        assertTrue(result.diagnostics.size <= 8)
    }

    @Test
    fun reader_discovers_all_source_files_scans_png_first_and_preserves_actual_png_case() {
        val root = temporaryFolder.newFolder("mixed-shell")
        File(root, "surfaces.txt").writeText("surface7\n{\ncollision0,0,0,1,1,Base\n}\n", StandardCharsets.UTF_8)
        File(root, "surfaces2.txt").writeText("surface.append7\n{\ncollision1,1,1,2,2,Added\n}\n", StandardCharsets.UTF_8)
        val exactPng = File(root, "Surface0007.PNG")
        exactPng.writeBytes(byteArrayOf())
        val manager = SurfaceManager("fixture")

        val reader = SurfaceReader(manager, root.absolutePath, File(root, "surfaces.txt").absolutePath)

        assertFalse(reader.error)
        assertEquals(exactPng.absolutePath, manager.getSurface("7")?.selfFilename)
        assertEquals(setOf(0, 1), manager.getSurface("7")?.collisionAreas?.keys)
        assertEquals(
            listOf("Base", "Added"),
            manager.getParsedSurfaceEntries("7").map { it.source.text.substringAfterLast(',') },
        )
        assertEquals(
            listOf(CollisionSort.NONE, CollisionSort.NONE),
            manager.getParsedSurfaceEntries("7").map { it.fileDirectives.collisionSort },
        )
        assertEquals(1, reader.diagnostics.count { it.reason == SurfaceDiagnosticReason.DECODE })
        assertTrue(reader.diagnostics.size <= 256)
    }

    @Test
    fun reader_discovers_numbered_source_when_surfaces_txt_is_absent() {
        val root = temporaryFolder.newFolder("numbered-only-shell")
        val numbered = File(root, "surfaces2.txt")
        numbered.writeText("surface12\n{\ncollision0,0,0,1,1,Only\n}\n", StandardCharsets.UTF_8)
        val manager = SurfaceManager("fixture")

        val reader = SurfaceReader(manager, root.absolutePath, File(root, "surfaces.txt").absolutePath)

        assertFalse(reader.error)
        assertEquals(setOf("12"), manager.getSurfaceKeys())
        assertEquals(listOf("Only"), manager.getParsedSurfaceEntries("12").map { it.source.text.substringAfterLast(',') })
    }

    @Test
    fun reader_rejects_oversized_file_before_reading_and_recovers_later_source() {
        val root = temporaryFolder.newFolder("oversized-source-shell")
        File(root, "surfaces1.txt").writeBytes(sizedSource(1, 1_048_577))
        File(root, "surfaces2.txt").writeText("surface2\n{\n}\n", StandardCharsets.UTF_8)
        val manager = SurfaceManager("fixture")

        val reader = SurfaceReader(manager, root.absolutePath, File(root, "surfaces.txt").absolutePath)

        assertEquals(setOf("2"), manager.getSurfaceKeys())
        assertEquals(listOf("surfaces1.txt"), reader.diagnostics.map { it.file })
    }

    @Test
    fun reader_accepts_a_source_at_the_exact_per_file_byte_limit() {
        val root = temporaryFolder.newFolder("exact-source-limit-shell")
        File(root, "surfaces1.txt").writeBytes(sizedSource(1, 1_048_576))
        val manager = SurfaceManager("fixture")

        val reader = SurfaceReader(manager, root.absolutePath, File(root, "surfaces.txt").absolutePath)

        assertEquals(setOf("1"), manager.getSurfaceKeys())
        assertTrue(reader.diagnostics.isEmpty())
    }

    @Test
    fun reader_enforces_aggregate_source_bytes_and_still_accepts_a_small_later_file() {
        val root = temporaryFolder.newFolder("aggregate-source-shell")
        repeat(4) { index ->
            File(root, "surfaces${index + 1}.txt").writeBytes(sizedSource(index + 1, 1_048_000))
        }
        File(root, "surfaces5.txt").writeBytes(sizedSource(5, 10_000))
        File(root, "surfaces6.txt").writeText("surface6\n{\n}\n", StandardCharsets.UTF_8)
        val manager = SurfaceManager("fixture")

        val reader = SurfaceReader(manager, root.absolutePath, File(root, "surfaces.txt").absolutePath)

        assertEquals(setOf("1", "2", "3", "4", "6"), manager.getSurfaceKeys())
        assertEquals(listOf("surfaces5.txt"), reader.diagnostics.map { it.file })
    }

    @Test
    fun reader_caps_many_tiny_source_files_in_deterministic_filename_order() {
        val root = temporaryFolder.newFolder("many-source-files-shell")
        repeat(257) { index ->
            File(root, "surfaces%03d.txt".format(index)).writeText(
                "surface$index\n{\n}\n",
                StandardCharsets.UTF_8,
            )
        }
        val manager = SurfaceManager("fixture")

        val reader = SurfaceReader(manager, root.absolutePath, File(root, "surfaces.txt").absolutePath)

        assertEquals(256, manager.getTotalSurfaceCount())
        assertTrue(manager.containsSurface("255"))
        assertFalse(manager.containsSurface("256"))
        assertEquals(listOf("surfaces256.txt"), reader.diagnostics.map { it.file })
    }

    @Test
    fun rejected_oversized_source_does_not_consume_the_tiny_file_count_budget() {
        val root = temporaryFolder.newFolder("skipped-source-count-shell")
        File(root, "surfaces000.txt").writeBytes(sizedSource(0, 1_048_577))
        repeat(256) { index ->
            val id = index + 1
            File(root, "surfaces%03d.txt".format(id)).writeText(
                "surface$id\n{\n}\n",
                StandardCharsets.UTF_8,
            )
        }
        val manager = SurfaceManager("fixture")

        val reader = SurfaceReader(manager, root.absolutePath, File(root, "surfaces.txt").absolutePath)

        assertEquals(256, manager.getTotalSurfaceCount())
        assertTrue(manager.containsSurface("256"))
        assertEquals(listOf("surfaces000.txt"), reader.diagnostics.map { it.file })
    }

    @Test
    fun aggregate_decoded_line_budget_rejects_one_file_without_hiding_a_smaller_later_file() {
        val root = temporaryFolder.newFolder("aggregate-lines-shell")
        repeat(5) { index ->
            File(root, "surfaces${index + 1}.txt").writeText(
                sourceWithLineCount(index + 1, 19_900),
                StandardCharsets.UTF_8,
            )
        }
        File(root, "surfaces6.txt").writeText(sourceWithLineCount(6, 1_000), StandardCharsets.UTF_8)
        File(root, "surfaces7.txt").writeText(sourceWithLineCount(7, 100), StandardCharsets.UTF_8)
        val manager = SurfaceManager("fixture")

        val reader = SurfaceReader(manager, root.absolutePath, File(root, "surfaces.txt").absolutePath)

        assertEquals(setOf("1", "2", "3", "4", "5", "7"), manager.getSurfaceKeys())
        assertEquals(listOf("surfaces6.txt"), reader.diagnostics.map { it.file })
    }

    @Test
    fun reader_caps_png_seeds_before_materializing_shell_surfaces() {
        val root = temporaryFolder.newFolder("many-png-seeds-shell")
        repeat(4_097) { index ->
            File(root, "surface%04d.png".format(index)).writeBytes(byteArrayOf())
        }
        File(root, "surfaces.txt").writeText("surface5000\n{\n}\n", StandardCharsets.UTF_8)
        val manager = SurfaceManager("fixture")

        val reader = SurfaceReader(manager, root.absolutePath, File(root, "surfaces.txt").absolutePath)

        assertEquals(4_097, manager.getTotalSurfaceCount())
        assertTrue(manager.containsSurface("4095"))
        assertFalse(manager.containsSurface("4096"))
        assertTrue(manager.containsSurface("5000"))
        assertEquals(1, reader.diagnostics.count { it.file == "surface4096.png" })
        assertTrue(reader.diagnostics.size <= 256)
    }

    @Test
    fun reader_recovers_nanika_shaped_utf8_source_with_legacy_declaration_and_seven_collisions() {
        val root = temporaryFolder.newFolder("nanika-legacy-declaration-shell")
        File(root, "surfaces.txt").writeText(
            buildString {
                appendLine("charset,Shift_JIS")
                appendLine("// Nanika ݒ transparent Kero")
                appendLine("surface10")
                appendLine("{")
                repeat(7) { index ->
                    appendLine("collision$index,$index,$index,${index + 1},${index + 1},Region$index")
                }
                appendLine("}")
            },
            StandardCharsets.UTF_8,
        )
        val manager = SurfaceManager("fixture")

        val reader = SurfaceReader(manager, root.absolutePath, File(root, "surfaces.txt").absolutePath)

        assertFalse(reader.error)
        assertEquals(7, requireNotNull(manager.getSurface("10")).collisionAreas.size)
        assertEquals(
            7,
            manager.getParsedSurfaceEntries("10").count { it.source.text.startsWith("collision") },
        )
        assertEquals(1, reader.diagnostics.count { it.reason == SurfaceDiagnosticReason.DECODE })
    }

    @Test
    fun reader_bounds_attempted_tiny_decode_failures_before_a_sorted_late_file() {
        val root = temporaryFolder.newFolder("many-decode-failures-shell")
        repeat(512) { index ->
            File(root, "surfaces%03d.txt".format(index)).writeBytes(byteArrayOf(0x81.toByte()))
        }
        File(root, "surfaces999.txt").writeText("surface999\n{\n}\n", StandardCharsets.UTF_8)
        val manager = SurfaceManager("fixture")

        val reader = SurfaceReader(manager, root.absolutePath, File(root, "surfaces.txt").absolutePath)

        assertTrue(manager.getSurfaceKeys().isEmpty())
        assertTrue(reader.diagnostics.size <= 256)
    }

    @Test
    fun reader_stops_after_exact_attempted_byte_budget_even_when_retained_sources_are_rejected() {
        val root = temporaryFolder.newFolder("attempted-byte-budget-shell")
        repeat(8) { index ->
            File(root, "surfaces${index + 1}.txt").writeBytes(sizedSource(index + 1, 1_048_576))
        }
        File(root, "surfaces9.txt").writeText("surface9\n{\n}\n", StandardCharsets.UTF_8)
        val manager = SurfaceManager("fixture")

        val reader = SurfaceReader(manager, root.absolutePath, File(root, "surfaces.txt").absolutePath)

        assertEquals(setOf("1", "2", "3", "4"), manager.getSurfaceKeys())
        assertFalse(manager.containsSurface("9"))
        assertEquals(
            listOf("surfaces5.txt", "surfaces6.txt", "surfaces7.txt", "surfaces8.txt", "surfaces9.txt"),
            reader.diagnostics.map { it.file },
        )
    }

    @Test
    fun reader_rejects_invalid_animation_pattern_ids_before_materialization_and_recovers() {
        val root = temporaryFolder.newFolder("bounded-animation-pattern-shell")
        File(root, "surfaces1.txt").writeText(
            """
            surface0
            {
            0pattern4095,-1,10,overlay,0,0
            animation1.pattern18,overlay,-1,20
            2pattern4096,-1,10,overlay,0,0
            animation3.pattern-1,overlay,-1,10
            animation4.pattern999999999999999999999999,overlay,-1,10
            5pattern100000,-1,10,overlay,0,0
            animation6.pattern2147483647,overlay,-1,10
            animation7.pattern2147483647,alternativestart,(1.2)
            collision0,0,0,1,1,Sibling
            }
            surface1
            {
            animation0.pattern0,overlay,-1,10
            }
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )
        File(root, "surfaces2.txt").writeText(
            "surface2\n{\n0pattern0,-1,10,overlay,0,0\n}\n",
            StandardCharsets.UTF_8,
        )
        val manager = SurfaceManager("fixture")

        val reader = SurfaceReader(manager, root.absolutePath, File(root, "surfaces1.txt").absolutePath)

        assertEquals(1, manager.getSurface("0")!!.getAnimationFrameCount(0))
        assertEquals(1, manager.getSurface("0")!!.getAnimationFrameCount(1))
        assertEquals(0, manager.getSurface("0")!!.getAnimationFrameCount(2))
        assertEquals(0, manager.getSurface("0")!!.getAnimationFrameCount(3))
        assertEquals(0, manager.getSurface("0")!!.getAnimationFrameCount(4))
        assertEquals(0, manager.getSurface("0")!!.getAnimationFrameCount(5))
        assertEquals(0, manager.getSurface("0")!!.getAnimationFrameCount(6))
        assertEquals(0, manager.getSurface("0")!!.getAnimationFrameCount(7))
        assertEquals(1, manager.getSurface("0")!!.collisionCount)
        assertEquals(1, manager.getSurface("1")!!.getAnimationFrameCount(0))
        assertEquals(1, manager.getSurface("2")!!.getAnimationFrameCount(0))
        assertEquals(6, reader.diagnostics.count { it.reason == SurfaceDiagnosticReason.ENTRY })
        assertTrue(reader.diagnostics.size <= 256)
    }

    @Test
    fun reader_bounds_invalid_animation_pattern_diagnostics() {
        val root = temporaryFolder.newFolder("many-invalid-animation-patterns-shell")
        val entries = (0 until 300).joinToString("\n") { animation ->
            "animation$animation.pattern4096,overlay,-1,10"
        }
        File(root, "surfaces.txt").writeText(
            "surface0\n{\n$entries\n0pattern0,-1,10,overlay,0,0\n}\n",
            StandardCharsets.UTF_8,
        )
        val manager = SurfaceManager("fixture")

        val reader = SurfaceReader(manager, root.absolutePath, File(root, "surfaces.txt").absolutePath)

        assertEquals(1, manager.getSurface("0")!!.getAnimationFrameCount(0))
        assertEquals(256, reader.diagnostics.count { it.reason == SurfaceDiagnosticReason.ENTRY })
    }

    private fun source(text: String, name: String = "surfaces.txt") =
        SurfaceSourceFile(name, StandardCharsets.UTF_8, text.lines())

    private fun names(result: SurfaceParseResult, id: Int): List<String> =
        result.surfaces.getValue(id).map { it.source.text.substringAfterLast(',') }

    private fun sizedSource(id: Int, size: Int): ByteArray {
        val header = "surface$id\n{\n}\n".toByteArray(StandardCharsets.UTF_8)
        require(size >= header.size)
        return ByteArray(size) { index ->
            when {
                index < header.size -> header[index]
                (index - header.size + 1) % 1_024 == 0 -> '\n'.code.toByte()
                else -> '/'.code.toByte()
            }
        }
    }

    private fun sourceWithLineCount(id: Int, lineCount: Int): String = buildString {
        append("surface$id\n{\n}\n")
        repeat(lineCount - 3) { append("//\n") }
    }
}
