# Archive intake and DownloadManager design

## Goal

Resolve GitHub issues #160 and #161 by retiring the unsupported external web-intent archive path and making user-entered HTTPS archive downloads reliable without Nanidroid-owned download notifications.

## Decision

Remove the generic HTTPS `ACTION_VIEW` filter and its activity lifecycle handling. Keep the user-entered URL flow, but validate it as an app-initiated remote archive request.

Replace the service-owned archive `AsyncTask` with `DownloadManager`. A non-exported completion receiver verifies an ID recorded by the coordinator, opens the completed download through `DownloadManager`, stages it in private cache with `NarContentUriImport`, and calls `GhostMgr.installGhost`. The archive transfer no longer starts `NanidroidService` or posts Nanidroid-owned notifications. Polling and ghost-update service behavior is unchanged.

## Constraints

- HTTPS URL with host and `.nar` or `.zip` path only.
- Completed content is copied to a private one-shot staging file before installation.
- Completion broadcasts are non-exported and re-query `DownloadManager` before handling.
- Target API range is 31–37; local SAF import is unchanged.

## Verification

JVM tests cover URL validation and stream staging. Manifest and source checks prove the generic HTTP intent filter and the archive-download service task are gone. Assemble and unit-test the Android module before publishing.
