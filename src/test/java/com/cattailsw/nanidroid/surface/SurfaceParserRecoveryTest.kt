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

    private fun source(text: String, name: String = "surfaces.txt") =
        SurfaceSourceFile(name, StandardCharsets.UTF_8, text.lines())

    private fun names(result: SurfaceParseResult, id: Int): List<String> =
        result.surfaces.getValue(id).map { it.source.text.substringAfterLast(',') }
}
