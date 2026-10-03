# Nanidroid Replacement Integration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Status:** Approved for execution by the author on 2026-10-03. Use GPT-6.1-Sol for implementation; retain the existing independent review process. M5 is accepted with exceptions; this plan does not reopen it. No merge or release is authorized.

**Goal:** Prepare one reproducible, reviewable replacement candidate for xCatG/Nanidroid, with a concrete integration diff and an honest upgrade/release checklist.

**Architecture:** Keep the accepted standalone single-module implementation intact. Review it in bounded responsibility groups, then transfer an explicit set of files into an isolated integration checkout. Do not reconstruct milestone history as artificial intermediate applications.

**Tech Stack:** Kotlin, Compose, ViewModel/StateFlow, Gradle wrapper, retained native SHIORI/JNI.

**Spec:** ../specs/2026-09-22-nanidroid-baseline-spec.md and ../specs/2026-09-22-blind-recreation-design.md. Acceptance authority: ../../testing/2026-10-01-m5-focused-acceptance-packet.md, author acceptance dated 2026-10-02.

## Author decision — 2026-10-03

The author reports no meaningful existing installation base. Keep the existing application ID and the approved fresh-install/no-migration policy. Legacy ghost discovery, save/settings conversion and old-version migration testing are not replacement prerequisites. Leave old external files untouched; document that they are not imported and that reinstalling a NAR does not recover its saved state. This is a product decision, not a measured installed-user count. Versioning/signing requirements remain separate release checks.


**Signing and distribution:** Key recovery belongs to the author and does not block local integration. Updating an existing Play listing under com.cattailsw.nanidroid requires the original app-signing identity: either recover the signing key, or confirm existing Play App Signing enrollment and recover/reset the upload key through Play. An upload-key reset does not recover a lost self-managed app-signing key. If neither route is available, decide before public release whether to use another distribution route or a new application ID for Play; do not rename the application automatically. Play listing/enrollment status remains unverified. See [Google Play signing guidance](https://support.google.com/googleplay/android-developer/answer/9842756) and [Android signing guidance](https://developer.android.com/studio/publish/app-signing).

A build signed with an unrelated new key cannot update an installed copy with the old signing identity. README/release instructions must explain that a fresh installation may require uninstalling the old copy, and that uninstalling can remove its app-owned data. The app leaving old files untouched is not an uninstall-preservation guarantee. Do not instruct users to uninstall without stating this consequence. Alternative stores and direct distribution still need their own eligibility and signing checks.

## Global constraints

- Namespace/application ID com.cattailsw.nanidroid; minSdk 31; compile/target 37; arm64-v8a + x86_64.
- One app module, manual constructor injection, application-owned runtime and one native owner.
- Preserve original-baseline scope, approved built-in balloons and logical artwork scaling.
- No automatic migration or deletion of old external-file ghost trees. Reimporting a NAR does not restore saved ghost state.
- No reference application source, tests, build-script contents, architecture documents or implementation history enter product work. Integration inventory may use path names and Git object IDs; native comparison uses the approved manifest.
- Native source remains pinned. A differing target native tree requires explicit reconciliation, not silent replacement.
- LOBO Pixel setting-cycle gap and intermittent Compose wrong-thread/keyboard failures remain accepted risks, not passing tests or resolved bugs.
- No external balloons, Markdown, new compatibility scan, architecture rewrite, shrinker activation or additional feature work.
- Preserve user devices and local changes. Validation uses disposable explicit-serial emulators.
- Fresh GPT-6.1-Sol implementers and independent GPT-6-Sol reviewers; reuse the responsible implementer for corrections. Lead coordinates. This author-approved implementation model supersedes the earlier GPT-6-Sol implementation requirement; other workflow constraints remain unchanged.
- No push, public PR, merge, signing-key handling, publication or real-user upgrade installation under this draft. Prepare local review artifacts first.

## Review focus

1. A file transfer silently omits assets, notices or native files: Task 3 checks transferred file identities.
2. Historical evidence is described as current: Tasks 1 and 4 distinguish exact source/APK/device provenance.
3. Fresh-install policy is mistaken for upgrade compatibility: Tasks 3 and 4 document no migration and keep existing external files untouched.
4. Target repository changes get overwritten: Task 3 pins target HEAD and requires regeneration if it moves.
5. A broad review becomes an indefinite rewrite: Task 2 requires concrete behavioral defects and bounded corrections.

## Budget and reporting

Estimate 6–10 active hours, provisional: inventory 1–2, review 2–4, integration 1–2, verification/report 2.
Checkpoint after Task 2 and at 10 active hours; reassess before 20. Exclude idle monitoring, but report unattended build/run elapsed time separately.
Pause for scope review after three distinct product fixes or +200 net test/tool lines. No new harness by default. These are checkpoints, not automatic rejection of the candidate.
Report production/test/tool line counts, largest production files, changed lines, review findings and actual active time. Native/data/generated files are separate.
A reproduced high-impact data-loss or crash defect is addressed explicitly; it cannot be hidden under M5 acceptance. Accepted intermittent risks alone do not trigger repeated sampling.

## Task 1: Freeze the accepted source and create the review map

**Files:** Create docs/review/2026-10-02-replacement-review-map.md. Read inputs.json, accepted evidence and current tracked tree. Do not edit historical evidence to remove failures.
**Consumes:** M5 acceptance commit and working-tree status.
**Produces:** Source commit, eligible file manifest, baseline-to-evidence map and review assignments.

- [ ] Wait for the focused M5 acceptance commit; record its full ID and all unrelated local changes. Use committed files only for the candidate.
- [ ] Inventory tracked production, tests, tools, documentation, native/data and build inputs. Flag required untracked inputs individually; do not use git add -A.
- [ ] Verify inputs.json bundled archive hash and all 297 native manifest entries against the recreation tree. Record any documented transfer transformations; reject unexplained changes.
- [ ] Map baseline sections to existing implementation/evidence with accepted limitations. Link prior reviews to the commits they cover; do not rerun M5.
- [ ] Record missing distribution prerequisites separately. Current inspection: versionCode=1, versionName=1.0, shrinking disabled, no recreation README.md or .github directory, and no configured remote reported.
- [ ] Commit the review map only. Reviewer confirms reproducible source identity and no private corpus, local paths/secrets, APKs or ignored raw evidence accidentally selected.

## Task 2: Bounded replacement review and necessary corrections

**Files:** Create docs/review/2026-10-02-replacement-review.md. Product edits, if any, stay in the finding's named files and focused existing test class.
**Consumes:** Task 1 source and review map.
**Produces:** Findings with severity, reproducible trigger, disposition and reviewed correction commits.

- [ ] Assign fresh reviewers sequential bounded groups:
  - engine/, shiori/, runtime/GhostRuntime.kt, application ownership and lifecycle;
  - install/, ghost/ repositories, descriptors and filesystem boundaries;
  - runtime/ playback/animation, ghost/ rendering, ui/ interaction/geometry;
  - Gradle/manifest, bundled assets/notices and test/tool reproducibility.
- [ ] Reuse earlier accepted review evidence for unchanged code. Inspect interfaces and subsequent changes; avoid treating the whole implementation as never reviewed.
- [ ] Require concrete defects or missed approved requirements. Cosmetic preferences, class length alone and optional features go to backlog.
- [ ] Return each agreed defect to its implementer: reproduce with a focused regression test, make the smallest fix, run affected tests, then independent review. No fix solely to silence a test whose failure is unexplained.
- [ ] Consolidate results, code-size figures and remaining risks. At the checkpoint, proceed only if no unresolved candidate-blocking finding remains; otherwise state the smallest corrective task.

## Task 3: Prepare an isolated integration candidate

**Files:** Create docs/review/2026-10-02-integration-manifest.md and README.md in the recreation. Integration checkout receives only the explicit manifest.
**Consumes:** Reviewed recreation commit; current target origin/master commit and path/object inventory.
**Produces:** Local codex/replacement-integration candidate, transfer/deletion manifest, reviewable diff and preservation checks.

- [ ] Obtain target origin/master identity through Git metadata without reading old application contents. Verify that it is current at execution time; do not assume the old reference worktree is current.
- [ ] Prepare the exact add/replace/delete/preserve path manifest. Preserve licenses/notices and unrelated repository administration; no blanket preservation of executable configuration or architecture guidance. The lead owns these dispositions, and workers receive only the resulting replacement requirements:
  - Replace root AGENTS.md with recreation-layout instructions; retain applicable neutral repository policies.
  - Replace obsolete README variants with one README.md; retain attribution. Do not leave competing active instructions.
  - Inventory every target CI workflow. Replace obsolete Android build/test jobs from the new build contract, including app/build/reports/ and correct artifact locations. Preserve unrelated automation only with an explicit disposition; verify required check names before changing them.
  - Remove superseded v2 architecture/design docs from the active tree, preserving them in target Git history and the rollback reference. Enumerate paths; do not rely on the review's reported document count. Keep the source-derived capability report outside blind-worker inputs, including if it later becomes tracked.
  - Classify every tracked path under tools/, scripts/ and .devcontainer/ as replace, remove or preserve. Transfer recreation tools explicitly. Do not run or retain old tooling merely because it exists, or copy its implementation.
  - When classification needs prohibited content, report the exact unresolved path to the lead for disposition; workers do not relax separation themselves. Do not touch untracked files in the original target checkout.
- [ ] Before replacement, create an annotated local rollback tag on the exact verified target HEAD being replaced (suggested name pre-recreation-2026-10-03). Verify its target; never move an existing tag silently. Do not label afc6a3a3 as v2-final without proving it is the intended target: it is the native baseline. Record the tag/object ID for later authorized publication. Source rollback does not guarantee installed-data rollback.
- [ ] Preserve recreation history without merging unrelated histories: make a Git bundle containing the reviewed recreation branch/history, run git bundle verify, test recovery into a temporary repository and compare the recovered tip ID. Record bundle SHA-256 and source commit in the manifest and proposed PR. Retain the standalone repository and bundle; exclude the bundle from the source diff. Before eventual repository retirement, require a verified copy on durable storage or a published archival ref under separate authorization.
- [ ] Compare target native object identities to the pinned transfer. If target native code has diverged, stop that part for author disposition; do not downgrade it or expand the native exception.
- [ ] Use an isolated target checkout. Preserve its history; transfer recreation files from the reviewed commit, never a broad copy of the working directory. Remove only explicitly listed superseded files inside that checkout.
- [ ] Write a concise README describing supported subset, build/test commands, accepted limits and data-location policy. Preserve attribution and license notices.
- [ ] Verify transferred file hashes, native/data identity, absence of accidentally retained duplicate source roots, and absence of secrets/private NARs/build outputs. Review git diff --check and the complete name/status manifest.
- [ ] Record the local candidate commit and target base. If origin/master moves before submission, reconcile in isolation and rerun checks affected by the change.
- [ ] Prepare one PR description with four review sections matching Task 2, not a fake stack of incomplete apps. Do not open or push the PR yet.

## Task 4: Verify candidate and prepare the author decision

**Files:** Create docs/testing/2026-10-02-replacement-candidate.md. Modify integration build/CI configuration only if required to build the accepted project; no unrelated modernization.
**Consumes:** Exact Task 3 candidate.
**Produces:** Candidate verification report, integration decision and separate distribution checklist.

- [ ] On a clean checkout, use the pinned wrapper/SDK/NDK to run testDebugUnitTest, lint, assembleDebug, assembleDebugAndroidTest and assembleRelease. Record command results, source and APK hashes; unsigned release build is not release readiness.
- [ ] Verify both packaged ABIs and native/data identity. Run one focused explicit-serial disposable-emulator flow: bundled startup, picker import, real supported ghost interaction, rotation, switch away/back and orderly close. Reuse existing tools and fixtures.
- [ ] Any integration product/config fix invalidates affected evidence: rerun only those checks. Preserve failed attempts, distinguish fixture absence from a pass, and carry M5 historical evidence with its original provenance.
- [ ] Verify recreation-debug to candidate-debug preservation on a disposable emulator using compatible signing. Label this as recreation-data preservation only. Old-version migration testing is excluded by the author decision; do not require a published binary or a migration harness to finish integration.
- [ ] Record published maximum versionCode/signing identity as unconfirmed until verified. Version 1 cannot be assumed eligible for update. Separate preservation of untouched old files from whether this app discovers those files; no migration implementation is authorized.
- [ ] List release-only decisions: versionCode/name and signing identity (including published version metadata if using an existing listing), the signing/distribution decision above, current store requirements, and 16 KiB native validation. Carry forward the approved no-migration policy; do not reopen it without new author direction. Consult current official guidance during that later release work.
- [ ] Independent final review checks candidate identity, preservation manifest, focused results and honest exceptions. Present ready-to-submit versus blocked, with the exact blocker if any. Request author review of the concrete local diff before external submission.

## Completion and later work

This plan is complete when a reviewed local integration candidate and its evidence are ready for the author's integration decision. It does not require closing every accepted risk or making a store release.
External balloon skins follow landing on main under a separate plan. Markdown and other enhancements remain backlog.

Proposed next planning priority, not approved feature scope: Japanese/Traditional Chinese app text and onboarding; tappable authored text anchors; speaker selection through the alternative numeric command; individually specified input options/cancel notification; and named character touch-region accessibility actions. Existing centre-point character accessibility clicks must not be described as absent. Define each requirement from user-visible behavior and public protocol references, verify it with real fixtures, and seek approval before implementation. Do not send workers the v2 comparison report. Recommend assessing these before external balloon skins; this proposal does not cancel the approved post-main balloon backlog.
