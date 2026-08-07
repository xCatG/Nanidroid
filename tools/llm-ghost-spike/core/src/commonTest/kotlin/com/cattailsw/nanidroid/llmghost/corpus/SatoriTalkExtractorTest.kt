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
    fun implicitlyAlternatesEachDialogueLineFromNativeKeroInitialScope() {
        val result = extractor.extract(
            input(source("＊Random\n：一行目\n：二行目\n：三行目\n")),
        )

        assertEquals(
            listOf(GhostSpeakerId.SAKURA, GhostSpeakerId.KERO, GhostSpeakerId.SAKURA),
            result.talks.single().turns.map { it.speaker },
        )
        assertEquals(listOf("一行目", "二行目", "三行目"), result.talks.single().turns.map { it.text })
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun persistsFullWidthNumericSurfacesIndependentlyForEachSpeaker() {
        val result = extractor.extract(
            input(
                source(
                    "＊Random\n：（３）桜A\n：（１９）ケロA\n：桜B\n：ケロB\n",
                ),
            ),
        )

        assertEquals(
            listOf(
                Triple(GhostSpeakerId.SAKURA, 3, "桜A"),
                Triple(GhostSpeakerId.KERO, 19, "ケロA"),
                Triple(GhostSpeakerId.SAKURA, 3, "桜B"),
                Triple(GhostSpeakerId.KERO, 19, "ケロB"),
            ),
            result.talks.single().turns.map { Triple(it.speaker, it.surface, it.text) },
        )
    }

    @Test
    fun explicitScopesOverrideTheCurrentLineAndDriveTheNextNativeToggle() {
        val result = extractor.extract(
            input(source("＊Random\n：\\1一行目\n：二行目\n：\\h三行目\n：四行目\n")),
        )

        assertEquals(
            listOf(GhostSpeakerId.KERO, GhostSpeakerId.SAKURA, GhostSpeakerId.SAKURA, GhostSpeakerId.KERO),
            result.talks.single().turns.map { it.speaker },
        )
    }

    @Test
    fun extractsRealShapedJapaneseTalkAndStripsOnlySafePresentationControls() {
        val result = extractor.extract(input(fixture("native-satori-talks.txt")))

        val safeTalk = result.talks.single()
        assertEquals("森の朝", safeTalk.heading)
        assertEquals(
            listOf(
                Triple(GhostSpeakerId.SAKURA, 3, "おはよう、今日は静かね。"),
                Triple(GhostSpeakerId.KERO, 19, "うん、少し待って……行こう。"),
                Triple(GhostSpeakerId.SAKURA, 3, "忘れ物はない？またね。"),
            ),
            safeTalk.turns.map { Triple(it.speaker, it.surface, it.text) },
        )
        assertTrue(result.diagnostics.any { it.code == "unsupported-control" && it.line == 7 })
    }

    @Test
    fun presentationOnlyLineStillTogglesNativeScope() {
        val result = extractor.extract(
            input(source("＊Random\n：\\w8\\w9\\e\n：次はケロの台詞\n")),
        )

        val turn = result.talks.single().turns.single()
        assertEquals(GhostSpeakerId.KERO, turn.speaker)
        assertEquals("次はケロの台詞", turn.text)
    }

    @Test
    fun rejectsAllowlistedPrefixesWhenNativeWouldConsumeALongerCommandToken() {
        val unsafeBodies = listOf(
            "\\efoo",
            "\\e[x]",
            "\\w8foo",
            "\\w8[x]",
            "\\0foo",
            "\\1bar",
            "\\hello",
            "\\u_name",
            "\\h?tail",
            "\\1!tail",
            "\\e9tail",
            "\\e*tail",
            "\\e&tail",
        )
        val sourceText = unsafeBodies.mapIndexed { index, body ->
            "＊UnsafeToken$index\n：$body"
        }.joinToString("\n")

        val result = extractor.extract(input(source(sourceText)))

        assertTrue(result.talks.isEmpty())
        assertEquals(unsafeBodies.size, result.diagnostics.count { it.code == "unsupported-control" })
    }

    @Test
    fun acceptsSlashAsANativeCommandDelimiterAndRetainsVisiblePathText() {
        val result = extractor.extract(
            input(source("＊SlashDelimiter\n：\\e/path\n：\\w8/path\n：\\0/path\n")),
        )

        assertEquals(listOf("/path", "/path", "/path"), result.talks.single().turns.map { it.text })
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun slashDelimiterCannotBypassTheIndependentCaseInsensitiveUrlGuard() {
        val result = extractor.extract(
            input(source("＊UnsafeUrl\n：\\e/HTTPS://example.invalid/action\n")),
        )

        assertTrue(result.talks.isEmpty())
        assertTrue(result.diagnostics.any { it.code == "unsupported-control" })
    }

    @Test
    fun acceptsExactUnbracketedControlsBeforeJapanesePunctuationOrEndOfLine() {
        val result = extractor.extract(
            input(
                source(
                    "＊Exact\n：\\0日本語\n：\\1、続き\n：\\h。句点\n：\\u！感嘆\n：\\w8…待った\n：\\e\n：\\s[3]表情\n：\\s[19]foo\n",
                ),
            ),
        )

        assertEquals(
            listOf("日本語", "、続き", "。句点", "！感嘆", "…待った", "表情", "foo"),
            result.talks.single().turns.map { it.text },
        )
        assertEquals(19, result.talks.single().turns.last().surface)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun rejectsHugeNativeCommandSuffixWithoutThrowingOrScanningPastTheLineBound() {
        val result = extractor.extract(
            input(source("＊HugeToken\n：\\e${"a".repeat(65_534)}\n")),
        )

        assertTrue(result.talks.isEmpty())
        assertTrue(result.diagnostics.any { it.code in setOf("unsupported-control", "malformed-control") })
    }

    @Test
    fun rejectsMalformedOrUnsafeFullWidthKakkoWithoutEvaluatingIt() {
        val unsafeTokens = listOf(
            "（）",
            "（－１）",
            "（-１）",
            "（２A）",
            "（２（３））",
            "（ユーザ名）",
            "（Ｒ０）",
            "（２１４７４８３６４８）",
            "(3)",
        )
        val sourceText = unsafeTokens.mapIndexed { index, token ->
            "＊Unsafe$index\n：${token}本文"
        }.joinToString("\n")

        val result = extractor.extract(input(source(sourceText)))

        assertTrue(result.talks.isEmpty())
        assertEquals(unsafeTokens.size, result.diagnostics.count { it.code in setOf("unsupported-control", "malformed-control") })
    }

    @Test
    fun rejectsUnknownCommandsUrlsAndExpressionsAtTheTalkBoundary() {
        val unsafeBodies = listOf(
            "\\c消去",
            "\\_q選択",
            "\\q選択",
            "https://example.invalid/action",
            "HTTPS://example.invalid/action",
            "${'$'}{user}",
            "${'$'}(call)",
        )
        val sourceText = unsafeBodies.mapIndexed { index, body ->
            "＊Unsafe$index\n：$body"
        }.joinToString("\n")

        val result = extractor.extract(input(source(sourceText)))

        assertTrue(result.talks.isEmpty())
        assertEquals(unsafeBodies.size, result.diagnostics.count { it.code == "unsupported-control" })
    }

    @Test
    fun boundsSurfaceAndPresentationControlScanningWithoutThrowing() {
        val hugeSurface = "９".repeat(20_000)
        val hugeWait = "9".repeat(20_000)
        val result = extractor.extract(
            input(source("＊Surface\n：（$hugeSurface）本文\n＊Wait\n：\\w${hugeWait}本文\n")),
        )

        assertTrue(result.talks.isEmpty())
        assertEquals(2, result.diagnostics.count { it.code == "malformed-control" })
    }

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
                    ：First
                    ＊1l-headつつかれ
                    ：Second
                    ＊1l-headつつかれ
                    ：Duplicate
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
    fun preservesPointerReactionMetadataWithImplicitNativeScope() {
        val result = extractor.extract(
            input(
                source(
                    "＊OnMouseDoubleClick\n＞（Ｒ３）（Ｒ４）つつかれ\n＊0Headつつかれ\n：（３）やさしくしてね。\n",
                ),
            ),
        )

        val talk = result.talks.single()
        assertEquals(TalkCategory.TOUCH, talk.category)
        assertEquals(GhostSpeakerId.SAKURA, talk.touchSpeaker)
        assertEquals("Head", talk.touchRegion)
        assertEquals(Triple(GhostSpeakerId.SAKURA, 3, "やさしくしてね。"), talk.turns.single().let {
            Triple(it.speaker, it.surface, it.text)
        })
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
                    ：Dynamic
                    ＊0Headつつかれ
                    ：Missing
                    ＊名前のない頭つつかれ
                    ：Named
                    ＊0つつかれ
                    ：Empty region
                    ＊0Headwrongwidth
                    ：Wrong-width selector
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
                    ：Cross-file
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
                    ：Ambiguous
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
                        ：Authored $heading
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
