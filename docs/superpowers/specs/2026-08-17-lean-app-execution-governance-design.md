# Lean App Execution Governance Design

## Goal

Execute Nanidroid's lean-app modernization as a sequence of reviewable,
releasable changes without allowing work from different architectural phases
to overlap or drift apart.

The program uses five canonical GitHub issues as phase umbrellas. Each phase
may contain multiple focused pull requests. The coordinator owns sequencing,
integration, review, merge decisions, and phase transitions; fresh specialist
agents perform bounded discovery, implementation, and independent review.

## Canonical Phases

The phases execute in this order:

1. `#382` — delete non-core product surface and reconcile shipped legacy state;
2. `#384` — replace archive ingress with foreground local NAR installation;
3. `#385` — collapse native and session ownership into `GhostRuntime`;
4. `#386` — move UI ownership to a UDF-lite ViewModel and thin Activity;
5. `#374` — finish build, resource, and compatibility verification.

Issue `#383`, the rolling ghost corpus, remains an independent evidence stream.
Issue `#261` remains unrelated to the architecture program.

## Phase Lock

Exactly one canonical phase is active at a time.

- Every implementation pull request links to the active phase.
- No branch, worktree, implementation task, or preparatory refactor for a later
  phase begins before the current phase passes its exit gate.
- Every new pull request starts from the latest verified default branch after
  the preceding pull request has merged.
- A cross-cutting discovery becomes a blocker or another focused pull request
  in the active phase. It does not authorize jumping ahead.
- Temporary dual implementations may exist inside a pull request only. A
  compatibility bridge may survive merge only when the active phase explicitly
  specifies its purpose, verification, and expiration condition.
- Each merge must leave the application buildable, testable, and internally
  consistent. Later work may not be required merely to restore the current
  phase's behavioral floor.

The coordinator activates the next phase only after producing a phase-boundary
report and confirming that the current issue's checklist, compatibility
decisions, regression gates, and exit review are complete.

## Canonical Issue Ledger

The active canonical issue is the source of truth for execution status. It
maintains:

- phase status and baseline commit;
- approved product and compatibility decisions;
- the selected direct-upgrade strategy where applicable;
- ordered pull-request slices;
- merged and current pull requests;
- remaining work;
- deferred work and explicit non-goals;
- latest regression evidence;
- active blockers and stop conditions;
- phase-exit review and final default-branch commit.

Focused pull requests do not require new GitHub issues. Their scope and status
are tracked as checklist entries in the canonical issue, preventing the issue
list from expanding back into implementation-shaped fragments.

## Coordinator Responsibilities

The primary coordinator owns the program rather than delegating integration to
one long-lived implementation team. The coordinator:

- defines the behavioral contract and file ownership for each pull request;
- approves the slice plan before implementation;
- chooses which work may run in parallel inside the active phase;
- prevents agents from editing outside their assigned ownership;
- reconciles conflicting expert recommendations;
- reviews every patch and verification result;
- ensures implementers do not approve their own work;
- opens and maintains draft pull requests;
- inspects aggregate GitHub reviews, CI results, and every unresolved inline
  review thread;
- dispatches fixes and requires re-review when a patch changes materially;
- merges focused pull requests after all technical gates pass;
- verifies the default branch after merge before dispatching the next slice;
- reports at phase boundaries before activating the following phase.

The user delegates pull-request merge authority to the coordinator. The
coordinator must not merge merely because CI is green: the local review,
multi-agent review, GitHub review, and issue-specific acceptance gates must all
pass. A phase-boundary report remains user-visible even when no additional
merge approval is required.

## Agent Roles

Fresh agents are preferred for each bounded slice so reviewers approach the
change without inheriting implementation assumptions.

### Discovery specialist

Performs read-only inspection of callers, tests, lifecycle and persistence
contracts, native or Android platform boundaries, and deletion reachability.
It identifies exact files, retained invariants, migration hazards, and the
narrowest independently testable deliverable.

### Implementation worker

Owns an explicit file set and one approved deliverable. It writes focused tests
first where practical, makes the smallest implementation satisfying the slice,
runs the assigned verification, and reports any necessary scope change rather
than silently broadening the patch.

### Area reviewer

Reviews the completed patch for the affected domain, such as Android lifecycle,
Compose state, installer security, WorkManager migration, JNI/native ownership,
or build/release behavior. It is read-only and independent from the implementer.

### Adversarial reviewer

Looks specifically for preserved-but-unproven behavior, unsafe deletion,
stale-state acceptance, lifecycle replay, process and upgrade hazards, native
ownership violations, security regression, and tests that mirror the
implementation instead of proving the contract.

### Coordinator review

The coordinator performs a separate full diff review after specialist findings
arrive. It classifies findings, resolves disagreements against the canonical
issue, requires fixes, reruns or validates relevant gates, and alone approves
publication and merge.

## Parallelism Rules

The five phases never implement in parallel. Within one phase, at most two
implementation workers may run concurrently, and only when:

- their file ownership is disjoint;
- neither consumes an API being created or changed by the other;
- their tests do not depend on unmerged behavior from the other slice;
- either patch can be reviewed, merged, reverted, or rejected independently;
- the coordinator has documented the merge order.

Read-only discovery and review agents may run in parallel because they do not
mutate shared state. Native/session ownership, manifest/dependency removal,
runtime authority handoff, NARFS removal, and WorkManager compatibility removal
are atomic slices and may not be split between concurrent implementers.

## Pull-Request Lifecycle

Every focused pull request follows the same loop:

1. Record the slice, contract, dependencies, non-goals, files, tests, and stop
   conditions in the canonical issue.
2. Create an isolated `codex/` branch and worktree from the latest verified
   default branch.
3. Dispatch read-only discovery when the boundary is not already proven.
4. Have the coordinator approve the detailed slice plan.
5. Dispatch the implementation worker with exclusive file ownership.
6. Run the narrow tests that prove the new behavior and the retained behavior
   nearest the changed boundary.
7. Run an independent area review and adversarial review.
8. Have the coordinator review the complete diff and all review findings.
9. Fix accepted findings and repeat affected verification and review.
10. Open a draft pull request linked to the canonical issue.
11. Confirm local review and verification evidence in the pull-request body.
12. Mark the pull request ready only after local gates pass.
13. Wait for GitHub CI and automatic review signals.
14. Inspect aggregate reviews and every unresolved inline review thread.
15. Dispatch review fixes, rerun affected gates, and request re-review.
16. Merge only after all required checks, human/agent reviews, and acceptance
    criteria pass with no unresolved actionable thread.
17. Verify the default branch, record the merged commit and results in the
    canonical issue, then plan the next slice.

Material GitHub review fixes receive the same independent area or adversarial
re-review as the original patch. A reviewer approval that predates a material
change is not treated as approval of the new code.

## Verification Tiers

Verification is proportional to the touched boundary.

### Per pull request

- focused unit or instrumentation tests for the slice;
- `testDebugUnitTest`, lint, debug assembly, and repository hygiene when they
  are available and relevant;
- screenshot validation for visible Compose changes;
- targeted connected tests for changed Android, provider, lifecycle, worker,
  or JNI adapters;
- both native ABIs for CMake, JNI, or packaging changes;
- explicit merged-manifest or dependency checks for removal slices.

### Per phase

- every phase acceptance criterion and stop condition;
- the retained ghost-loading, SHIORI, dialogue, stage, installer, and upgrade
  tests affected by that phase;
- multi-agent exit review of the accumulated default-branch result;
- coordinator diff/risk review from the phase baseline to its final commit;
- confirmation that no next-phase assumption was implemented early.

### Release candidate

Phase `#374` owns the full native, both-ABI, optimized-release, lifecycle,
installed-upgrade, malicious-archive, screenshot, pinned 23-NAR, and rolling
corpus gates. These gates supplement rather than replace focused proof in the
earlier phases.

## Phase-One Entry Gate

The first implementation phase begins with a read-only shipped-state audit,
not a deletion patch. The audit must determine which supported path applies:

1. no released build could persist the affected WorkManager/update state;
2. an intermediate cleanup release is required; or
3. one temporary compatibility release must retain `work-runtime` and exact
   cleanup/tombstone support.

No pull request may delete WorkManager integration, Worker class names, queue
decoders, DownloadManager ownership, URI-grant ownership, or update recovery
until the audit proves the retirement path and fixtures cover every supported
historical topology.

## Failure and Escalation

An agent stops and returns control to the coordinator when scope, ownership,
compatibility, or evidence differs from the approved slice. The coordinator
does not waive a stop condition to maintain momentum.

A pull request remains unmerged when:

- reviewers disagree on a safety or compatibility invariant;
- a GitHub review thread remains actionable;
- required CI or local evidence is missing;
- the patch needs work from a future phase to become correct;
- rollback would require an undocumented data migration;
- the diff has become too broad to review or attribute confidently.

The coordinator may split or abandon a pull request, revise the active issue,
or request a user decision when resolving the blocker changes an approved
product contract. Difficulty or delay alone is not permission to broaden scope.

## Phase Transition

At the end of each phase, the coordinator publishes a concise report covering:

- merged pull requests and final commit;
- production and test surface removed or added;
- compatibility decisions and temporary bridges still active;
- verification completed and any explicitly deferred release-only gates;
- remaining known risks;
- confirmation that the canonical issue may close;
- the proposed entry slice for the next phase.

Only after that report and a clean default-branch verification does the
coordinator activate the next canonical issue and begin its just-in-time design
and implementation plan.
