package com.cattailsw.nanidroid.compose.stage

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.graphics.withSave
import com.cattailsw.nanidroid.SurfaceCollision
import com.cattailsw.nanidroid.runtime.stage.CollisionRegionPx
import com.cattailsw.nanidroid.runtime.stage.CollisionShapePx
import com.cattailsw.nanidroid.runtime.stage.SurfaceTransformPx

/** Decorative collision diagnostics built from the same transform as hit testing. */
@Composable
fun CollisionOverlay(
    collisions: List<SurfaceCollision>,
    transform: SurfaceTransformPx,
    visible: Boolean,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
) {
    val cachedShapes = remember(collisions, transform) {
        lazy(LazyThreadSafetyMode.NONE) {
            val localOffset = androidx.compose.ui.unit.IntOffset(
                -transform.renderedBounds.left,
                -transform.renderedBounds.top,
            )
            val regions = transform.toStageRegions(collisions.map(SurfaceCollision::shape))
            collisions.zip(regions).map { (collision, stageRegion) ->
                val region = stageRegion.translated(localOffset)
                val authored = transform.toStage(collision.shape).translated(localOffset)
                TransformedCollision(
                    id = collision.id,
                    identifier = collision.identifier,
                    region = region,
                    fillPath = region.fillPath(),
                    boundaryPath = region.boundaryPath(),
                    authoredPath = authored.path(),
                )
            }
        }
    }
    val localShapes = if (visible) cachedShapes.value else emptyList()
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.MAGENTA
            textSize = 24f
            style = Paint.Style.FILL
        }
    }
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("collision-overlay")
            .clearAndSetSemantics { },
    ) {
        if (!visible) return@Canvas
        localShapes.forEach { collision ->
            collision.fillPath?.let { path ->
                drawPath(path, color = Color.Magenta.copy(alpha = 0.35f))
            }
            collision.boundaryPath?.let { path ->
                drawPath(
                    path = path,
                    color = Color.Magenta,
                    style = Stroke(width = 1f),
                )
            }
            drawPath(
                path = collision.authoredPath,
                color = if (collision.region.isExact) {
                    Color.Cyan.copy(alpha = 0.55f)
                } else {
                    Color.Yellow
                },
                style = Stroke(width = 1f),
            )
            val anchor = collision.region.rects.firstOrNull()
                ?: collision.authoredPath.getBounds().let { bounds ->
                    com.cattailsw.nanidroid.runtime.stage.DoubleRect(
                        bounds.left.toDouble(),
                        bounds.top.toDouble(),
                        bounds.right.toDouble(),
                        bounds.bottom.toDouble(),
                    )
                }
            if (showLabels) drawContext.canvas.nativeCanvas.withSave {
                clipRect(0f, 0f, size.width, size.height)
                val suffix = if (collision.region.isExact) "" else " [authored guide; exact footprint omitted]"
                val label = "${collision.id}:${collision.identifier}$suffix"
                drawText(
                    label,
                    anchor.left.toFloat().coerceIn(0f, size.width),
                    collisionLabelBaselinePx(anchor.top.toFloat(), labelPaint.textSize, size.height),
                    labelPaint,
                )
            }
        }
    }
}

internal fun collisionLabelBaselinePx(anchorTop: Float, textSize: Float, canvasHeight: Float): Float {
    val height = canvasHeight.takeIf { it.isFinite() && it > 0f } ?: 0f
    val preferred = (anchorTop + textSize).takeIf { it.isFinite() } ?: 0f
    return preferred.coerceIn(0f, height)
}

private data class TransformedCollision(
    val id: Int,
    val identifier: String,
    val region: CollisionRegionPx,
    val fillPath: Path?,
    val boundaryPath: Path?,
    val authoredPath: Path,
)

private fun CollisionRegionPx.fillPath(): Path? = rects.takeIf { isExact && it.isNotEmpty() }?.let { areas ->
    Path().apply {
        areas.forEach { area ->
            addRect(
                Rect(
                    area.left.toFloat(),
                    area.top.toFloat(),
                    area.right.toFloat(),
                    area.bottom.toFloat(),
                ),
            )
        }
    }
}

private fun CollisionRegionPx.boundaryPath(): Path? =
    boundarySegments.takeIf { isExact && it.isNotEmpty() }?.let { segments ->
        Path().apply {
            segments.forEach { segment ->
                moveTo(segment.start.x, segment.start.y)
                lineTo(segment.end.x, segment.end.y)
            }
        }
    }

private fun CollisionShapePx.path(): Path = Path().apply {
    when (val shape = this@path) {
        is CollisionShapePx.Rectangle -> addRect(shape.bounds.rect())
        is CollisionShapePx.Ellipse -> addOval(shape.bounds.rect())
        is CollisionShapePx.Circle -> addOval(shape.bounds.rect())
        is CollisionShapePx.Polygon -> shape.points.firstOrNull()?.let { first ->
            moveTo(first.x, first.y)
            shape.points.drop(1).forEach { point -> lineTo(point.x, point.y) }
            close()
        }
    }
}

private fun com.cattailsw.nanidroid.runtime.stage.FloatRect.rect() =
    Rect(left, top, right, bottom)
