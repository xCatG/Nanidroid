package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.shiori.Shiori
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class ManualRuntimeScheduler : SScriptPlaybackScheduler {
    data class Scheduled(val delayMillis: Long, val action: () -> Unit)
    private val pending = ArrayDeque<Scheduled>()

    override fun schedule(delayMillis: Long, action: () -> Unit) {
        pending += Scheduled(delayMillis, action)
    }

    override fun cancelPending() = pending.clear()
    fun runNext() = requireNotNull(pending.removeFirstOrNull()).action()
    fun runAll() { while (pending.isNotEmpty()) runNext() }
    fun delays(): List<Long> = pending.map(Scheduled::delayMillis)
}

internal class BlockingRuntimeAdapter(
    private val delegate: Shiori,
) : Shiori by delegate {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun request(request: String): String {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS))
        return delegate.request(request)
    }
}
