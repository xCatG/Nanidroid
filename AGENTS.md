# Nanidroid recreation integration

The accepted implementation is the standalone Kotlin/Compose recreation described
by README.md, the approved baseline/design, M5 acceptance with exceptions and
docs/superpowers/plans/2026-10-02-replacement-integration.md. User instructions govern.

## Workspace and separation

- Approved isolated-workspace exception: integration and verification may operate
  in `C:/work/src/nanidroid-recreation/app/build/replacement-integration/candidate`.
  The standalone source workspace remains `C:/work/src/nanidroid-recreation`.
  Workers report their workspace and loaded AGENTS.md paths first.
- Do not read, copy or use superseded application source, tests, build scripts,
  architecture documents or implementation history. Target Git parent/history is
  retained for rollback, not as implementation input. Integration inventory uses
  path/object metadata; only the approved pinned native tree and license notice
  are permitted retained contents. Do not search adjacent repositories.
- Shared memory is background and must not expand scope or supply legacy code.
- Lead coordinates/reviews; tasks execute sequentially. Fresh author-approved
  GPT-6.1-Sol implementers and independent GPT-6-Sol reviewers use `fork_turns: none`.
  Reuse responsible workers for fixes; preserve others' edits and assigned ownership.

## Implementation and gates

- One app module, Kotlin, Compose, ViewModel, lifecycle-aware StateFlow and manual
  constructor injection. Production Kotlin lives under
  `app/src/main/kotlin/com/cattailsw/nanidroid`; tests under `app/src/test/java`
  and `app/src/androidTest/java`.
- Namespace/application ID `com.cattailsw.nanidroid`; minSdk 31; compile/target 37;
  arm64-v8a and x86_64. Use the pinned wrapper/catalog/NDK/CMake configuration.
- Native contents under `app/src/main/jni` remain pinned by inputs.json and the
  297-file native manifest. No additional native or product changes are authorized
  by the replacement integration plan.
- Test observable behavior with JUnit 4, coroutines-test and focused Compose
  instrumentation. Follow plan gates and report actual evidence; accepted risks
  and historical fixture gaps do not become passing results.
- Preserve fresh-install/no-migration policy and old external files. No external
  balloons, Markdown, broad parity matrix, DI/navigation rewrite or extra features.
- Keep secrets, local.properties, private NARs, build outputs/APKs and recovery
  bundles out of Git. Commit concise focused changes on `codex/` branches.
- No push, public PR, merge, publication, signing-key handling or user-device
  installation is authorized by the local replacement plan.
