package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.engine.BuiltInAppAction

/** A single-use request owned by a trusted session and its current delivery lifetime. */
data class PresentedAppAction(
    val requestId: Long,
    val sessionId: Long,
    val replacementGeneration: Long,
    val deliveryEpoch: Long,
    val action: BuiltInAppAction,
)
