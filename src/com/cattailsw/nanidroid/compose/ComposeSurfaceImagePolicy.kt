@file:JvmName("ComposeSurfaceImagePolicy")

package com.cattailsw.nanidroid.compose

import com.cattailsw.nanidroid.ShellSurface
import com.cattailsw.nanidroid.SurfaceDefinition

/** Selects only the surface states that Compose can reproduce without animation. */
fun shouldRenderComposeSurface(
    definition: SurfaceDefinition?,
    animationId: String?,
    talkingAnimationEnabled: Boolean,
    balloonVisible: Boolean,
): Boolean = definition != null &&
    definition.type == ShellSurface.S_TYPE_BASE &&
    definition.imagePath != null &&
    animationId == null &&
    !(talkingAnimationEnabled && balloonVisible)
