# Default ghost assistant plan: Astra review

Reviewed plan: `docs/superpowers/plans/2026-10-11-default-ghost-assistant.md`.

Reviewer: fresh `gpt-6-astra` subagent, high reasoning, `fork_turns: none`, read-only. Review requested by the author on 2026-10-11. Workspace: `C:/Users/yenchi/.codex/worktrees/cc61/Nanidroid`; reviewer loaded the root `AGENTS.md` and inspected relevant current recreation code. No legacy/history input or product edits.

## Findings and resolution

1. **P2: In-flight app actions could survive pause/resume.** The draft's session/generation checks and pending-request clearing did not invalidate a request started before pause whose reply arrived after resume. Current `setResumed` does not advance replacement generation. **Resolved in plan:** Task 2 specifies a dedicated action-delivery epoch captured before requesting and invalidated on pause, checked at both reply admission and claim. Adds tests for replies during pause and after pause/resume, preserving ordinary dialogue.
2. **P2: First-boot onboarding had two owners.** The draft proposed updating runtime onboarding and adding an assistant `OnFirstBoot` welcome. Current activation queues the English runtime welcome before the SHIORI response, yielding duplicate/inconsistently localized introductions. **Resolved in plan:** Task 2 gives the trusted assistant's localized `OnFirstBoot` sole ownership, consumes the existing onboarding marker without an extra script, preserves activation state, and adds runtime English/fallback/existing-install tests.

## Assessment

Astra's initial assessment: ready after these two fixes. APK-owned content, unchanged installed files/artwork, a dedicated bundled factory, path/directory trust checks, typed actions, and existing dialogs fit the approved intent. Bounds-only Debug tools is an appropriate minimal first pass; event/script debugging is outside this plan. Validation distinguishes debug-policy tests, release assembly, and instrumentation availability.

Lead incorporated both findings. Astra then confirmed that the pause-invalidated epochs, admission/claim tests, single localized welcome, marker preservation, and fresh/existing-install coverage resolve both findings. Final assessment: ready for branch preparation and handoff. This record concerns plan review only; it is not implementation or test evidence.
