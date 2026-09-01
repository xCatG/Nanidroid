package com.cattailsw.nanidroid

import android.os.SystemClock
import android.util.Log

/**
 * Small boundary around Android-only timing and logging APIs used by legacy
 * parsers.  Android remains the production default; tests may replace the
 * boundary with deterministic collaborators without constructing framework
 * stubs.
 */
internal object LegacyPlatform {
    private var clock: () -> Long = { SystemClock.uptimeMillis() }
    private var logger: (String, String) -> Unit = { tag, message -> Log.d(tag, message) }
    private var delayedScheduler: (Long, () -> Unit) -> Unit = { _, action -> action() }
    private var delayedCancellation: (() -> Unit) -> Unit = { action -> action() }

    fun uptimeMillis(): Long = clock()

    fun debug(tag: String, message: String) = logger(tag, message)

    fun scheduleDelayed(delayMillis: Long, action: () -> Unit) = delayedScheduler(delayMillis, action)

    fun cancelDelayed(action: () -> Unit) = delayedCancellation(action)

    internal fun <T> withTestSeams(
        clock: () -> Long,
        logger: (String, String) -> Unit = { _, _ -> },
        delayedScheduler: (Long, () -> Unit) -> Unit = this.delayedScheduler,
        delayedCancellation: (() -> Unit) -> Unit = this.delayedCancellation,
        block: () -> T,
    ): T {
        val previousClock = this.clock
        val previousLogger = this.logger
        val previousDelayedScheduler = this.delayedScheduler
        val previousDelayedCancellation = this.delayedCancellation
        this.clock = clock
        this.logger = logger
        this.delayedScheduler = delayedScheduler
        this.delayedCancellation = delayedCancellation
        return try {
            block()
        } finally {
            this.clock = previousClock
            this.logger = previousLogger
            this.delayedScheduler = previousDelayedScheduler
            this.delayedCancellation = previousDelayedCancellation
        }
    }

}
