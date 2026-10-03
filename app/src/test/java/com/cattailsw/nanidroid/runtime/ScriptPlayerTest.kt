package com.cattailsw.nanidroid.runtime

import org.junit.Assert.*
import org.junit.Test

class ScriptPlayerTest {
    @Test fun textAdvanceEdgeSurvivesClearAndDoesNotReplay() {
        val player = ScriptPlayer("\\0A\\c\\e")
        val initial = player.advanceBy(0)
        assertEquals("", initial.sakura.text)
        assertTrue(initial.textAdvanceSpeakers.isEmpty())
        val advanced = player.advanceBy(50)
        assertEquals("", advanced.sakura.text)
        assertEquals(setOf(0), advanced.textAdvanceSpeakers)
        assertTrue(player.advanceBy(50).textAdvanceSpeakers.isEmpty())
    }
    @Test fun implicitEndWaitsAfterLastCharacterAndHidesBalloons() {
        val player = ScriptPlayer("\\0Hi")
        assertEquals("Hi", player.advanceBy(100).sakura.text)
        assertFalse(player.advanceBy(999).ended)
        val ended = player.advanceBy(1)
        assertTrue(ended.ended)
        assertFalse(ended.sakura.balloonVisible)
    }

    @Test fun implicitEndHonorsWaitAndRetainsChoiceAfterCompletion() {
        val waiting = ScriptPlayer("\\0A\\_w[500]")
        assertEquals("A", waiting.advanceBy(50).sakura.text)
        assertFalse(waiting.advanceBy(1_499).ended)
        assertTrue(waiting.advanceBy(1).ended)

        val interaction = ScriptPlayer("\\0A\\q[Yes,id]")
        interaction.advanceBy(50)
        assertTrue(interaction.advanceBy(10_000).ended)
        assertEquals("id", interaction.advanceBy(0).sakura.choices.single().choiceId)
        assertTrue(interaction.advanceBy(999).sakura.balloonVisible)
        assertTrue(interaction.advanceBy(1).ended)
    }
    @Test fun bootScriptRevealsTextThenEnds() {
        val player = ScriptPlayer("\\0\\s0Hi\\e")
        val start = player.advanceBy(0)
        assertFalse(start.ended)
        assertEquals("", start.sakura.text)
        val halfway = player.advanceBy(50)
        assertEquals("H", halfway.sakura.text)
        val complete = player.advanceBy(1_500)
        assertTrue(complete.ended)
        assertEquals("Hi", complete.sakura.text)
        assertEquals(0, complete.sakura.surfaceId)
        assertEquals(10, complete.kero.surfaceId)
        assertFalse(complete.sakura.balloonVisible)
    }

    @Test fun commandsDoNotCostTimeAndUnsupportedPayloadIsHidden() {
        val player = ScriptPlayer("\\1\\s[10]A\\foo[secret]\\nB\\cC\\0\\s[-1]D\\e")
        assertEquals("", player.advanceBy(0).kero.text)
        assertEquals("A", player.advanceBy(50).kero.text)
        assertEquals("A\n", player.advanceBy(50).kero.text)
        assertEquals("", player.advanceBy(50).kero.text)
        assertEquals("C", player.advanceBy(50).kero.text)
        val frame = player.advanceBy(50)
        assertEquals("D", frame.sakura.text)
        assertFalse(frame.sakura.visible)
        assertFalse(frame.sakura.text.contains("secret"))
    }

    @Test fun switchingSpeakersClearsTheNewBalloon() {
        val player = ScriptPlayer("\\0A\\1B\\0C\\e")
        assertEquals("A", player.advanceBy(50).sakura.text)
        assertEquals("B", player.advanceBy(50).kero.text)
        val switched = player.advanceBy(0)
        assertFalse(switched.sakura.balloonVisible)
        assertEquals("A", switched.sakura.text)
    }

    @Test fun speakerSwitchPreservesExistingInlineChoicesAndText() {
        val player = ScriptPlayer("\\_q\\0Sakura\\q[First,first]\\1Kero\\q[Second,second]\\0\\e")
        val frame = player.advanceBy(0)
        assertEquals("Sakura", frame.sakura.text)
        assertEquals("Kero", frame.kero.text)
        assertEquals(listOf("first"), frame.sakura.choices.map { it.choiceId })
        assertEquals(listOf("second"), frame.kero.choices.map { it.choiceId })
        assertTrue(frame.sakura.balloonVisible)
        assertTrue(frame.kero.balloonVisible)
        assertEquals(frame.sakura.choices, player.advanceBy(1_000).sakura.choices)
    }

    @Test fun legacyAndModernWaitsUseDifferentUnits() {
        for (n in 1..9) {
            val player = ScriptPlayer("\\w${n}A")
            assertEquals("", player.advanceBy(n * 50L - 1).sakura.text)
            assertEquals("", player.advanceBy(1).sakura.text)
            assertEquals("A", player.advanceBy(50).sakura.text)
        }
        val modern = ScriptPlayer("\\_w[75]A")
        assertEquals("", modern.advanceBy(124).sakura.text)
        assertEquals("A", modern.advanceBy(1).sakura.text)
    }

    @Test fun quickAndSyncAppendEdgesSurviveClear() {
        val player = ScriptPlayer("\\_q\\_sAB\\cC\\_sD\\e")
        val frame = player.advanceBy(0)
        assertEquals("CD", frame.sakura.text)
        assertEquals("ABC", frame.kero.text)
        assertEquals(setOf(0, 1), frame.textAdvanceSpeakers)
        assertTrue(player.advanceBy(0).textAdvanceSpeakers.isEmpty())
    }

    @Test fun choicesCollectInOrderAtSpeakerOffsetsAndSurviveEnd() {
        val player = ScriptPlayer("\\_qA\\q[One,id1,ignored]\\q[Two,id2]B\\_s\\q[Both,id3]\\e")
        val frame = player.advanceBy(0)
        assertEquals(listOf("id1", "id2", "id3"), frame.sakura.choices.map { it.choiceId })
        assertEquals(listOf(1, 1, 2), frame.sakura.choices.map { it.textOffset })
        assertEquals(listOf("id3"), frame.kero.choices.map { it.choiceId })
        assertEquals(frame.sakura.choices.last().key, frame.kero.choices.single().key)
        assertTrue(player.advanceBy(1_000).sakura.balloonVisible)
        assertTrue(player.advanceBy(0).ended)
    }

    @Test fun inputPausesWithoutTimeDebtAndRequiresMatchingKey() {
        val player = ScriptPlayer("\\_qA\\![open,inputbox,box,500]B\\e")
        val pending = player.advanceBy(10_000)
        val key = pending.input!!.key
        assertEquals("A", pending.sakura.text)
        assertFalse(player.resolveInput(key + 1))
        assertFalse(player.advanceBy(10_000).ended)
        assertTrue(player.resolveInput(key))
        assertFalse(player.resolveInput(key))
        assertEquals("AB", player.advanceBy(0).sakura.text)
        assertFalse(player.advanceBy(999).ended)
        assertTrue(player.advanceBy(1).ended)
    }

    @Test fun animationAndSurfaceCommandsEmitOrderedOneShotEdges() {
        val player = ScriptPlayer("\\_q\\s[2]\\i[7]\\s[2]\\1\\s[-1]\\i[3]\\0\\s[4]\\e")
        val frame = player.advanceBy(0)
        assertEquals(listOf(SurfaceChange(2, 10), SurfaceChange(2, -1), SurfaceChange(4, -1)), frame.surfaceChanges)
        assertEquals(listOf(AnimationRequest(0, 7), AnimationRequest(1, 3)), frame.animationRequests)
        assertTrue(player.advanceBy(0).surfaceChanges.isEmpty())
        assertTrue(player.advanceBy(0).animationRequests.isEmpty())
    }

    @Test fun unknownAndIgnoredTagsConsumePayloadAndKeepAdjacentText() {
        val player = ScriptPlayer("\\_q\\-\\4\\5\\6\\v\\_n\\_V\\_l[x]\\_a[y]\\_v[z]\\foo[secret]A\\b[-1]B\\_b[0]C\\n[half]D\\e")
        val frame = player.advanceBy(0)
        assertEquals("ABC\nD", frame.sakura.text)
        assertTrue(frame.sakura.balloonVisible)
    }

    @Test fun ignoredSingleCharacterTagsDoNotEatFollowingText() {
        val frame = ScriptPlayer("\\_q\\4Hello\\vWorld\\_nAgain\\e").advanceBy(0)
        assertEquals("HelloWorldAgain", frame.sakura.text)
    }

    @Test fun oversizedUnknownArgumentYieldsBeforeConsumingFollowingText() {
        val player = ScriptPlayer("\\_q\\foo[" + "x".repeat(10_000) + "]Z")
        assertEquals("", player.advanceBy(0).sakura.text)
        assertEquals("", player.advanceBy(0).sakura.text)
        assertEquals("Z", player.advanceBy(0).sakura.text)
    }

    @Test fun commandWorkIsBoundedPerAdvance() {
        val player = ScriptPlayer("\\_q" + "\\4".repeat(3_000) + "\\0Z")
        assertEquals("", player.advanceBy(0).sakura.text)
        assertEquals("Z", player.advanceBy(0).sakura.text)
    }

    @Test fun commandCrossingBudgetBoundaryIsDeferredWithoutLosingItsEffect() {
        val player = ScriptPlayer("\\_q" + "\\4".repeat(2_045) + "\\s[2]Z")
        assertEquals(0, player.advanceBy(0).sakura.surfaceId)
        val next = player.advanceBy(0)
        assertEquals(2, next.sakura.surfaceId)
        assertEquals("Z", next.sakura.text)
    }

    @Test fun digitBalloonTagCrossingBudgetBoundaryIsDeferredLikeDigitSurfaceTag() {
        // The first call stops with two characters left, so a three-character \b2 must
        // wait for the next call and count against its budget instead of overrunning.
        val player = ScriptPlayer("\\_q" + "\\4".repeat(2_045) + "A\\b2" +
            "\\4".repeat(2_046) + "\\s2Z")
        assertEquals("A", player.advanceBy(0).sakura.text)
        val second = player.advanceBy(0)
        assertEquals(0, second.sakura.surfaceId)
        assertEquals("A", second.sakura.text)
        val third = player.advanceBy(0)
        assertEquals(2, third.sakura.surfaceId)
        assertEquals("AZ", third.sakura.text)
    }

    @Test fun literalNameSubstitutionUsesLongestMacroWithoutRescan() {
        assertEquals("$\\%keroname|Second|First|Kero|%other",
            ScriptPlayer.substituteNames("%username|%selfname2|%selfname|%keroname|%other",
                mapOf("username" to "$\\%keroname", "selfname" to "First", "selfname2" to "Second", "keroname" to "Kero")))
    }

    @Test fun aliasesNewlineFormsAndBalloonHideKeepTheirDeclaredEffects() {
        val player = ScriptPlayer("\\_q\\h\\s2A\\u\\s9B\\n[half]\\n[50%]\\n\\b[-1]\\_b[0]\\e")
        val frame = player.advanceBy(0)
        assertEquals(2, frame.sakura.surfaceId)
        assertEquals(9, frame.kero.surfaceId)
        assertEquals("A", frame.sakura.text)
        assertEquals("B\n\n\n", frame.kero.text)
        assertFalse(frame.kero.balloonVisible)
        assertEquals(listOf(SurfaceChange(2, 10), SurfaceChange(2, 9)), frame.surfaceChanges)
    }

    @Test fun simpleInputFormAndMalformedTagsAreHandledSafely() {
        val malformed = ScriptPlayer("\\_q\\q[missing]\\![open,inputbox]A\\bad[unclosed")
        val frame = malformed.advanceBy(0)
        assertEquals("A", frame.sakura.text)
        assertTrue(frame.sakura.choices.isEmpty())
        assertNull(frame.input)
        assertFalse(frame.sakura.text.contains("unclosed"))

        val valid = ScriptPlayer("\\![open,inputbox,box]B")
        val pending = valid.advanceBy(0)
        assertEquals("box", pending.input?.boxId)
        assertTrue(valid.resolveInput(pending.input!!.key))
        assertEquals("B", valid.advanceBy(50).sakura.text)
    }

    @Test fun unterminatedChoiceAndInputDoNotCreatePendingActions() {
        val choice = ScriptPlayer("\\_qA\\q[Yes,id")
        val choiceFrame = choice.advanceBy(0)
        assertEquals("A", choiceFrame.sakura.text)
        assertTrue(choiceFrame.sakura.choices.isEmpty())
        assertTrue(choice.advanceBy(1_000).ended)

        val input = ScriptPlayer("\\_qA\\![open,inputbox,box")
        val inputFrame = input.advanceBy(0)
        assertEquals("A", inputFrame.sakura.text)
        assertNull(inputFrame.input)
        assertTrue(input.advanceBy(1_000).ended)
    }

    @Test fun longChoiceCompletesOnceAfterBoundedAdvanceCalls() {
        val label = "L".repeat(9_000)
        val player = ScriptPlayer("\\_q\\q[$label,choice]Z\\e")
        repeat(2) { assertTrue(player.advanceBy(0).sakura.choices.isEmpty()) }
        val completed = player.advanceBy(0)
        assertEquals(label, completed.sakura.choices.single().label)
        assertEquals("choice", completed.sakura.choices.single().choiceId)
        assertEquals("Z", completed.sakura.text)
        assertEquals(1, player.advanceBy(0).sakura.choices.size)
    }

    @Test fun longInputCompletesOnceAfterBoundedAdvanceCalls() {
        val boxId = "B".repeat(9_000)
        val player = ScriptPlayer("\\![open,inputbox,$boxId]Z")
        repeat(2) { assertNull(player.advanceBy(0).input) }
        val pending = player.advanceBy(0)
        assertEquals(boxId, pending.input?.boxId)
        assertEquals("", pending.sakura.text)
        assertTrue(player.resolveInput(pending.input!!.key))
        assertFalse(player.resolveInput(pending.input.key))
        assertEquals("Z", player.advanceBy(50).sakura.text)
    }

    @Test fun deferredBalloonCommandExecutesAndUnterminatedLongInputDoesNot() {
        val balloon = ScriptPlayer("\\_qA\\_b[" + " ".repeat(9_000) + "-1]")
        repeat(2) { assertTrue(balloon.advanceBy(0).sakura.balloonVisible) }
        assertFalse(balloon.advanceBy(0).sakura.balloonVisible)

        val input = ScriptPlayer("\\![open,inputbox," + "B".repeat(9_000))
        repeat(3) { assertNull(input.advanceBy(0).input) }
        assertTrue(input.advanceBy(1_000).ended)
    }

    @Test fun knownTagsWithoutBracketsDoNotStallBeforeLongPlainText() {
        val text = "A".repeat(9_000)
        for (prefix in listOf("\\_q", "\\q", "\\s")) {
            val player = ScriptPlayer(prefix + text)
            repeat(4) { player.advanceBy(0) }
            val frame = player.advanceBy(0)
            if (prefix == "\\_q") assertEquals(text, frame.sakura.text)
            else {
                // The ordinary 50 ms cadence still applies without quick mode.
                assertEquals("", frame.sakura.text)
                assertEquals("A", player.advanceBy(50).sakura.text)
            }
        }
    }

    @Test fun unicodeTextAdjacentToTagsRemainsVisible() {
        val frame = ScriptPlayer("\\_q\\xそれでね、\\n日付[2026]\\e").advanceBy(0)
        assertEquals("それでね、\n日付[2026]", frame.sakura.text)
    }

    @Test fun unbracketedBalloonNumberIsConsumedWithoutText() {
        val frame = ScriptPlayer("\\_q前\\b2後\\b[-1]\\_b[0]\\e").advanceBy(0)
        assertEquals("前後", frame.sakura.text)
        assertFalse(frame.sakura.balloonVisible)
    }

    @Test fun onChoiceKeepsPositionalReferencesAndOrdinaryChoiceDoesNot() {
        val frame = ScriptPlayer("\\_q\\q[Last,OnLastTalk,one,two]\\q[Other,ordinary,ignored]\\e").advanceBy(0)
        assertEquals(listOf("one", "two"), frame.sakura.choices[0].references)
        assertTrue(frame.sakura.choices[1].references.isEmpty())
    }

    @Test fun choiceWithHeaderControlsIsRejected() {
        val frame = ScriptPlayer("\\_q\\q[Bad,OnLastTalk,ok\r\nInjected: yes]\\q[Good,ordinary]\\e").advanceBy(0)
        assertEquals(listOf("ordinary"), frame.sakura.choices.map { it.choiceId })
    }

    @Test fun satoriChoiceMetadataReachesNativeInChoiceReference() {
        // Unchanged 2elf 2.46 Satori reply appends label and ordinal after U+0001 separators.
        val frame = ScriptPlayer("\\_q\\q[ 見切れ・重なり・消滅表示,見切れ重なり消滅表示\u0001 見切れ・重なり・消滅表示\u00015]\\e")
            .advanceBy(0)
        val choice = frame.sakura.choices.single()
        assertEquals("見切れ重なり消滅表示\u0001 見切れ・重なり・消滅表示\u00015", choice.choiceId)
        assertEquals("OnChoiceSelect", choice.event.id)
        assertEquals(listOf("見切れ重なり消滅表示\u0001 見切れ・重なり・消滅表示\u00015"), choice.event.references)
    }

    @Test fun malformedSatoriMetadataAndUnsafeDispatchIdsAreRejected() {
        val frame = ScriptPlayer("\\_q" +
            "\\q[Bad prefix,OnBad\r\nHeader: x\u0001Bad prefix\u00011]" +
            "\\q[Bad metadata,safe\u0001line\r\nHeader: x\u00012]" +
            "\\q[Bad ordinal,safe\u0001label\u0001not-a-number]" +
            "\\q[Bad event,OnUnsafe\u0001Bad event\u00014]" +
            "\\q[Good,safe\u0001Good\u00013]\\e").advanceBy(0)
        assertEquals(listOf("Good"), frame.sakura.choices.map { it.label })
        assertEquals(listOf("safe\u0001Good\u00013"), frame.sakura.choices.map { it.choiceId })
    }

    @Test fun unchangedEarthquakeBalloonFragmentDoesNotPrintThemeDigit() {
        // Earthquake Duo 1.0.1, duo_menu.dic:28 (archive SHA-256 06db71e7...)
        val frame = ScriptPlayer("\\0\\s[0]\\b2Welcom to the offical MAN Software menu. Please make a selection when you are ready.").advanceBy(5_000)
        assertEquals("Welcom to the offical MAN Software menu. Please make a selection when you are ready.", frame.sakura.text)
    }
}
