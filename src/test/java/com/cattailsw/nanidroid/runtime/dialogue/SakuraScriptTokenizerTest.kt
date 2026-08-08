package com.cattailsw.nanidroid.runtime.dialogue

import com.cattailsw.nanidroid.runtime.GhostSpeaker
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic, ASCII-compatible grammar fixtures for structured SakuraScript. */
class SakuraScriptTokenizerTest {
    @Test
    fun choicesKeepSpeakerOwnershipQuotedEmptyAndDoubledQuoteReferences() {
        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(
                        DialogueSegment.Text("Sakura: "),
                        DialogueSegment.Choice(
                            DialogueAction.Normal(
                                "Pick, please",
                                "choice-id",
                                listOf("first", "", "third, value", "quoted \"value\""),
                            ),
                        ),
                    ),
                ),
                DialogueContent(
                    GhostSpeaker.KERO,
                    listOf(
                        DialogueSegment.SpeakerChangeClear,
                        DialogueSegment.Text("Kero: "),
                        DialogueSegment.Choice(
                            DialogueAction.Normal("Second", "second-id", emptyList()),
                        ),
                    ),
                ),
            ),
            tokenize(
                "\\hSakura: \\q[\"Pick, please\",choice-id,first,\"\",\"third, value\",\"quoted \"\"value\"\"\"]" +
                    "\\uKero: \\q[Second,second-id]\\e",
            ),
        )
    }

    @Test
    fun directAndNestedScriptChoicesRemainTypedAndDoNotSplitBalancedPayloads() {
        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(
                        DialogueSegment.Choice(
                            DialogueAction.DirectEvent(
                                "Direct",
                                "OnPick",
                                listOf("a,b", "c\"d", "", "escaped ]"),
                            ),
                        ),
                        DialogueSegment.Choice(
                            DialogueAction.Script("Local", "\\h\\q[inner,inside] queued\\e"),
                        ),
                    ),
                ),
            ),
            tokenize(
                "\\q[Direct,OnPick,\"a,b\",\"c\"\"d\",\"\",escaped \\]]" +
                    "\\q[Local,\"script:\\h\\q[inner,inside] queued\\e\"]\\e",
            ),
        )
    }

    @Test
    fun anchorsRemainAnchorsEvenWhenTheirIdsLookLikeUrlsAndJCreatesExternalUrls() {
        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(
                        DialogueSegment.Text("Before "),
                        DialogueSegment.Anchor(
                            AnchorAction.Normal("anchor label", "anchor-id", listOf("a,b", "")),
                        ),
                        DialogueSegment.Text(" and "),
                        DialogueSegment.Anchor(
                            AnchorAction.DirectEvent("event label", "OnAnchor", listOf("one", "two")),
                        ),
                        DialogueSegment.Text(" then "),
                        DialogueSegment.Anchor(
                            AnchorAction.Normal("not external", "https://example.invalid/a,b?x=1", emptyList()),
                        ),
                        DialogueSegment.Text(" and "),
                        DialogueSegment.ExternalUrl("https://example.invalid/real", "https://example.invalid/real"),
                        DialogueSegment.Text("."),
                    ),
                ),
            ),
            tokenize(
                "\\hBefore \\_a[anchor-id,\"a,b\",\"\"]anchor label\\_a and " +
                    "\\_a[OnAnchor,one,two]event label\\_a then " +
                    "\\_a[\"https://example.invalid/a,b?x=1\"]not external\\_a and " +
                    "\\j[https://example.invalid/real].\\e",
            ),
        )
    }

    @Test
    fun positionalAndNamedInputBoxesRetainAllAuthoredData() {
        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(
                        DialogueSegment.Text("Ask: "),
                        DialogueSegment.InputBox(
                            InputBoxSpec(
                                dispatch = InputDispatch.Normal("answer-id"),
                                timeoutMillis = 9_000L,
                                initialText = "hello, world",
                                behaviorOptions = setOf(
                                    InputBehavior.PASSWORD,
                                    InputBehavior.MULTILINE,
                                    InputBehavior.NO_EMPTY,
                                    InputBehavior.NO_CANCEL,
                                ),
                                supplement = "",
                                extraReferences = listOf("a,b", "", "tail"),
                                unknownOptions = listOf("future", "--flag=value"),
                            ),
                        ),
                        DialogueSegment.InputBox(
                            InputBoxSpec(
                                dispatch = InputDispatch.DirectEvent("OnReply"),
                                timeoutMillis = null,
                                initialText = "",
                                behaviorOptions = emptySet(),
                                supplement = "named supplement",
                                extraReferences = emptyList(),
                                unknownOptions = emptyList(),
                            ),
                        ),
                        DialogueSegment.Text(" done"),
                    ),
                ),
            ),
            tokenize(
                "\\hAsk: \\![open,inputbox,answer-id,9000,\"hello, world\",--option=password," +
                    "--option=multiline,--option=noempty,--option=nocancel," +
                    "--option=future,--flag=value,--reference=\"a,b\"," +
                    "--reference=\"\",--reference=tail]" +
                    "\\![open,inputbox,OnReply,--supplement=\"named supplement\"] done\\e",
            ),
        )
    }

    @Test
    fun positionalInitialTextIsRetainedWhenDisplayTimeIsOmitted() {
        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(
                        DialogueSegment.InputBox(
                            InputBoxSpec(
                                dispatch = InputDispatch.Normal("answer-id"),
                                timeoutMillis = null,
                                initialText = "prefilled",
                                behaviorOptions = emptySet(),
                                supplement = "",
                                extraReferences = emptyList(),
                                unknownOptions = emptyList(),
                            ),
                        ),
                    ),
                ),
            ),
            tokenize("\\![open,inputbox,answer-id,prefilled]\\e"),
        )
    }

    @Test
    fun speakerAliasesAtomicallySelectTheirMatchingScope() {
        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.KERO,
                    listOf(
                        DialogueSegment.SpeakerChangeClear,
                        DialogueSegment.Text("K"),
                        DialogueSegment.SpeakerChangeClear,
                        DialogueSegment.Text("K"),
                        DialogueSegment.SpeakerChangeClear,
                        DialogueSegment.Text("K"),
                        DialogueSegment.SpeakerChangeClear,
                    ),
                ),
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(
                        DialogueSegment.SpeakerChangeClear,
                        DialogueSegment.Text("SS"),
                        DialogueSegment.SpeakerChangeClear,
                        DialogueSegment.Text("visible"),
                    ),
                ),
            ),
            tokenize("\\uK\\p0S\\hS\\p1K\\p2drop\\hvisible\\p1K\\1"),
        )
    }

    @Test
    fun speakerReentryClearsPreviousSegmentsFromReenteredSpeaker() {
        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(
                        DialogueSegment.Text("First"),
                        DialogueSegment.Anchor(
                            AnchorAction.Normal("old", "anchor-id", listOf()),
                        ),
                        DialogueSegment.Choice(
                            DialogueAction.Normal("Old", "old-choice", emptyList()),
                        ),
                        DialogueSegment.SpeakerChangeClear,
                        DialogueSegment.Text("Second"),
                    ),
                ),
                DialogueContent(
                    GhostSpeaker.KERO,
                    listOf(
                        DialogueSegment.SpeakerChangeClear,
                        DialogueSegment.Text("Reply"),
                    ),
                ),
            ),
            tokenize("\\hFirst\\_a[anchor-id]old\\_a\\q[Old,old-choice]\\uReply\\hSecond\\e"),
        )
    }

    @Test
    fun repeatedSameSpeakerSelectionAccumulatesVisibleSegments() {
        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(
                        DialogueSegment.Text("First"),
                        DialogueSegment.Choice(DialogueAction.Normal("Keep", "keep", emptyList())),
                        DialogueSegment.Text("Again"),
                    ),
                ),
            ),
            tokenize("\\hFirst\\q[Keep,keep]\\hAgain\\e"),
        )
    }

    @Test
    fun reselectingCurrentKeroSelectionClearsItsText() {
        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.KERO,
                    listOf(
                        DialogueSegment.SpeakerChangeClear,
                        DialogueSegment.Text("First"),
                        DialogueSegment.SpeakerChangeClear,
                        DialogueSegment.Text("Again"),
                    ),
                ),
            ),
            tokenize("\\uFirst\\uAgain\\e"),
        )
        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.KERO,
                    listOf(
                        DialogueSegment.SpeakerChangeClear,
                        DialogueSegment.Text("First"),
                        DialogueSegment.SpeakerChangeClear,
                        DialogueSegment.Text("Again"),
                    ),
                ),
            ),
            tokenize("\\1First\\1Again\\e"),
        )
    }

    @Test
    fun synchronizedOutputIsDuplicatedUntilToggledOff() {
        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(
                        DialogueSegment.Text("ABC"),
                    ),
                ),
                DialogueContent(
                    GhostSpeaker.KERO,
                    listOf(
                        DialogueSegment.Text("B"),
                    ),
                ),
            ),
            tokenize("\\hA\\_sB\\_sC\\e"),
        )
    }

    @Test
    fun synchronizedNewlineIsVisibleToBothSpeakers() {
        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(
                        DialogueSegment.Text("A"),
                        DialogueSegment.NewLine,
                        DialogueSegment.Text("BC"),
                    ),
                ),
                DialogueContent(
                    GhostSpeaker.KERO,
                    listOf(DialogueSegment.NewLine, DialogueSegment.Text("B")),
                ),
            ),
            tokenize("\\hA\\_s\\nB\\_sC\\e"),
        )
    }

    @Test
    fun completedSynchronizedAnchorKeepsOtherSpeakerLabelWithoutDuplicatingCapability() {
        val revealed = SakuraScriptTokenizer.tokenizeRevealed("\\h\\_s\\_a[id]Link")
        val complete = tokenize("\\h\\_s\\_a[id]Link\\_a\\_s\\e")

        assertEquals(
            listOf(
                DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text("Link"))),
                DialogueContent(GhostSpeaker.KERO, listOf(DialogueSegment.Text("Link"))),
            ),
            revealed,
        )
        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(DialogueSegment.Anchor(AnchorAction.Normal("Link", "id", emptyList()))),
                ),
                DialogueContent(GhostSpeaker.KERO, listOf(DialogueSegment.Text("Link"))),
            ),
            complete,
        )
        assertEquals(
            1,
            complete.sumOf { content -> content.segments.count { it is DialogueSegment.Anchor } },
        )
    }

    @Test
    fun synchronizedChoiceKeepsCapabilityOnOwnerAndMirrorsOnlyItsLabel() {
        val contents = tokenize("\\h\\_s\\q[Pick,choice-id]\\_s\\e")

        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(
                        DialogueSegment.Choice(
                            DialogueAction.Normal("Pick", "choice-id", emptyList()),
                        ),
                    ),
                ),
                DialogueContent(GhostSpeaker.KERO, listOf(DialogueSegment.Text("Pick"))),
            ),
            contents,
        )
        assertEquals(
            1,
            contents.sumOf { content -> content.segments.count { it is DialogueSegment.Choice } },
        )
    }

    @Test
    fun speakerAliasesMirrorPrimaryAndSecondarySelectionWithPerSpeakerProjection() {
        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(
                        DialogueSegment.Text("first"),
                        DialogueSegment.SpeakerChangeClear,
                        DialogueSegment.Text("second"),
                        DialogueSegment.SpeakerChangeClear,
                        DialogueSegment.Text("fourth"),
                    ),
                ),
                DialogueContent(
                    GhostSpeaker.KERO,
                    listOf(
                        DialogueSegment.SpeakerChangeClear,
                        DialogueSegment.Text("reply"),
                        DialogueSegment.SpeakerChangeClear,
                        DialogueSegment.Text("third"),
                    ),
                ),
            ),
            tokenize("\\0first\\1reply\\p0second\\p1third\\p0fourth\\e"),
        )
    }

    @Test
    fun positionalInputAllowsEmptyTimeoutSlotAndNamedInvalidTimeoutIsRetainedAsUnknown() {
        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(
                        DialogueSegment.InputBox(
                            InputBoxSpec(
                                InputDispatch.Normal("first"), null, "prefilled", emptySet(), "",
                                emptyList(), emptyList(),
                            ),
                        ),
                        DialogueSegment.InputBox(
                            InputBoxSpec(
                                InputDispatch.Normal("second"), 7L, "", emptySet(), "",
                                emptyList(), listOf("--timeout=abc"),
                            ),
                        ),
                    ),
                ),
            ),
            tokenize("\\![open,inputbox,first,,prefilled]\\![open,inputbox,second,7,--timeout=abc]\\e"),
        )
    }

    @Test
    fun recognizedControlsAndUnsupportedUrlsAreConsumedWithoutLeakingPayloads() {
        val diagnostics = mutableListOf<String>()

        assertEquals(
            listOf(DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text("ABC")))),
            tokenize(
                "\\h\\s0\\b2\\f[Snake]\\x[Otacon]\\_s[1]A" +
                    "\\j[mailto:otacon@example.invalid]B\\q[unterminated\\hC\\e",
                diagnostics,
            ),
        )
        Assert.assertTrue(diagnostics.any { it.startsWith("unsupported-url:") })
        Assert.assertTrue(diagnostics.contains("truncated-choice"))
    }

    @Test
    fun malformedScopeAndTruncatedAnchorAndUrlResumeAtTheNextCommand() {
        val diagnostics = mutableListOf<String>()

        assertEquals(
            listOf(DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text("good")))),
            tokenize("\\p[nope]\\_a[id]broken\\hgood\\j[broken\\h", diagnostics),
        )
        Assert.assertEquals(
            listOf("malformed-scope", "truncated-anchor", "truncated-url"),
            diagnostics,
        )
    }

    @Test
    fun commandAwareControlsDoNotLeakBracketPayloadsAndDirectIdsConsumeOneDigit() {
        val diagnostics = mutableListOf<String>()

        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(
                        DialogueSegment.Wait(5L),
                        DialogueSegment.Text("A202420242024BC"),
                    ),
                ),
            ),
            tokenize(
                "\\_b[balloon,2]\\_v[Snake]\\_l[Otacon]\\_w[5]" +
                    "\\r[unknown]\\_r[unknown]\\z[unknown]\\_z[unknown]A" +
                        "\\b22024\\x2024\\i2024B\\c[char,0]C\\e",
                diagnostics,
            ),
        )
        Assert.assertTrue(diagnostics.any { it.startsWith("unsupported-command:r") })
        Assert.assertTrue(diagnostics.any { it.startsWith("unsupported-command:_r") })
    }

    @Test
    fun anchorClosingMarkerMustBeUnescapedAndLabelIsFlattenedWithoutActionControls() {
        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(
                        DialogueSegment.Anchor(
                            AnchorAction.Normal("literal \\_a formatted\nlabel", "anchor-id", emptyList()),
                        ),
                    ),
                ),
            ),
            tokenize("\\_a[anchor-id]literal \\\\_a \\s[120]formatted\\z[hidden]\\nlabel\\_a\\e"),
        )
    }

    @Test
    fun truncatedUnderscoreWaitAndGenericUnknownControlsResumeWithoutPayloadLeakage() {
        val diagnostics = mutableListOf<String>()

        assertEquals(
            listOf(DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text("safe")))),
            tokenize("\\_w[broken\\h\\z[unknown]\\_z[unknown]safe\\e", diagnostics),
        )
        Assert.assertTrue(diagnostics.contains("unsupported-command:z"))
        Assert.assertTrue(diagnostics.contains("unsupported-command:_z"))
        Assert.assertTrue(diagnostics.contains("truncated-wait"))
    }

    @Test
    fun malformedTopLevelScopeAndNewlineRecoverWithoutLeakingTheirBracketPayload() {
        val scopeDiagnostics = mutableListOf<String>()
        val newlineDiagnostics = mutableListOf<String>()

        assertEquals(
            listOf(DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text("good")))),
            tokenize("\\p[broken\\hgood\\e", scopeDiagnostics),
        )
        assertEquals(listOf("malformed-scope"), scopeDiagnostics)
        assertEquals(
            listOf(DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text("good")))),
            tokenize("\\n[broken\\hgood\\e", newlineDiagnostics),
        )
        assertEquals(listOf("truncated-newline"), newlineDiagnostics)
    }

    @Test
    fun flattenedAnchorLabelsFollowTopLevelControlOwnership() {
        val diagnostics = mutableListOf<String>()

        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(DialogueSegment.Anchor(AnchorAction.Normal("line\n2024text", "id", emptyList()))),
                ),
            ),
            tokenize("\\_a[id]line\\n[half]\\i2024\\p0text\\_a\\e", diagnostics),
        )
        assertEquals(emptyList<String>(), diagnostics)

        assertEquals(
            listOf(DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text("good")))),
            tokenize("\\_a[id]bad\\n[broken\\hgood\\e", diagnostics),
        )
    }

    @Test
    fun scopesAndCompletePassiveTokensAreOrderedButNeverBecomeVisibleText() {
        val diagnostics = mutableListOf<String>()

        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(
                        DialogueSegment.Text("A"),
                        DialogueSegment.PassiveMode(entering = true),
                        DialogueSegment.PassiveMode(entering = false),
                        DialogueSegment.NewLine,
                        DialogueSegment.Wait(200L),
                        DialogueSegment.Clear,
                        DialogueSegment.Text("B\\C"),
                    ),
                ),
            ),
            tokenize(
                "\\hA\\4\\p2drop\\q[ignored,ignored-id]\\![open,inputbox,ignored,9000,ignored]" +
                    "\\p[2]still dropped\\q[also-ignored,id]\\![open,inputbox,ignored-too,9000,ignored]\\p0" +
                    "\\![enter,passivemode]\\![leave,passivemode]\\n[half]\\w4\\cB\\\\C\\e",
                diagnostics,
            ),
        )
        assertEquals(listOf("unsupported-presentation:4", "unsupported-scope:2"), diagnostics)
    }

    @Test
    fun malformedAndUnknownControlsResynchronizeWithoutLeakingOrSwallowingLaterContent() {
        val diagnostics = mutableListOf<String>()

        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(
                        DialogueSegment.Text("before after "),
                        DialogueSegment.Choice(
                            DialogueAction.Normal("usable", "id", emptyList()),
                        ),
                        DialogueSegment.Text(" tail"),
                    ),
                ),
            ),
            tokenize(
                "\\hbefore \\![enter,passivemode,unexpected]after \\![open,inputbox,broken " +
                    "\\q[usable,id]\\![future,unknown] tail\\e",
                diagnostics,
            ),
        )
        assertEquals(
            listOf("malformed-passive", "truncated-inputbox", "unsupported-command:future"),
            diagnostics,
        )
    }

    @Test
    fun tokenizationRetainsChoiceSourceOrderAcrossSpeakerReturns() {
        val tokenization = SakuraScriptTokenizer.tokenizeWithInteractions(
            "\\h\\q[A,a]\\u\\q[B,b]\\h\\q[C,c]",
        )

        assertEquals(listOf("A", "B", "C"), tokenization.interactions.map { it.action.label() })
        assertEquals(
            listOf(GhostSpeaker.SAKURA, GhostSpeaker.KERO, GhostSpeaker.SAKURA),
            tokenization.interactions.map { it.speaker },
        )
        Assert.assertTrue(tokenization.interactions.zipWithNext().all { (first, second) ->
            first.sourceEnd < second.sourceEnd
        })
    }

    @Test
    fun remainingVisibleChoicesSkipsChoicesInUnsupportedScopes() {
        val script = "\\q[A,a]\\p2\\q[H,h]\\p0\\q[B,b]"

        assertEquals(
            listOf(
                LegacyChoice("A", "a"),
                LegacyChoice("B", "b"),
            ),
            SakuraScriptTokenizer.remainingVisibleChoices(
                script = script,
                commandStart = 0,
                initialScope = 0,
            ),
        )
    }

    private fun tokenize(script: String, diagnostics: MutableList<String> = mutableListOf()): List<DialogueContent> =
        SakuraScriptTokenizer.tokenize(script, diagnostics::add)

    private fun DialogueAction.label(): String = when (this) {
        is DialogueAction.Normal -> label
        is DialogueAction.DirectEvent -> label
        is DialogueAction.Script -> label
    }
}
