package com.cattailsw.nanidroid.compose.stage

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.graphics.withSave
import com.cattailsw.nanidroid.SurfaceCollision
import com.cattailsw.nanidroid.runtime.stage.CollisionShapePx
import com.cattailsw.nanidroid.runtime.stage.SurfaceTransformPx

/** Decorative collision diagnostics built from the same transform as hit testing. */
@Composable
fun CollisionOverlay(
    collisions: List<SurfaceCollision>,
    transform: SurfaceTransformPx,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val localShapes = remember(collisions, transform) {
        val localOffset = androidx.compose.ui.unit.IntOffset(
            -transform.renderedBounds.left,
            -transform.renderedBounds.top,
        )
        collisions.map { collision ->
            val shape = transform.toStage(collision.shape).translated(localOffset)
            val polygonPath = (shape as? CollisionShapePx.Polygon)?.let { polygon ->
                Path().apply {
                    polygon.points.firstOrNull()?.let { first ->
                        moveTo(first.x, first.y)
                        polygon.points.drop(1).forEach { point -> lineTo(point.x, point.y) }
                        close()
                    }
                }
            }
            TransformedCollision(collision.id, collision.identifier, shape, polygonPath)
        }
    }
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
            .clearAndSetSemantics { },
    ) {
        if (!visible) return@Canvas
        localShapes.forEach { collision ->
            when (val shape = collision.shape) {
                is CollisionShapePx.Rectangle -> drawRect(
                    color = Color.Magenta,
                    topLeft = Offset(shape.bounds.left, shape.bounds.top),
                    size = Size(shape.bounds.width, shape.bounds.height),
                    style = Stroke(width = 1f),
                )
                is CollisionShapePx.Ellipse -> drawOval(
                    color = Color.Magenta,
                    topLeft = Offset(shape.bounds.left, shape.bounds.top),
                    size = Size(shape.bounds.width, shape.bounds.height),
                    style = Stroke(width = 1f),
                )
                is CollisionShapePx.Circle -> drawOval(
                    color = Color.Magenta,
                    topLeft = Offset(shape.bounds.left, shape.bounds.top),
                    size = Size(shape.bounds.width, shape.bounds.height),
                    style = Stroke(width = 1f),
                )
                is CollisionShapePx.Polygon -> {
                    drawPath(requireNotNull(collision.polygonPath), Color.Magenta, style = Stroke(width = 1f))
                }
            }
            drawContext.canvas.nativeCanvas.withSave {
                clipRect(0f, 0f, size.width, size.height)
                val label = "${collision.id}:${collision.identifier}"
                drawText(
                    label,
                    collision.shape.bounds.left.coerceIn(0f, size.width),
                    (collision.shape.bounds.top + labelPaint.textSize).coerceIn(labelPaint.textSize, size.height),
                    labelPaint,
                )
            }
        }
    }
}

private data class TransformedCollision(
    val id: Int,
    val identifier: String,
    val shape: CollisionShapePx,
    val polygonPath: Path?,
)
