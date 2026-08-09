# UI-audit untracked build-input provenance (#255)

`Get-TrackedRepositoryState` currently calls `git status --porcelain=v1
--untracked-files=no`, so capture provenance records a clean tracked HEAD even
when an untracked Android build input can change the generated APK.

The replacement gate will inspect the full porcelain status before and after
reading HEAD. It will fail closed only for untracked, non-ignored paths that
can feed this application build: root Gradle files and wrappers, `gradle/`,
`src/`, `jni/`, and `libs/`. Ignored generated reports/build output remain
allowed. NAR corpora supplied outside the repository are not Git worktree
paths and remain allowed.

The dry-run regression covers a protected untracked source/Gradle path, an
ignored generated report, and an external corpus path. Capture and completion
use the same repository-state function, so the rule cannot diverge across the
provenance lifecycle.
