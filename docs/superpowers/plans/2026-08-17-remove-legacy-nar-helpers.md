# Remove Legacy Shared NAR Helpers

## Goal

Delete the obsolete `NarUtil` compatibility layer without changing the active
transactional installer, archive ingress, descriptor decoding, or bundled ghost
bootstrap behavior. This is a focused deletion slice under #382; #384 remains
blocked.

## Reviewed boundary

Three independent read-only reviews inspected call sites, Android storage and
intent security, and behavioral-test coverage on `bd0a56a8`.

Delete:

- raw shared `/sdcard/nar` creation, listing, temporary extraction, and reads;
- the unreachable unbounded ZIP extractor and its forced-ID characterization;
- unused MD5 and HTML text helpers;
- the source-text descriptor contract that pins `NarUtil.UTF8_BOM`.

Retain:

- `GhostMgr -> NarTransactionalInstaller` for bundled and selected archives;
- descriptor UTF-8 BOM, declared charset, and default Shift_JIS behavior;
- direct behavior tests for granted `content:` archive ingress;
- direct behavior tests for failed/discarded private staging cleanup;
- all NARFS, retained-overlay, queue, durable, update, JNI, manifest, and
  dependency code for their later atomic slices;
- the historical shipped-state ledger entry classifying `/sdcard/nar` as
  `FOREIGN_PRESERVE`. Never delete or migrate that shared content.

## Implementation

1. Move the three live tests from `NarArchiveCharacterizationTest` into focused
   `ArchiveIntentAdapterTest` and `NarLocalArchiveStagerTest` suites. Expand the
   adapter matrix to positively cover both supported MIME types plus
   case-insensitive content/MIME matching, and to reject wrong actions,
   `file:`/HTTP(S), unsupported MIME, and missing read grants.
2. Remove the obsolete forced-ID `NarUtil.readNarArchive` characterization and
   its archive fixture/helper code, then delete the empty characterization file.
3. Give `DescReader` its own private UTF-8 BOM constant and keep its executable
   characterization suite authoritative.
4. Replace the bundled asset's ignored-MD5 copy with a unique temporary file in
   internal `cacheDir`, nested `use` scopes, `InputStream.copyTo`, and `finally`
   deletion. The output must close before `GhostMgr.installFirstGhost` runs.
5. Remove the startup shared-root mkdir call and delete `NarUtil.kt`.
6. Delete `tools/test_kotlin_descriptor_reader_contract.py`; do not replace it
   with another source-text assertion.

## Verification

Run on the exact committed head:

- focused JVM tests for `ArchiveIntentAdapterTest`,
  `NarLocalArchiveStagerTest`, `DescReaderCharacterizationTest`,
  `NarTransactionalInstallerTest`, and `NanidroidGhostStartupTest`;
- `testDebugUnitTest compileDebugAndroidTestKotlin
  compileDebugScreenshotTestKotlin assembleDebug`;
- `lint` and screenshot validation, classifying only exact baseline failures;
- `python tools/verify_phase1_shipped_state_audit.py`;
- `git diff --check` and hygiene searches proving no live `NarUtil`,
  `Environment.getExternalStorageDirectory`, or `/mnt/sdcard/nar` source call
  site remains. Negative security assertions and the authoritative historical
  audit verifier/ledger are expected exceptions.

If an API 31+ device is available, perform a clean-install first-launch smoke
test proving the bundled ghost installs and loads and no `nanidroid*.nar`
temporary file remains. If no device is available, record that residual gate
for CI/device verification rather than claiming it ran.

## Stop conditions

Stop this slice if any production/JNI/reflection caller of `NarUtil` emerges;
the bundled asset no longer reaches the transactional installer; descriptor
charset behavior regresses; archive ingress becomes broader; staging cleanup
regresses; shared `/sdcard/nar` content is touched; or the diff reaches #384's
installer/NARFS/durable boundary.
