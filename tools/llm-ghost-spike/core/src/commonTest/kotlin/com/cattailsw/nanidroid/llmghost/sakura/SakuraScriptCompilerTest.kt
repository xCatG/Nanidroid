package com.cattailsw.nanidroid.llmghost.sakura

import com.cattailsw.nanidroid.llmghost.generation.GeneratedDialogueValidator
import com.cattailsw.nanidroid.llmghost.generation.TrustedDialogue
import com.cattailsw.nanidroid.llmghost.generation.TrustedTurn
import com.cattailsw.nanidroid.llmghost.model.GeneratedDialogue
import com.cattailsw.nanidroid.llmghost.model.GeneratedTurn
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SakuraScriptCompilerTest {
    private val compiler = SakuraScriptCompiler()

    @Test
    fun compilesOnlyTheSupportedSubsetAndRoundTrips() {
        val validatedDialogue = validatedDialogue(
            listOf(
                GeneratedTurn("sakura", 3, "Hello", 400),
                GeneratedTurn("kero", 19, "Hi", 0),
            ),
        )

        val result = compiler.compile(validatedDialogue)

        assertEquals("\\0\\s[3]Hello\\_w[400]\\1\\s[19]Hi\\_w[0]\\e", result.script)
        assertEquals(validatedDialogue, CompiledScriptValidator.validate(result.script).dialogue)
    }

    @Test
    fun rejectsDirectlyConstructedForbiddenTextInsteadOfCompilingIt() {
        val texts = listOf(
            "Hello\\e\\1",
            "line\nbreak",
            "ftp://evil.example/file",
            "SCRIPT:OnBoot",
            "（Choose,OnChoice）",
            "bad\uD800text",
        )

        texts.forEach { text ->
            val dialogue = TrustedDialogue(
                listOf(TrustedTurn(GhostSpeakerId.SAKURA, 3, text, 0)),
            )

            assertFailsWith<IllegalArgumentException>(text) {
                compiler.compile(dialogue)
            }
        }
    }

    @Test
    fun rejectsDirectlyConstructedOutOfBoundsTrustedValues() {
        val invalidDialogues = listOf(
            TrustedDialogue(emptyList()),
            TrustedDialogue(
                List(9) { TrustedTurn(GhostSpeakerId.SAKURA, 3, "Turn $it", 0) },
            ),
            TrustedDialogue(
                listOf(TrustedTurn(GhostSpeakerId.SAKURA, -1, "Hello", 0)),
            ),
            TrustedDialogue(
                listOf(TrustedTurn(GhostSpeakerId.SAKURA, 3, "Hello", 2_001)),
            ),
            TrustedDialogue(
                listOf(TrustedTurn(GhostSpeakerId.SAKURA, 3, "   ", 0)),
            ),
            TrustedDialogue(
                listOf(TrustedTurn(GhostSpeakerId.SAKURA, 3, "😀".repeat(501), 0)),
            ),
        )

        invalidDialogues.forEach { dialogue ->
            assertFailsWith<IllegalArgumentException>(dialogue.toString()) {
                compiler.compile(dialogue)
            }
        }
    }

    @Test
    fun preservesUnicodeAndCommandLookingBracketsOnRoundTrip() {
        val dialogue = validatedDialogue(
            listOf(GeneratedTurn("kero", 19, "😀 [not-a-command]", 2_000)),
        )

        val compilation = compiler.compile(dialogue)

        assertEquals(dialogue, CompiledScriptValidator.validate(compilation.script).dialogue)
    }

    @Test
    fun rejectsEveryCommandOutsideTheCompilerSubset() {
        val scripts = listOf(
            "\\0\\s[3]Hello\\q[Choose,OnChoice]\\_w[0]\\e",
            "\\0\\s[3]Hello\\![raise,OnBoot]\\_w[0]\\e",
            "\\p[0]\\s[3]Hello\\_w[0]\\e",
        )

        scripts.forEach { script ->
            val validation = CompiledScriptValidator.validate(script)

            assertNull(validation.dialogue, script)
            assertEquals("unsupported-command", validation.violations.single().code, script)
        }
    }

    @Test
    fun requiresExactlyOneTerminalEndCommandAtTheEnd() {
        val scripts = listOf(
            "\\0\\s[3]Hello\\_w[0]",
            "\\0\\s[3]Hello\\_w[0]\\e trailing",
            "\\0\\s[3]Hello\\_w[0]\\e\\e",
            "\\0\\s[3]Hello\\e\\_w[0]\\e",
        )

        scripts.forEach { script ->
            val validation = CompiledScriptValidator.validate(script)

            assertNull(validation.dialogue, script)
            assertTrue(validation.violations.isNotEmpty(), script)
        }
    }

    @Test
    fun rejectsMalformedOrOutOfOrderCompilerCommands() {
        val scripts = listOf(
            "\\0Hello\\_w[0]\\e",
            "\\0\\s[]Hello\\_w[0]\\e",
            "\\0\\s[-1]Hello\\_w[0]\\e",
            "\\0\\s[3]Hello\\_w[]\\e",
            "\\0\\s[3]Hello\\_w[-1]\\e",
            "\\0\\s[3]Hello\\_w[0]\\s[4]Again\\_w[0]\\e",
        )

        scripts.forEach { script ->
            val validation = CompiledScriptValidator.validate(script)

            assertNull(validation.dialogue, script)
            assertEquals("invalid-grammar", validation.violations.single().code, script)
        }
    }

    @Test
    fun refusesToReconstructSemanticallyInvalidCompilerGrammarAsTrustedDialogue() {
        val scriptsAndCodes = listOf(
            "\\0\\s[3]Hello\\_w[2001]\\e" to "wait-out-of-range",
            "\\0\\s[3]   \\_w[0]\\e" to "text-blank",
            "\\0\\s[3]line\nbreak\\_w[0]\\e" to "forbidden-control",
            "\\0\\s[3]ftp://evil.example/file\\_w[0]\\e" to "forbidden-url",
            "\\0\\s[3]${"😀".repeat(501)}\\_w[0]\\e" to "text-too-long",
            "\\0\\s[3]bad\uD800text\\_w[0]\\e" to "invalid-unicode",
            "\\0\\s[3]Hello\\\\e\\_w[0]\\e" to "forbidden-backslash",
            buildString {
                repeat(9) { append("\\0\\s[3]Turn $it\\_w[0]") }
                append("\\e")
            } to "turn-count",
        )

        scriptsAndCodes.forEach { (script, code) ->
            val validation = CompiledScriptValidator.validate(script)

            assertNull(validation.dialogue, script)
            assertTrue(validation.violations.any { it.code == code }, script)
        }
    }

    private fun validatedDialogue(turns: List<GeneratedTurn>): TrustedDialogue {
        val validSurfaces = mapOf(
            GhostSpeakerId.SAKURA to turns.filter { it.speaker == "sakura" }.map { it.surface }.toSet(),
            GhostSpeakerId.KERO to turns.filter { it.speaker == "kero" }.map { it.surface }.toSet(),
        )
        val result = GeneratedDialogueValidator().validate(GeneratedDialogue(turns), validSurfaces)

        assertTrue(result.violations.isEmpty())
        return assertNotNull(result.dialogue)
    }
}
