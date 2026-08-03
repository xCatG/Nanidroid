@file:JvmName("SurfaceDefinitionMapper")

package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.surface.CollisionShape

/** Platform-neutral shell surface semantics, suitable for a Compose renderer. */
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
)

data class SurfaceCollision(
    val id: Int,
    val identifier: String,
    val shape: CollisionShape,
    val authoredOrder: Int,
) {
    /** Legacy observation fields retained for compatibility callers. */
    val name: String get() = identifier
    val x: Int get() = shape.bounds.left
    val y: Int get() = shape.bounds.top
    val width: Int get() = shape.bounds.width
    val height: Int get() = shape.bounds.height
}

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
 * Captures the legacy parser's semantic output without decoding bitmaps or
 * constructing Android Drawables. The legacy compositor remains the runtime
 * authority until Compose consumes this snapshot in a subsequent slice.
 */
fun ShellSurface.toSurfaceDefinition(): SurfaceDefinition = SurfaceDefinition(
    id = surfaceId,
    type = surfaceType,
    imagePath = selfFilename,
    fallbackImagePath = bp2,
    width = origW,
    height = origH,
    collisions = canonicalCollisions ?: collisionAreas.values
        .map { collision ->
            SurfaceCollision(
                id = collision.id,
                identifier = collision.name.orEmpty(),
                shape = CollisionShape.Rectangle.fromAuthored(
                    collision.startX,
                    collision.startY,
                    inclusiveEndpoint(collision.startX, collision.W),
                    inclusiveEndpoint(collision.startY, collision.H),
                ),
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
)

private fun inclusiveEndpoint(start: Int, size: Int): Int = Math.toIntExact(
    Math.addExact(start.toLong(), size.toLong() - 1L),
)
