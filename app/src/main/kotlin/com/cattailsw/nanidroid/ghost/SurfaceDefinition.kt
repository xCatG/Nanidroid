package com.cattailsw.nanidroid.ghost

data class SurfaceDefinition(
    val id: Int,
    val elements: List<SurfaceElement>,
    val collisions: List<SurfaceCollision>,
    val animations: List<SurfaceAnimation>,
)

data class SurfaceElement(val id: Int, val method: String, val fileName: String, val x: Int, val y: Int)
data class SurfaceCollision(
    val id: Int, val left: Int, val top: Int, val right: Int, val bottom: Int, val name: String,
)
data class SurfaceAnimation(val id: Int, val interval: String, val patterns: List<SurfacePattern>)

sealed interface SurfacePattern {
    data class Image(
        val surfaceId: Int, val method: String, val waitMinMs: Long, val waitMaxMs: Long,
        val x: Int, val y: Int,
    ) : SurfacePattern
    data class Alternative(val animationIds: List<Int>) : SurfacePattern
}
