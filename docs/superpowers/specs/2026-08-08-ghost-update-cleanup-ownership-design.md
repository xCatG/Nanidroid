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
record uses the existing `PREPARED` phase: the topology is `LIVE_CANDIDATE`
while candidate cleanup is incomplete, or `LIVE_ONLY` after candidate deletion
but before journal/root cleanup completes. The existing exact-identity
cancelled rollback path handles both idempotently.

| Event | Persist first | Then | Replay |
| --- | --- | --- | --- |
| User cancellation | `PREPARED` journal for exact request | Delete the exact candidate, then journal and transaction root; terminal `Cancelled` | `LIVE_CANDIDATE` or `LIVE_ONLY` evidence remains retryable; journal-less empty transaction roots are swept before recovery scheduling |
| Terminal precommit failure (missing manifest, digest mismatch, exception, or commit-gate failure) | `PREPARED` journal for exact request | Delete the exact candidate before journal/root cleanup; terminal `Failed` | Exact-identity recovery rolls back `LIVE_CANDIDATE` or `LIVE_ONLY`; incomplete cleanup remains durable rather than becoming a journal-less orphan |
| System interruption; staging delete fails | Nothing new | Return `Interrupted` | Worker retry repeats its attempt; no user-terminal state is manufactured |
| Journal recovery with stale/mismatched identity | Existing validation | Leave evidence blocked | Fail closed; do not delete |

## Ownership and safety

Only `transactionRoot(ghostRoot, operationId)` is deleted, with candidate
cleanup scoped to that transaction. The live ghost, other operation
directories, and unrelated storage are never cleanup targets. The journal is
written before candidate deletion and restored if final transaction-root
deletion reports failure. A journal-less empty transaction root from an
interrupted final deletion is swept before recovery scheduling. A journal-write
failure returns a non-terminal failure, retaining both the staging directory
and the durable record for diagnosis rather than claiming cancellation is clean.

## Simplification

No new journal phase, worker result, or recovery worker is needed. Reusing
`PREPARED` keeps the durable transition table and exact-identity authorization
in one place while accurately representing an uncommitted live tree plus
candidate staging.
