# PR D9b2b2b1 — lease primitives and cleanup repair

Package-private leases make staged-tree facts and verified archive plans
exclusive without exposing files, paths, handles, tokens, streams, or writers.
`READY` can become `BUSY`, then release back to `READY` or transfer ownership
to a consumed lease; direct cleanup remains available only when no lease owns
the resource.

Archive close and staged-file deletion are independent retryable components.
Every cleanup call attempts every unfinished component, preserves the first
failure (including linkage and allocation errors), and never repeats work that
already succeeded. Native error ordinals 0–20 remain unchanged; `BUSY` is
Java-only and appended to the error enum.
