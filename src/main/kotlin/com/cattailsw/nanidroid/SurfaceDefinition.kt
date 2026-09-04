@file:JvmName("SurfaceDefinitionMapper")

package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.surface.CollisionShape

/** Platform-neutral shell surface semantics passed from parsing to Compose rendering. */
data class SurfaceDefinition(
    val id: Int,
    val type: Int,
    val imagePath: String?,
    val fallbackImagePath: String?,
    val width: Int,
    val height: Int,
    val collisions: List<SurfaceCollision>,
    val animations: List<SurfaceAnimation>,
    val elements: List<SurfaceElement>,
    val transparencyPolicy: SurfaceTransparencyPolicy = SurfaceTransparencyPolicy.LEGACY_COLOR_KEY,
)

enum class SurfaceTransparencyPolicy {
    LEGACY_COLOR_KEY,
    AUTHORED_ALPHA;

    companion object {
        fun fromShellDescriptor(descriptor: Map<String, String>?): SurfaceTransparencyPolicy {
            val selfAlpha = descriptor.orEmpty().entries
                .firstOrNull { (key, _) -> key.trim().equals("seriko.use_self_alpha", ignoreCase = true) }
                ?.value
                ?.trim()
            return if (selfAlpha == "1") AUTHORED_ALPHA else LEGACY_COLOR_KEY
        }
    }
}

data class SurfaceCollision(
    val id: Int,
    val identifier: String,
    val shape: CollisionShape,
    val authoredOrder: Int,
)

data class SurfaceAnimation(
    val id: String,
    val interval: Int,
    val exclusive: Boolean,
    val frames: List<SurfaceAnimationFrame>,
    val alternativeAnimationIds: List<String> = emptyList(),
)

data class SurfaceAnimationFrame(
    val index: Int,
    val sourceSurfaceId: String?,
    val imagePath: String?,
    val type: Int,
    val durationMillis: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class SurfaceElement(
    val index: Int,
    val imagePath: String?,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * Captures parser output for the Compose rendering boundary without decoding
 * bitmaps or constructing Android Drawables.
 */
fun ShellSurface.toSurfaceDefinition(): SurfaceDefinition = SurfaceDefinition(
    id = surfaceId,
    type = surfaceType,
    imagePath = selfFilename,
    fallbackImagePath = bp2,
    width = origW,
    height = origH,
    collisions = canonicalCollisions ?: collisionAreas.values
        .mapNotNull { collision ->
            val endX = inclusiveEndpointOrNull(collision.startX, collision.W) ?: return@mapNotNull null
            val endY = inclusiveEndpointOrNull(collision.startY, collision.H) ?: return@mapNotNull null
            val shape = CollisionShape.Rectangle.fromAuthoredOrNull(
                collision.startX,
                collision.startY,
                endX,
                endY,
            ) ?: return@mapNotNull null
            SurfaceCollision(
                id = collision.id,
                identifier = collision.name.orEmpty(),
                shape = shape,
                authoredOrder = collision.id,
            )
        },
    animations = (animationTable ?: emptyMap()).values
        .sortedBy { it.id }
        .map { animation ->
            SurfaceAnimation(
                id = animation.id,
                interval = animation.interval,
                exclusive = animation.exclusive,
                frames = (animation.frames ?: emptyList()).mapIndexed { index, frame ->
                    SurfaceAnimationFrame(
                        index = index,
                        sourceSurfaceId = frame.sid,
                        imagePath = frame.filePath,
                        type = frame.frameType,
                        durationMillis = frame.time,
                        x = frame.startX,
                        y = frame.startY,
                        width = frame.W,
                        height = frame.H,
                    )
                },
                alternativeAnimationIds = (animation as? ShellSurface.AltAnimation)
                    ?.refidz
                    ?.toList()
                    ?: emptyList(),
            )
        },
    elements = (elementList ?: emptyList()).mapIndexed { index, element ->
        SurfaceElement(
            index = index,
            imagePath = element.filePath,
            x = element.startX,
            y = element.startY,
            width = element.W,
            height = element.H,
        )
    },
    transparencyPolicy = transparencyPolicy,
)

private fun inclusiveEndpointOrNull(start: Int, size: Int): Int? = runCatching {
    Math.toIntExact(Math.addExact(start.toLong(), size.toLong() - 1L))
}.getOrNull()
