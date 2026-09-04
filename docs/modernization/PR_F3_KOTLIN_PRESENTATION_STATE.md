# PR F3: Kotlin Sakura Script presentation state (retired)

## Status

This document records an abandoned migration prototype. Phase 3 removed
`SakuraScriptPresentationReducer` and its focused tests because production
presentation already flows from `SScriptRunner` through
`GhostPresentationFrame` into `KotlinGhostPresentationRuntime`. Do not recreate
the reducer or use it as a future migration boundary.

## Historical scope

The prototype modeled active-speaker text, surfaces, balloon visibility,
one-shot animations, and next-script reset as immutable Kotlin transitions. It
was never wired into the production runtime.

## Retirement rationale

Keeping a second presentation reducer would duplicate the active runner and
renderer contract without owning any production behavior. The Phase 3
prototype-retirement slice deleted that unused path and its tests; retained
runner, renderer, lifecycle, and screenshot tests validate the authoritative
path.
