package com.cattailsw.nanidroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Locks the state transitions that will replace SScriptRunner's mutable fields. */
public final class SakuraScriptPresentationReducerTest {
    @Test
    public void requiredMigrationInvariant_scriptResetKeepsSurfacesButClearsTransientPresentation() {
        SakuraScriptPresentationState changed = SakuraScriptPresentationReducer.queueAnimation(
                SakuraScriptPresentationReducer.changeBalloon(
                        SakuraScriptPresentationReducer.changeSurface(
                                SakuraScriptPresentationReducer.selectSpeaker(
                                        SakuraScriptPresentationReducer.initial(), GhostSpeaker.KERO),
                                "42"),
                        "7"),
                "3");

        SakuraScriptPresentationState reset =
                SakuraScriptPresentationReducer.resetForNextScript(changed);

        assertEquals(GhostSpeaker.SAKURA, reset.getActiveSpeaker());
        assertEquals("0", reset.getSakuraSurfaceId());
        assertEquals("42", reset.getKeroSurfaceId());
        assertEquals("-1", reset.getSakuraBalloonId());
        assertEquals("-1", reset.getKeroBalloonId());
        assertNull(reset.getSakuraAnimationId());
        assertNull(reset.getKeroAnimationId());
    }

    @Test
    public void requiredMigrationInvariant_synchronizationAndKeroTextPreserveLegacyBalloonPolicy() {
        SakuraScriptPresentationState state = SakuraScriptPresentationReducer.toggleSynchronization(
                SakuraScriptPresentationReducer.resetForNextScript(
                        SakuraScriptPresentationReducer.initial()));
        state = SakuraScriptPresentationReducer.append(state, 'A');
        GhostPresentationState snapshot = SakuraScriptPresentationReducer.snapshot(state);

        assertEquals("A", snapshot.getSakura().getText());
        assertEquals("A", snapshot.getKero().getText());
        assertTrue(snapshot.getKero().getBalloonVisible());

        state = SakuraScriptPresentationReducer.clearActiveText(state);
        snapshot = SakuraScriptPresentationReducer.snapshot(state);
        assertFalse(snapshot.getSakura().getBalloonVisible());
        assertTrue(snapshot.getKero().getBalloonVisible());
    }

    @Test
    public void requiredMigrationInvariant_animationIsVisibleOnceThenExplicitlyConsumed() {
        SakuraScriptPresentationState queued = SakuraScriptPresentationReducer.queueAnimation(
                SakuraScriptPresentationReducer.initial(), "3");

        assertEquals("3", SakuraScriptPresentationReducer.snapshot(queued)
                .getSakura().getAnimationId());
        assertNull(SakuraScriptPresentationReducer.snapshot(
                SakuraScriptPresentationReducer.consumeAnimations(queued))
                .getSakura().getAnimationId());
    }
}
