# Pointer Dispatch Diagnostics Design

**Issue:** [#222](https://github.com/xCatG/Nanidroid/issues/222)

## Goal

Make the debug surface report both the pointer event resolved from input and the
runtime's actual dispatch outcome, so it cannot imply that a rejected event was
delivered.

## Scope

The change is limited to the debug pointer record, its rendering, localized
status strings, and regression coverage. It does not alter pointer routing,
event resolution, session gates, or SHIORI dispatch behavior.

## Design

`SScriptRunner` will resolve the diagnostic candidate event and perform the
dispatch inside the same live-session gate. It returns those exact facts to
`Nanidroid`, which stores one immutable debug record. The candidate is nullable;
the outcome is an explicit three-state value:

- `NOT_RESOLVED` when the interaction has no dispatchable candidate event.
- `REJECTED` when a candidate exists but the runner returns `false`.
- `ACCEPTED` when a candidate exists and the runner returns `true`.

The debug surface will render the candidate independently from the localized
outcome. A rejected `OnMouseClick`, for example, remains visible as the resolved
candidate while its status clearly says that runtime dispatch was rejected.

## Data Flow

1. Stage input emits `SurfaceInteractionEffect` through `SurfaceInteractionPort`.
2. `Nanidroid` calls the runner's diagnostic dispatch operation.
3. The runner resolves the candidate and dispatches it under its live-session gate,
   returning the candidate and accepted result together.
4. `Nanidroid` maps those returned facts into the immutable debug record, only in
   debuggable builds.
5. All adaptive debug presentations render the same record, preserving existing
   compact/full-stage behavior.

## Testing

- Add a regression that resolves a pointer event, then causes the runner's
  session gate to reject it; assert the record retains the candidate and reports
  `REJECTED`.
- Cover the three outcomes in the smallest appropriate JVM or instrumentation
  tests, following existing debug-panel test patterns.
- Run the focused test target, the full local unit suite, and a debug build.

## Non-Goals

- Explaining the internal reason for a runner rejection.
- Retrying a rejected pointer dispatch.
- Changing interaction semantics or the panel's adaptive presentation policy.
