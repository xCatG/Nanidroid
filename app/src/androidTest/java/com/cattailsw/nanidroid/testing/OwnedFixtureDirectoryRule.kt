package com.cattailsw.nanidroid.testing

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Wrap UI rules inside this rule so their jobs and Activity dispose before files. */
class OwnedFixtureDirectoryRule : TestRule {
    lateinit var root: File
        private set

    fun directory(name: String): File = File(root, name).apply {
        check(mkdir()) { "Cannot create owned fixture directory: $absolutePath" }
    }

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "owned-fixture-${UUID.randomUUID()}")
            var primary: Throwable? = null
            var created = false
            try {
                check(root.mkdir()) { "Cannot create owned fixture root: ${root.absolutePath}" }
                created = true
                base.evaluate()
            } catch (failure: Throwable) {
                primary = failure
                throw failure
            } finally {
                if (created) {
                    try {
                        check(root.deleteRecursively() && !root.exists()) {
                            "Owned fixture cleanup failed: ${root.absolutePath} exists=${root.exists()}"
                        }
                        println("OWNED_FIXTURE_CLEANUP passed path=${root.absolutePath}")
                    } catch (cleanup: Throwable) {
                        if (primary != null) {
                            primary.addSuppressed(cleanup)
                            System.err.println("OWNED_FIXTURE_CLEANUP failed path=${root.absolutePath}: $cleanup")
                        } else throw cleanup
                    }
                }
            }
        }
    }
}
