# PR D9b2a — app-private staged NAR snapshot

D9b2a adds only the production boundary that copies an external NAR into a
fresh file below an existing, canonical, trusted staging root. Creation uses
create-new semantics with bounded collision retries and never overwrites an
existing name.

The streaming copy accepts exactly 544 MiB. It consumes at most one additional
byte to reject an oversize source and never writes that extra byte. A
successful copy is synced, its writer is closed, and its source is closed
before the opaque `NarStagedSource` capability is minted.

On failure, cleanup closes the writer, closes the source, and then deletes the
partial file. The first typed failure remains primary; later cleanup failures
are retained in order. On success, this factory does not delete the snapshot:
the later verified session remains its sole deleter.

No raw staged path, writer, or replacement operation escapes. The existing
package-private synchronized `claim()` is the sole raw-file handoff to the
validator. The app must exclusively own the staging root from create-new until
verified-session close, including the interval between writer close and
session close. Portable API-9 file APIs cannot prevent a malicious same-UID
ABA replacement, and `File.setReadOnly()` neither fixes that limitation nor
allows reliable later cleanup on Windows. Capability discipline is therefore
the portable control.

This slice performs no ZIP validation, extraction, merge, target traversal,
publication, or manager integration.
