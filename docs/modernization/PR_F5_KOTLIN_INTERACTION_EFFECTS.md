# PR F5: Kotlin interaction effects (retired)

## Status

This document records an abandoned migration prototype.
`SakuraScriptInteractionInterpreter` and its focused tests were removed in
Phase 3. `SScriptRunner` remains authoritative for ordered Sakura Script
interaction and lifecycle behavior. Do not recreate this extractor or treat it
as a pending production boundary.

## Historical scope

The prototype extracted `\q[...]` choices and
`\![open,inputbox,...]` commands into immutable interaction effects. It was
never wired into the running app.

## Retirement rationale

An interaction-only pass would duplicate parsing while omitting waits, SHIORI
callbacks, queue ordering, and lifecycle scheduling. The Phase 3
prototype-retirement slice deletes that unused path; retained runner dialogue,
interaction, lifecycle, and Compose tests validate the authoritative behavior.
