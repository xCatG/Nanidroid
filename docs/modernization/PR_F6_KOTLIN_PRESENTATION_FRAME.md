# PR F6 — Kotlin presentation frame and reusable Ant overlay

`GhostPresentationFrame` and its speaker facts are now Kotlin data models. The
legacy Java renderer retains direct field access through `@JvmField`, while the
Compose host consumes the same immutable object. Java callers may still supply
null surface and animation ids; only the Compose reducer normalizes them to an
empty identifier.

The frozen Ant lane remains a Java-only reference build. Its disposable Docker
copy now overlays every source below `legacy/src/` onto `src/` after checkout
copy. Gradle never compiles `legacy/src`; it compiles the Kotlin production
models. This replaces the one-off GhostMgr overlay and provides the repeatable
compatibility mechanism for subsequent Kotlin migrations.

The focused frame and `SScriptRunner` presentation tests verify the legacy
balloon visibility policy and Java/Kotlin field interoperability. Static
contracts cover the Kotlin models and the generic overlay; the full CI validates
the Ant reference APK plus API 37 Gradle APK/AAB.
