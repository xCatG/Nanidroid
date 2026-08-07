package com.cattailsw.nanidroid.llmghost.generation

import com.cattailsw.nanidroid.llmghost.model.GeneratedDialogue
import com.cattailsw.nanidroid.llmghost.model.GeneratedTurn
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeneratedDialogueValidatorTest {
    private val validator = GeneratedDialogueValidator()

    @Test
    fun rejectsZeroAndNineTurns() {
        assertEquals(
            listOf(DialogueViolation("turn-count", null, "Dialogue must contain between 1 and 8 turns.")),
            validator.validate(GeneratedDialogue(emptyList()), VALID_SURFACES).violations,
        )
        assertEquals(
            listOf(DialogueViolation("turn-count", null, "Dialogue must contain between 1 and 8 turns.")),
            validator.validate(
                GeneratedDialogue(List(9) { GeneratedTurn("sakura", 3, "Turn $it") }),
                VALID_SURFACES,
            ).violations,
        )
    }

    @Test
    fun rejectsUnknownSpeakerWithoutRepairingItsCase() {
        val result = validator.validate(
            GeneratedDialogue(listOf(GeneratedTurn("SAKURA", 3, "Hello"))),
            VALID_SURFACES,
        )

        assertNull(result.dialogue)
        assertEquals("unknown-speaker", result.violations.single().code)
        assertEquals(0, result.violations.single().turnIndex)
    }

    @Test
    fun rejectsBlankAndMoreThanFiveHundredUnicodeScalars() {
        val result = validator.validate(
            GeneratedDialogue(
                listOf(
                    GeneratedTurn("sakura", 3, "   "),
                    GeneratedTurn("kero", 19, "😀".repeat(501)),
                ),
            ),
            VALID_SURFACES,
        )

        assertEquals(
            listOf("text-blank", "text-too-long"),
            result.violations.map { it.code },
        )
        assertEquals(listOf(0, 1), result.violations.map { it.turnIndex })
    }

    @Test
    fun countsSupplementaryCharactersAsOneUnicodeScalar() {
        val result = validator.validate(
            GeneratedDialogue(listOf(GeneratedTurn("sakura", 3, "😀".repeat(500)))),
            VALID_SURFACES,
        )

        assertNotNull(result.dialogue)
        assertTrue(result.violations.isEmpty())
    }

    @Test
    fun rejectsUnpairedHighAndLowSurrogates() {
        val result = validator.validate(
            GeneratedDialogue(
                listOf(
                    GeneratedTurn("sakura", 3, "bad\uD800text"),
                    GeneratedTurn("kero", 19, "bad\uDC00text"),
                ),
            ),
            VALID_SURFACES,
        )

        assertEquals(listOf("invalid-unicode", "invalid-unicode"), result.violations.map { it.code })
        assertEquals(listOf(0, 1), result.violations.map { it.turnIndex })
    }

    @Test
    fun rejectsWaitsOutsideZeroThroughTwoThousandMilliseconds() {
        val result = validator.validate(
            GeneratedDialogue(
                listOf(
                    GeneratedTurn("sakura", 3, "Hello", -1),
                    GeneratedTurn("kero", 19, "Hi", 2_001),
                ),
            ),
            VALID_SURFACES,
        )

        assertEquals(listOf("wait-out-of-range", "wait-out-of-range"), result.violations.map { it.code })
    }

    @Test
    fun rejectsCorpusUnobservedSurfaceAbsentFromPerSpeakerAuthorization() {
        val result = validator.validate(
            GeneratedDialogue(listOf(GeneratedTurn("sakura", 7, "Hello"))),
            VALID_SURFACES,
        )

        assertEquals("surface-not-allowed", result.violations.single().code)
    }

    @Test
    fun rejectsShellMissingSurfaceAbsentFromPerSpeakerAuthorization() {
        val result = validator.validate(
            GeneratedDialogue(listOf(GeneratedTurn("kero", 99, "Hi"))),
            VALID_SURFACES,
        )

        assertEquals("surface-not-allowed", result.violations.single().code)
    }

    @Test
    fun accumulatesAllForbiddenPayloadDiagnostics() {
        val result = validator.validate(
            GeneratedDialogue(
                listOf(
                    GeneratedTurn("sakura", 3, "Hello\\e"),
                    GeneratedTurn("kero", 19, "line\nbreak"),
                    GeneratedTurn("sakura", 3, "https://example.com"),
                    GeneratedTurn("kero", 19, "SCRIPT:OnBoot"),
                    GeneratedTurn("sakura", 3, "（Choose,OnChoice）"),
                ),
            ),
            VALID_SURFACES,
        )

        assertNull(result.dialogue)
        assertEquals(
            listOf(
                "forbidden-backslash",
                "forbidden-control",
                "forbidden-url",
                "forbidden-script-scheme",
                "forbidden-choice",
            ),
            result.violations.map { it.code },
        )
        assertEquals(listOf(0, 1, 2, 3, 4), result.violations.map { it.turnIndex })
        assertTrue(result.violations.all { it.detail.isNotBlank() })
    }

    @Test
    fun rejectsNonHttpSchemesAndBareDomainPathsAsUrls() {
        val result = validator.validate(
            GeneratedDialogue(
                listOf(
                    GeneratedTurn("sakura", 3, "ftp://evil.example/file"),
                    GeneratedTurn("kero", 19, "mailto:user@example.com"),
                    GeneratedTurn("sakura", 3, "evil.example/path"),
                ),
            ),
            VALID_SURFACES,
        )

        assertNull(result.dialogue)
        assertEquals(
            listOf("forbidden-url", "forbidden-url", "forbidden-url"),
            result.violations.map { it.code },
        )
        assertEquals(listOf(0, 1, 2), result.violations.map { it.turnIndex })
    }

    @Test
    fun rejectsOneCharacterUriSchemesAsUrls() {
        val result = validator.validate(
            GeneratedDialogue(listOf(GeneratedTurn("sakura", 3, "x:payload"))),
            VALID_SURFACES,
        )

        assertNull(result.dialogue)
        assertEquals("forbidden-url", result.violations.single().code)
        assertEquals(0, result.violations.single().turnIndex)
    }

    @Test
    fun acceptsAuthorizedTwoSpeakerDialogueAsTrustedSpeakerIds() {
        val result = validator.validate(
            GeneratedDialogue(
                listOf(
                    GeneratedTurn("sakura", 3, "Hello", 400),
                    GeneratedTurn("kero", 19, "Hi"),
                ),
            ),
            VALID_SURFACES,
        )

        assertTrue(result.violations.isEmpty())
        assertEquals(
            listOf(GhostSpeakerId.SAKURA, GhostSpeakerId.KERO),
            result.dialogue?.turns?.map { it.speaker },
        )
    }

    private companion object {
        val VALID_SURFACES = mapOf(
            GhostSpeakerId.SAKURA to setOf(3),
            GhostSpeakerId.KERO to setOf(19),
        )
    }
}
