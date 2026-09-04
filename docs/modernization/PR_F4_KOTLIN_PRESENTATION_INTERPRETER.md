# PR F4: Kotlin presentation interpreter (retired)

## Status

This document records an abandoned migration prototype.
`SakuraScriptPresentationInterpreter` and its focused tests were removed in
Phase 3. `SScriptRunner` remains the authoritative Sakura Script control-flow
implementation and publishes complete `GhostPresentationFrame` values to the
Compose presentation runtime. Do not resume the partial-interpreter sequence
described by the original slice.

## Historical scope

The prototype interpreted presentation-only commands for speaker selection,
text, surfaces, balloons, animations, newline, and end-of-script reset. It was
not a production replacement for the runner and never owned choices, input,
waits, SHIORI callbacks, lifecycle effects, or queue scheduling.

## Retirement rationale

A presentation-only interpreter duplicated parsing without preserving the
runner's full ordered behavior. The Phase 3 prototype-retirement slice removes
that non-authoritative path rather than extending it. Retained runner,
presentation, lifecycle, and screenshot tests cover the production path.
