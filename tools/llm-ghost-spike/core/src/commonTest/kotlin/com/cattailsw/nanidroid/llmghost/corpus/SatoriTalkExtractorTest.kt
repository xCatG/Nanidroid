package com.cattailsw.nanidroid.llmghost.corpus

import com.cattailsw.nanidroid.llmghost.model.GhostCorpusInput
import com.cattailsw.nanidroid.llmghost.model.GhostIdentity
import com.cattailsw.nanidroid.llmghost.model.GhostSourceFile
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId
import com.cattailsw.nanidroid.llmghost.model.TalkCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SatoriTalkExtractorTest {
    private val extractor = SatoriTalkExtractor()

    @Test
    fun extractsSpeakersSurfacesCategoriesAndProvenance() {
        val result = extractor.extract(input(fixture("2elf-shaped-talks.txt")))

        assertEquals(2, result.talks.size)
        assertEquals(
            listOf(GhostSpeakerId.SAKURA, GhostSpeakerId.KERO),
            result.talks.first().turns.map { it.speaker },
        )
        assertEquals(listOf(3, 19), result.talks.first().turns.map { it.surface })
        assertEquals(TalkCategory.TOUCH, result.talks[1].category)
        assertTrue(result.talks.first().sourceLine > 0)
        assertEquals("fixture/2elf-shaped-talks.txt:1:1", result.talks.first().id)
    }

    @Test
    fun skipsSaoriAndVariablesWithoutEvaluatingThem() {
        val result = extractor.extract(input(source("＊OnBoot\n：（call,external.saori）\n")))

        assertTrue(result.talks.isEmpty())
        assertTrue(result.diagnostics.any { it.code == "unsupported-control" })
    }

    @Test
    fun skipsMalformedControlsAndIncludesDiagnosticProvenance() {
        val result = extractor.extract(
            input(
                source(
                    """
                    ＊Truncated
                    ：\0\s[3話
                    ＊UnknownScope
                    ：\zNot allowed
                    ＊Condition
                    ：（if,flag）
                    """.trimIndent(),
                ),
            ),
        )

        assertTrue(result.talks.isEmpty())
        assertEquals(
            setOf("malformed-control", "unsupported-control"),
            result.diagnostics.map { it.code }.toSet(),
        )
        assertTrue(result.diagnostics.all { it.path == "fixture/talk.txt" && it.line > 0 })
    }

    @Test
    fun skipsEmptyTalksAndScopesAboveOne() {
        val result = extractor.extract(
            input(
                source(
                    """
                    ＊Empty
                    ：\0\s[3]
                    ＊ThirdScope
                    ：\2\s[8]Nope
                    """.trimIndent(),
                ),
            ),
        )

        assertTrue(result.talks.isEmpty())
        assertTrue(result.diagnostics.any { it.code == "empty-talk" })
        assertTrue(result.diagnostics.any { it.code == "unsupported-control" })
    }

    @Test
    fun acceptsCrLfAndRetainsVisibleTextOnly() {
        val result = extractor.extract(
            input(source("＊Random\r\n：\\h\\s[3] Hello \r\n：\\u\\s[19]World\r\n")),
        )

        assertEquals(TalkCategory.IDLE, result.talks.single().category)
        assertEquals(listOf(" Hello ", "World"), result.talks.single().turns.map { it.text })
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun rejectsDiagnosticOnlyFilesWithoutThrowing() {
        val result = extractor.extract(
            input(source("＊Unsafe\n：https://example.invalid/action\n")),
        )

        assertTrue(result.talks.isEmpty())
        assertTrue(result.diagnostics.isNotEmpty())
        assertTrue(result.diagnostics.all { it.path == "fixture/talk.txt" && it.line > 0 })
    }

    private fun input(vararg sources: GhostSourceFile) = GhostCorpusInput(
        identity = GhostIdentity(
            ghostName = "Fixture ghost",
            sakuraName = "Sakura",
            keroName = "Kero",
            shellSurfaces = mapOf(
                GhostSpeakerId.SAKURA to setOf(3),
                GhostSpeakerId.KERO to setOf(19),
            ),
        ),
        files = sources.toList(),
    )

    private fun source(text: String) = GhostSourceFile("fixture/talk.txt", text)

    private fun fixture(name: String): GhostSourceFile {
        val stream = checkNotNull(javaClass.getResourceAsStream("/fixtures/$name"))
        return GhostSourceFile("fixture/$name", stream.bufferedReader().use { it.readText() })
    }
}
