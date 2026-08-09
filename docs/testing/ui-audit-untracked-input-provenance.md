# UI-audit untracked build-input provenance (#255)

`Get-TrackedRepositoryState` formerly called `git status --porcelain=v1
--untracked-files=no`, so capture provenance could record a clean tracked HEAD
even when an untracked Android build input changed the generated APK.

The replacement gate inspects the full NUL-delimited porcelain status before
and after reading HEAD. It fails closed for untracked or ignored paths that can
feed this application build: root Gradle files and wrappers, `gradle/`,
`buildSrc/`, `src/`, `jni/`, and `libs/`. Ignored paths are allowed only below
the generated `build/reports/` root. NAR corpora supplied outside the
repository are not Git worktree paths and remain allowed.

The dry-run regression covers protected untracked source, Gradle, and
`buildSrc` paths; a rejected ignored Android asset; an ignored generated
report; and an external corpus path. Capture and completion use the same
repository-state function, so the rule cannot diverge across the provenance
lifecycle.
