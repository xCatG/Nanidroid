package com.cattailsw.nanidroid.llmghost.sakura

import com.cattailsw.nanidroid.llmghost.generation.TrustedDialogue
import com.cattailsw.nanidroid.llmghost.generation.TrustedTurn
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SakuraScriptCompilerTest {
    private val compiler = SakuraScriptCompiler()

    @Test
    fun compilesOnlyTheSupportedSubsetAndRoundTrips() {
        val validatedDialogue = TrustedDialogue(
            listOf(
                TrustedTurn(GhostSpeakerId.SAKURA, 3, "Hello", 400),
                TrustedTurn(GhostSpeakerId.KERO, 19, "Hi", 0),
            ),
        )

        val result = compiler.compile(validatedDialogue)

        assertEquals("\\0\\s[3]Hello\\_w[400]\\1\\s[19]Hi\\_w[0]\\e", result.script)
        assertEquals(validatedDialogue, CompiledScriptValidator.validate(result.script).dialogue)
    }

    @Test
    fun escapesTextBackslashesSoTheyCannotInjectCommands() {
        val dialogue = TrustedDialogue(
            listOf(TrustedTurn(GhostSpeakerId.SAKURA, 3, "Hello\\e\\1", 0)),
        )

        val result = compiler.compile(dialogue)

        assertEquals("\\0\\s[3]Hello\\\\e\\\\1\\_w[0]\\e", result.script)
        assertEquals(dialogue, CompiledScriptValidator.validate(result.script).dialogue)
    }

    @Test
    fun preservesUnicodeAndCommandLookingBracketsOnRoundTrip() {
        val dialogue = TrustedDialogue(
            listOf(TrustedTurn(GhostSpeakerId.KERO, 19, "😀 [not-a-command]", 2_000)),
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
}
