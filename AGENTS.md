# Nanidroid development

The replacement phase is complete. The current checkout is the authoritative
implementation for ongoing development. README.md describes current behavior;
the approved baseline/design, M5 acceptance, replacement plan and integration
manifests record historical decisions and evidence, not permanent scope or
authorization gates. User instructions govern.

## Workspace and collaboration

- Work in the current checkout or an appropriate isolated worktree. Workers report
  their workspace and loaded AGENTS.md paths first.
- Use current source and contracts as implementation input. Do not copy superseded
  application code or search adjacent repositories without user authorization.
- Shared memory is background and must not expand the requested scope.
- Preserve others' edits, untracked files and assigned ownership when collaborating.

## Implementation and verification

- One app module, Kotlin, Compose, ViewModel, lifecycle-aware StateFlow and manual
  constructor injection. Production Kotlin lives under
  `app/src/main/kotlin/com/cattailsw/nanidroid`; tests under `app/src/test/java`
  and `app/src/androidTest/java`. These are current conventions; deliberate changes
  may be made when the requested work calls for them.
- Namespace/application ID `com.cattailsw.nanidroid`; minSdk 31; compile/target 37;
  arm64-v8a and x86_64 are current defaults. Use the committed wrapper/catalog/NDK/
  CMake configuration unless deliberately changing it as part of the requested work.
- User-requested features, native changes and build changes are permitted. Historical
  exclusions and frozen native manifests do not prohibit ongoing development.
- Review the actual requested change against current contracts and relevant risks;
  do not impose the replacement phase's exclusions or acceptance gates on every change.
- Test observable behavior with JUnit 4, coroutines-test and focused Compose
  instrumentation. Run checks appropriate to the change and report actual evidence;
  accepted risks and historical fixture gaps do not become passing results.
- Preserve existing data and old external files. Keep the current fresh-install/
  no-automatic-legacy-migration behavior unless a migration is specifically requested.
- Keep secrets, local.properties, private NARs, build outputs/APKs and recovery
  bundles out of Git. Commit concise focused changes on `codex/` branches.
- Follow user authorization for Git and external actions; push and PR creation are
  permitted when requested. Publication, releases, signing-key handling and
  user-device installation require explicit authorization.
