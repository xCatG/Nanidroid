package com.cattailsw.nanidroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/** Locks the legacy choice/input extraction before Kotlin runtime dispatch exists. */
public final class SakuraScriptInteractionInterpreterTest {
    @Test
    public void requiredMigrationInvariant_choicesBecomeLabelsAndOneOrderedSelectionEffect() {
        SakuraScriptInteractionResult result = SakuraScriptInteractionInterpreter.extract(
                "\\hA\\q[One,id1]B\\q[Two,id2]\\e");

        assertEquals("\\hAOneBTwo\\e", result.getPresentationScript());
        assertEquals(1, result.getEffects().size());
        SakuraScriptInteractionEffect.ShowSelection effect =
                (SakuraScriptInteractionEffect.ShowSelection) result.getEffects().get(0);
        assertEquals(Arrays.<String>asList("One", "Two"), effect.getLabels());
        assertEquals(Arrays.<String>asList("id1", "id2"), effect.getIds());
    }

    @Test
    public void requiredMigrationInvariant_inputBoxIsConsumedAndRetainsItsExactId() {
        SakuraScriptInteractionResult result = SakuraScriptInteractionInterpreter.extract(
                "\\hA\\![open,inputbox,user-name]B\\e");

        assertEquals("\\hAB\\e", result.getPresentationScript());
        assertEquals(1, result.getEffects().size());
        SakuraScriptInteractionEffect.OpenInputBox effect =
                (SakuraScriptInteractionEffect.OpenInputBox) result.getEffects().get(0);
        assertEquals("user-name", effect.getId());
    }

    @Test
    public void requiredMigrationInvariant_scriptsWithoutInteractionsRemainUntouched() {
        SakuraScriptInteractionResult result =
                SakuraScriptInteractionInterpreter.extract("\\hplain text\\e");

        assertEquals("\\hplain text\\e", result.getPresentationScript());
        assertEquals(Collections.<Object>emptyList(), result.getEffects());
    }

    @Test
    public void legacyObserved_inputBoxPatternUsesTheSameGreedyCaptureAsJavaRunner() {
        SakuraScriptInteractionResult result = SakuraScriptInteractionInterpreter.extract(
                "\\![open,inputbox,first]X\\![open,inputbox,second]");

        assertEquals("", result.getPresentationScript());
        assertEquals(1, result.getEffects().size());
        SakuraScriptInteractionEffect.OpenInputBox effect =
                (SakuraScriptInteractionEffect.OpenInputBox) result.getEffects().get(0);
        assertEquals("first]X\\![open,inputbox,second", effect.getId());
    }

    @Test
    public void requiredMigrationInvariant_effectsKeepInputAndChoiceSourceOrder() {
        SakuraScriptInteractionResult result = SakuraScriptInteractionInterpreter.extract(
                "\\q[One,id]A\\![open,inputbox,name]");

        assertEquals(2, result.getEffects().size());
        assertEquals(SakuraScriptInteractionEffect.ShowSelection.class,
                result.getEffects().get(0).getClass());
        assertEquals(SakuraScriptInteractionEffect.OpenInputBox.class,
                result.getEffects().get(1).getClass());
    }

    @Test
    public void requiredMigrationInvariant_effectCollectionsCannotBeMutatedFromJava() {
        SakuraScriptInteractionResult result = SakuraScriptInteractionInterpreter.extract(
                "\\q[One,id]");
        SakuraScriptInteractionEffect.ShowSelection selection =
                (SakuraScriptInteractionEffect.ShowSelection) result.getEffects().get(0);

        assertUnmodifiable(result.getEffects());
        assertUnmodifiable(selection.getLabels());
        assertUnmodifiable(selection.getIds());
    }

    private static void assertUnmodifiable(java.util.List<?> values) {
        try {
            values.add(null);
            fail("Expected unmodifiable collection");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }
}
