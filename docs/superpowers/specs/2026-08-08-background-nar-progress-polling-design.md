# Background NAR Progress Polling Design

## Scope

Implement GitHub issue #173 only. The remote NAR progress observer must not
run synchronous `DownloadManager` queries or durable progress reporting on the
main looper. This does not change queue states, poll cadence, retry/recovery,
or cancellation semantics.

## Design

`DownloadManagerProgressObserver` owns one daemon-backed, single-threaded
scheduled executor by default. Its existing per-`OperationHandle` observation
identity remains the fence for every callback:

1. `start` replaces any previous observation for the handle, schedules an
   immediate poll, and records its exact runnable as the current owner.
2. The executor invokes the poll. It reads downloaded bytes, reports durable
   progress, reads the remote status, and schedules the next one-second poll
   only if the same runnable still owns the handle and the row remains in
   progress.
3. `stop` removes the exact owner and cancels its scheduled callback. A
   callback already running may finish its current query/report, but it cannot
   schedule further work after ownership has been removed or replaced.

The observer publishes no direct UI state, so no main-thread marshaling is
needed. Durable persistence remains in `DurableOperationSupervisor`, now on
the observer's background thread, with the existing exact DownloadManager
binding and attempt fence.

## Ownership and Recovery

`NarDownload` and the durable operation record remain the persisted sources of
truth. `DownloadManager` owns its row and transfer notification. The observer
owns only ephemeral scheduled polling callbacks and never creates, removes, or
rebinds a row. After process death, normal repository reconciliation creates a
new observer; no polling callback is persisted or replayed.

| Event | Ephemeral observer result | Durable result |
| --- | --- | --- |
| Start or restore an in-progress bound row | Schedule one background poll | None |
| Progress increases | Report through the exact binding | Existing CAS progress transition |
| Row stops being in progress | Drop the callback | None |
| Stop, delete, retry, or replacement | Cancel/drop exact callback; no reschedule | Existing lifecycle transition |
| Process death | Executor and callbacks disappear | Reconciliation remains authoritative |

## Tests

Add deterministic JVM tests around the observer's injectable scheduler/gateway
to verify that polling and persistence happen off the main thread, that stop
cancels future polls, and that an in-flight stale/replaced callback cannot
reschedule itself. Existing repository tests continue covering the persistent
queue and reconciliation behavior.

## Simplification Boundary

Keep the abstraction limited to a small scheduler interface suitable for JVM
tests. Remove the main-looper-specific implementation and avoid parallel
handler/executor paths. Do not broaden the change into a repository or UI
refactor.
