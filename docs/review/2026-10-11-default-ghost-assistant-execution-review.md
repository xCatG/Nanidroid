# Default ghost assistant execution review, 2026-10-11

Plan: [default ghost assistant](../superpowers/plans/2026-10-11-default-ghost-assistant.md).
Evidence: [focused acceptance](../testing/2026-10-11-default-ghost-assistant.md).

Author approved execution in the plan and reaffirmed it in chat. Implementation
ran sequentially in `C:/Users/yenchi/.codex/worktrees/cc61/Nanidroid` on
`codex/default-ghost-assistant`, with fresh GPT-6.1-Sol implementers and independent
GPT-6-Sol reviewers using isolated context. Workers loaded the root AGENTS.md.

## Review gates

| Gate | Implementation | Independent verdict |
| --- | --- | --- |
| Dialogue/content | `2d37efb6` | Spec compliant; task quality approved; no actionable findings |
| Runtime bridge | `46ece717` | Spec compliant; task quality approved; no actionable findings |
| UI and instrumentation | `6c9a8fe6` | Spec compliant; task quality approved; no actionable findings |
| Whole branch | Execution base `eaff9bc6` through source `a79ffab5`, plus acceptance documentation | Spec compliant; quality approved; no actionable P0–P3 findings |

Final review checked default-session capability, canonical path/directory
verification, typed choice admission, replacement/pause invalidation, single-use
claiming, modal/session lifecycle, locale content, debug gating and evidence
boundaries. It did not rerun unchanged suites. Full task reports and final review
are retained locally under the ignored acceptance artifact directory.

## Implementation decisions

1. Register the nine new instrumentation methods in the existing strict device
   suite manifest. Its discovery rejects unregistered methods, so registration is
   necessary CI integration. If a classification is wrong, correct the suite
   selection. Current inventory: 126 self-contained / 172 total methods.
2. Use an optional nullable `bundledEngineFactory`, rather than defaulting it to
   the ordinary factory. Supplying the factory explicitly opts into assistant
   behavior without comparing function identities, while preserving ordinary
   onboarding for existing callers. Parameter placement preserves trailing
   lambdas. If this choice is wrong, callers require constructor adaptation.

These decisions amended the plan without expanding the approved product scope.

## Verification and limits

The full unit execution recorded 416 cases, zero failures/errors and four existing
skips; the combined final local gate reused that unchanged unit output and passed
lint/debug/test/unsigned-release assembly. Lint has zero errors, 26 existing
warnings and one hint. Nine focused API 31 x86_64 UI tests passed without skips.
Normal application checks separately verified menus, app dialogs, orientation
recreation, switching back to the default and preservation of all eight installed
bundled files. ZIP and PNG hashes match the originals; pinned native/toolchain
paths are unchanged. The lead inspected help/tools/debug portrait screenshots.

Non-debug behavior has modeled-policy and static APK metadata evidence; actual
release-device UI was not executed. Broader API/ABI/private corpus, process-death
and Japanese device coverage are not claimed. No push, PR, merge, publication,
signing-key operation or user-device installation occurred. The branch remains
local for the author.
