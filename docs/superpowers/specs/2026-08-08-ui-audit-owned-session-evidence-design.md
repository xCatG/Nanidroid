# UI Audit Owned-Session Evidence Design

**Issue:** #254

## Goal

Bind the two required manual interaction PNGs to the exact audit-owned emulator session and debug APK before the runner performs cleanup.

## Scope

Only `scripts/run-ui-visual-audit.ps1` and its host-only dry-run contract checks change. The design does not add SHIORI event payload evidence (#256), alter worktree provenance policy (#255), or change process-tree cleanup (#251).

## Design

After the 67 automated cases complete, the runner enters an explicit manual-interaction checkpoint while its owned emulator is still running and the audited APK remains installed. The operator captures the two manifest-declared PNGs from that live session, then confirms the checkpoint.

On confirmation, the runner requires exactly the two manifest paths, validates their PNG format, hashes them, and writes an `interactionCapture` record into the capture summary. The record contains the session's serial, AVD name, snapshot name, owned emulator PID and start-time ticks, the audit capture start time, the current APK provenance, and each declared artifact path with its SHA-256. It is written before the runner uninstalls packages or stops the emulator.

Manual completion requires that record. It fails closed unless the recorded session identity equals the summary's owned session values, the stored APK provenance matches capture provenance, each required artifact is represented once at its declared path, and its current hash equals the recorded checkpoint hash. The existing current-build and freshness checks remain in force.

## Error Behavior

The checkpoint never creates missing interaction evidence and never accepts a substituted path, duplicate row, invalid hash, or stale artifact. A failed checkpoint records a failed audit summary and cleanup continues through the existing `finally` path. The runner does not cancel ghost work automatically; it simply delays normal cleanup until the explicit checkpoint completes or fails.

## Testing

Dry-run probes cover a valid owned-session interaction record plus failure for a missing record, changed APK provenance, mismatched session identity, missing artifact, substituted path, duplicated entry, and changed artifact hash. The tests exercise the same parsing and assertion helpers used by manual completion.

## Simplification Review

Keep session construction and assertion in small helpers shared by report writing and manual verification. No broad reformatting or unrelated extraction is justified in the large audit script.
