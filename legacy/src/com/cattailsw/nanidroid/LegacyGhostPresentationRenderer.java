package com.cattailsw.nanidroid;

import android.view.View;

/** Java-only copy used by the frozen Ant build, which cannot compile Kotlin. */
public final class LegacyGhostPresentationRenderer implements GhostPresentationRenderer {
    private final SakuraView sakura; private final KeroView kero;
    private final Balloon sakuraBalloon; private final Balloon keroBalloon;
    private final LayoutManager layoutManager;
    public LegacyGhostPresentationRenderer(SakuraView sakura, KeroView kero, Balloon sakuraBalloon, Balloon keroBalloon, LayoutManager layoutManager) {
        this.sakura=sakura; this.kero=kero; this.sakuraBalloon=sakuraBalloon; this.keroBalloon=keroBalloon; this.layoutManager=layoutManager;
    }
    public void render(GhostPresentationFrame frame) {
        sakura.changeSurface(frame.sakura.surfaceId); kero.changeSurface(frame.kero.surfaceId);
        renderBalloon(sakura,sakuraBalloon,frame.sakura,frame.talkingAnimationEnabled); renderBalloon(kero,keroBalloon,frame.kero,frame.talkingAnimationEnabled);
        if(layoutManager!=null) layoutManager.checkAndUpdateLayoutParam(); renderAnimation(sakura,frame.sakura.animationId); renderAnimation(kero,frame.kero.animationId);
    }
    private static void renderBalloon(SakuraView view, Balloon balloon, GhostPresentationFrame.Speaker speaker, boolean talking) {
        if(!speaker.balloonVisible){balloon.setVisibility(View.INVISIBLE);return;} balloon.setVisibility(View.VISIBLE); balloon.setText(speaker.text); if(speaker.animationId==null&&talking)view.startTalkingAnimation();
    }
    private static void renderAnimation(SakuraView view,String id){if(id==null)return;view.loadAnimation(id);view.startAnimation();}
}
