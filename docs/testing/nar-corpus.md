# NAR corpus runtime audit

## Local synthetic metadata ledger

Build the metadata-only ledger from the checked-in synthetic fixture with:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/build-nar-corpus-metadata-ledger.ps1 `
  -FixturePath scripts/tests/fixtures/nar-corpus-metadata-resolver/phase-one-synthetic.json `
  -OutputRoot build/nar-corpus-metadata-ledger
```

The command writes `ledger.json` under the chosen output root. Its rows are
classified as exactly one of these dispositions: `nar-downloadable`,
`manifest-only`, `unavailable`, `permission-excluded`, or
`duplicate-catalog-record`.

This is a local, metadata-only resolver. It accepts the synthetic JSON fixture
only: it must not be given a `.nar` payload or fixture fields that point to
archive payloads, and it performs no network requests. It is neither the rolling
downloader nor the emulator runtime-audit runner. In particular, it does not
download, install, inspect, or execute archives, and it leaves the pinned corpus
and its manifest unchanged.

`run-nar-corpus-audit.ps1` executes a deterministic, manifest-driven compatibility
run. For each archive, the script installs/builds target and test APKs once, copies
the archive into one per-run private staging path, invokes
`com.cattailsw.nanidroid.corpus.NarCorpusRuntimeTest` with:

- `narCorpusPath`: private input/output directory for the archive and per-run reports
- `narCorpusSha256`: expected archive digest (required)
- `narCorpusLabel`: manifest label for output grouping

The runner enforces emulator-only execution (`ro.kernel.qemu=1`), API 31–37
targets, supported ABIs (`x86_64`, `arm64-v8a`), debug builds, and a fixed
per-run run-id rooted at `/data/local/tmp/nanidroid-corpus/<run-id>`. It copies each
archive under one constant filename, applies mode `0644`, and removes all per-run
device copies in `finally` blocks.

Each installed ghost's SHIORI audit runs through one closeable `GhostRuntime`.
The corpus fixture retains only the immutable ghost handle and generation,
submits tagged requests through the runtime's single native command thread, and
performs typed runtime unload before closing. Instrumentation never constructs,
receives, or retains a SHIORI adapter.

## Inputs

- `-DeviceSerial`: connected emulator serial. Omit with `-DryRun`.
- `-CorpusRoots` (optional): one or more roots containing `.nar` files. A root can be
  a directory or an individual `.nar` file path.
  Default: `.` and `build/ui-audit`.
- `-ApkSignerPath` (optional): path to `apksigner.bat` or `apksigner`; required
  when it is not discoverable on `PATH`.
- `-ManifestPath` (optional): manifest path.
  Default: `docs/testing/nar-corpus-manifest.json`.
- `-PerArchiveTimeoutMinutes` (optional): hard timeout in minutes for each
  `am instrument` run. This is a host-side corpus safety bound, not the app's
  user-visible script-hang policy. Nanidroid does not cancel a running ghost
  automatically; its explicit Stop action appears after 30 seconds.
- `-BuildTimeoutMinutes` (optional): Gradle assemble timeout in minutes.
- `-DevicePathProbeTimeoutSeconds` (optional): deadline for the run-owned
  device-path absence probes before and after each archive. Defaults to 60 seconds
  and rejects values below 60 so emulator `adbd` can recover after native-crash
  processing without weakening the fail-closed transport cutoff.
- `-DryRun` (optional): preflight-only manifest/hash validation.
- `-MinimumFreeBytes` (optional): guard before test execution, defaults to 3 GB.

Each run removes the exact manifest labels' prior local `result.json` and
screenshot evidence before starting the device work. This prevents an aborted
run from satisfying later sentinel checks with artifacts from an older run.
If `am instrument` reports success with empty stdout and stderr, the audit
classifies it as `instrumentation-empty-protocol`, captures process, Activity
Manager, phase-marker, and logcat diagnostics, and fails closed. It does not
automatically retry that envelope because an early runner or app-process death
must remain visible until the retained evidence proves otherwise.

The script requires:

- connected serial and working `adb`
- existing and valid manifest
- zero pre-existing `com.cattailsw.nanidroid` and
  `com.cattailsw.nanidroid.test` installs
- clear free storage on `/data`
- successful `run-as com.cattailsw.nanidroid`
- build/install once per run

## Output

- `build/reports/nar-corpus/summary.json`
- `build/reports/nar-corpus/summary.md`
- `build/reports/nar-corpus/failures/` (per-archive evidence on failures)
- `build/reports/nar-corpus/screenshots/<label>.png`
- `build/reports/nar-corpus/<label>/result.json`

## Behavior in detail

- Loads manifest entries and requires exact hash-set agreement with discovered local
  archives.
- Pushes each archive once per archive to `/data/local/tmp/nanidroid-corpus/<run-id>/`,
  then copies to app-owned private corpus input path for test invocation.
- Invokes `am instrument` with:
  - `class='com.cattailsw.nanidroid.corpus.NarCorpusRuntimeTest#probesArchive'`
  - `narCorpusPath`
  - `narCorpusSha256`
  - `narCorpusLabelBase64` (the UTF-8 manifest label encoded by the host so adb
    argument boundaries cannot alter it)
- Copies back JSON result and screenshot, validates required evidence fields, and
  records a summary record.
- Forces stage cleanup via `run-as`, guarded `adb rm`, and `am force-stop` in
  every normal completion path, then proves that the app-private input, external
  result, and host temporary paths are absent. If adb itself times out, the runner
  writes the partial report and deliberately avoids issuing further device cleanup
  commands through the hung transport.
- Treats a root `install.txt` as the authoritative package descriptor. Descriptor
  files below that package root are payload; a single depth-two wrapper descriptor
  is accepted only when no root descriptor exists. Ambiguous wrappers and deep-only
  descriptors remain invalid.
- Runs strict global and representative-ghost sentinels over the collected JSON.
  These checks cover result count, zero failures, cleanup, parser provenance,
  authored collision geometry and routing, dialogue sequences, optical bounds,
  asymmetric Sakura/Kero surfaces, and intentionally unsupported package kinds.
- Persists fixed metadata in `summary.json`/`summary.md`, including:
  - git commit
  - APK hashes
  - manifest hash
  - manifest fingerprint/rows
  - device fingerprint/API/ABI/density
  - run duration
  - per-archive outcomes

The run fails for:

- malformed/missing required args
- manifest mismatch
- device gate or security violations
- missing result payload or screenshot
- unsupported classification transitions
- unexpected instrument crashes and per-archive timeout
- any failed global or representative-ghost sentinel

Intentionally incompatible and unsupported packages are successful audit rows when
their classification and structured diagnostics match the manifest. A native crash
is accepted only for an exact manifest row with `allowNativeKawariCrash: true` when
the instrument output, crash buffer, target process, `SIGSEGV`, and `libkawari8`
markers all agree and the row permits `incompatible`; every other native crash fails
the run. If any ADB process exceeds its host deadline, the runner records the partial
result and stops issuing device commands because the transport is no longer trusted;
cleanup is reported as unverified for that run.

## Cross-engine reuse of the corpus boundary

`scripts/run-cross-engine-runtime-audit.ps1` is a focused consumer of this
corpus framework. It accepts the same default roots (`.` and `build/ui-audit`),
plus explicit absolute directory or `.nar` roots, recursively deduplicates nested
directory roots, and binds every discovered file to the unchanged canonical
manifest by SHA-256. Its report distinguishes physical files, unique hashes,
canonical matches, unexpected extras, rejected archives, and every missing
manifest row. An extra archive may be classified for availability diagnostics,
but it is never eligible for engine selection or device execution.

Before selecting an archive, the focused harness applies the same bounded ZIP
inventory and package-root policy. It rejects files larger than 544 MiB before
hashing, validates the bounded EOCD/ZIP64 central directory as a single-disk
archive with at most 10,000 declared records before opening `ZipFile`, and then
enumerates the bounded entries directly. It also enforces bounded normalized
relative paths/components, duplicate and file/directory collision rejection,
declared size/ratio limits, root `install.txt` precedence, and exactly one
otherwise-uniform depth-two wrapper. It bounds descriptors to 64 KiB, requires a
ghost package, reads exact `ghost/master/descript.txt`, and classifies only:

- `satori.dll` as Satori;
- `yaya.dll` as YAYA; and
- `shiori.dll` plus `ghost/master/kawarirc.kis` as Kawari 8.

Candidates are sorted by SHA-256 and then path. A connected run requires the
complete canonical hash set and at least one manifest-bound candidate for every
engine. It records the selected manifest label, path, and digest, copies Satori
under two distinct private names, and invokes the lifecycle and transition tests.
Every temporary push is changed to mode `0644` before its exact `run-as cp`.
Missing optional roots remain visible in reports without invalidating a complete
resolved corpus; zero resolved roots, extras, and missing canonical rows still
fail closed. `-HostOnlySelfTest` exercises the bounded parser and host ownership
oracles without corpus discovery or adb.
This focused runner does not change the 23-row manifest, relax the full corpus
runner, copy local payloads into the repository, or reinterpret an unavailable
row as a pass.
