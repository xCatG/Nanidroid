package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.engine.GhostTalkGenerator
import com.cattailsw.nanidroid.engine.ManagedGhostTalkGenerator
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The holder survives toggles; each attached generator belongs to one enabled interval. */
internal class SessionTalkGenerator(private val parent: Job?) : GhostTalkGenerator, AutoCloseable {
    private class Owner(val generator: ManagedGhostTalkGenerator) {
        val lifetime = Job()
    }
    private val lock = Any()
    private val requests = Mutex()
    private var owner: Owner? = null
    private var closed = false
    private var registration = parent?.invokeOnCompletion { close() }

    val available: Boolean get() = synchronized(lock) { owner != null && !closed }

    fun attach(generator: ManagedGhostTalkGenerator) {
        val accepted = synchronized(lock) {
            if (closed || parent?.isActive == false || owner != null) false
            else { owner = Owner(generator); true }
        }
        if (!accepted) closeSafely(generator)
    }

    override suspend fun reply(userText: String): String {
        val current = synchronized(lock) { checkNotNull(owner) { "AI chat is disabled" } }
        return coroutineScope {
            val pending = async {
                requests.withLock {
                    current.lifetime.ensureActive()
                    current.generator.reply(userText).also { current.lifetime.ensureActive() }
                }
            }
            val cancellation = current.lifetime.invokeOnCompletion { pending.cancel() }
            try { pending.await() } finally { cancellation.dispose() }
        }
    }

    fun release() {
        val previous = synchronized(lock) { owner.also { owner = null } } ?: return
        previous.lifetime.cancel()
        closeSafely(previous.generator)
    }

    override fun close() {
        synchronized(lock) { closed = true }
        release()
        registration?.dispose()
        registration = null
    }

    private fun closeSafely(generator: ManagedGhostTalkGenerator) {
        try { generator.close() } catch (error: Exception) {
            Logger.getLogger(SessionTalkGenerator::class.java.name).log(Level.WARNING, "AI session close failed", error)
        }
    }
}
