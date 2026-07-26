package com.cattailsw.nanidroid.runtime;

import static org.junit.Assert.assertEquals;

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
        assertEquals(Arrays.asList("One", "Two"), effect.getLabels());
        assertEquals(Arrays.asList("id1", "id2"), effect.getIds());
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
        assertEquals(Collections.emptyList(), result.getEffects());
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
}
