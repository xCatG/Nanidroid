package com.cattailsw.nanidroid

import android.graphics.Rect
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Installs deterministic Android clock/log collaborators for host-only tests.
 * Production code always invokes Android directly through [LegacyPlatform].
 */
class HostAndroidStubRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            var failure: Throwable? = null
            LegacyPlatform.withTestSeams(clock = { 0L }, rectangles = ::hostRect) {
                try {
                    base.evaluate()
                } catch (error: Throwable) {
                    failure = error
                }
            }
            failure?.let { throw it }
        }
    }

    private fun hostRect(left: Int, top: Int, right: Int, bottom: Int): Rect {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").let { field ->
            field.isAccessible = true
            field.get(null) as sun.misc.Unsafe
        }
        return (unsafe.allocateInstance(Rect::class.java) as Rect).apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
    }
}
