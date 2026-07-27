package com.cattailsw.nanidroid

import android.content.Context
import android.util.AttributeSet

/** Kero-specific surface resolver for the shared retained surface view. */
open class KeroView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : SakuraView(context, attrs, defStyle) {
    protected override fun loadSurface(surfaceId: String) {
        currentSurface = mgr!!.getKeroSurface(surfaceId)
    }
}
