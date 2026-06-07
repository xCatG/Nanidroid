package com.cattailsw.nanidroid.test.support

import com.cattailsw.nanidroid.SakuraView

class RecordingUiCallback : SakuraView.UIEventCallback {
    data class HitEvent(val type: Int, val x: Int, val y: Int, val orientation: Int, val collId: Int, val buttonId: Int)
    
    val events = mutableListOf<HitEvent>()

    override fun onHit(type: Int, x: Int, y: Int, orientation: Int, collId: Int, buttonId: Int) {
        events.add(HitEvent(type, x, y, orientation, collId, buttonId))
    }
}
