# PR F8 — Kotlin Ghost domain owner

`Ghost` is now Kotlin. This moves the central non-NDK owner of ghost metadata,
surface preparation, SHIORI request construction, and create-count persistence
into the modern language while preserving its Java API and subclass seams.

`InfoOnlyGhost` and existing Java characterization fakes still subclass it.
The Kotlin class therefore keeps its protected fields and marks the exact
overridable behavior methods open: descriptor loading, create count, identity
and display getters, user name, and SHIORI event dispatch. Compilation against
the existing `RecordingGhost` test fake is the compatibility gate.

The existing Kotlin `GhostMgr` is adjusted to call the now-Kotlin `getX()`
methods explicitly. Display-name arrays retain nullable entries, matching the
Java API's previous ability to return a null descriptor value.

The frozen Ant build continues to receive the original Java source only from
`legacy/src/` through the reusable source overlay. The Gradle production source
set compiles only `Ghost.kt`.
