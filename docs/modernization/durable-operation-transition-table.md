# Durable operation transition table

The durable record is the source of truth for non-archive updates. Archive
adapters use the same transitions while extending the `NarDownload` record,
whose `id` is the canonical `OperationId`. An `OperationHandle` combines that
stable ID with an incrementing attempt ID. Every callback must compare and set
the complete expected record snapshot so a competing writer, older
DownloadManager row, or WorkManager execution cannot mutate newer state.

The supervisor's monotonic `lastProgressAt` value is deliberately in memory.
Restoring a running record starts a new observation window without restarting
the external job. A restored cancellation request is sent to the external job
again because cancellation is idempotent.

| Event | Durable precondition | Persisted transition | External action / replay rule |
| --- | --- | --- | --- |
| Accept new operation | No record with the operation ID | Insert `RUNNING`, exact attempt and external binding, initial phase/count, prompt hidden | Persist before enqueueing or starting the external side effect. Duplicate acceptance is rejected. |
| Accept retry or installation handoff | Existing attempt is terminal; new attempt ID is greater; kind is unchanged, `REMOTE_NAR -> NAR_INSTALL`, or `LOCAL_NAR -> NAR_INSTALL`; and any supplied binding is absent from complete operation history | CAS the terminal record to the new `RUNNING` attempt and binding; carry forward every previously used binding | Reverse installation handoff, remote/local provider switching, ghost transitions, and active kind changes are rejected. Prior attempts remain fenced out and no earlier DownloadManager row or Work UUID may be rebound. |
| Start external side effect | Accepted `RUNNING` record exists with a null pending binding | CAS-bind its exact external job once before callbacks are accepted | The binding is immutable for the attempt. Death before the side effect leaves accepted work for reconciliation. Death after the side effect but before binding requires the adapter to re-find that job by its idempotency key. |
| Report real progress | Exact handle is bound and `RUNNING`; phase changes or completed count increases | CAS new progress and hide the prompt | Unbound callbacks are rejected. Reset the in-memory 30-second window only after CAS succeeds. Repeated phase/count and regressing counts are not heartbeats. |
| Observe 30-second stall | Exact handle remains `RUNNING` or `CANCEL_REQUESTED` | CAS prompt visible; cancellation stalls also retain a diagnostic | Never cancel or restart automatically. |
| Keep waiting | Exact handle is active | CAS prompt hidden | Start a fresh observation window; do not touch the external job. |
| Request stop | Exact handle is `RUNNING` | CAS `CANCEL_REQUESTED`, phase `Stopping...`, prompt hidden | Only after persistence, cancel the exact `(handle, binding)`. If binding is pending, its later persistence immediately triggers cancellation. Failure to issue cancellation records a bounded sanitized diagnostic and keeps `CANCEL_REQUESTED`; duplicate requests are no-ops and successful retries clear that diagnostic. |
| Confirm no external job | Exact handle is unbound `CANCEL_REQUESTED`; adapter confirms no job was created | CAS `CANCELLED`, prompt hidden | Do not invent a cancellation side effect. Reject bound, running, terminal, and stale attempts. |
| Restore running work | Persisted state is `RUNNING` | No immediate write | Start a fresh observation window and keep observing the bound job. |
| Restore stop request | Persisted state is `CANCEL_REQUESTED` | No immediate write | Immediately repeat cancellation for the exact bound job, or wait for the pending binding and cancel it then; start a fresh `Stopping...` observation window. |
| Terminal callback | Exact handle is bound and active | CAS `COMPLETED`, `FAILED`, or `CANCELLED`; hide prompt | Unbound callbacks are rejected. Active snapshots omit terminal records. Durable terminal state remains as a replay fence and as the CAS base for a higher retry attempt. |
| Duplicate callback | Handle is already terminal | No change | Ignore it; cleanup remains idempotent. |
| Stale callback / worker replay | Attempt differs, or expected status is no longer active | No change | Ignore it, including late callbacks from a cancelled/retried attempt. |

The transition rules apply to every operation kind as follows:

| Kind | Acceptance and external identity | Stop ownership | Terminal cleanup / recovery |
| --- | --- | --- | --- |
| `REMOTE_NAR` | `NarDownload.id`; bind the exact DownloadManager row | Remove/cancel only that row | Archive adapter records terminal state, then retries owned-file/row cleanup; duplicate receiver delivery is ignored. |
| `LOCAL_NAR` | `NarDownload.id`; new attempts derive a deterministic Work UUID from kind + operation + attempt; valid legacy UUIDs remain accepted | Cancel only that exact copy UUID. A malformed binding is CAS-repaired to the deterministic ID only while the same handle is active; never cancel the reusable unique-work name. | Preserve or release the URI grant according to archive ownership; stale workers cannot replace a retry. |
| `NAR_INSTALL` | `NarDownload.id`; new attempts derive a deterministic Work UUID from kind + operation + attempt; valid legacy UUIDs remain accepted | Cancel only that exact install UUID, using the same fenced malformed-binding repair rule as local copy. | Publication is transactional; terminal state precedes bounded staging/archive cleanup. |
| `GHOST_UPDATE` | Stable ghost update ID; new attempts derive a deterministic Work UUID from kind + operation + attempt; valid legacy UUIDs remain accepted | Cancel only that exact updater UUID, using the same fenced malformed-binding repair rule as archive work. | Recovery journal rolls publication forward/back before boot; terminal state precedes staging cleanup. |

| Surface | Ownership | Transition rule |
| --- | --- | --- |
| Queue UI (`observeDownloads` cards, prompt text, Keep waiting / Stop buttons) | UI state is derived from `NarDownload` and durable records; actions call repository methods and are not source-of-truth | Queue surfaces are **A2-owned** and send exact-handle, idempotent stop/keep-waiting requests into the durable supervisor only; no adapter-specific binding is mutated directly by UI |
| Remote download notification (`DownloadManager`) | `DownloadManager` owns the system notification while a `DownloadManager` row is active | `requestStop` cancels durable work and `downloads.remove(downloadManagerId)` is used by repository lifecycle paths when cancelling/retrying/deleting or terminal cleanup decides the transfer no longer owns the row |
| Work notification / worker lifecycle (`WorkManager`) | WorkManager owns any worker-owned notification or progress channel | Only the durable external identity (Work UUID) is retained for cancellation; repository side effects are always driven from durable transition outcomes, and the platform notification channel is not a source of truth |

## A2 notification transition/effect table

The A2 stalled-attention notification is keyed to the **exact operation handle**:
its tag is `OperationId::AttemptId`, its ID is one fixed attention-specific value,
and each action uses an explicit immutable `PendingIntent` whose action and data URI
encode that same handle. Extras alone never define action identity.

| Trigger | Durable state | Published attention | System notification / action validity |
| --- | --- | --- | --- |
| `RUNNING` heartbeat | `RUNNING`, `showStallPrompt=false` | No stalled-operation prompt | Cancel any stale attention notification for this exact handle; publish no recovery actions. |
| First stalled `RUNNING` | `RUNNING`, `showStallPrompt=true` | Show the in-app prompt in stable exact-handle order | Post/update this handle's attention notification with **Keep waiting**, operation-specific **Stop**, and diagnostics. |
| **Keep waiting** | Active record changes `showStallPrompt=true -> false` | Hide this prompt and advance deterministically to the next stalled handle | Cancel this handle's attention notification and start a fresh 30-second window; do not touch the external job. |
| **Stop** -> `CANCEL_REQUESTED` | Exact `RUNNING` handle changes to `CANCEL_REQUESTED`, phase `Stopping...` | Show `Stopping...` for the selected operation while the transition remains visible | Issue cancellation only for the exact binding. If an attention notification already exists, update it to omit **Stop**; a stop requested before any stall does not create attention by itself. |
| Second stalled stopping | `CANCEL_REQUESTED`, `showStallPrompt=true`, `isCancellationDispatchFailure(record)` | Show `Stopping...` plus bounded diagnostics | Post/update this handle's notification with **Keep waiting**, **Retry stop request**, and diagnostics only; successful stop reissue removes any extra stop action and there is no second force-stop action. |
| Stalled but dispatched stop in progress | `CANCEL_REQUESTED`, `showStallPrompt=true`, `!isCancellationDispatchFailure(record)` | Show `Stopping...` plus bounded diagnostics | Post/update this handle's notification with **Keep waiting** and diagnostics only; there is no second **Stop request** action and no force-stop action. |
| Terminal (`COMPLETED`/`FAILED`/`CANCELLED`) | Exact active record becomes terminal | Remove this handle from published attention and advance to the next stalled handle | Cancel only this handle's tag plus the fixed attention ID; all older actions become no-ops. |
| Process restart reconciliation | Persisted active records load with a fresh observation window | Publish the deterministic active snapshot after reconciliation | Cancel surviving notifications that do not match currently attentive exact handles; reuse canonical tag/action identities for matches. |
| Attempt rollover or missing record | A newer attempt replaces the old handle, or no durable record exists | Drop the old handle and advance without disturbing other prompts | Cancel only the old handle's tag plus fixed attention ID; never use `cancelAll`. |
| Stale exact-handle action | URI handle is malformed, terminal, missing, or no longer the active attempt | No visible or durable change | Reject as a no-op. Reconciliation may cancel that exact leftover notification, but must not act on a newer attempt. |
