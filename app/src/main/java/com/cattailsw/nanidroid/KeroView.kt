package com.cattailsw.nanidroid

import android.content.Context
import android.util.AttributeSet

open class KeroView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SakuraView(context, attrs, defStyleAttr) {

    override fun loadSurface(sid: String) {
        currentSurface = mgr?.getKeroSurface(sid)
    }
}
