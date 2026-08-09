package com.cattailsw.nanidroid.durable

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal interface DurableAttentionScheduler {
    fun schedule(delayMillis: Long, task: Runnable)
    fun cancel()
}

internal interface DurableAttentionNotifier {
    fun reconcile(stalled: List<DurableOperationRecord>)
}

internal class DurableOperationAttentionCoordinator(
    private val supervisor: DurableOperationSupervisor,
    private val scheduler: DurableAttentionScheduler,
    private val notifier: DurableAttentionNotifier,
) {
    private val stalledOperations = MutableStateFlow<List<DurableOperationRecord>>(emptyList())
    private val reconcileTask = Runnable(::reconcile)
    private val coordinationLock = Any()
    private var revision = 0L
    private var started = false

    fun observeStalledOperations(): StateFlow<List<DurableOperationRecord>> =
        stalledOperations.asStateFlow()

    fun start() {
        synchronized(coordinationLock) { started = true }
        supervisor.setMutationListener(::wake)
    }

    fun stop() {
        supervisor.setMutationListener(null)
        synchronized(coordinationLock) {
            started = false
            revision += 1L
            scheduler.cancel()
        }
        stalledOperations.value = emptyList()
        notifier.reconcile(emptyList())
    }

    fun refresh() {
        wake()
    }

    private fun wake() {
        synchronized(coordinationLock) {
            if (!started) return
            revision += 1L
            scheduler.schedule(0L, reconcileTask)
        }
    }

    private fun reconcile() {
        val observedRevision = synchronized(coordinationLock) {
            if (!started) return
            revision
        }
        val snapshot = supervisor.attentionSnapshot()
        val stalled = snapshot.records.filter(DurableOperationRecord::showStallPrompt)
        stalledOperations.value = stalled
        notifier.reconcile(snapshot.notificationRecords)
        synchronized(coordinationLock) {
            if (!started || revision != observedRevision) return
            snapshot.nextCheckDelayMillis?.let { scheduler.schedule(it, reconcileTask) }
        }
    }
}

internal class BackgroundDurableAttentionScheduler : DurableAttentionScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "nanidroid-durable-attention").apply { isDaemon = true }
    }
    private var generation = 0L
    private var pending: ScheduledFuture<*>? = null

    @Synchronized
    override fun schedule(delayMillis: Long, task: Runnable) {
        generation += 1L
        val expectedGeneration = generation
        pending?.cancel(false)
        pending = executor.schedule(scheduled@{
            synchronized(this) {
                if (generation != expectedGeneration) return@scheduled
                pending = null
            }
            task.run()
        }, delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }

    @Synchronized
    override fun cancel() {
        generation += 1L
        pending?.cancel(false)
        pending = null
    }
}
