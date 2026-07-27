# PR D9b3 — transactional fresh NAR installation

This slice connects the D9b staged source and validated-plan capabilities to
the actual `GhostMgr` installation path. It replaces the legacy direct
`NarUtil.readNarArchive` call for all new ghost installs.

## Product policy

Nanidroid now supports a **fresh install only** transaction. An existing target
directory is rejected without modification. This deliberately means a ghost
"update" must be removed and installed again until a separately designed,
authenticated upgrade transaction exists. The user-visible error is explicit:
an existing ghost must be removed before a new copy is installed; unsupported
refresh/compound descriptors say that the ghost update is incompatible.

## Transaction

1. Copy the caller-selected archive to a newly-created private transaction
   directory beneath the ghost root.
2. Identity-check and validate that exact snapshot: central-directory inventory,
   normalized paths, descriptor, target id, entry count, declared sizes and
   compression ratios.
3. Reject a target that already exists.
4. Extract only validated relative paths to a newly-created sibling candidate.
   Streaming enforces 128 MiB per file and 512 MiB total actual bytes, checks
   declared length and CRC, and syncs every output file.
5. Close and delete the verified archive snapshot. Publish only a complete
   candidate by same-filesystem rename to the absent target name.
6. On every failure, delete this transaction's candidate and snapshot. Stale
   transaction siblings from a prior process are never reused as install input.

The app serializes transactions in-process. The ghost root is app-specific
external storage; Android scoped storage (minimum API 31 / target API 37)
prevents other apps from writing it. Native no-follow retained-tree staging
remains the prerequisite for a future overwrite/update transaction and is not
claimed by this fresh-install slice.

## Error surface

`NarTransactionalInstaller.Result` has stable categories for unavailable
source, invalid storage, rejected archive, existing target, staging/extraction,
publication, and cleanup. `GhostMgr` retains the human-readable result for the
AsyncTask completion path; the Activity sends the normal `OnInstallFailure`
event and shows the explanation in a long Toast.

## Tests and device evidence

`NarTransactionalInstallerTest` proves wrapped forced-id installation, byte
preservation, existing-target preservation, invalid-archive cleanup, and the
user-facing error category. API 36.1 emulator validation installed the device
APK and observed the initial ghost at:

```text
/sdcard/Android/data/com.cattailsw.nanidroid/files/ghost/nanidroid/
  install.txt
  readme.txt
```

The test suite also preserves the old native-stage contract while asserting
that `GhostMgr` calls `NarTransactionalInstaller.install` and does not call
`NarUtil.readNarArchive`.
