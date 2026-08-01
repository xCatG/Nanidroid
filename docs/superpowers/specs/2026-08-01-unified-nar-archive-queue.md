# Unified NAR Archive Queue Design

## Goal

Replace service-owned remote archive downloads with a durable, user-controlled queue. The existing Activity supports manual HTTPS URLs, the SAF picker, and file-browser `content://` Open-with intents. All converge on the existing transactional installer.

## Boundaries

```text
Existing Activity / existing Compose UI
            -> NarDownloadRepository -> persistent queue store
                                         -> DownloadManager (remote transfer only)
                                         -> WorkManager (one install attempt)
            -> InstallNarWorker -> NarContentUriImport -> GhostMgr
```

The Activity is only an input adapter and UI host; it is not another Activity and does not install files. `NarDownloadRepository` is the durable source of truth and owns `enqueueRemote`, `enqueueLocal`, `retry`, `delete`, and `observeDownloads`. DownloadManager owns only transfer. The worker owns one cancellable install attempt. GhostMgr owns transactional validation/publication, not URLs or lifecycle.

## Entry points

| Input | Supported form | Queue source |
| --- | --- | --- |
| Manual dialog | complete, normalized HTTPS URL | remote URL |
| Existing picker | persistable `content://` grant | local URI |
| File browser Open-with | granted `content://` URI | local URI |

Remove the generic HTTPS `ACTION_VIEW` filter and retired URL-intent helpers. Retain a narrow local `content://` `ACTION_VIEW` adapter in the existing Activity. Its manifest filter declares only supported NAR/ZIP MIME types (for example `application/x-nar` and `application/zip`), never `*/*` or `application/octet-stream`; the Activity also rejects non-content schemes, missing read grants, and unsupported data. SAF and Open-with call the same `enqueueLocal(uri)` operation.

A persistable URI grant may be retained for background work, but each worker open handles `SecurityException` and `FileNotFoundException` and turns a revoked or unavailable provider into `NeedsAttention`. A temporary external-provider grant is copied on `Dispatchers.IO` by the Activity before it returns from handling the intent, with the size cap, into app-owned storage. If that foreground copy is interrupted or cannot finish while the grant is valid, remove the partial file and persist `NeedsAttention` with a reselect action that directs the user to the SAF picker. A worker never relies on a temporary grant.

## Durable item and state

Each record has a freshly generated opaque enqueue ID, source, state, timestamps, optional DownloadManager ID, optional retained app-owned URI/path, and an optional user-safe failure. Store mutations are serialized and atomic per record; never use a shared mutable `StringSet` of IDs. Each worker attempt writes only beneath an item- and attempt-specific temporary directory; an old cancelled item can never clean a later enqueue's staging files.

```text
Downloading -> Installing -> Installed
     |              |
     +-------> NeedsAttention
```

`NeedsAttention` is durable and offers Retry/Delete. Retry re-enqueues a failed remote transfer, or re-installs from a retained archive. A source whose temporary URI grant expired instead offers Reselect/Delete. Delete cancels unique work, calls `DownloadManager.remove(downloadId)` for an active or retained remote transfer, removes app-owned remote/temp data and the record, and never deletes a user-owned picker/Open-with document. Completed items may be hidden after the UI refreshes its GhostMgr list.

## Transfer, recovery, and app lifetime

Remote URLs are parsed as complete values; URI syntax errors are caught and only HTTPS is accepted. Normalize the accepted scheme before constructing DownloadManager requests. Persist `Downloading` before enqueueing. Destination/enqueue exceptions become `NeedsAttention`, never an Activity crash.

The completion receiver verifies the DownloadManager ID belongs to a record, schedules unique work named `install-nar-<itemId>` using `KEEP`, and immediately returns. It performs no copy/extraction. A non-exported `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` receiver and normal repository startup reconciliation cross-reference every nonterminal remote record with its actual DownloadManager cursor status: successful means schedule install, failed/missing means `NeedsAttention`, pending/running stays `Downloading`. This closes the race where process death occurs after DownloadManager completes but before its broadcast schedules work.

DownloadManager normally continues after the user dismisses the app. A force-stop prevents app components from running until the next launch; reconciliation restores the correct state. WorkManager may resume an interrupted installation attempt, but completed archive or storage failures never request an automatic retry.

## Install outcome and cancellation

Replace nullable/string-only status with:

```kotlin
sealed interface ArchiveInstallResult {
    data class Installed(val installPath: String) : ArchiveInstallResult
    data class Failed(val message: String, val kind: ArchiveFailureKind) : ArchiveInstallResult
    data object Cancelled : ArchiveInstallResult
}
```

`NarContentUriImport`, staging, and extraction accept a cancellation predicate and check it inside streaming loops. Each worker starts by creating a new item- and attempt-specific staging location; it deletes only that location on stop. An interrupted attempt therefore never reuses partial output, and cancellation from a deleted item cannot clean a later re-enqueue's staging data. The initial copy applies the existing maximum-byte limit while reading, before filling internal storage. Cancellation/deletion closes streams and cleans only partial staging/transaction data. Any `Failed` result is persisted as `NeedsAttention`; the category exists for diagnostics/tests, not retry policy.

## UI and notification behavior

The current screen observes queue records and renders Downloading, Installing, and NeedsAttention. Retry/Delete invoke repository actions. On Installed it refreshes the Activity's GhostMgr list, then hides/removes the completed queue item. An optional attention notification uses an explicit PendingIntent to the existing Activity and is not required for correctness.

## Acceptance criteria

1. Unknown completion broadcasts do nothing.
2. Completed remote files survive receiver return, process death, reboot, and package update.
3. Failed files are retained and visible with Retry/Delete.
4. Validation/install failures never loop automatically.
5. Delete cooperatively stops work and cleans partial files.
6. Every staging copy is byte-limited.
7. Malformed URLs and unavailable destination storage are reported, not thrown.
8. SAF and file-browser URI input share one installer path, and loss of a persisted URI grant/provider becomes a reselectable failure.
9. Reconciliation schedules every registered successful DownloadManager item that lacks completed installation work.
10. Delete/re-enqueue cannot share a staging directory.
9. A successful background install refreshes the ghost list.
10. Python intent/service contracts are migrated in the same change.
