# Durable workflow review checklist

Use this checklist for work that can outlive an Activity or process: background
workers, downloads, content-URI imports, installation, long-running copies, or
any workflow with durable user-visible status.

## Before implementation

- Write a transition table for every durable state. Include user actions,
  background callbacks, process death, Activity recreation, and worker replay.
- Name the durable source of truth and the identity used to make each external
  action idempotent. Do not use an Activity field as the only claim that work
  was accepted.
- Assign ownership for each resource: private file, document grant,
  DownloadManager row, notification, staging directory, and installed target.
  Define when ownership transfers and when cleanup is retried.
- Put intent delivery, content copying, download handoff, and installation in
  separate boundaries. Each boundary must tolerate duplicate delivery and an
  interruption immediately before or after its external side effect.

## Worked recovery examples

Use the following pattern when deciding what to persist and what recovery must
do. The exact state names may differ, but the ordering should not.

| When this happens | Persist first | Then do | On replay or restart |
| --- | --- | --- | --- |
| An Activity accepts an archive intent | The accepted URI and a pending handoff | Start asynchronous initialization or copying | Ignore the restored delivery of that handoff; treat a new `onNewIntent` as a new user action |
| A temporary `content://` grant needs copying | A visible `Copying` record | Copy into an app-owned location | Convert an interrupted copy to actionable attention and sweep unreferenced temporary files |
| A remote archive is enqueued | The intended destination URI | Call `DownloadManager.enqueue` and persist its ID | Re-find the row by its destination when the ID was not committed |
| A worker runs after a user-visible failure | Nothing; preserve `NeedsAttention` | Return without installing | Retry only after the user explicitly chooses Retry or Select again |
| Installation publishes successfully | `Complete` before cleanup | Remove the owned file, grant, and download row | Retry cleanup during reconciliation; never reinstall a completed record |
| Two records use one document URI | The records themselves are the reference count | Release a persisted grant only after deletion/replacement/completion | Keep the grant while any non-complete record still references it |

## Test matrix

For every durable transition, add focused tests for the applicable cases:

- process death between persisting state and performing the external action;
- process death after the external action but before its result is persisted;
- duplicate callback, intent, or worker execution;
- a stale worker after Retry, Delete, or Select again;
- concurrent operations on the same record;
- shared ownership of a URI grant or retained file;
- cleanup interrupted after the user-visible operation completes.

Keep Activity tests at the boundary and put transition behavior in pure,
repository-level tests where possible.

## Review discipline

- Review the complete state table before opening a PR, not only the happy-path
  implementation.
- If two consecutive review rounds uncover adjacent lifecycle or recovery
  cases, stop patching one comment at a time. Re-audit the whole transition
  table, add the missing scenario tests as a batch, then request review again.
- Keep a PR slice bounded to one durable boundary where practical: state/store,
  Android ingress/background adapter, or UI actions.

## Completion checks

- Run the repository's required unit, build, lint, and contract suites.
- Confirm the current head—not an earlier commit—received the final review.
- Verify that the durable state remains actionable or clean after every failed
  external operation; it must never silently become orphaned work.
