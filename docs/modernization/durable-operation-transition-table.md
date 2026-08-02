# Durable operation transition table

The durable record is the source of truth for non-archive updates. Archive
adapters use the same transitions while extending the `NarDownload` record,
whose `id` is the canonical `OperationId`. An `OperationHandle` combines that
stable ID with an incrementing attempt ID. Every callback must compare and set
the exact handle so an older DownloadManager row or WorkManager execution
cannot mutate a replacement attempt.

The supervisor's monotonic `lastProgressAt` value is deliberately in memory.
Restoring a running record starts a new observation window without restarting
the external job. A restored cancellation request is sent to the external job
again because cancellation is idempotent.

| Event | Durable precondition | Persisted transition | External action / replay rule |
| --- | --- | --- | --- |
| Accept new operation | No record with the operation ID | Insert `RUNNING`, exact attempt and external binding, initial phase/count, prompt hidden | Persist before enqueueing or starting the external side effect. Duplicate acceptance is rejected. |
| Accept retry | Existing attempt is terminal, new attempt ID is greater, and any supplied binding differs from the prior attempt | CAS the terminal record to the new `RUNNING` attempt and binding; retain the prior binding while a replacement is pending | The prior attempt remains fenced out. A retry never directly or later rebinds the prior DownloadManager row or Work UUID. |
| Start external side effect | Accepted `RUNNING` record exists with a null pending binding | CAS-bind its exact external job once before callbacks are accepted | The binding is immutable for the attempt. Death before the side effect leaves accepted work for reconciliation. Death after the side effect but before binding requires the adapter to re-find that job by its idempotency key. |
| Report real progress | Exact handle is bound and `RUNNING`; phase changes or completed count increases | CAS new progress and hide the prompt | Unbound callbacks are rejected. Reset the in-memory 30-second window only after CAS succeeds. Repeated phase/count and regressing counts are not heartbeats. |
| Observe 30-second stall | Exact handle remains `RUNNING` or `CANCEL_REQUESTED` | CAS prompt visible; cancellation stalls also retain a diagnostic | Never cancel or restart automatically. |
| Keep waiting | Exact handle is active | CAS prompt hidden | Start a fresh observation window; do not touch the external job. |
| Request stop | Exact handle is `RUNNING` | CAS `CANCEL_REQUESTED`, phase `Stopping...`, prompt hidden | Only after persistence, cancel the exact `(handle, binding)`. If binding is pending, its later persistence immediately triggers cancellation. Duplicate requests are no-ops. |
| Restore running work | Persisted state is `RUNNING` | No immediate write | Start a fresh observation window and keep observing the bound job. |
| Restore stop request | Persisted state is `CANCEL_REQUESTED` | No immediate write | Immediately repeat cancellation for the exact bound job, or wait for the pending binding and cancel it then; start a fresh `Stopping...` observation window. |
| Terminal callback | Exact handle is bound and active | CAS `COMPLETED`, `FAILED`, or `CANCELLED`; hide prompt | Unbound callbacks are rejected. Active snapshots omit terminal records. Durable terminal state remains as a replay fence and as the CAS base for a higher retry attempt. |
| Duplicate callback | Handle is already terminal | No change | Ignore it; cleanup remains idempotent. |
| Stale callback / worker replay | Attempt differs, or expected status is no longer active | No change | Ignore it, including late callbacks from a cancelled/retried attempt. |

The transition rules apply to every operation kind as follows:

| Kind | Acceptance and external identity | Stop ownership | Terminal cleanup / recovery |
| --- | --- | --- | --- |
| `REMOTE_NAR` | `NarDownload.id`; bind the exact DownloadManager row | Remove/cancel only that row | Archive adapter records terminal state, then retries owned-file/row cleanup; duplicate receiver delivery is ignored. |
| `LOCAL_NAR` | `NarDownload.id`; bind the exact copy Work UUID | Cancel only that copy worker | Preserve or release the URI grant according to archive ownership; stale workers cannot replace a retry. |
| `NAR_INSTALL` | `NarDownload.id`; bind the exact install Work UUID | Cancel only that staged install | Publication is transactional; terminal state precedes bounded staging/archive cleanup. |
| `GHOST_UPDATE` | Stable ghost update ID; bind the exact Work UUID | Cancel only that updater | Recovery journal rolls publication forward/back before boot; terminal state precedes staging cleanup. |
