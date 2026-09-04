package com.cattailsw.nanidroid

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
            LegacyPlatform.withTestSeams(clock = { 0L }, delayedScheduler = { _, _ -> }, delayedCancellation = { _ -> }) {
                try {
                    base.evaluate()
                } catch (error: Throwable) {
                    failure = error
                }
            }
            failure?.let { throw it }
        }
    }
}
