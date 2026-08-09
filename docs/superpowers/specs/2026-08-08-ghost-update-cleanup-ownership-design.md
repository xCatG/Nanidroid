# Ghost Update Cancellation Cleanup Ownership Design

## Scope

Issue #187 covers a pre-commit ghost-update staging directory whose deletion
fails after a user cancellation. The live ghost was never mutated, but the
directory must remain owned by the exact durable attempt until cleanup
succeeds. System interruption remains retryable and is not converted to user
cancellation.

## Durable state and identity

The source of truth is the existing `journal.v1` in the exact transaction root
derived from the canonical ghost root and operation ID. It carries the same
operation ID, attempt ID, and WorkManager UUID as the request. A cleanup-only
record uses the existing `PREPARED` phase: the topology is `LIVE_CANDIDATE`, so
the existing exact-identity cancelled rollback path is already the correct
idempotent cleanup operation.

| Event | Persist first | Then | Replay |
| --- | --- | --- | --- |
| User cancellation; staging delete succeeds | Nothing | Delete exact transaction root; terminal `Cancelled` | No evidence remains |
| User cancellation; staging delete fails | `PREPARED` journal for exact request | Return `Cancelled` while retaining staging | Classify exact cancelled attempt, remove only transaction root |
| System interruption; staging delete fails | Nothing new | Return `Interrupted` | Worker retry repeats its attempt; no user-terminal state is manufactured |
| Journal recovery with stale/mismatched identity | Existing validation | Leave evidence blocked | Fail closed; do not delete |

## Ownership and safety

Only `transactionRoot(ghostRoot, operationId)` is deleted. The live ghost,
other operation directories, and unrelated storage are never cleanup targets.
The journal is written only after the initial delete has reported failure, so
normal cancellation adds no persistence work. A journal-write failure returns
a non-terminal failure, retaining both the staging directory and the durable
record for diagnosis rather than claiming cancellation is clean.

## Simplification

No new journal phase, worker result, or recovery worker is needed. Reusing
`PREPARED` keeps the durable transition table and exact-identity authorization
in one place while accurately representing an uncommitted live tree plus
candidate staging.
