package com.cattailsw.nanidroid

import android.view.View

/** Retained View renderer while the Compose surface is incrementally adopted. */
class LegacyGhostPresentationRenderer(
    private val sakura: SakuraView,
    private val kero: KeroView,
    private val sakuraBalloon: Balloon,
    private val keroBalloon: Balloon,
    private val layoutManager: LayoutManager?,
) : GhostPresentationRenderer {
    override fun render(frame: GhostPresentationFrame) {
        sakura.changeSurface(frame.sakura.surfaceId!!)
        kero.changeSurface(frame.kero.surfaceId!!)
        renderBalloon(sakura, sakuraBalloon, frame.sakura, frame.talkingAnimationEnabled)
        renderBalloon(kero, keroBalloon, frame.kero, frame.talkingAnimationEnabled)
        layoutManager?.checkAndUpdateLayoutParam()
        renderAnimation(sakura, frame.sakura.animationId)
        renderAnimation(kero, frame.kero.animationId)
    }

    private fun renderBalloon(view: SakuraView, balloon: Balloon, speaker: GhostPresentationFrame.Speaker, talking: Boolean) {
        if (!speaker.balloonVisible) { balloon.visibility = View.INVISIBLE; return }
        balloon.visibility = View.VISIBLE
        balloon.setText(speaker.text)
        if (speaker.animationId == null && talking) view.startTalkingAnimation()
    }

    private fun renderAnimation(view: SakuraView, animationId: String?) {
        if (animationId == null) return
        view.loadAnimation(animationId)
        view.startAnimation()
    }
}
