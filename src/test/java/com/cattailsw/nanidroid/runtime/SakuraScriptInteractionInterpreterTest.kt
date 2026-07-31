package com.cattailsw.nanidroid.runtime

import org.junit.Assert
import org.junit.Test

/** Locks the legacy choice/input extraction before Kotlin runtime dispatch exists.  */
class SakuraScriptInteractionInterpreterTest {
    @Test
    fun requiredMigrationInvariant_choicesBecomeLabelsAndOneOrderedSelectionEffect() {
        val result = SakuraScriptInteractionInterpreter.extract(
            "\\hA\\q[One,id1]B\\q[Two,id2]\\e"
        )

        Assert.assertEquals("\\hAOneBTwo\\e", result.presentationScript)
        Assert.assertEquals(1, result.effects.size)
        val effect = result.effects[0] as SakuraScriptInteractionEffect.ShowSelection
        Assert.assertEquals(listOf("One", "Two"), effect.labels)
        Assert.assertEquals(listOf("id1", "id2"), effect.ids)
    }

    @Test
    fun requiredMigrationInvariant_inputBoxIsConsumedAndRetainsItsExactId() {
        val result = SakuraScriptInteractionInterpreter.extract(
            "\\hA\\![open,inputbox,user-name]B\\e"
        )

        Assert.assertEquals("\\hAB\\e", result.presentationScript)
        Assert.assertEquals(1, result.effects.size)
        val effect = result.effects[0] as SakuraScriptInteractionEffect.OpenInputBox
        Assert.assertEquals("user-name", effect.id)
    }

    @Test
    fun requiredMigrationInvariant_scriptsWithoutInteractionsRemainUntouched() {
        val result = SakuraScriptInteractionInterpreter.extract("\\hplain text\\e")

        Assert.assertEquals("\\hplain text\\e", result.presentationScript)
        Assert.assertEquals(emptyList<Any>(), result.effects)
    }

    @Test
    fun legacyObserved_inputBoxPatternUsesTheSameGreedyCaptureAsJavaRunner() {
        val result = SakuraScriptInteractionInterpreter.extract(
            "\\![open,inputbox,first]X\\![open,inputbox,second]"
        )

        Assert.assertEquals("", result.presentationScript)
        Assert.assertEquals(1, result.effects.size)
        val effect = result.effects[0] as SakuraScriptInteractionEffect.OpenInputBox
        Assert.assertEquals("first]X\\![open,inputbox,second", effect.id)
    }

    @Test
    fun requiredMigrationInvariant_effectsKeepInputAndChoiceSourceOrder() {
        val result = SakuraScriptInteractionInterpreter.extract(
            "\\q[One,id]A\\![open,inputbox,name]"
        )

        Assert.assertEquals(2, result.effects.size)
        Assert.assertEquals(
            SakuraScriptInteractionEffect.ShowSelection::class.java,
            result.effects[0]::class.java
        )
        Assert.assertEquals(
            SakuraScriptInteractionEffect.OpenInputBox::class.java,
            result.effects[1]::class.java
        )
    }

    @Test
    fun requiredMigrationInvariant_effectCollectionsCannotBeMutatedFromJava() {
        val result = SakuraScriptInteractionInterpreter.extract("\\q[One,id]")
        val selection = result.effects[0] as SakuraScriptInteractionEffect.ShowSelection

        assertUnmodifiable(result.effects)
        assertUnmodifiable(selection.labels)
        assertUnmodifiable(selection.ids)
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private fun assertUnmodifiable(values: List<*>) {
            try {
                (values as MutableList<Any?>).add(null)
                Assert.fail("Expected unmodifiable collection")
            } catch (expected: UnsupportedOperationException) {
                // Expected.
            }
        }
    }
}