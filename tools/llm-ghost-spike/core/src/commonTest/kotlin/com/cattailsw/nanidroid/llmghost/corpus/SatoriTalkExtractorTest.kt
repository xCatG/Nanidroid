package com.cattailsw.nanidroid.llmghost.corpus

import com.cattailsw.nanidroid.llmghost.model.GhostCorpusInput
import com.cattailsw.nanidroid.llmghost.model.GhostIdentity
import com.cattailsw.nanidroid.llmghost.model.GhostSourceFile
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId
import com.cattailsw.nanidroid.llmghost.model.TalkCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    @Test
    fun linksLiteralPointerDispatchToConcreteReactionHeadings() {
        val result = extractor.extract(
            input(
                source(
                    """
                    ＊OnMouseDoubleClick
                    ＞（Ｒ３）（Ｒ４）つつかれ
                    ＊0Headつつかれ
                    ：\0First
                    ＊1l-headつつかれ
                    ：\1Second
                    ＊1l-headつつかれ
                    ：\1Duplicate
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals(3, result.talks.size)
        assertEquals(
            listOf(GhostSpeakerId.SAKURA, GhostSpeakerId.KERO, GhostSpeakerId.KERO),
            result.talks.map { it.touchSpeaker },
        )
        assertEquals(listOf("Head", "l-head", "l-head"), result.talks.map { it.touchRegion })
        assertTrue(result.talks.all { it.category == TalkCategory.TOUCH })
        assertEquals(listOf("0Headつつかれ", "1l-headつつかれ", "1l-headつつかれ"), result.talks.map { it.heading })
        assertEquals(listOf("First", "Second", "Duplicate"), result.talks.map { it.turns.single().text })
        assertEquals(3, result.talks.map { it.id }.toSet().size)
    }

    @Test
    fun doesNotInferPointerMetadataWithoutASafeObservedSuffix() {
        val tooLong = "x".repeat(65)
        val result = extractor.extract(
            input(
                source(
                    """
                    ＊OnMouseDoubleClick
                    ＞（Ｒ３）（Ｒ４）動的（call）
                    ＞（Ｒ３）（Ｒ４）変数「${'$'}{name}」
                    ＞（Ｒ３）（Ｒ４）$tooLong
                    ＞（Ｒ3）（Ｒ4）wrongwidth
                    ＊0Head動的（call）
                    ：\0Dynamic
                    ＊0Headつつかれ
                    ：\0Missing
                    ＊名前のない頭つつかれ
                    ：\0Named
                    ＊0つつかれ
                    ：\0Empty region
                    ＊0Headwrongwidth
                    ：\0Wrong-width selector
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals(5, result.talks.size)
        assertTrue(result.talks.all { it.category == TalkCategory.OTHER })
        assertTrue(result.talks.all { it.touchSpeaker == null && it.touchRegion == null })
    }

    @Test
    fun keepsPointerSuffixDiscoveryWithinOneSourceFile() {
        val result = extractor.extract(
            input(
                source(
                    "dispatch.txt",
                    """
                    ＊OnMouseDoubleClick
                    ＞（Ｒ３）（Ｒ４）つつかれ
                    """.trimIndent(),
                ),
                source(
                    "reaction.txt",
                    """
                    ＊0Headつつかれ
                    ：\0Cross-file
                    """.trimIndent(),
                ),
            ),
        )

        val talk = result.talks.single()
        assertEquals("fixture/reaction.txt", talk.sourcePath)
        assertEquals(TalkCategory.OTHER, talk.category)
        assertNull(talk.touchSpeaker)
        assertNull(talk.touchRegion)
    }

    @Test
    fun doesNotInferWhenObservedSuffixesMakeHeadingAmbiguous() {
        val result = extractor.extract(
            input(
                source(
                    """
                    ＊OnMouseDoubleClick
                    ＞（Ｒ３）（Ｒ４）かれ
                    ＞（Ｒ３）（Ｒ４）つつかれ
                    ＊0Headつつかれ
                    ：\0Ambiguous
                    """.trimIndent(),
                ),
            ),
        )

        val talk = result.talks.single()
        assertEquals(TalkCategory.OTHER, talk.category)
        assertNull(talk.touchSpeaker)
        assertNull(talk.touchRegion)
    }

    @Test
    fun preservesTalkWhenObservedSuffixConsumesSpeakerPrefix() {
        listOf(
            "0" to "0",
            "00" to "00",
            "0Head" to "0Head",
            "1" to "1",
        ).forEach { (suffix, heading) ->
            val result = extractor.extract(
                input(
                    source(
                        """
                        ＊OnMouseDoubleClick
                        ＞（Ｒ３）（Ｒ４）$suffix
                        ＊$heading
                        ：\0Authored $heading
                        """.trimIndent(),
                    ),
                ),
            )

            val talk = result.talks.single()
            assertEquals("Authored $heading", talk.turns.single().text)
            assertEquals(TalkCategory.OTHER, talk.category)
            assertNull(talk.touchSpeaker)
            assertNull(talk.touchRegion)
        }
    }

    @Test
    fun pointerMatchingUsesBoundedHashProbesInsteadOfScanningObservedSuffixes() {
        val observedSuffixes = ProbeCountingSet(
            (0 until 4_096).mapTo(mutableSetOf()) { "suffix$it" },
        )
        val talkCount = 512

        repeat(talkCount) { index ->
            assertNull(
                extractor.pointerMetadata(
                    heading = "0region${index}unmatched",
                    suffixes = observedSuffixes,
                ),
            )
        }

        assertEquals(0, observedSuffixes.iteratedElements)
        assertTrue(observedSuffixes.containsCalls <= talkCount * 64)
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

    private fun source(text: String) = source("talk.txt", text)

    private fun source(name: String, text: String) = GhostSourceFile("fixture/$name", text)

    private fun fixture(name: String): GhostSourceFile {
        val stream = checkNotNull(javaClass.getResourceAsStream("/fixtures/$name"))
        return GhostSourceFile("fixture/$name", stream.bufferedReader().use { it.readText() })
    }

    private class ProbeCountingSet(
        private val values: Set<String>,
    ) : Set<String> by values {
        var containsCalls: Int = 0
            private set
        var iteratedElements: Int = 0
            private set

        override fun contains(element: String): Boolean {
            containsCalls += 1
            return values.contains(element)
        }

        override fun iterator(): Iterator<String> {
            val delegate = values.iterator()
            return object : Iterator<String> {
                override fun hasNext(): Boolean = delegate.hasNext()

                override fun next(): String {
                    iteratedElements += 1
                    return delegate.next()
                }
            }
        }
    }
}
