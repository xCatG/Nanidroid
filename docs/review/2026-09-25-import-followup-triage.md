# Ghost import follow-up triage (2026-09-25)

This is the final disposition of the reviewed follow-ups, within Task 5's tested scope. The separate failure-message handoff records that issue only; it does not establish further allegations.

## Fixed and reviewed

- **Failure reason:** `52a2bbd` shows the reason in the result dialog. An allowlist passes known archive-limit messages and `Invalid ghost directory`; provider-controlled `IllegalArgumentException` text becomes `Unable to import ghost`. JVM red/green tests and the focused Compose assertion cover this policy. The post-fix API 37 `GhostImportUiTest` run passed **8/8**.
- **Descriptor resource bound:** `0c8f12c` caps descriptor reads at **64 KiB**; `4ea7d85` scopes the archive cap to the selected ghost root, correcting a false rejection of unrelated archive entries. Five focused failures preceded the initial cap implementation and passed afterward. A separate selected-root false-rejection regression failed before `4ea7d85` and passed after it. The final gate passed **227 JVM tests, four skipped**, plus lint and debug/release APK builds. Third-party descriptors larger than 64 KiB are intentionally rejected; this is a compatibility limit. Fixture size measurements from the user-provided NAR corpus were not bundled.
- **Runtime filesystem I/O:** `3cc0b52` moves installed-ghost discovery and bounded readme loading off Main while guarding session and prompt identity on return. Three targeted regressions failed before the fix and passed afterward. The final gate passed **232 JVM tests, four skipped**, lint, and debug/release/androidTest APK builds. Independent Sol reviews of both fixes were clean after the selected-root correction. A focused repeated-picker provider integration run passed **1/1**. `9fe66f4` added a dedicated device test that switched to an imported built-in ghost through real I/O; it passed **1/1**.

## Assessed without a new fix

| Category | Disposition |
| --- | --- |
| Picking restoration and admission | No reproduced defect. Current lifecycle and callback tests cover process recreation, rotation, failed launch, cancellation, and repeated callback admission. Reopen with a concrete stuck-Picking or stopped-Activity copy reproduction and a failing regression. |
| EOCD/ZIP64 pre-check | The end-record and ZIP64 guard deliberately fails closed on ambiguous or unsupported layouts before entry construction. No parser vulnerability or fail-open was demonstrated. Revisit a false rejection only with a safe representative fixture and preserved entry-bound proof. |
| Repeated decompression | Inspection validates/decompresses content and extraction reads it again; the install descriptor receives an additional inspection read. Measure CPU and load time in milestone five before changing this safety tradeoff. Preserve CRC, byte, and publication guarantees. |

## Deferred work

- **Install-event recovery timing — milestone five robustness:** a JNI install-event failure quarantines the session. Recovery in roughly one second depends on resumed runtime/timer activity; it is not an unconditional deadline. Revisit earlier only if a concrete blocked interaction is reproduced.
- **Byte-at-a-time archive pre-check I/O — milestone five performance:** profile or replace the seeks and single-byte reads if they have material cost. Revisit earlier only for a concrete blocked interaction.
- **Switch while Running:** no reachable defect has been demonstrated through the current UI path. This is not a promised fix; reopen with a reproduction.
- **Error-only Active helper consolidation — optional milestone five cleanup:** retain prompt/session behavior and add focused invariants if consolidated.
- **Four earlier cleanup items — individually optional milestone five:** duplicate directory-ID validation needs accepted-ID invariants; duplicate package-root checks need containment invariants; redundant `Completed.pendingDirectoryId` needs prompt-identity invariants; the likely unreachable same-attempt completion guard needs single-attempt publication invariants. Reopen any item earlier only for an in-scope failing regression.

## Evidence boundary

The earlier **78-test** API 37 suite and API 31 device evidence predate these follow-up production changes. Post-fix device evidence is limited to API 37: the focused **8/8** UI run, **1/1** repeated-picker provider run, and **1/1** imported built-in switch run. Retained raw command output is under ignored `.superpowers/sdd/2026-09-24-ghost-import/followup-2026-09-25/`: `offline-gate.log`, `ghost-import-ui-api37.log`, `repeated-picker-api37.log`, and `import-switch-api37.log`. The offline gate and all three focused connected wrapper commands exited zero. The historical Gradle wrapper lock cleared for those focused runs; the earlier blocked-wrapper log remains historical evidence. One intermediate `StageViewModel` timeout did not recur in the final suite. These results extend scoped Task 5 acceptance evidence; they do not establish full NAR or native compatibility.
