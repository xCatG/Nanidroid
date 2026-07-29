package com.cattailsw.nanidroid

import android.os.SystemClock
import android.util.Log
import android.graphics.Rect

/**
 * Small boundary around Android-only timing and logging APIs used by legacy
 * parsers.  Android remains the production default; tests may replace the
 * boundary with deterministic collaborators without constructing framework
 * stubs.
 */
internal object LegacyPlatform {
    private var clock: () -> Long = { SystemClock.uptimeMillis() }
    private var logger: (String, String) -> Unit = { tag, message -> Log.d(tag, message) }
    private var rectangles: (Int, Int, Int, Int) -> Rect = ::Rect

    fun uptimeMillis(): Long = clock()

    fun debug(tag: String, message: String) = logger(tag, message)

    fun rectangle(left: Int, top: Int, right: Int, bottom: Int): Rect =
        rectangles(left, top, right, bottom)

    internal fun <T> withTestSeams(
        clock: () -> Long,
        logger: (String, String) -> Unit = { _, _ -> },
        rectangles: (Int, Int, Int, Int) -> Rect = this.rectangles,
        block: () -> T,
    ): T {
        val previousClock = this.clock
        val previousLogger = this.logger
        val previousRectangles = this.rectangles
        this.clock = clock
        this.logger = logger
        this.rectangles = rectangles
        return try {
            block()
        } finally {
            this.clock = previousClock
            this.logger = previousLogger
            this.rectangles = previousRectangles
        }
    }

}
