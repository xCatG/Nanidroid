package com.cattailsw.nanidroid.llmghost.generation

import com.cattailsw.nanidroid.llmghost.model.GeneratedDialogue
import com.cattailsw.nanidroid.llmghost.model.GeneratedTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GeneratedDialogueDecoderTest {
    private val decoder = GeneratedDialogueDecoder()

    @Test
    fun decodesBareJsonObjectAfterRemovingOnlySurroundingWhitespace() {
        val result = decoder.decode("  \n$VALID_JSON\r\n ")

        assertEquals(EXPECTED_DIALOGUE, result.dialogue)
        assertNull(result.error)
    }

    @Test
    fun decodesExactlyOneCompleteJsonFence() {
        val result = decoder.decode("""
            ```json
            $VALID_JSON
            ```
        """.trimIndent())

        assertEquals(EXPECTED_DIALOGUE, result.dialogue)
        assertNull(result.error)
    }

    @Test
    fun decodesBareJsonWhoseTextContainsFenceCharacters() {
        val result = decoder.decode(
            "{\"turns\":[{\"speaker\":\"sakura\",\"surface\":3,\"text\":\"literal ``` text\"}]}",
        )

        assertEquals("literal ``` text", result.dialogue?.turns?.single()?.text)
        assertNull(result.error)
    }

    @Test
    fun rejectsProseAroundJsonAsAmbiguousOutput() {
        assertDecodeError("Here is the dialogue: $VALID_JSON", "ambiguous-output")
    }

    @Test
    fun rejectsTwoFencedBlocksAsAmbiguousOutput() {
        val raw = """
            ```json
            $VALID_JSON
            ```
            ```json
            $VALID_JSON
            ```
        """.trimIndent()

        assertDecodeError(raw, "ambiguous-output")
    }

    @Test
    fun rejectsTrailingJsonAsAmbiguousOutput() {
        assertDecodeError("$VALID_JSON {}", "ambiguous-output")
    }

    @Test
    fun rejectsMalformedAndTruncatedJsonAsMalformedJson() {
        assertDecodeError("{\"turns\":[}", "malformed-json")
        assertDecodeError("{\"turns\":[{\"speaker\":\"sakura\"", "malformed-json")
    }

    @Test
    fun rejectsMissingTurnsAndUnknownKeysAsSchemaInvalid() {
        assertDecodeError("{}", "schema-invalid")
        assertDecodeError("{\"turns\":[],\"commentary\":\"extra\"}", "schema-invalid")
        assertDecodeError(
            "{\"turns\":[{\"speaker\":\"sakura\",\"surface\":3,\"text\":\"Hello\",\"extra\":true}]}",
            "schema-invalid",
        )
    }

    private fun assertDecodeError(raw: String, expectedCode: String) {
        val result = decoder.decode(raw)

        assertNull(result.dialogue)
        assertEquals(expectedCode, result.error?.code)
    }

    private companion object {
        const val VALID_JSON =
            "{\"turns\":[{\"speaker\":\"sakura\",\"surface\":3,\"text\":\"Hello\",\"waitAfterMs\":400}]}"

        val EXPECTED_DIALOGUE = GeneratedDialogue(
            listOf(GeneratedTurn("sakura", 3, "Hello", 400)),
        )
    }
}
