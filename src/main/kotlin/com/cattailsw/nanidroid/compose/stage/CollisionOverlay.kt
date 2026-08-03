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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.graphics.withSave
import com.cattailsw.nanidroid.SurfaceCollision
import com.cattailsw.nanidroid.runtime.stage.CollisionRegionPx
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
    val localShapes = remember(collisions, transform, visible) {
        if (!visible) {
            emptyList()
        } else {
            val localOffset = androidx.compose.ui.unit.IntOffset(
                -transform.renderedBounds.left,
                -transform.renderedBounds.top,
            )
            collisions.map { collision ->
                val region = transform.toStageRegion(collision.shape).translated(localOffset)
                TransformedCollision(collision.id, collision.identifier, region)
            }
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
            .testTag("collision-overlay")
            .clearAndSetSemantics { },
    ) {
        if (!visible) return@Canvas
        localShapes.forEach { collision ->
            collision.region.rects.forEach { rect ->
                drawRect(
                    color = Color.Magenta.copy(alpha = 0.35f),
                    topLeft = Offset(rect.left.toFloat(), rect.top.toFloat()),
                    size = Size((rect.right - rect.left).toFloat(), (rect.bottom - rect.top).toFloat()),
                )
            }
            collision.region.boundarySegments.forEach { segment ->
                drawLine(
                    color = Color.Magenta,
                    start = segment.start,
                    end = segment.end,
                    strokeWidth = 1f,
                )
            }
            val anchor = collision.region.rects.firstOrNull()
            if (showLabels && anchor != null) drawContext.canvas.nativeCanvas.withSave {
                clipRect(0f, 0f, size.width, size.height)
                val label = "${collision.id}:${collision.identifier}"
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
)
