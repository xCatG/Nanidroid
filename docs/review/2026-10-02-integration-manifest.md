# Replacement integration manifest — 2026-10-03

## Frozen identities and scope

Reviewed Task 2 base: `63d29252ac62cc771e40271127dbc12ec54f3a3f`; accepted product: `90952d020a7bf5742dba685d7266a08755c076f6`. Frozen source: `49a68e28b006de3e440041579c7bcd87212fdc4d`. Task 3 adds only README/CI/template/audit documentation; production, test and native behavior are unchanged.

Verified target HEAD, local origin/master, fresh remote master and approved native referenceRevision: `afc6a3a350fd10da35d085d9889c407ac5429a2b`. Recursive Git metadata agrees with all 701 blobs in the untruncated API inventory. The original target worktree/index/branches are not used as transfer input and receive no mutation. The isolated candidate is `app/build/replacement-integration/candidate`, branch `codex/replacement-integration`.

The candidate commit and annotated rollback tag object are recorded after creation in ignored `app/build/replacement-integration/candidate-result.json` and the local Task 3 report. Obtain them with `git -C app/build/replacement-integration/candidate rev-parse HEAD refs/tags/pre-recreation-2026-10-03`. The annotated tag exists only in the candidate repository and targets the exact base above; it is not a v2-final assertion. No external publication is authorized. Source rollback is not installed-data rollback.

## Recovery and supplemental audit

History bundle: `app/build/replacement-integration/recreation-history.bundle`; SHA-256 `f8a0dad3868e7a0eb7c8c7b2aaa41d3655fde330b25852c0c38943457473b767`. `git bundle verify` exited zero. Fetching its `refs/heads/codex/replacement-integration-source` into a fresh temporary repository recovered `49a68e28b006de3e440041579c7bcd87212fdc4d` exactly. The standalone repository and bundle are retained. Before retiring either repository, require verified durable storage or an authorized archival ref.

The bundle freezes the source tip above, including all product history and the fresh README/CI/template. This manifest is a later documentation-only commit, transferred as a supplemental committed blob; it is intentionally outside that bundle. Its source commit/blob ID is recorded in candidate-result.json and the Task 3 report after commit. The manifest cannot contain its own commit/hash or the commit that embeds it; these are independently derivable from the recorded candidate. Later audit commits do not alter the frozen product/source or bundle identity.

## Preservation and administration

Preserve `LICENSE.txt` verbatim as target blob `7e5e0af1967dba4ebe721a2888f704c236f63f08`. Retain native embedded notices, all 19 offline notice assets and the unchanged bundled ghost/test ZIP (SHA-256 `2ebf24a2be8255011c5e004459a58dd1317eb7b12a55adcbe6b8b2977c89dc2d`). README attributes CatTail Software LLC and the original xCatG/Nanidroid project; old README content was not read.

Every legacy src/, docs/, tools/, scripts/, .devcontainer/ path and .dockerignore is explicitly disposed below under the lead-approved classification. Legacy jni/ is removed only after the pinned relocation identity proof below. Target history remains the candidate parent. There is one target workflow, android-build.yml, and one PR template; both receive fresh contents. The lead authenticated master-protection request returned 404 and effective branch rules []; no required contexts were discovered. Recheck remote identity/rules before submission.

The fresh workflow installs the pinned SDK/NDK/CMake, uses JDK 17 and Bash for the non-executable wrapper, runs local tests/lint/debug/androidTest/unsigned release builds, and uploads app/build/reports/ and app/build/outputs/apk/. Official action tag documentation was checked for checkout/setup-java/upload-artifact v4, gradle/actions v4 and android-actions/setup-android v3. Google SDK metadata lists platforms;android-37.0, build-tools;37.0.0, ndk;28.2.13676358 and cmake;3.22.1. These are availability/static checks, not hosted-CI execution. [SDK metadata](https://dl.google.com/android/repository/repository2-3.xml), [sdkmanager](https://developer.android.com/tools/sdkmanager), [checkout](https://github.com/actions/checkout/tree/v4), [Java](https://github.com/actions/setup-java/tree/v4), [Gradle](https://github.com/gradle/actions/tree/v4), [Android setup](https://github.com/android-actions/setup-android/tree/v3), [artifacts](https://github.com/actions/upload-artifact/tree/v4).

Fresh candidate AGENTS.md replaces the target instructions and explicitly allows this isolated candidate workspace. Source AGENTS.md is unchanged. Recreation .gitattributes replaces unknown target attributes and preserves native -text. No target filters, tooling or application contents were read/run. Metadata-only local alternates and a task-specific temporary index construct the replacement tree; only that resulting tree is checked out.

## Active documents and exclusions

README restates essential approved picker import, original built-in balloon and logical artwork scale decisions, linking tracked baseline/design/replacement plan. No untracked import plan, Option A design/preview/spike or scale measurements are adopted. There are no active links requiring those untracked files. Historical ignored evidence links retain their unavailable-artifact provenance and are not current prerequisites. The source-derived v2 capability report is excluded. Unrelated eight-line implementation-handoff modification and all untracked local files are preserved. Only committed source files selected below transfer.

Excluded tracked coordination reports (preserved in standalone history/bundle): `.superpowers/sdd/2026-09-22-walking-skeleton/task-2-report.md`, `.superpowers/sdd/2026-09-23-native-engines/task-2-report.md`, `.superpowers/sdd/2026-09-23-native-state-persistence/task-4-report.md`.

No src/ or jni/ duplicate root, private NAR, secret/key, local.properties, APK/build-output or bundle path is selected. The pinned bundled ZIP and wrapper JAR are deliberate committed inputs. No build/emulator/device gate ran in Task 3; Task 4 verifies the exact candidate. Accepted LOBO Pixel, Compose wrong-thread and IME risks remain unresolved. No migration, signing/version eligibility or 16 KiB compatibility claim is made. README states uninstall/data consequences.

## Complete target path dispositions

Git blob IDs below identify deleted/preserved target contents without reading legacy blobs. Replacement identities and SHA-256 values are in the transfer table. All 701 target blob paths are enumerated; no directory shorthand authorizes deletion.

| Target path | Disposition | Target mode | Target blob |
| --- | --- | --- | --- |
| `.devcontainer/Dockerfile` | remove | 100644 | `5920af72e600512f2bdb30df3fc20be985c99a5c` |
| `.devcontainer/compose.yaml` | remove | 100644 | `82b5c803e5e09fcced3e353cfbac3a4a8be65c65` |
| `.devcontainer/devcontainer.json` | remove | 100644 | `06c93fdb5a7443a04aee5088e3e4f15fbf904cc5` |
| `.dockerignore` | remove | 100644 | `7c19bba1375a1f3368292ac53c5a7a168e689fc6` |
| `.gitattributes` | replace | 100644 | `32d8f71cd0b4224aec32c1e3618344a5bb6acd8b` |
| `.github/pull_request_template.md` | replace | 100644 | `d22a87bf06a545cdef2b4fd785e843a8014990d3` |
| `.github/workflows/android-build.yml` | replace | 100644 | `e37f0f053b92d482ce4673a0970095a9ec0e3f9f` |
| `.gitignore` | replace | 100644 | `d538d9b4b61e9e4954e47b415a90860570157777` |
| `AGENTS.md` | replace | 100644 | `675c8f645e6ec6b311b21b1e78cc106895c920f4` |
| `LICENSE.txt` | preserve | 100644 | `7e5e0af1967dba4ebe721a2888f704c236f63f08` |
| `README` | remove | 100644 | `4ddfef18888d74cdf3cfc189058dccf3d72eff6b` |
| `build.gradle.kts` | replace | 100644 | `98b539c9483e11302bb0ee404edb54825c00c3e3` |
| `docs/modernization/CURRENT_NARFS_BUILD_2026-07-29.md` | remove | 100644 | `72149d4b5a6eec7f244018d5fcfa35e6c3ed177c` |
| `docs/modernization/MAINLINE_STATUS_2026-07-29.md` | remove | 100644 | `86333beb6ab0480d244b2d6e4f52374a76604c41` |
| `docs/modernization/PR44_SECURITY_ALIGNMENT.md` | remove | 100644 | `ba9a7224a15d5a1f2e5db1b1607d2f0dc404fac0` |
| `docs/modernization/PR_A_BASELINE.md` | remove | 100644 | `d451a52e9670614ef2b2215646708d0a96faae2e` |
| `docs/modernization/PR_D1_DESCRIPTOR_CHARACTERIZATION.md` | remove | 100644 | `8de6803c508b7d28d2467aab577291301726f208` |
| `docs/modernization/PR_D2_SAKURA_SCRIPT_CHARACTERIZATION.md` | remove | 100644 | `5afe7b61067cf312ffb5f89f44b223770a2c9935` |
| `docs/modernization/PR_D5_SURFACE_DEFINITION_CHARACTERIZATION.md` | remove | 100644 | `6b27194f190fe9696034023795f025a1f3d8b892` |
| `docs/modernization/PR_D9B1A_NAR_INVENTORY.md` | remove | 100644 | `ef22eb9a90895325b8255d3097c3d73f9c3eae7f` |
| `docs/modernization/PR_D9B1B_NAR_DESCRIPTOR.md` | remove | 100644 | `8c84149cd6d89946b1c0b4828b1b8b8bb6bb5178` |
| `docs/modernization/PR_D9B1C_ZIP_PREFLIGHT.md` | remove | 100644 | `6addc4cb326499c1cb2c0c0cf655a9386bdde550` |
| `docs/modernization/PR_D9B1D_NAR_PLAN.md` | remove | 100644 | `d1b02ce2bb62ae7cfec3ce646fa9687da9dac236` |
| `docs/modernization/PR_D9B1E_STAGED_SESSION.md` | remove | 100644 | `c558e45d1ce54c33667f9f7ae677345b99085b7a` |
| `docs/modernization/PR_D9B2A_STAGED_COPY.md` | remove | 100644 | `0344d4a5bfb1336358a0df9d18b4678bf4af2524` |
| `docs/modernization/PR_D9B2B1A_GHOST_TREE_POLICY.md` | remove | 100644 | `8e28b888f6faac97f167f794047c25ba85e32e38` |
| `docs/modernization/PR_D9B2B1B_NOFOLLOW_CORE.md` | remove | 100644 | `e19a56821ab892301ea39141ae99b7c00cfd10f8` |
| `docs/modernization/PR_D9B2B1C_NARFS_STATIC_BUILD.md` | remove | 100644 | `1e9015b244860c9edc7c6d80ccd01d71adbb4678` |
| `docs/modernization/PR_D9B2B2A_RETAINED_OVERLAY_POLICY.md` | remove | 100644 | `415375020055937fc21c10a562421a4ea90cefd0` |
| `docs/modernization/PR_D9B2B2B1_LEASE_CLEANUP.md` | remove | 100644 | `8f570c353f0744523378d8f5aa69fe2b4b0ff9ff` |
| `docs/modernization/PR_D9B3_TRANSACTIONAL_INSTALLER.md` | remove | 100644 | `416ab698f9aa6fba5e48da93297df0d15eb06881` |
| `docs/modernization/PR_E1_SDK36_ARTIFACTS.md` | remove | 100644 | `2ff5efa73f4a3dc2798ff6e326b577c31c5f2814` |
| `docs/modernization/PR_E3_API36_EMULATOR_VALIDATION.md` | remove | 100644 | `185c05b3cb993e62ec158421d981e03cb19ce12d` |
| `docs/modernization/PR_E4_SDK37_PREVIEW.md` | remove | 100644 | `4e33cd4a48a195ddec0cf0fe68003e3fb2526986` |
| `docs/modernization/PR_F1_KOTLIN_PRESENTATION_CONTRACT.md` | remove | 100644 | `846a24fc0d5f3905341678c2a7e90f2e0c0a3fa4` |
| `docs/modernization/PR_F2_LEGACY_RENDERER_BOUNDARY.md` | remove | 100644 | `c462adfe48269901a39324a170dbeefd79813d3d` |
| `docs/modernization/PR_F3_KOTLIN_PRESENTATION_STATE.md` | remove | 100644 | `d515be4ad2f40a7c790d04bb437ff6655afefbe7` |
| `docs/modernization/PR_F4_KOTLIN_PRESENTATION_INTERPRETER.md` | remove | 100644 | `df268202f6b9d7e55c5917c17d07013dcfe14d71` |
| `docs/modernization/PR_F5_KOTLIN_GHOST_MANAGER.md` | remove | 100644 | `cda6ff4564196ed4b24c4c8bbeed1ec57aa00125` |
| `docs/modernization/PR_F5_KOTLIN_INTERACTION_EFFECTS.md` | remove | 100644 | `d87f427a59e626d747776ec814bb3d309faee793` |
| `docs/modernization/PR_F6_ANDROID12_MINIMUM.md` | remove | 100644 | `635e65539800b1f0848abd71011a62fdde9cedd7` |
| `docs/modernization/PR_F6_KOTLIN_PRESENTATION_FRAME.md` | remove | 100644 | `962f167e3f24ab142b49aa2447ffec2bf408e330` |
| `docs/modernization/PR_F7_KOTLIN_INCOMING_NAR_INTENT.md` | remove | 100644 | `9f0f258a1a7f3144f55865fca2fa772a61935d0d` |
| `docs/modernization/PR_F8_KOTLIN_GHOST_DOMAIN.md` | remove | 100644 | `46edff2d17dde91bf83e4246bba68fe2b6fee267` |
| `docs/modernization/PR_F9_KOTLIN_SURFACE_CATALOG.md` | remove | 100644 | `0eeb087045a9f35bac88c5c9353b7cc05b0d85f2` |
| `docs/modernization/PR_G1_KOTLIN_SURFACE_READER.md` | remove | 100644 | `54933c8c6cf1d72af5c1fdab5401d15eafb9634b` |
| `docs/modernization/PR_G2_SURFACE_DEFINITION_BOUNDARY.md` | remove | 100644 | `42764a94cda3204176c51ddf0277751a5f68fc2c` |
| `docs/modernization/PR_G3_COMPOSE_SURFACE_IMAGE.md` | remove | 100644 | `65f6bb9b94f8f8e566622f690592bb97a5dc426f` |
| `docs/modernization/PR_G4_SURFACE_HIT_TEST.md` | remove | 100644 | `fc3bad94a968425fdc677b43255c1c59b1dfd8d3` |
| `docs/modernization/PR_G5_KOTLIN_BALLOON.md` | remove | 100644 | `eac4b997ffc126e717fc34d01d934f0c666f1207` |
| `docs/modernization/PR_G6_KOTLIN_KERO_VIEW.md` | remove | 100644 | `eadcff15f2866db9f0219dbee4e0a335942abe3f` |
| `docs/modernization/SECURITY_ALIGNMENT_AUDIT.md` | remove | 100644 | `659df33edd454ccd36cd8647754cb20f48c76800` |
| `docs/modernization/THREAT_MODEL.md` | remove | 100644 | `82f8b4ba4b4ed7c0c1a1054bea61f0bb87418499` |
| `docs/modernization/durable-workflow-review-checklist.md` | remove | 100644 | `b7d8a24328bf0fe0e9cb4401051d374bb9e82fd7` |
| `docs/modernization/phase1-shipped-state-audit.md` | remove | 100644 | `506b8129bdb9f027f2103991c9040d957782f347` |
| `docs/modernization/phase1-shipped-state-ledger.json` | remove | 100644 | `58e77552394ee902358a6cadbb5b73741ea62057` |
| `docs/superpowers/plans/2026-07-29-adaptive-launcher-icon.md` | remove | 100644 | `2360559f84fffbf1339f0fcdc0075b0a284e7a59` |
| `docs/superpowers/plans/2026-07-30-boot-dispatch-coverage.md` | remove | 100644 | `e50bd61bcdbf501bcb6dcec02e2359cb4856a57e` |
| `docs/superpowers/plans/2026-08-01-test-scaffolding-cleanup.md` | remove | 100644 | `490afac0e281e98d883b8d604d932b9c8e984797` |
| `docs/superpowers/plans/2026-08-01-unified-nar-archive-queue.md` | remove | 100644 | `b7216d6197835ff6c8aab08fec510b3303fdfbcf` |
| `docs/superpowers/plans/2026-08-02-adaptive-ghost-stage-usability.md` | remove | 100644 | `07e7be49fd9f4f2ed30adab51ee6673958c8ccf4` |
| `docs/superpowers/plans/2026-08-06-llm-ghost-dialogue-spike.md` | remove | 100644 | `05b0114754eaec5b63150b14ce47a863a039e93c` |
| `docs/superpowers/plans/2026-08-08-background-nar-progress-polling.md` | remove | 100644 | `b0783d03685c82cbc2ff9f2d40146904173177f2` |
| `docs/superpowers/plans/2026-08-08-ghost-update-cleanup-ownership.md` | remove | 100644 | `b2082685ea778bc2cbee807371782713e0dfac0b` |
| `docs/superpowers/plans/2026-08-08-ghost-update-network-retries.md` | remove | 100644 | `0cc1a8e33f92f393c08f7556005c6eb66c586d6b` |
| `docs/superpowers/plans/2026-08-08-ghost-update-terminal-events.md` | remove | 100644 | `efc9f559777cef05d617726e6c60b9dc70b46882` |
| `docs/superpowers/plans/2026-08-08-pointer-dispatch-diagnostics.md` | remove | 100644 | `4ccf14618714493d87819b0131d6a7be30aca2da` |
| `docs/superpowers/plans/2026-08-08-sakurascript-interaction-integrity.md` | remove | 100644 | `7a0dac5b6b05f28b779b4ec32e691b3747793007` |
| `docs/superpowers/plans/2026-08-08-ui-audit-owned-session-evidence.md` | remove | 100644 | `80619eae93ec535069a95f06e53a2ad4717deda6` |
| `docs/superpowers/plans/2026-08-09-archive-intent-runner-guard.md` | remove | 100644 | `250ffead3933209bf87c7312643dfff8f5ca98d1` |
| `docs/superpowers/plans/2026-08-09-input-presentation-options.md` | remove | 100644 | `e61c336e7d8fd43881c8b1a39428cca6a1b2a867` |
| `docs/superpowers/plans/2026-08-09-timer-response-fence.md` | remove | 100644 | `79e952f869a53f23c1208ed3dd2af2ef0c7fb82a` |
| `docs/superpowers/plans/2026-08-14-github-actions-ci.md` | remove | 100644 | `797f63d18d4fe23e0b97bd1797492389c87b330d` |
| `docs/superpowers/plans/2026-08-14-satori-source-encoding.md` | remove | 100644 | `68f00bf8ad2e46333e51a53d6af72836407c5f71` |
| `docs/superpowers/plans/2026-08-17-phase1-shipped-state-audit.md` | remove | 100644 | `d6ed6b35270b25102d3f0776b05bcc9b53ac803e` |
| `docs/superpowers/plans/2026-08-17-remove-legacy-nar-helpers.md` | remove | 100644 | `3c4041c90680e78954ab390620676e5657f756d1` |
| `docs/superpowers/plans/2026-08-17-remove-obsolete-app-chrome.md` | remove | 100644 | `cae157be583dd9f25c46cbba4758145d2e81920e` |
| `docs/superpowers/plans/2026-08-17-remove-shipped-debug-ui.md` | remove | 100644 | `5914b43494d27a8d380af5ead937fcce2eb5af5b` |
| `docs/superpowers/plans/2026-08-17-remove-update-service-entrypoints.md` | remove | 100644 | `cd152bc1acb8d5804e2c16368f9b988b16c4a1d3` |
| `docs/superpowers/plans/2026-08-18-remove-updater-backend.md` | remove | 100644 | `827178e7baf86d6173854bdb0fd3c73b8a2dc7aa` |
| `docs/superpowers/plans/2026-08-24-delete-legacy-archive-runtime.md` | remove | 100644 | `14b536164c06c56d3f85c8459663210e20781582` |
| `docs/superpowers/plans/2026-08-24-foreground-nar-import.md` | remove | 100644 | `d2130507eaca80062bc0e7b76c833eef2ee83074` |
| `docs/superpowers/plans/2026-08-24-ghost-runtime-composition-root.md` | remove | 100644 | `80103597a7fd100980fdd2e192d071a3682bc136` |
| `docs/superpowers/plans/2026-08-27-ghost-runtime-native-session-authority.md` | remove | 100644 | `068e5305fea0ef302462ecd70f159879c001efbf` |
| `docs/superpowers/reports/2026-08-07-llm-ghost-dialogue-spike.md` | remove | 100644 | `d98fc4913fffe467b282c53fa548ffe73f09b460` |
| `docs/superpowers/reports/2026-08-18-updater-backend-baseline.md` | remove | 100644 | `ca9fba2f9569cc740afda414bfd76dd95e5fec29` |
| `docs/superpowers/specs/2026-07-29-adaptive-launcher-icon-design.md` | remove | 100644 | `c2ba314a40e4d9e355210318bb755940cbdd6372` |
| `docs/superpowers/specs/2026-07-30-standard-android-layout-design.md` | remove | 100644 | `5dcfb49a85a31465882cb27a4f41800c848e6967` |
| `docs/superpowers/specs/2026-08-01-adaptive-ghost-stage-usability-design.md` | remove | 100644 | `7569d5e409fb80c1a7c89dd0c8449512bed46957` |
| `docs/superpowers/specs/2026-08-01-unified-nar-archive-queue.md` | remove | 100644 | `a1de60b870396a4fbd8bdfdd709620732e269d87` |
| `docs/superpowers/specs/2026-08-06-llm-ghost-dialogue-spike-design.md` | remove | 100644 | `b3c213d9c3caf2a4de4f5628e933cc08eba0825f` |
| `docs/superpowers/specs/2026-08-08-background-nar-progress-polling-design.md` | remove | 100644 | `774ae15408d88ba618aabdbd163ab52ad89023c5` |
| `docs/superpowers/specs/2026-08-08-ghost-update-cleanup-ownership-design.md` | remove | 100644 | `1785fdbaf6910ace92f5440550af2cd4daa1e4fe` |
| `docs/superpowers/specs/2026-08-08-ghost-update-network-retries-design.md` | remove | 100644 | `c6d02b6a3d0321fbec2e9d83accf54a007f7076e` |
| `docs/superpowers/specs/2026-08-08-ghost-update-terminal-events-design.md` | remove | 100644 | `5f5a2af023dd5acbc54ae90fb5df681ee7330638` |
| `docs/superpowers/specs/2026-08-08-llm-ghost-product-improvements-brief.md` | remove | 100644 | `8947bedbd7366f268a5a1c5d2ffd0beb207129ab` |
| `docs/superpowers/specs/2026-08-08-pointer-dispatch-diagnostics-design.md` | remove | 100644 | `6a1344203613e0c4d3031d88c1e8b2337d2f953c` |
| `docs/superpowers/specs/2026-08-08-sakurascript-interaction-integrity-design.md` | remove | 100644 | `32284689efd038302107d17ec3bfccab6e9d0636` |
| `docs/superpowers/specs/2026-08-08-ui-audit-owned-session-evidence-design.md` | remove | 100644 | `05c76eec8f2ea3b54c25444fef1b37550e58885e` |
| `docs/superpowers/specs/2026-08-14-github-actions-ci-design.md` | remove | 100644 | `070ac01b1e55d1331339bbb4be515019b1145733` |
| `docs/superpowers/specs/2026-08-17-lean-app-execution-governance-design.md` | remove | 100644 | `4607b881c8447abfdb5f67a690cfda789fa04fd1` |
| `docs/superpowers/specs/2026-08-23-foreground-nar-import-design.md` | remove | 100644 | `919ac92e6c05398b3315ffbf5dc85611352e7e08` |
| `docs/superpowers/specs/2026-08-24-delete-legacy-archive-runtime-design.md` | remove | 100644 | `6deddb09e8629ccd52866ebb7b13bb3b1de9deb5` |
| `docs/superpowers/specs/2026-08-24-ghost-runtime-composition-root-design.md` | remove | 100644 | `a357f82fd3e7f82e261f1e34849de042266438d0` |
| `docs/superpowers/specs/2026-08-27-ghost-runtime-native-session-authority-design.md` | remove | 100644 | `7888f7c99f10fb2ac8ab1b8101e7f89b3c8ed910` |
| `docs/testing.md` | replace | 100644 | `e62b7d86f7282333539df60530ca69be64d7950d` |
| `docs/testing/nar-corpus-manifest.json` | remove | 100644 | `5350b46294b8a1f6f4f3887aa63a0822631d4321` |
| `docs/testing/nar-corpus.md` | remove | 100644 | `e9588cf7235d5c3123718e65418d715044966138` |
| `docs/testing/ui-audit-post-interaction-shiori-evidence.md` | remove | 100644 | `235394a10d573dc4b288bedc61ec412df4f1c04e` |
| `docs/testing/ui-audit-untracked-input-provenance.md` | remove | 100644 | `5dc9e52a823be2b7c1333c236aeb6579923a53b6` |
| `gradle.properties` | replace | 100644 | `4fec55b2a68688f8d0d87bf0945f2096621e3958` |
| `gradle/libs.versions.toml` | replace | 100644 | `f057772cf1e026b5ba1d6455937693ee0efdf2d7` |
| `gradle/wrapper/gradle-wrapper.jar` | replace | 100644 | `b1b8ef56b44f16b14dc800fa8103a6d89abb526f` |
| `gradle/wrapper/gradle-wrapper.properties` | replace | 100644 | `4b271a6469f8ecd6477442c5c61eb1b97ef5171d` |
| `gradlew` | replace | 100755 | `b9bb139f790567973216cd313e69ae65789c3754` |
| `gradlew.bat` | replace | 100644 | `24c62d56f2d4a91975bd1aa72103d2ec628e449f` |
| `jni/CMakeLists.txt` | remove | 100644 | `55af158bb3565039ff8a40f81bfe72205deeb553` |
| `jni/_/Dialog.cpp` | remove | 100644 | `0063ce29e5b32c45fd39282ebf5e17f4b25d1187` |
| `jni/_/Dialog.h` | remove | 100644 | `e900db308191e8abe7a2a9ebc87f802c9460e9fa` |
| `jni/_/FMO.h` | remove | 100644 | `d88254034c90612119e0e73e75d14773a09bfd6a` |
| `jni/_/Font.cpp` | remove | 100644 | `8b00f6c851bd68148cd9b9c7ddb9c9a6041e3e5f` |
| `jni/_/Font.h` | remove | 100644 | `68257005b9f15bb16a1520efd262c7c91abb5459` |
| `jni/_/Sender.cpp` | remove | 100644 | `14a3ef2d4fe50e3edc4a6040801dd57f2e3ef3a7` |
| `jni/_/Sender.h` | remove | 100644 | `411b0d59071fc5ea1e243ada89fa042dcbd91315` |
| `jni/_/Thread.cpp` | remove | 100644 | `b6910d644fec907a0cbce46948a233673e435608` |
| `jni/_/Thread.h` | remove | 100644 | `306b92845e891505cdf607d8fbb634e8374d4f6f` |
| `jni/_/Utilities.cpp` | remove | 100644 | `13555e182ded4f24086545e0f03d42707f29134c` |
| `jni/_/Utilities.h` | remove | 100644 | `3d0614c27bd756a89992556f77b02a8017591ba7` |
| `jni/_/Win32.cpp` | remove | 100644 | `5920dec1c0f9dfe7198cfe8e57ce7a1deb0218cf` |
| `jni/_/Win32.h` | remove | 100644 | `af1fdf7a126f05901d2520f1d63306da9a0a19f9` |
| `jni/_/Window.cpp` | remove | 100644 | `5f2e4811269dad79c1913237877e2232765ff506` |
| `jni/_/Window.h` | remove | 100644 | `d9888efaa00413584ea0696cd5de649640fe0e2a` |
| `jni/_/calc.cpp` | remove | 100644 | `173775d1c03bab9ae78e8fd8f68ac7e226d2e42e` |
| `jni/_/calc_float.cpp` | remove | 100644 | `8957d2982d01e910b828445a6359e54dcf5b7e57` |
| `jni/_/simple_stack.h` | remove | 100644 | `e2763e0f5539a59ec6a6148c82398037c4602082` |
| `jni/_/source-literal-manifest.json` | remove | 100644 | `40989d5f9923b5686dcc6c48fe535da6aff5abf6` |
| `jni/_/stltool.cpp` | remove | 100644 | `dbb5534b05e0332ef813c1e0555f579aaacd852a` |
| `jni/_/stltool.h` | remove | 100644 | `4ff5ac688b593e108fc6f4eab491f0ceebaaf2e8` |
| `jni/kawari8/Android.mk` | remove | 100644 | `7dcecf3bb6795f09fc857c084cd9e8587e0ce4dc` |
| `jni/kawari8/bcc.mak` | remove | 100644 | `2b1e94669c33f9c3c887bf21cd95e610ab2338c8` |
| `jni/kawari8/config.h` | remove | 100644 | `44c98a399217d8181fa5268e8a3d0b7db6c9c5bd` |
| `jni/kawari8/depend.mak` | remove | 100644 | `9ede604b560bf1abc7b651345dddd1a2e3cc61ee` |
| `jni/kawari8/files.mak` | remove | 100644 | `abf8c166b6ee681adbeca261a9bbcc1aed1dc92f` |
| `jni/kawari8/gcc-mach.mak` | remove | 100644 | `aca557a56b2ae49bc283c318862d63e6361c0866` |
| `jni/kawari8/gcc.mak` | remove | 100644 | `fe6682eb550a391d17338a46c902361bdf73b017` |
| `jni/kawari8/include/old/shiori_posix.h` | remove | 100644 | `33ba62bb5bd6dac0d06be4322a522032037fbbc8` |
| `jni/kawari8/include/shiori.h` | remove | 100644 | `e94d44705705df519409cf753773a9e5ecb7a74f` |
| `jni/kawari8/include/shiori_object.h` | remove | 100644 | `20c3e6ead2a0391acd2fe65b7c4db0a92d71c8f0` |
| `jni/kawari8/kawari_jni.cpp` | remove | 100644 | `b5f27d9e6572cc321017fc71fc35a1546eb4077c` |
| `jni/kawari8/kis/kis_base.h` | remove | 100644 | `1d628820a8fc49d33a69b1b069dc13ce856dfb7b` |
| `jni/kawari8/kis/kis_communicate.cpp` | remove | 100644 | `708e10af02ea36fe72de2820a02fdbfbfd6ea0e1` |
| `jni/kawari8/kis/kis_communicate.h` | remove | 100644 | `d42cb1c4171ea5326112593bca91a1c67a7fa244` |
| `jni/kawari8/kis/kis_config.h` | remove | 100644 | `9fae8a9a571254b31b114f5c5ab60f6d66a5461f` |
| `jni/kawari8/kis/kis_counter.cpp` | remove | 100644 | `d4b081dbe88651938ec8a8f26644478c638f7428` |
| `jni/kawari8/kis/kis_counter.h` | remove | 100644 | `257b75b51241f3a3a46945eadf09c1fcf89ea066` |
| `jni/kawari8/kis/kis_date.cpp` | remove | 100644 | `26e1c32a37c4fdf8e3902ac9c03ab3889edd5d7e` |
| `jni/kawari8/kis/kis_date.h` | remove | 100644 | `4d75d1412e76b977a2139395b1ded4fbad844f8e` |
| `jni/kawari8/kis/kis_dict.cpp` | remove | 100644 | `11455d2ec3312da338b79ac1e8cbdaca4389f13a` |
| `jni/kawari8/kis/kis_dict.h` | remove | 100644 | `1156864b4427b4a91826ae08dbdc47d4d432fcfa` |
| `jni/kawari8/kis/kis_echo.cpp` | remove | 100644 | `f1bf9d9d42411d3b9b720a8180ee6345425587f5` |
| `jni/kawari8/kis/kis_echo.h` | remove | 100644 | `e7927f9e63474ce03f7e1fc97004969f5aeb7289` |
| `jni/kawari8/kis/kis_escape.cpp` | remove | 100644 | `f1e2ccfdeeb98cee2936096ad30ba102f3c68b2d` |
| `jni/kawari8/kis/kis_escape.h` | remove | 100644 | `75c2039d823bab11abf877202078dae8366b2c8f` |
| `jni/kawari8/kis/kis_file.cpp` | remove | 100644 | `0e51fdfe3132bf44bb088bfdf231b308a87c6739` |
| `jni/kawari8/kis/kis_file.h` | remove | 100644 | `2f9e55e306fa3e97de669f175747da744765228c` |
| `jni/kawari8/kis/kis_help.cpp` | remove | 100644 | `7ca64488dac4960a5433ee5061e1bd5ee734b191` |
| `jni/kawari8/kis/kis_help.h` | remove | 100644 | `576b1c9ac2ddcc79255d4ba49240dac2cf913f8d` |
| `jni/kawari8/kis/kis_math.h` | remove | 100644 | `a23359419f8ac28b8420182adaa3a8adc99091e5` |
| `jni/kawari8/kis/kis_saori.cpp` | remove | 100644 | `eaf7d599224d79bebd491e0c85856f3427cf8605` |
| `jni/kawari8/kis/kis_saori.h` | remove | 100644 | `42b86336414095a28de5f8bfc8becb9573486aab` |
| `jni/kawari8/kis/kis_split.cpp` | remove | 100644 | `9b8ff03e919f3192bf85cfb9c6eb85cc62ed0a41` |
| `jni/kawari8/kis/kis_split.h` | remove | 100644 | `89814d6ee3ff2248440d44fb39ed638e061a7722` |
| `jni/kawari8/kis/kis_string.cpp` | remove | 100644 | `dcd96971111b3706f25de9e35d19247736f5e276` |
| `jni/kawari8/kis/kis_string.h` | remove | 100644 | `4def8c01704292e4e0399f8c495b01c59fbb880c` |
| `jni/kawari8/kis/kis_substitute.cpp` | remove | 100644 | `d04374721fb877dfccb27ef015139db1fefb842e` |
| `jni/kawari8/kis/kis_substitute.h` | remove | 100644 | `7bb82963ea2c94df09d3250c1c354b1b3dd5f8dc` |
| `jni/kawari8/kis/kis_system.cpp` | remove | 100644 | `3bf7d5f610a1ad35039a8823cb88e33af5c8da56` |
| `jni/kawari8/kis/kis_system.h` | remove | 100644 | `8d8fea71a1b49bc252a23e18586e832929f4bab8` |
| `jni/kawari8/kis/kis_urllist.cpp` | remove | 100644 | `e0019e76cf5947c8d4e7d269d3dc9115fc0f79bb` |
| `jni/kawari8/kis/kis_urllist.h` | remove | 100644 | `8cab90d225a38c2ee31b0386b2ed855f01dbb9d5` |
| `jni/kawari8/kis/kis_xargs.cpp` | remove | 100644 | `12bc5d228bf8d4eed02d20bc5cc3e0a841b70218` |
| `jni/kawari8/kis/kis_xargs.h` | remove | 100644 | `b32b0ce7dcebc6bba5d06008acae36d398fecfb8` |
| `jni/kawari8/libkawari/kawari_code.cpp` | remove | 100644 | `39a05cc29b22c76e43554f4cc98669411c051cc4` |
| `jni/kawari8/libkawari/kawari_code.h` | remove | 100644 | `b4b2bf3949be360c10b6c73b9c6c873ae97e79bb` |
| `jni/kawari8/libkawari/kawari_codeexpr.cpp` | remove | 100644 | `4f0aa1c008cf906439a27b46d871ec2a8e8aec94` |
| `jni/kawari8/libkawari/kawari_codeexpr.h` | remove | 100644 | `fd8a46d4c5e4a5a6d3f2c4ff1ced0f44dd939cd0` |
| `jni/kawari8/libkawari/kawari_codekis.cpp` | remove | 100644 | `c875e766c6a2501d4d52cbd7da78df40c9bf21a9` |
| `jni/kawari8/libkawari/kawari_codekis.h` | remove | 100644 | `1cdf339e6ffeb21ed38b99548b2b1360afac2ebf` |
| `jni/kawari8/libkawari/kawari_codeset.cpp` | remove | 100644 | `1f2ea22172dcd0dc77df5f0408465d2444c24fdb` |
| `jni/kawari8/libkawari/kawari_codeset.h` | remove | 100644 | `f6a061235fcd82f22cc0c2eb174f23822d29a7f6` |
| `jni/kawari8/libkawari/kawari_compiler.cpp` | remove | 100644 | `3173ebc24ca78eb9bfb327dcd870cd04e0bf6c32` |
| `jni/kawari8/libkawari/kawari_compiler.h` | remove | 100644 | `e5b6ee9f3f611a9cce78f8f426fbc05d595972a5` |
| `jni/kawari8/libkawari/kawari_crypt.cpp` | remove | 100644 | `e699cc6d130c62ef581ada950033b7aca3dc6748` |
| `jni/kawari8/libkawari/kawari_crypt.h` | remove | 100644 | `42cb78b969ad5a4c17efcaa87e25e24368794195` |
| `jni/kawari8/libkawari/kawari_dict.cpp` | remove | 100644 | `488f9b33a195190b24fe6b6c8bba08740560da93` |
| `jni/kawari8/libkawari/kawari_dict.h` | remove | 100644 | `ba6b8c71a9db4b51fad39818681c01daa431349a` |
| `jni/kawari8/libkawari/kawari_engine.cpp` | remove | 100644 | `7ca4a5cad061d0bbcbdeef3590134f4b0eec88dd` |
| `jni/kawari8/libkawari/kawari_engine.h` | remove | 100644 | `63df569893c2b1cb46b690f5539a471fe8354e9a` |
| `jni/kawari8/libkawari/kawari_lexer.cpp` | remove | 100644 | `0f3f872ef367ab4238b2dd51b885ff22e177908c` |
| `jni/kawari8/libkawari/kawari_lexer.h` | remove | 100644 | `db8da1ec65b6f10a3fede006b83e23208f9f8de8` |
| `jni/kawari8/libkawari/kawari_log.cpp` | remove | 100644 | `bd5bf1931686ecdc583013a8ec3b6cca9a9edfb2` |
| `jni/kawari8/libkawari/kawari_log.h` | remove | 100644 | `dead37647b582ffb5080911c020450fe073baeee` |
| `jni/kawari8/libkawari/kawari_ns.cpp` | remove | 100644 | `afae405762f2633974a9aa6e57e0fadb75d787a0` |
| `jni/kawari8/libkawari/kawari_ns.h` | remove | 100644 | `e310e3d3757919ce96118ba5061038246c2c0e01` |
| `jni/kawari8/libkawari/kawari_rc.cpp` | remove | 100644 | `6ac27b8c73539562e4aba649028fbd976de43599` |
| `jni/kawari8/libkawari/kawari_rc.h` | remove | 100644 | `3948c37391592d922df59ddca78eeedad461189d` |
| `jni/kawari8/libkawari/kawari_rc.sjis` | remove | 100644 | `af8062c92203ef38aa3e7d61a9bba0d6f102716d` |
| `jni/kawari8/libkawari/kawari_rc_sjis_encoded.h` | remove | 100644 | `f495b38e23164f61a1b4b9026ad11e03b6557573` |
| `jni/kawari8/libkawari/kawari_version.h` | remove | 100644 | `35ef33b3332a209f0a5c38186a3d2b6ccc295997` |
| `jni/kawari8/libkawari/kawari_vm.cpp` | remove | 100644 | `6ed3539bdd74004b4dee1f0253046cad99184e91` |
| `jni/kawari8/libkawari/kawari_vm.h` | remove | 100644 | `fa707d5fde564906b7844492426111486cc9a1fe` |
| `jni/kawari8/libkawari/wordcollection.h` | remove | 100644 | `8c569f62634938377ca8d8a56924c2fa42103e23` |
| `jni/kawari8/makedepend.rb` | remove | 100644 | `998fc53b9bd6d90d10372e6f9094c04c3a9c727b` |
| `jni/kawari8/misc/_dirent.cpp` | remove | 100644 | `514273d9ace03654ea01625819edbf4d235a6791` |
| `jni/kawari8/misc/_dirent.h` | remove | 100644 | `8484f5eace3d2b58581a1b32c396093b19cb0e99` |
| `jni/kawari8/misc/base64.cpp` | remove | 100644 | `e70931f4af10117de9f5e9c780c8a124d1d03524` |
| `jni/kawari8/misc/base64.h` | remove | 100644 | `95b5736ec036ff32d0bcb5afec94e7211c20a97e` |
| `jni/kawari8/misc/l10n.cpp` | remove | 100644 | `62107078cc17b340c14649f820854edcc06b8a1c` |
| `jni/kawari8/misc/l10n.h` | remove | 100644 | `a53d8c5ed69f218d18dbceb299f2281202aa5153` |
| `jni/kawari8/misc/misc.cpp` | remove | 100644 | `2407d29d758521553e88daa055ba454c617811f7` |
| `jni/kawari8/misc/misc.h` | remove | 100644 | `dc5e182d20b4ca69d74fa08cf6a9693a77a041dc` |
| `jni/kawari8/misc/mmap.h` | remove | 100644 | `f5ba35f8eb9d9a4a2a94619f12127e530e97e626` |
| `jni/kawari8/misc/mt19937ar.cpp` | remove | 100644 | `84ad9c238ce9b1bb919764190e674b51c91499f6` |
| `jni/kawari8/misc/mt19937ar.h` | remove | 100644 | `2423332989e7c4cb8d15700ec8e575b1be18ad3c` |
| `jni/kawari8/misc/phttp.cpp` | remove | 100644 | `4a7ce0ba9a06594f70941a5d1fddce02798b730b` |
| `jni/kawari8/misc/phttp.h` | remove | 100644 | `60b09ad924ed31c660ec892350cdb64e9c9b16ce` |
| `jni/kawari8/saori/old/saori_libdl.cpp` | remove | 100644 | `fd581f092068657dbbc0ded510b882cdbf7b0a31` |
| `jni/kawari8/saori/old/saori_libdl.h` | remove | 100644 | `367970a2c9350fdc4021034c52e6fa7d8d8a63a3` |
| `jni/kawari8/saori/old/saori_win32.h` | remove | 100644 | `03fa8cc8afa7146a61c8ff550a7e4749f23fd438` |
| `jni/kawari8/saori/saori.cpp` | remove | 100644 | `3a0826d6a6b077a5abf2331bf1d2150975f97b6b` |
| `jni/kawari8/saori/saori.h` | remove | 100644 | `8269c0c1f3378c1b252eecbb33b0d586bb064c5a` |
| `jni/kawari8/saori/saori_java.cpp` | remove | 100644 | `db87d8f5c754f8b103c0072ced1c3323e4ef86d1` |
| `jni/kawari8/saori/saori_java.h` | remove | 100644 | `70d623edbc90761be66711c9d3d4a88b5435c324` |
| `jni/kawari8/saori/saori_module.cpp` | remove | 100644 | `7a65bb3bc11de6695389c63ca123d3ae8101321d` |
| `jni/kawari8/saori/saori_module.h` | remove | 100644 | `ab94842c5ad307de5469334445164ef2fcc05a86` |
| `jni/kawari8/saori/saori_native.cpp` | remove | 100644 | `9edba4c400055dccbab4d337837340cca2ac29dd` |
| `jni/kawari8/saori/saori_native.h` | remove | 100644 | `9b0e1764a858f28093fc279429062424ebedcb97` |
| `jni/kawari8/saori/saori_python.cpp` | remove | 100644 | `a861dd181b2200a2d1b0210deb812e79e8c0e796` |
| `jni/kawari8/saori/saori_python.h` | remove | 100644 | `71e3f59c5533e418e25325b4c722a3346c94ccb5` |
| `jni/kawari8/saori/saori_unique.cpp` | remove | 100644 | `bc88e2a4b62d082890f38ee0475d69df4203d6e9` |
| `jni/kawari8/saori/saori_unique.h` | remove | 100644 | `2e5f94586b655da6619ba1bc0a63f66f26be25de` |
| `jni/kawari8/shiori/kawari_shiori.cpp` | remove | 100644 | `8bfb28ef5cf49a79c0e80dfd3ca5c0cd7229957a` |
| `jni/kawari8/shiori/kawari_shiori.h` | remove | 100644 | `d4e41bdcc88eaa5a5c84f08bc220f3596b4ff8c4` |
| `jni/kawari8/shiori/old/shiori_posix.cpp` | remove | 100644 | `758385cdcafee1779407310302463845924ff554` |
| `jni/kawari8/shiori/py_shiori.cpp` | remove | 100644 | `e71ee25f24065cc33126917769b8fe2d62dbae78` |
| `jni/kawari8/shiori/py_shiori.h` | remove | 100644 | `c67320c1a2b7d8a21975116d086c5b4a61aa9f9d` |
| `jni/kawari8/shiori/shiori.cpp` | remove | 100644 | `7d38cc531460cbec75ef90aa375d4b679731aa05` |
| `jni/kawari8/shiori/shiori_object.cpp` | remove | 100644 | `2ef84c6a6f3a96f2859d973370ae4776b4478ee5` |
| `jni/kawari8/sjis2ascii.rb` | remove | 100644 | `d4ec61a365e3b4796bc399bd509ec086bd9e1204` |
| `jni/kawari8/tool/kawari_decode2.cpp` | remove | 100644 | `4a80e5d15202faed3d0ed83ae67d8aba8ca543c0` |
| `jni/kawari8/tool/kawari_encode.cpp` | remove | 100644 | `decb91e3bacc823d6d5d9dd36a7d5423d4ea38f1` |
| `jni/kawari8/tool/kawari_encode2.cpp` | remove | 100644 | `fb7123585cd71b18f8e879f3850b822efbddbe4e` |
| `jni/kawari8/tool/kawari_kosui.h` | remove | 100644 | `dd0bd8d4f2ee5ace28bc5045bfd4badf1d947682` |
| `jni/kawari8/tool/kdb.cpp` | remove | 100644 | `e22cc58662bf6092657069ab4bbf658f9a882aac` |
| `jni/kawari8/tool/kdb.h` | remove | 100644 | `ac5b9c99ca98e8af0e50354aac87d1c117ba2880` |
| `jni/kawari8/tool/kosui.cpp` | remove | 100644 | `b394a809f07f979f7eca26029cc7c4de3b8b61f1` |
| `jni/kawari8/tool/kosui_base.h` | remove | 100644 | `97212ecae0cbef67953bc39aabcd1da1c29f4598` |
| `jni/kawari8/tool/kosui_dsstp.cpp` | remove | 100644 | `9ba8791dff45e6a2e60fbeda3ebb3f9c1035de90` |
| `jni/kawari8/tool/kosui_dsstp.h` | remove | 100644 | `6f846136b186ddef7f3a295e59c04050c668c684` |
| `jni/kawari8/tool/logserver.cpp` | remove | 100644 | `880920b9a66db27a6d8d53cf806f8297fe7251df` |
| `jni/kawari8/vc_kawari/vc_kawari.dsp` | remove | 100644 | `95c560c6dd3522a9be5f8c764025701f1ccf6faf` |
| `jni/kawari8/vc_kawari/vc_kawari.dsw` | remove | 100644 | `7cbb124c8e6efd9c6d68b669fd93a130c8fd1a7d` |
| `jni/kawari8/vc_kawari/vc_kawari.sln` | remove | 100644 | `c5673bc496c635120eebfa09ed7d62761ba2020b` |
| `jni/kawari8/vc_kawari/vc_kawari.vcproj` | remove | 100644 | `505d8d8e86d9d8089648882fbb17be34393fa2e1` |
| `jni/kawari8/vc_kawari/vc_kosui.dsp` | remove | 100644 | `3fcb01992d5d4c44857eec03a2df89d75d1b5f3f` |
| `jni/kawari8/vc_kawari/vc_kosui.dsw` | remove | 100644 | `261220131d134bf5105d2fd7df18824035079042` |
| `jni/kawari8/vc_kawari/vc_kosui.sln` | remove | 100644 | `2d756c29a5a5c78dca24216916d1a98c91e5fa1d` |
| `jni/kawari8/vc_kawari/vc_kosui.vcproj` | remove | 100644 | `ae728a67c056866d3d84664b737759b323c13b3c` |
| `jni/kawari8/win32jvm.def` | remove | 100644 | `af8e94633890b743933fbe3a62693cc0f1411ac8` |
| `jni/satori/Android.mk` | remove | 100644 | `4a3c7b88c52c38ca51f356b92128511d7de13723` |
| `jni/satori/Families.h` | remove | 100644 | `7cd22d296a3ada6038e2e7b638d84a588e674983` |
| `jni/satori/Family.h` | remove | 100644 | `9158d9a1bc6f41d5b11f6f4dbdb4efbca574eba6` |
| `jni/satori/OverlapController.h` | remove | 100644 | `23f1e7353890a90386a55d18b619f9aba37a98b1` |
| `jni/satori/SSTPClient.cpp` | remove | 100644 | `8ee2e25b246077749712f2a78be0671c13397431` |
| `jni/satori/SSTPClient.h` | remove | 100644 | `eb302e5a6fa2c6a542ac9cdbc773a9516addd734` |
| `jni/satori/SakuraCS.cpp` | remove | 100644 | `280255bc77a4a3350bd6519790fc50d7dc83ce05` |
| `jni/satori/SakuraCS.h` | remove | 100644 | `051dfb63483bf17716630b24b4e0a8030c725a1b` |
| `jni/satori/SakuraClient.cpp` | remove | 100644 | `79d7bb6d7c01b3dc916bebef1b0b7cb6e64d37c0` |
| `jni/satori/SakuraClient.h` | remove | 100644 | `cf3627661a19b62066c17dc838386ba207fc3ed1` |
| `jni/satori/SakuraDLLClient.cpp` | remove | 100644 | `2cdc392838303ef0570ebec305d30cf8d01af23c` |
| `jni/satori/SakuraDLLClient.h` | remove | 100644 | `380fbec016062a209a07f0f75793f30a62b42c68` |
| `jni/satori/SakuraDLLHost.cpp` | remove | 100644 | `c4025baf298ab68ac7721f7cdff0abe8360a67e7` |
| `jni/satori/SakuraDLLHost.h` | remove | 100644 | `cbb0cd2ea3b25f78f4b8acac4a758a817bed9a56` |
| `jni/satori/SaoriClient.cpp` | remove | 100644 | `eb5d49d883f08380b851734897949b9fb04c8d5e` |
| `jni/satori/SaoriClient.h` | remove | 100644 | `b25660564d18d9fe66defba6e7f3c470f4d3e9e3` |
| `jni/satori/SaoriHost.cpp` | remove | 100644 | `58a491b0fc62ae6dfa951fca41430ccabe286b35` |
| `jni/satori/SaoriHost.h` | remove | 100644 | `699f0c2b1523b087dad8fae7a1b3fc5cface7dcb` |
| `jni/satori/Selector.h` | remove | 100644 | `59a1b990198c5502ba1748f0e124ce1572292457` |
| `jni/satori/ShioriClient.cpp` | remove | 100644 | `018172df3806565461d704288a8d82db074937cb` |
| `jni/satori/ShioriClient.h` | remove | 100644 | `e9077f662adccfbc4b1f70938ea1611d5a33870f` |
| `jni/satori/TimeCommands.cpp` | remove | 100644 | `d36680c10d33758ee2d571dcac0f1bb6c5043a60` |
| `jni/satori/WinMain.cpp` | remove | 100644 | `f6051c91dc8d3d652d1f2129a94763083024636b` |
| `jni/satori/console_application.h` | remove | 100644 | `a0a474020f866a1a4a9ce88da4f468270e3ed754` |
| `jni/satori/index.html` | remove | 100644 | `65b33be88cf5ac25c3b399b544a4ebf5c2f31395` |
| `jni/satori/main.cpp` | remove | 100644 | `70a1d8aa1ba4aa18ec983d9bacaa670d226c9b2c` |
| `jni/satori/makefile.cygwin` | remove | 100644 | `a7df8e52d93f676de88586f7d0250b2c3b374c78` |
| `jni/satori/makefile.posix` | remove | 100644 | `e6982c4f2bf89d4312b1c8803ae259c2bdb0a6c1` |
| `jni/satori/posix_utils.h` | remove | 100644 | `1beb445969593f3269a58332ce1d964d44d40744` |
| `jni/satori/satori.cpp` | remove | 100644 | `0460a5bee64a8176b9d908d668874f6705fdcee9` |
| `jni/satori/satori.dsp` | remove | 100644 | `947af031bdfd82047e9e95e591f3b5e65fab3e76` |
| `jni/satori/satori.dsw` | remove | 100644 | `d7b298a507e37913cb32f174b65497f2c0e2e564` |
| `jni/satori/satori.h` | remove | 100644 | `05a6ff47716cf3de2b1f3e65b94d26e254d369f9` |
| `jni/satori/satoriFMO.cpp` | remove | 100644 | `7a492194442a0f6823c35a8925d0fee5ceb4471e` |
| `jni/satori/satoriTranslate.cpp` | remove | 100644 | `4900969685759fb1c4ab4abfe0c01bffafd0887f` |
| `jni/satori/satori_AnalyzeRequest.cpp` | remove | 100644 | `8d42401ee0c1cfe24305b35a390ed773f97fdea4` |
| `jni/satori/satori_CreateResponce.cpp` | remove | 100644 | `2b12b16c1f51993a5347bf3f727c53e780c971d6` |
| `jni/satori/satori_EventOperation.cpp` | remove | 100644 | `b80fe27ae0d11fe42953fd3eadd166b223a31dc4` |
| `jni/satori/satori_Kakko.cpp` | remove | 100644 | `ac7a468734b882f51903d32f2fead47b7e380f88` |
| `jni/satori/satori_jni.cpp` | remove | 100644 | `f76937e6aa7a35a16ac500e705842c5b8982c104` |
| `jni/satori/satori_load_dict.cpp` | remove | 100644 | `263aa853a95b84bb015e9ebfbefc91b13415f622` |
| `jni/satori/satori_load_dict.h` | remove | 100644 | `d5b1421ada533765d7ba9c26e671bf38a2e2d5d0` |
| `jni/satori/satori_load_unload.cpp` | remove | 100644 | `4b4147717934fd5bdb9fa432a78d039d73a6f59e` |
| `jni/satori/satori_sentence.cpp` | remove | 100644 | `4cb60af37403cf0fada080bcca242c45253bdd8e` |
| `jni/satori/satori_test.dsp` | remove | 100644 | `9479b5b0ac2c5e8f0f43d9230fcec2702f1ed7e9` |
| `jni/satori/satori_tool.cpp` | remove | 100644 | `813868db145d7fd99748136c74863b61063ef966` |
| `jni/satori/satorite.dsp` | remove | 100644 | `51fda25a64d8814aad4aa6a688a2615e890852b5` |
| `jni/satori/shiori_plugin.cpp` | remove | 100644 | `7f0807df06807906ddaf2a32c23484c2b5be9393` |
| `jni/satori/shiori_plugin.h` | remove | 100644 | `ce15b232578a4794af9a09f14fa4495ca1b086f1` |
| `jni/satori/source-literal-manifest.json` | remove | 100644 | `c4bd0722d4ff770d85cf7375f40bf006fa8fb3f4` |
| `jni/satori/ssu.cpp` | remove | 100644 | `ca001e565b87a8d2653c2f9ff29fed1ed03756a6` |
| `jni/satori/ssu.dsp` | remove | 100644 | `75d899c82cd1bc6b79c4a7ddd2fa4e60b399fdc7` |
| `jni/satori/ssu_anchor.cpp` | remove | 100644 | `8863757e014fda985a6b6ab73f4e32066d2f61dd` |
| `jni/satori/test/characters.ini` | remove | 100644 | `15de9bf3616c3e9b45a48f789ab59158a6ad1d82` |
| `jni/satori/test/dic1.txt` | remove | 100644 | `d0ce79b0684dd2828a5563ef1bd551df32be133e` |
| `jni/satori_compat.h` | remove | 100644 | `83f507ddc465f6aa4283a328275a72551ca0d3ed` |
| `jni/satori_license.txt` | remove | 100644 | `3358c0545f47b74c84ba6993aa918d49bc071d29` |
| `jni/yaya/.clang-format` | remove | 100644 | `cfd3c54b98263174c62ea8c504737c7aab3ff04c` |
| `jni/yaya/.gitignore` | remove | 100644 | `2e400d07f8234c6b79cc0ad64ef093b619ae11e6` |
| `jni/yaya/LICENSE` | remove | 100644 | `7716b63908f965f88c2b9744c4178ace96bdc4ce` |
| `jni/yaya/android_charset.cpp` | remove | 100644 | `d6473460cedbc833b08ebb3aa57b348ddaacbc05` |
| `jni/yaya/android_charset.h` | remove | 100644 | `e927cb23e34c6489ad66c95024864c6903f69fee` |
| `jni/yaya/aya5.cpp` | remove | 100644 | `045ea4f4ab5f6dd40a7930669f626e09055279ba` |
| `jni/yaya/aya5.h` | remove | 100644 | `ac5fe2d74b7477dedeb5157a67e224197c091bbb` |
| `jni/yaya/aya_profile.cpp` | remove | 100644 | `fa10de5f3ac7ce686e0ea8cbb25cf5d39a1b4beb` |
| `jni/yaya/ayavm.cpp` | remove | 100644 | `9e2a64e574c2d1afceb684ae1d4f6119580b6fc4` |
| `jni/yaya/ayavm.h` | remove | 100644 | `e60151f4f75e5d258753f6301e7633559f42ba6d` |
| `jni/yaya/basis.cpp` | remove | 100644 | `bd2acb0ea519008a35bbf4112f32da62485b852b` |
| `jni/yaya/basis.h` | remove | 100644 | `86dbfa5116e6dc98d9de5259ca95bfac57eebc28` |
| `jni/yaya/ccct.cpp` | remove | 100644 | `2c5d81ed10c7574e58ea6edd20272ffaec165a95` |
| `jni/yaya/ccct.h` | remove | 100644 | `66682be73fe1751997ca7583ed0d5216ebd4be09` |
| `jni/yaya/cell.h` | remove | 100644 | `ccd09906a2f7cf15f74eab9cd805f8ac738c6063` |
| `jni/yaya/comment.cpp` | remove | 100644 | `f100d12e52011bba902e368609a9cf6ac79afe91` |
| `jni/yaya/comment.h` | remove | 100644 | `162bc7263f9cc5271e0d4cfa450c593572091dd2` |
| `jni/yaya/cpp.hint` | remove | 100644 | `76a5928d2631dbfb4f09380cfc7b573a9a4c6b5f` |
| `jni/yaya/crc32.c` | remove | 100644 | `b8ab7462754684ae608e501812ee1c631ae3aa3f` |
| `jni/yaya/crc32.h` | remove | 100644 | `3ba080eee711064039e24a3298f61a524f1a55ca` |
| `jni/yaya/deelx.h` | remove | 100644 | `4ece822b71153f28cf55c7dd8c035e6b9ecacdb8` |
| `jni/yaya/dir_enum.cpp` | remove | 100644 | `3f44819f1bde694a69cc9b598c286d50dccecb5a` |
| `jni/yaya/dir_enum.h` | remove | 100644 | `21780811e2ac016e517c7cc9cdedb8654215112d` |
| `jni/yaya/duplevinfo.cpp` | remove | 100644 | `c901e789e77fa62ab8e5018700eef881d0c76eb6` |
| `jni/yaya/em-post.js` | remove | 100644 | `140c44fda76b0ffe654a846124459bc75c2f5277` |
| `jni/yaya/em-pre.js` | remove | 100644 | `9b99db7fb8d2d89b43e0b9698cf4cfb613a33e56` |
| `jni/yaya/file.cpp` | remove | 100644 | `b98872d872da01e81fa61bd3c896fa15a2c0282f` |
| `jni/yaya/file.h` | remove | 100644 | `cef03221e693a79f7c239cd5a65236d7c65aee65` |
| `jni/yaya/file1.cpp` | remove | 100644 | `fd77c6819ee9573d3111105e18991f75def4fa82` |
| `jni/yaya/fix_unistd.h` | remove | 100644 | `ce55dfcdb6ad45e380bb2ea56411c809066c5a4f` |
| `jni/yaya/function.cpp` | remove | 100644 | `fc58fba788cd369aae4bdb2d16928eea93c3619a` |
| `jni/yaya/function.h` | remove | 100644 | `a0aa1eaf8c8baff9c82bb4ab9a8921e3014745bd` |
| `jni/yaya/global.h` | remove | 100644 | `fe5f38fcfefa337bb8fd84fa608efc9f32267b57` |
| `jni/yaya/globaldef.h` | remove | 100644 | `5ed68a725c97748e0878d333d0134fd74c1c667a` |
| `jni/yaya/globalvariable.cpp` | remove | 100644 | `91e707551681edae23a128826d8487b0bfadaf69` |
| `jni/yaya/lib.cpp` | remove | 100644 | `3ca9b4a685d95132fb55820cb5eae45a1a154ba9` |
| `jni/yaya/lib.h` | remove | 100644 | `30e2b48d63e193e7ac0584fb6fe5d93b0e60251b` |
| `jni/yaya/lib1.cpp` | remove | 100644 | `d4e278cabad25bd9b9dd39412dc283d9b9e5fd68` |
| `jni/yaya/localvariable.cpp` | remove | 100644 | `f1d80dd9bfa89cdcdfe8e57b90234faafcea510c` |
| `jni/yaya/log.cpp` | remove | 100644 | `a4d4c15a5d81eaf0d20663bb5b638492ff5c0d19` |
| `jni/yaya/log.h` | remove | 100644 | `6c4e54140e2d802e553d177a48ea27f81bff2801` |
| `jni/yaya/logexcode.cpp` | remove | 100644 | `1e8423f5b81174a207f0415bf14049a55c3718f8` |
| `jni/yaya/logexcode.h` | remove | 100644 | `fd488e867b16176f974cb64b66c7533038dafc4f` |
| `jni/yaya/make_aya.bat` | remove | 100644 | `aa2f17ad7b07e28aa4919f96f3bdf6448eac7d8d` |
| `jni/yaya/makefile.emscripten` | remove | 100644 | `46465f874a255355fde07db35888024ee9af41cb` |
| `jni/yaya/makefile.fc6` | remove | 100644 | `c51c121643f239d57c36c00826954c6709f8539c` |
| `jni/yaya/makefile.freebsd` | remove | 100644 | `2707ee39e4cdb1ba2354329e215a15f71d5b887a` |
| `jni/yaya/makefile.linux` | remove | 100644 | `8336c87a1b744c06ea914700b41f5bea1c76ab9c` |
| `jni/yaya/makefile.mingw32` | remove | 100644 | `22b9520cfbd182232c1c6da91a634f17b05b5484` |
| `jni/yaya/makefile.posix` | remove | 100644 | `d6b9bcf42424a7cabb7aa6bc5f036aa616d61b81` |
| `jni/yaya/manifest.cpp` | remove | 100644 | `55954b08f19a4fc65700b4a7dcec3101d6b75186` |
| `jni/yaya/manifest.h` | remove | 100644 | `509ff698c8bd77ac24cd240648adca95223d7e4a` |
| `jni/yaya/md5.h` | remove | 100644 | `7411800513d81cbab489031458b080cf163dafa7` |
| `jni/yaya/md5c.c` | remove | 100644 | `76a6e4608367345817884c565935315d1f07de2c` |
| `jni/yaya/messages.cpp` | remove | 100644 | `ce299ee1fd618f3f6214ba051f669eb00d681124` |
| `jni/yaya/messages.h` | remove | 100644 | `c63d94e44099d5237abe079c044c91bcca2bfa4e` |
| `jni/yaya/misc.cpp` | remove | 100644 | `fd11769f58760d84377b159e9e41fd471a9c4bfd` |
| `jni/yaya/misc.h` | remove | 100644 | `8a69fb464a0272246140c9337c0c141176248af7` |
| `jni/yaya/mt19937ar.cpp` | remove | 100644 | `b39f55484893d9cbf6be77828ad4f501819955b9` |
| `jni/yaya/mt19937ar.h` | remove | 100644 | `b67ac8ec8b74a84bf5b0664c4945f4a92b222606` |
| `jni/yaya/parser0.cpp` | remove | 100644 | `f23b69e0fcd102edb88f23c34b74f640347c13ff` |
| `jni/yaya/parser0.h` | remove | 100644 | `e45bbec380a80f161cbbab26f76594e18a8203f6` |
| `jni/yaya/parser1.cpp` | remove | 100644 | `0ad9db6f5a23622f473c5aaa5ad3e69eb7184f18` |
| `jni/yaya/parser1.h` | remove | 100644 | `7fec0741809037b8d9bc9ad6424c49c3e298b029` |
| `jni/yaya/posix_utils.cpp` | remove | 100644 | `406c89663e259f53d0046d8e8488f3e09510dbaa` |
| `jni/yaya/posix_utils.h` | remove | 100644 | `124255dfb8248d8160d04b98a51811ac9e6a975e` |
| `jni/yaya/readme-original.txt` | remove | 100644 | `f569b00e3b9cea0a2be7915d4dcc5656c30d553f` |
| `jni/yaya/readme.txt` | remove | 100644 | `ecbd54f8e4b59dea644b909bc19052c34fca3b4c` |
| `jni/yaya/resource.h` | remove | 100644 | `79ba9f7bfede31962c15126a11fe8238e1da33f5` |
| `jni/yaya/selecter.cpp` | remove | 100644 | `f312b559c63dc48d8a18abb328ce1e95ac51d5ea` |
| `jni/yaya/selecter.h` | remove | 100644 | `a0586f22356effcb3ad1de35f91112a6f95f9b2d` |
| `jni/yaya/sha1.c` | remove | 100644 | `6febd0ec2b03070012464018a9c538abed8d2149` |
| `jni/yaya/sha1.h` | remove | 100644 | `1978f5f438f89bc8ffe5847dfb5fe49d409f5fb9` |
| `jni/yaya/stdafx.cpp` | remove | 100644 | `a1f786439194a115927f0d8ebf4da90fda71f862` |
| `jni/yaya/stdafx.h` | remove | 100644 | `dd99fbb63746c55e3c425275427ec5bc530eef89` |
| `jni/yaya/sysfunc.cpp` | remove | 100644 | `6780975cdf9880d98d53cdaaa542419213fd210f` |
| `jni/yaya/sysfunc.h` | remove | 100644 | `7c14d2a51aef49b28b84e9c99efd51c59bc13179` |
| `jni/yaya/timer.h` | remove | 100644 | `20ee01dd03da225db37153d65fa43fef5aeecbd8` |
| `jni/yaya/value.cpp` | remove | 100644 | `827adb9d335c735fec893baa29123d5e053676e3` |
| `jni/yaya/value.h` | remove | 100644 | `21c11b31a41713bf907734ced04757b2f7df219c` |
| `jni/yaya/valuesub.cpp` | remove | 100644 | `729cebe96a88629bb162dad60a3a85405d70f032` |
| `jni/yaya/variable.cpp` | remove | 100644 | `dac71b13075f75a15b68878d8020b2c691ef878d` |
| `jni/yaya/variable.h` | remove | 100644 | `0222a4286f7e30e38ff93c9c0de1ecae5f10f3a0` |
| `jni/yaya/wsex.cpp` | remove | 100644 | `30c069f03138fef90c44dc0b7ee9b23fa58a581d` |
| `jni/yaya/wsex.h` | remove | 100644 | `7d05e8c1d9f506b58b50ea1ceb97926bf39aa893` |
| `jni/yaya/yaya_jni.cpp` | remove | 100644 | `97bdc3561f888967d3f7ca2ca4b5392c8f401411` |
| `jni/yaya/yayad.py` | remove | 100644 | `02fbedc708e75ea08e6ba8f9a359f376d553ef35` |
| `scripts/run-cross-engine-runtime-audit.ps1` | remove | 100644 | `9faecc45ace6177aeaea39cc4904c96945dafa6f` |
| `scripts/run-nar-corpus-audit.ps1` | remove | 100644 | `79c4c717364b32b749574b1d4b2d5a4919da8bfd` |
| `scripts/run-ui-visual-audit.ps1` | remove | 100644 | `3b5e892e02bdd0afdfcc0926a31e24f19cff775b` |
| `settings.gradle.kts` | replace | 100644 | `c97debbd3e69f9ae625429e71fd1f55abb920729` |
| `src/androidTest/AndroidManifest.xml` | remove | 100644 | `35d09f5f74b791f1c8fa91e6557e8886eaacf17b` |
| `src/androidTest/java/com/cattailsw/nanidroid/CrossEngineRuntimeInstrumentationTest.kt` | remove | 100644 | `b783c00192b0de94af5e3effc51ec1b6318b1470` |
| `src/androidTest/java/com/cattailsw/nanidroid/NanidroidLifecycleInstrumentationTest.kt` | remove | 100644 | `7c18acd4cfcc0850852f0a4c01c4674db8310425` |
| `src/androidTest/java/com/cattailsw/nanidroid/NativeLibraryPackagingInstrumentationTest.kt` | remove | 100644 | `afdf523018db4fc353bc199897d8a2e3efd28212` |
| `src/androidTest/java/com/cattailsw/nanidroid/SScriptRunnerMainThreadRequestInstrumentationTest.kt` | remove | 100644 | `4a86371239262350e64fe07e204e6f9f09081427` |
| `src/androidTest/java/com/cattailsw/nanidroid/ShioriLifecycleInstrumentationTest.kt` | remove | 100644 | `26c718ad4a930a5f6da984d2caa9feec87e4ce8d` |
| `src/androidTest/java/com/cattailsw/nanidroid/SurfaceAnimationExecutionCharacterizationTest.kt` | remove | 100644 | `a9d66947771837af149f2f59dbd762bdaa159a94` |
| `src/androidTest/java/com/cattailsw/nanidroid/TextDocumentRestoreSnapshotInstrumentationTest.kt` | remove | 100644 | `2c26b486a8ffd69c2fad192186023f97117a2e64` |
| `src/androidTest/java/com/cattailsw/nanidroid/TransientUiBundleInstrumentationTest.kt` | remove | 100644 | `88e9a163fc9b8f142c423c244f2ee06c23b5e42b` |
| `src/androidTest/java/com/cattailsw/nanidroid/compose/ForegroundNarImportPresentationTest.kt` | remove | 100644 | `95e06552270436903a83725bea15ea6702fa7df8` |
| `src/androidTest/java/com/cattailsw/nanidroid/compose/NanidroidComposeShellTest.kt` | remove | 100644 | `a6a482be357adbcb7574f80aa4d980ead1c7f048` |
| `src/androidTest/java/com/cattailsw/nanidroid/compose/NanidroidComposeShellUiAutomatorTest.kt` | remove | 100644 | `0a5705ca334cd1aeccb94d2819bdf3843bcf085e` |
| `src/androidTest/java/com/cattailsw/nanidroid/compose/NanidroidSimpleDialogsTest.kt` | remove | 100644 | `ca448578d781dfe3ddba528f5da5195e26678779` |
| `src/androidTest/java/com/cattailsw/nanidroid/compose/OpaqueStageTestSurface.kt` | remove | 100644 | `9ab6db4d9e10f8d0a14cd3fa1691c17f23d25667` |
| `src/androidTest/java/com/cattailsw/nanidroid/compose/stage/DialogueActionSurfaceTest.kt` | remove | 100644 | `b9c533ece825af50e0719855d7c7cb77a0d82bcc` |
| `src/androidTest/java/com/cattailsw/nanidroid/compose/stage/GhostBubbleInteractionTest.kt` | remove | 100644 | `c3d010863a591e5194919644f51f30aa82a170f1` |
| `src/androidTest/java/com/cattailsw/nanidroid/compose/stage/GhostStageAccessibilityTest.kt` | remove | 100644 | `4600b599e1ab26c5ee7370178fe97157a6ec09db` |
| `src/androidTest/java/com/cattailsw/nanidroid/compose/stage/GhostStageRestorationTest.kt` | remove | 100644 | `06d97515d040858d762f4ee2b440413acdbcb6a9` |
| `src/androidTest/java/com/cattailsw/nanidroid/compose/stage/RenderedTransformContractTest.kt` | remove | 100644 | `92451a3a9973bb9be7788f51652e79ec021fc1a8` |
| `src/androidTest/java/com/cattailsw/nanidroid/compose/stage/StageEnvironmentProviderTest.kt` | remove | 100644 | `5ec094506e30ea19a90fab6a934e50dee27c6de8` |
| `src/androidTest/java/com/cattailsw/nanidroid/compose/stage/StagePointerInputTest.kt` | remove | 100644 | `c648950928c497b895fc7f9350c89f83a2a849e4` |
| `src/androidTest/java/com/cattailsw/nanidroid/corpus/NarCorpusProbeContent.kt` | remove | 100644 | `e36f155b911e88cd6566c63f4ee643882762a634` |
| `src/androidTest/java/com/cattailsw/nanidroid/corpus/NarCorpusRuntimeTest.kt` | remove | 100644 | `630ea50c2f6f4b614c00c22061927a81d735663d` |
| `src/androidTest/java/com/cattailsw/nanidroid/corpus/NarCorpusSourceSyntaxInspector.kt` | remove | 100644 | `bca9fee94fcb9dd8a7d0af1504d7d2841c0df945` |
| `src/androidTest/java/com/cattailsw/nanidroid/corpus/NarCorpusSourceSyntaxInspectorTest.kt` | remove | 100644 | `4b6f0097cae306a2aa247ccf05c05bd580a398ea` |
| `src/androidTest/java/com/cattailsw/nanidroid/install/NarTransactionalInstallerInstrumentationTest.kt` | remove | 100644 | `b9abb13cc6f8da97438b577a1d3967db680e49ea` |
| `src/main/AndroidManifest.xml` | remove | 100644 | `bce4dd07ae0b65e726efd525ba2c8057dd689282` |
| `src/main/assets/nanidroid.zip` | remove | 100644 | `f01abd7497107a075e7e6955741b11165b8bf277` |
| `src/main/kotlin/com/cattailsw/nanidroid/BootDispatchState.kt` | remove | 100644 | `116c6c7a4e6c17ed7b4e4fbf97d843f14bf7ca35` |
| `src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt` | remove | 100644 | `0884194d3be7f598e78fa3cb584fee658d683f9d` |
| `src/main/kotlin/com/cattailsw/nanidroid/DescReader.kt` | remove | 100644 | `5bcd18465953084187ec4ceb33e448a7e51409d6` |
| `src/main/kotlin/com/cattailsw/nanidroid/DialogueDialogBinding.kt` | remove | 100644 | `95971fcb0821d936ce6375cf163d9d61db2e9363` |
| `src/main/kotlin/com/cattailsw/nanidroid/Ghost.kt` | remove | 100644 | `ee530cf5b407bb33f1d2e61b06b90246176e4712` |
| `src/main/kotlin/com/cattailsw/nanidroid/GhostMgr.kt` | remove | 100644 | `7a3bca23ab8e59d2a7f5efe65ac8d26460bf7ab0` |
| `src/main/kotlin/com/cattailsw/nanidroid/GhostPreparation.kt` | remove | 100644 | `1c465a750e70f110dba116a708f1cf15b27a46c2` |
| `src/main/kotlin/com/cattailsw/nanidroid/GhostPresentationFrame.kt` | remove | 100644 | `dd0ca8300bcac53f81661f0de919236572b11c3d` |
| `src/main/kotlin/com/cattailsw/nanidroid/GhostPresentationRenderer.kt` | remove | 100644 | `f127a6dc681220d620f64e4c0a322eec26cfe203` |
| `src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt` | remove | 100644 | `c985b354fc6f1608ce15943120c4288a573a36b7` |
| `src/main/kotlin/com/cattailsw/nanidroid/LegacyPlatform.kt` | remove | 100644 | `659878d54069f335b4afeb7972673d7f191b57fa` |
| `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt` | remove | 100644 | `1c4f87e31391afb4e9d5c8097afd78f9bbabdbee` |
| `src/main/kotlin/com/cattailsw/nanidroid/PatternHolders.kt` | remove | 100644 | `92ca75873322c03836cb908397ae60901e98d30c` |
| `src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt` | remove | 100644 | `eb55385edc4283256fd15683ac64fdc7ff67d3bb` |
| `src/main/kotlin/com/cattailsw/nanidroid/Setup.kt` | remove | 100644 | `3ec7ca8578605c2daba268a37d0711f5891005a4` |
| `src/main/kotlin/com/cattailsw/nanidroid/ShellSurface.kt` | remove | 100644 | `e092710d5cd9cab710439da730814f80f3fb817c` |
| `src/main/kotlin/com/cattailsw/nanidroid/ShioriResponse.kt` | remove | 100644 | `188d03e032b847a4a70952cd57d6a8d0c169801a` |
| `src/main/kotlin/com/cattailsw/nanidroid/SurfaceDefinition.kt` | remove | 100644 | `5e253a3f67e65865acf39f8c10ec7b86478b97cd` |
| `src/main/kotlin/com/cattailsw/nanidroid/SurfaceHitTest.kt` | remove | 100644 | `c234daa578efcbf5c807576e5a7e885e2c48678a` |
| `src/main/kotlin/com/cattailsw/nanidroid/SurfaceManager.kt` | remove | 100644 | `c45e7bbe718667bb82f0fb94fb6fbe65f3d1d6dd` |
| `src/main/kotlin/com/cattailsw/nanidroid/SurfaceReader.kt` | remove | 100644 | `775c27b27b91c5626f0b0e2131684169eba3f64e` |
| `src/main/kotlin/com/cattailsw/nanidroid/compose/ComposeGhostStageHost.kt` | remove | 100644 | `e2511f1bc3a10a31002785b969be5b7cd9fa1bdc` |
| `src/main/kotlin/com/cattailsw/nanidroid/compose/ForegroundNarImportPresentation.kt` | remove | 100644 | `599cfb2760981d17f7c4ee994b9420d4a8424058` |
| `src/main/kotlin/com/cattailsw/nanidroid/compose/GhostPresentationStage.kt` | remove | 100644 | `c1168a6f93c7115170e8c43940df22ae321cd3b7` |
| `src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidComposeShell.kt` | remove | 100644 | `716c7f8908dea85ecd19a696bf5d0380d3f4b3d0` |
| `src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidSimpleDialogs.kt` | remove | 100644 | `fa09b6d50d9d6334cf2d75820bc3b6b466425b1a` |
| `src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidTheme.kt` | remove | 100644 | `65a020e546253698f34233cf4fc6e7a8ed59eb69` |
| `src/main/kotlin/com/cattailsw/nanidroid/compose/PlainTextDocument.kt` | remove | 100644 | `258ca831d2189b17c0e3a57852c87f7c2d771b4e` |
| `src/main/kotlin/com/cattailsw/nanidroid/compose/SurfaceAnimationScheduler.kt` | remove | 100644 | `887ca5661e4970e283660c98be9adb94da40038f` |
| `src/main/kotlin/com/cattailsw/nanidroid/compose/SurfaceCompositor.kt` | remove | 100644 | `1a12e2dbfdc41970421579eafecb6c011316de0a` |
| `src/main/kotlin/com/cattailsw/nanidroid/compose/SurfacePointerInteraction.kt` | remove | 100644 | `6f6ac2d7e59e1c922e2c09d217e69db924d666b5` |
| `src/main/kotlin/com/cattailsw/nanidroid/compose/SurfaceRenderPlan.kt` | remove | 100644 | `9f17d45a138676d92537fc47198c62c1b963a356` |
| `src/main/kotlin/com/cattailsw/nanidroid/compose/stage/CollisionOverlay.kt` | remove | 100644 | `1f05d817fbefed184f60caca93469c0df9f0fd4d` |
| `src/main/kotlin/com/cattailsw/nanidroid/compose/stage/DialogueActionSurface.kt` | remove | 100644 | `12b163fc35140dfb01024dca6cd59c77ea0cccd1` |
| `src/main/kotlin/com/cattailsw/nanidroid/compose/stage/GhostBubble.kt` | remove | 100644 | `158c9517b5a86e263dfb41150245eeb080638918` |
| `src/main/kotlin/com/cattailsw/nanidroid/compose/stage/GhostStageSemantics.kt` | remove | 100644 | `4855669327a17ba7f59a0831145878bb514a4952` |
| `src/main/kotlin/com/cattailsw/nanidroid/compose/stage/MeasuredGhostStageLayout.kt` | remove | 100644 | `bcc2d05fe460e3052485008269f5110cf9bee4d2` |
| `src/main/kotlin/com/cattailsw/nanidroid/compose/stage/RenderedSurfaceLayer.kt` | remove | 100644 | `e4043cc19961a48a6733d0f44edd2233d7cfc23a` |
| `src/main/kotlin/com/cattailsw/nanidroid/compose/stage/StageEnvironmentProvider.kt` | remove | 100644 | `bbfe7019e2917d59af074c2a25347b82caa96a54` |
| `src/main/kotlin/com/cattailsw/nanidroid/compose/stage/StagePointerInput.kt` | remove | 100644 | `97fb022b50bdc4e309179366bb29998525165732` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/ForegroundNarImportBackend.kt` | remove | 100644 | `c68637e8675954d69c19f3c549eaaca9c2e0faa6` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/ForegroundNarImportCoordinator.kt` | remove | 100644 | `6abe5fa2c90df2a047ab65a199f1b6f142ff09e2` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/ForegroundNarImportState.kt` | remove | 100644 | `fa5f37e2d5ac13ec978a0569ccc0b21262cb5a92` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/NarArchiveInventory.kt` | remove | 100644 | `e7d81bbfe4c7fbef681ab5b88b369bdc409322ec` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/NarArchiveInventoryResult.kt` | remove | 100644 | `bbab25334f8908c3e1fd0a0024ed353a7c8efe70` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/NarArchiveInventoryValidator.kt` | remove | 100644 | `d08495fcf4a81da7735bed28bfc7af2428bd7995` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/NarContentUriImport.kt` | remove | 100644 | `c55a6a7332ff0d3b76af7bb2b406f0e77611cbcb` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/NarDescriptorParser.kt` | remove | 100644 | `97a9558019cf0bb92f8adca407107c666ebb7e0e` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/NarDescriptorResult.kt` | remove | 100644 | `736bef17271d33864f60a8a9fea5c8f3fbcd0169` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/NarGhostDiscoverabilityValidator.kt` | remove | 100644 | `4e2b12d008b6759298f86ac9435a183794b12c84` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/NarGhostTreePolicy.kt` | remove | 100644 | `217223b253beb81c7afa5209482fe115e8439fa9` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/NarInstallDescriptor.kt` | remove | 100644 | `04e3330071680c65e3f9fd77049b6a85915004cd` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/NarInstallError.kt` | remove | 100644 | `f9f9e8e1731818e1bc4e0c1485bd8fd9db446c6f` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/NarInstallPlan.kt` | remove | 100644 | `bca713a00bd7c04cad402826ce9bf3558b449635` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/NarInstallPlanResult.kt` | remove | 100644 | `b3f7f1a3152a30707b79cace8a8ab9c8aba4ab8d` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/NarInstallPlanValidator.kt` | remove | 100644 | `0f62e1878b9f3e803bcd8f5386ff2787cf0a74fd` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/NarRelativePathPolicy.kt` | remove | 100644 | `ebca12e43152971dadd88d527c64205f28e13a86` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/NarStagedSource.kt` | remove | 100644 | `bfb42c852a791a1edd88fa7306606f4eb709c285` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/NarStagedSourceCopyError.kt` | remove | 100644 | `5ad869011fc8814a422073544f394b6997787d0d` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/NarStagedSourceCopyResult.kt` | remove | 100644 | `7ead7dd53db2ed5d312afc831f5dec5c15e32aad` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/NarTransactionalInstaller.kt` | remove | 100644 | `1f3e164b0db5fc5a4a62f19c9d74ac1d54aa2d97` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/NarVerifiedInstallSession.kt` | remove | 100644 | `5798c2abffcdd13f390fd043b2bfd0a6eff67c3e` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/NarZipCentralPreflight.kt` | remove | 100644 | `4fab919da9705e2cc8b436372eb2f6942482164a` |
| `src/main/kotlin/com/cattailsw/nanidroid/install/OwnedStagingRecovery.kt` | remove | 100644 | `ab04bc424f887859567e9183e903e0a087ad5e76` |
| `src/main/kotlin/com/cattailsw/nanidroid/runtime/GhostPresentationState.kt` | remove | 100644 | `292aa43862adf82316ebc50fb936bce7fc1499a7` |
| `src/main/kotlin/com/cattailsw/nanidroid/runtime/KotlinGhostPresentationRuntime.kt` | remove | 100644 | `2594e9bf4c55dad95ab3bd26b5fd85bb6e37f0b3` |
| `src/main/kotlin/com/cattailsw/nanidroid/runtime/MonotonicClock.kt` | remove | 100644 | `d6e29c2b444668d0deb631c5eb17609504dd41c7` |
| `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/DialogueContent.kt` | remove | 100644 | `922365471a22f5ecac85bf3b0e44d7c435f55975` |
| `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/DialogueSpeakerOwnership.kt` | remove | 100644 | `7746e29bcc51fec7de440031e8a89ee2a7992588` |
| `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/GhostEventCapabilities.kt` | remove | 100644 | `ea0af61b176ec578dd812c3ecd16fdae3d87e861` |
| `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/GhostRuntimeMode.kt` | remove | 100644 | `e91f8373f270fef6a030f2d42d028520a8ead02f` |
| `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptCommandParser.kt` | remove | 100644 | `daa9525b09b8fb1841cbfa2b75ad1a6549e34c6d` |
| `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptInteractionStream.kt` | remove | 100644 | `fbf04456be3bf0727902e40f6485fdb60dc6a26c` |
| `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptTokenizer.kt` | remove | 100644 | `0331d4d7f9c2447fa6caa5df7456534c3d24d315` |
| `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/SurfaceInteractionEffect.kt` | remove | 100644 | `02bf43a8bc1b65253a1548105850a77fb44487c5` |
| `src/main/kotlin/com/cattailsw/nanidroid/runtime/stage/BubbleRegionPublication.kt` | remove | 100644 | `a2b435e42e5268cc79cb8ec4f5762b2ed42981df` |
| `src/main/kotlin/com/cattailsw/nanidroid/runtime/stage/BubbleScrollMemory.kt` | remove | 100644 | `bebe8e80467fba3f86fbdec42db22e16e9c2b9ce` |
| `src/main/kotlin/com/cattailsw/nanidroid/runtime/stage/GhostStageLayoutPolicy.kt` | remove | 100644 | `5137ef22c7f100a8c85f8f74c76db9c86742e5b2` |
| `src/main/kotlin/com/cattailsw/nanidroid/runtime/stage/PhysicalClickSequencer.kt` | remove | 100644 | `1fde710be67e80c262281b17433394cb832234da` |
| `src/main/kotlin/com/cattailsw/nanidroid/runtime/stage/StageEnvironment.kt` | remove | 100644 | `19c7ff1cd8fc8dcfc934245e08612e08fb5dd7c1` |
| `src/main/kotlin/com/cattailsw/nanidroid/runtime/stage/StageInputRouter.kt` | remove | 100644 | `4a9e7642d2832e705568e7dec8f9333013b2a487` |
| `src/main/kotlin/com/cattailsw/nanidroid/runtime/stage/SurfaceSizingPolicy.kt` | remove | 100644 | `6a5d9c1abc4235f7e11b8c3b4325bed7105941c0` |
| `src/main/kotlin/com/cattailsw/nanidroid/runtime/stage/SurfaceTransformPx.kt` | remove | 100644 | `0f75baf7ccec2acf549dd98057a514adac6083d6` |
| `src/main/kotlin/com/cattailsw/nanidroid/shiori/EchoShiori.kt` | remove | 100644 | `07754b4a70cec66a0e8c74f781ecf46436fc7747` |
| `src/main/kotlin/com/cattailsw/nanidroid/shiori/Kawari.kt` | remove | 100644 | `a88c97593ebd87d113ce84b93ee87eecb0eba362` |
| `src/main/kotlin/com/cattailsw/nanidroid/shiori/NanidroidShiori.kt` | remove | 100644 | `0cbc513c3b1157c9be5895f1c3734fe8e02386fe` |
| `src/main/kotlin/com/cattailsw/nanidroid/shiori/NotSupportedShiori.kt` | remove | 100644 | `0c05c604ec2fd158b32028e3533980f21f71b554` |
| `src/main/kotlin/com/cattailsw/nanidroid/shiori/SatoriShiori.kt` | remove | 100644 | `55f773650a6245ee9b0a647339d114df7c9bb421` |
| `src/main/kotlin/com/cattailsw/nanidroid/shiori/Shiori.kt` | remove | 100644 | `325ae8d47ae5f6089aa65f6ed751f1055a1e960a` |
| `src/main/kotlin/com/cattailsw/nanidroid/shiori/YayaShiori.kt` | remove | 100644 | `8d7240c897ed26f652354a17dfa10523719ca95c` |
| `src/main/kotlin/com/cattailsw/nanidroid/surface/CollisionGeometry.kt` | remove | 100644 | `b4f767939e1f567198978996dd1fb3124b11b6cd` |
| `src/main/kotlin/com/cattailsw/nanidroid/surface/SurfaceParser.kt` | remove | 100644 | `35e4c7c8fa38abc26c6e093a7e27660244208a3a` |
| `src/main/kotlin/com/cattailsw/nanidroid/surface/SurfaceSelector.kt` | remove | 100644 | `be26977f9f7295a7d15425ea4613bb7379a251d8` |
| `src/main/kotlin/com/cattailsw/nanidroid/surface/SurfaceSourceFile.kt` | remove | 100644 | `4b84222194578fa6d7e3de972536651235725d5c` |
| `src/main/kotlin/com/cattailsw/nanidroid/util/PrefUtil.kt` | remove | 100644 | `0c05f48067f9280253813ce69ad8b7dcd94a60dd` |
| `src/main/res/drawable-hdpi/balloon.9.png` | remove | 100644 | `7c9d3ef745ddc5c03275abef8c530dec5aaa496a` |
| `src/main/res/drawable-hdpi/ic_launcher.png` | remove | 100644 | `53f1e0139f52fb33f0d3b576841340c2cdfd51c1` |
| `src/main/res/drawable-ja-hdpi/ic_launcher.png` | remove | 100644 | `2e91ccae0b7ae9218fa25283ae59b9609a8896b2` |
| `src/main/res/drawable-ja-nodpi/ic_launcher_foreground.png` | remove | 100644 | `b743740669b6cabada6e1e12cb11cd6d98be20a5` |
| `src/main/res/drawable-ja-xhdpi/ic_launcher.png` | remove | 100644 | `15ab0cb0b4369ccac8a12f416e288cae74b3115a` |
| `src/main/res/drawable-ldpi/ic_launcher.png` | remove | 100644 | `d05fb23a47007b468200cea3ecb12f04d9d06f19` |
| `src/main/res/drawable-mdpi/ic_launcher.png` | remove | 100644 | `4774658b6831af4472522ae7324d717e33a31eb7` |
| `src/main/res/drawable-nodpi/ic_launcher_foreground.png` | remove | 100644 | `11fbde74affb200c8cd43c7c044b3cc87492a54b` |
| `src/main/res/drawable-xhdpi/ic_launcher.png` | remove | 100644 | `7c23388dba3c827ff37f78c4d57be6a0f046fddc` |
| `src/main/res/drawable/ic_launcher_background.xml` | remove | 100644 | `362d63e37f6355456b87d676b16e07a61686abfd` |
| `src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | remove | 100644 | `6b78462d615bfe7003b31d0534dcad416b75ad25` |
| `src/main/res/mipmap/ic_launcher.xml` | remove | 100644 | `d2af44f66f6e54242a9316c0c5fdb5b2ea8ac006` |
| `src/main/res/raw-ja/first_run_script.txt` | remove | 100644 | `25f2a32fed122e528d6a05b0e8334295bd2c0d8a` |
| `src/main/res/raw-zh-rTW/first_run_script.txt` | remove | 100644 | `3ad13414b80c63d8b6d5b9950ecce2edbbb9791d` |
| `src/main/res/raw/first_run_script.txt` | remove | 100644 | `1408a0d280b68d8f838695db3a0ddfa9450e1648` |
| `src/main/res/values-ja/strings.xml` | remove | 100644 | `ba94b85665b3fad274f8ba8a62b08929ae0337a3` |
| `src/main/res/values-zh-rTW/strings.xml` | remove | 100644 | `1e1c00a63e15c8b20082a8e5b09aae9e39950b4b` |
| `src/main/res/values/colors.xml` | remove | 100644 | `1a1967aa229994c7e57163b6d7eae30dd3620683` |
| `src/main/res/values/strings.xml` | remove | 100644 | `4948f335845d4b0a90c09e8bb8328c64dcef657e` |
| `src/main/res/values/styles.xml` | remove | 100644 | `1aa47c15a661d5008c5e3d3fdc3c03783e3c35a8` |
| `src/screenshotTest/kotlin/com/cattailsw/nanidroid/compose/AdaptiveGhostStageFixtures.kt` | remove | 100644 | `7e14e1c89422f8b4934c41f117e6227815a2a5d7` |
| `src/screenshotTest/kotlin/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotRenderer.kt` | remove | 100644 | `3bb4263e423921ba1fd9e439786157fdbe1b4d43` |
| `src/screenshotTest/kotlin/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTest.kt` | remove | 100644 | `64313b59453eaacb3ebfcd9846047ea6d1cec131` |
| `src/screenshotTest/kotlin/com/cattailsw/nanidroid/compose/ScreenshotHarness.kt` | remove | 100644 | `14a5bbeb60826ce7732d72a6dcd35a32cc45d5ed` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/CollisionShapesCombinedPreview_collision_shapes_combined_e37f14f6_0.png` | remove | 100644 | `4644bba77b45f1a77b8a39be02209813869e1509` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/CompactLandscapeEmptyPreview_compact_landscape_empty_801c9793_0.png` | remove | 100644 | `30768b38a647a6bf9bcd38e250b06ff8b9e99eca` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/CompactLandscapeLongPreview_compact_landscape_long_1012a401_0.png` | remove | 100644 | `d46526ce4ac88f1f6910316e76e07b54c882f4ae` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/CompactLandscapeOnePreview_compact_landscape_one_692f3d29_0.png` | remove | 100644 | `78d74c072cd1f1e454a3ddc91f9a5d929381d9d5` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/CompactLandscapeTwoPreview_compact_landscape_two_b8c6edd9_0.png` | remove | 100644 | `58bdcc7579d01183c82a6ea32bedad0b597a3534` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/FoldableFlatPreview_foldable_flat_b11f284a_0.png` | remove | 100644 | `764ce92ac63df6d52cf88d141261534893586d85` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/FoldableVerticalSeparatingPreview_foldable_vertical_separating_03414dd5_0.png` | remove | 100644 | `5ede3d644875fed994a29ae614fcfd9c3abd8262` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/Grid400x1000Preview_grid_400x1000_d7b85df3_0.png` | remove | 100644 | `dc71295359ca219e8cb5b0fca5ae7ebfdd6cf4fa` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/Grid400x400Preview_grid_400x400_6d06b048_0.png` | remove | 100644 | `790b6b33c8eff2b8c09e67a748f370c906f9e3ad` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/Grid400x500Preview_grid_400x500_d8f8ee14_0.png` | remove | 100644 | `8a7ea0f9ee9ccefe5a9b8147361b5c5c89600386` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/Grid610x1000Preview_grid_610x1000_ece7dbea_0.png` | remove | 100644 | `a09b08d9943612c519a74069d4cfbf8c0c76c27b` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/Grid610x400Preview_grid_610x400_d82efbda_0.png` | remove | 100644 | `a9045ceaab390b05c7bdd331fff5cf2aa3d3e6e3` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/Grid610x500Preview_grid_610x500_d1c258c3_0.png` | remove | 100644 | `630961aafb986334b3d1ffbfa0060fc35f69b9b2` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/Grid900x1000Preview_grid_900x1000_f2ddc13a_0.png` | remove | 100644 | `6517c3debe6150fadf0d21695003d9f567f6b8bf` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/Grid900x400Preview_grid_900x400_92475aed_0.png` | remove | 100644 | `86bb9e9b0343315cf0426aaced101edb13e30d1d` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/Grid900x500Preview_grid_900x500_51b2c7e8_0.png` | remove | 100644 | `6b93bc9f6c68799cdaf4f2fc74261a0f0a3bdb1c` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/ImportFailedPreview_import_failed_ce7fa325_0.png` | remove | 100644 | `a8e22e44793ae5e585c0076da85bf4b85849dc8d` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/ImportInstallingPreview_import_installing_41d80d6d_0.png` | remove | 100644 | `c04780c3d11a3842d9cc6a9520bfde4bc1db0b85` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/PairLtrDarkF150D320Preview_pair_ltr_dark_f150_d320_f09d3507_0.png` | remove | 100644 | `492011fd7fe945abf19b2e389939d54b522e89ff` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/PairLtrLightF100D160Preview_pair_ltr_light_f100_d160_4c743c96_0.png` | remove | 100644 | `fadf52d0910730133f24fe1793f555b2e41fe8f9` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/PairLtrLightF200D320Preview_pair_ltr_light_f200_d320_a4e6691c_0.png` | remove | 100644 | `8946b1535e58ccfc8350e1ad18e08b225c37cec2` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/PairRtlDarkF100D320Preview_pair_rtl_dark_f100_d320_f0bc4798_0.png` | remove | 100644 | `9a1ba8efc61bd4478bed19f0d34f073d9f3f508c` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/PairRtlDarkF200D160Preview_pair_rtl_dark_f200_d160_a4272636_0.png` | remove | 100644 | `2136ab6aadd8addc1043727c9b733c1d6c35b939` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/PairRtlLightF150D160Preview_pair_rtl_light_f150_d160_fee886be_0.png` | remove | 100644 | `cc3156eedc915582bbeb4400559adda97d4d452f` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/PhonePortraitOneBubblePreview_phone_portrait_one_bubble_3465ad5a_0.png` | remove | 100644 | `117ab238e5e43aef5ea9c54aec8c7920f17a2704` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/PhonePortraitTwoBubblesPreview_phone_portrait_two_bubbles_2a06a8ff_0.png` | remove | 100644 | `63c0c8c2af27b2f5f40055e4b10bd701ab86c8c3` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/TabletLandscapePreview_tablet_landscape_66f003c0_0.png` | remove | 100644 | `3ae7c2174973c827b1a42169dcddff388903d501` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/TabletPortraitPreview_tablet_portrait_88742450_0.png` | remove | 100644 | `cb21b225535b3018b9503628f68bbdd397ddfa25` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/TallPhoneTwoPreview_tall_phone_two_bb788103_0.png` | remove | 100644 | `bebec2b2f1c9a86df429e43fb77910b148eba5c4` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/TinyTallPreview_tiny_tall_d779a6c2_0.png` | remove | 100644 | `1d5286bd175cfabdb425232b9b996f4a489eabf6` |
| `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/TinyWidePreview_tiny_wide_c3ef11e4_0.png` | remove | 100644 | `811f55f8a884c2c339f4cd36d7f76217486300f8` |
| `src/test/java/com/cattailsw/nanidroid/BootDispatchStateTest.kt` | remove | 100644 | `8e0ee8e35f4f19f3d6cb2db111f075fe236cec35` |
| `src/test/java/com/cattailsw/nanidroid/DescReaderCharacterizationTest.kt` | remove | 100644 | `256e49fb6e85353903797c0d6fb1a22f4cc0520e` |
| `src/test/java/com/cattailsw/nanidroid/DialogueDialogBindingTest.kt` | remove | 100644 | `2789ed9900a1ee52b94657720c02f5ccb7060d1f` |
| `src/test/java/com/cattailsw/nanidroid/DialogueExternalUriLaunchTest.kt` | remove | 100644 | `1ac05b6d300095e85cceb320b2e7e364df99b736` |
| `src/test/java/com/cattailsw/nanidroid/ForegroundNarPickerOwnershipTest.kt` | remove | 100644 | `931425476de3431793d17647300457dcbbb0b9e3` |
| `src/test/java/com/cattailsw/nanidroid/GhostPreparationTest.kt` | remove | 100644 | `a675d082f5f7c1cac65dab38351fe6c6a8df0145` |
| `src/test/java/com/cattailsw/nanidroid/GhostPresentationFrameTest.kt` | remove | 100644 | `133f379b75939be2334688b667d5be47f91222de` |
| `src/test/java/com/cattailsw/nanidroid/GhostRuntimeAttachmentTest.kt` | remove | 100644 | `1f05bfa37b802f98bcdb93037baf40e84494b669` |
| `src/test/java/com/cattailsw/nanidroid/GhostRuntimeNativeThreadTest.kt` | remove | 100644 | `986c97a737bf5c5b4ceb65c6f09e74e3f00ce3af` |
| `src/test/java/com/cattailsw/nanidroid/GhostRuntimeSwitchTest.kt` | remove | 100644 | `2a2015ce4ef0ce2e4075e6cfddbbbb185996c87e` |
| `src/test/java/com/cattailsw/nanidroid/GhostRuntimeTest.kt` | remove | 100644 | `c9a3ec04b91610e91e563f7c33cd772862e33a7e` |
| `src/test/java/com/cattailsw/nanidroid/GhostShellNameCompatibilityTest.kt` | remove | 100644 | `6c11e69fd8b804ca44ca4a44298fcd2e47f818fd` |
| `src/test/java/com/cattailsw/nanidroid/GhostShioriTrafficTest.kt` | remove | 100644 | `b0aeb9401da9c86d5fb8eed71b1873259c387d96` |
| `src/test/java/com/cattailsw/nanidroid/GhostSwitchRequestTest.kt` | remove | 100644 | `b84d342b588f925a18119614a84f40edef61df3a` |
| `src/test/java/com/cattailsw/nanidroid/GhostSwitchingCharacterizationTest.kt` | remove | 100644 | `53229aabc8b6b061f40286a56672a46f8e79f826` |
| `src/test/java/com/cattailsw/nanidroid/HostAndroidStubRule.kt` | remove | 100644 | `8805ab96865ba6e4e20743a7a514a9f7f2167e50` |
| `src/test/java/com/cattailsw/nanidroid/LegacyPlatformSeamTest.kt` | remove | 100644 | `d365e7d27be48cc9bd6099ee48f51774dabbdbd5` |
| `src/test/java/com/cattailsw/nanidroid/NanidroidGhostStartupTest.kt` | remove | 100644 | `d185bff0c885455daabb551c090239604ca92340` |
| `src/test/java/com/cattailsw/nanidroid/NanidroidShioriCharacterizationTest.kt` | remove | 100644 | `6d81451ace55b14e310ba70944c41f7cd0e02839` |
| `src/test/java/com/cattailsw/nanidroid/RuntimeFixture.kt` | remove | 100644 | `77b85a79697674b2080736d5b85c171fbcb8d9f1` |
| `src/test/java/com/cattailsw/nanidroid/SScriptRunnerAuthorityTest.kt` | remove | 100644 | `64fc8a25c421b848be0ea8c01e9550f2eaac718f` |
| `src/test/java/com/cattailsw/nanidroid/SScriptRunnerBootDispatchTest.kt` | remove | 100644 | `cce216bfd2f7ad957005943122aee9a567bb9808` |
| `src/test/java/com/cattailsw/nanidroid/SScriptRunnerDialogueObserverTest.kt` | remove | 100644 | `d75a59b15b451a68cb154ec0aae21e65af8018a8` |
| `src/test/java/com/cattailsw/nanidroid/SScriptRunnerDialogueTimingTest.kt` | remove | 100644 | `c2c933a34f18d24b17d31b685d839870dc165179` |
| `src/test/java/com/cattailsw/nanidroid/SScriptRunnerHostBindingTest.kt` | remove | 100644 | `86107850186e0cfe90c3cafd5ad78241ecfd1613` |
| `src/test/java/com/cattailsw/nanidroid/SScriptRunnerPresentationTest.kt` | remove | 100644 | `68797bde8b4e9a1d0627696786ad74585fa89d87` |
| `src/test/java/com/cattailsw/nanidroid/SakuraScriptCharacterizationTest.kt` | remove | 100644 | `7de567d55734da7909cd05b99f018ce035b16aa4` |
| `src/test/java/com/cattailsw/nanidroid/ShellTransparencyPolicyTest.kt` | remove | 100644 | `94604fd9feb7106d8f05fe4e5cc8c8c6ae9bd332` |
| `src/test/java/com/cattailsw/nanidroid/SurfaceDefinitionCharacterizationTest.kt` | remove | 100644 | `f732a25cc539724848c328bf766ead51e341c9d6` |
| `src/test/java/com/cattailsw/nanidroid/TransientUiStateTest.kt` | remove | 100644 | `b0c36e16f4b2cc99c67fc9475511a2dd67e93872` |
| `src/test/java/com/cattailsw/nanidroid/compose/ComposeGhostStageHostDialogueStateTest.kt` | remove | 100644 | `339efdd871a6d87817bd1f08f3670fc8c866744e` |
| `src/test/java/com/cattailsw/nanidroid/compose/NanidroidThemeTest.kt` | remove | 100644 | `3d1c4280f779e74ae185d94c37b069803a0cc3c3` |
| `src/test/java/com/cattailsw/nanidroid/compose/PlainTextDocumentTest.kt` | remove | 100644 | `de830bcc8672b66bf3b65b3b9d5f45c01a3fcdba` |
| `src/test/java/com/cattailsw/nanidroid/compose/SurfaceAnimationSchedulerTest.kt` | remove | 100644 | `0b3086faf16f1d522e07db41f4c485cc5e9138ff` |
| `src/test/java/com/cattailsw/nanidroid/compose/SurfaceCompositorTest.kt` | remove | 100644 | `d26a7d53ed12928e7908a7397a4024f47a5b7073` |
| `src/test/java/com/cattailsw/nanidroid/compose/SurfacePointerInteractionTest.kt` | remove | 100644 | `1ed1e2a39c70f72f4da3303196e6cefa7916bd07` |
| `src/test/java/com/cattailsw/nanidroid/compose/SurfaceRenderPlanTest.kt` | remove | 100644 | `e06bd77a74276678f95494a7de6eeffc6b3a834a` |
| `src/test/java/com/cattailsw/nanidroid/compose/stage/BubbleRegionStageIntegrationTest.kt` | remove | 100644 | `198eba5ef3deeb65d3ef1402ba83a210e0c75cf3` |
| `src/test/java/com/cattailsw/nanidroid/compose/stage/CollisionOverlayGeometryTest.kt` | remove | 100644 | `fc8f9fc4fd4a14d961a871b6b7768caff9cbfa5f` |
| `src/test/java/com/cattailsw/nanidroid/compose/stage/DialogueActionSurfacePolicyTest.kt` | remove | 100644 | `b1f3a4fe9e3795acc8ed13b794c651d7ec226788` |
| `src/test/java/com/cattailsw/nanidroid/compose/stage/GhostStageSemanticsTest.kt` | remove | 100644 | `ff87bc680686cd9bc7d9341162b879ee532da65f` |
| `src/test/java/com/cattailsw/nanidroid/install/ForegroundNarImportBackendTest.kt` | remove | 100644 | `e518395f7978f5cc9c3a019726a2baa4bdc01786` |
| `src/test/java/com/cattailsw/nanidroid/install/ForegroundNarImportCoordinatorTest.kt` | remove | 100644 | `4ce0a922d4a6419e4067253a34b992dfe6249b32` |
| `src/test/java/com/cattailsw/nanidroid/install/NarArchiveInventoryValidatorTest.kt` | remove | 100644 | `d2982cea22c465e41b774117fa09caead6b6cf95` |
| `src/test/java/com/cattailsw/nanidroid/install/NarContentUriImportTest.kt` | remove | 100644 | `fca37d1869dae6349abb326b16ce06c1b1bf3be5` |
| `src/test/java/com/cattailsw/nanidroid/install/NarDescriptorParserTest.kt` | remove | 100644 | `2f6be27f9f2802772454359c8cefe10ba7b1c527` |
| `src/test/java/com/cattailsw/nanidroid/install/NarGhostDiscoverabilityValidatorTest.kt` | remove | 100644 | `beba7841fb04a977f5e34fe9487470ef155d08f6` |
| `src/test/java/com/cattailsw/nanidroid/install/NarGhostTreePolicyTest.kt` | remove | 100644 | `e045762935bd527784a364a65408c4410434fbcf` |
| `src/test/java/com/cattailsw/nanidroid/install/NarInstallPlanValidatorTest.kt` | remove | 100644 | `9d311363eb5e39bcf89e3961f150fd69e4d2a341` |
| `src/test/java/com/cattailsw/nanidroid/install/NarStagedSourceCopyTest.kt` | remove | 100644 | `6f7a0348514abea1abb341a53d97f1a071c5d8ee` |
| `src/test/java/com/cattailsw/nanidroid/install/NarTransactionalInstallerTest.kt` | remove | 100644 | `9765b70121c3dfd65c419d02b542f367eb8619ef` |
| `src/test/java/com/cattailsw/nanidroid/install/NarZipCentralPreflightTest.kt` | remove | 100644 | `33ded37be3d08a630e33dcfe2f515b9da91af2f6` |
| `src/test/java/com/cattailsw/nanidroid/install/OwnedStagingRecoveryTest.kt` | remove | 100644 | `35e4ec988a1c7e1fbb402235f3696804a751fcff` |
| `src/test/java/com/cattailsw/nanidroid/runtime/GhostPresentationReducerTest.kt` | remove | 100644 | `583f133b79eb1b685f3bb00c6918d24c30f27c54` |
| `src/test/java/com/cattailsw/nanidroid/runtime/GhostStageLayoutPolicyTest.kt` | remove | 100644 | `20311758b97f67341243445f81d34a30a089bf6e` |
| `src/test/java/com/cattailsw/nanidroid/runtime/KotlinGhostPresentationRuntimeTest.kt` | remove | 100644 | `5cd255a610aa3f537a91a680534ee7b075f34333` |
| `src/test/java/com/cattailsw/nanidroid/runtime/dialogue/DialogueSpeakerOwnershipTest.kt` | remove | 100644 | `5f46a3d4573127eecf633a82c867fa12f170ad6d` |
| `src/test/java/com/cattailsw/nanidroid/runtime/dialogue/GhostEventCapabilitiesTest.kt` | remove | 100644 | `62fd92e68aa39643a9c6f5cc541ec314f622c1e3` |
| `src/test/java/com/cattailsw/nanidroid/runtime/dialogue/GhostRuntimeModeTest.kt` | remove | 100644 | `47594449a1a39fc166d238a22c25f2f1af267760` |
| `src/test/java/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptRevelationTest.kt` | remove | 100644 | `8805fd5f4bf5b7fbc2ec78d3226c37f3e2e93244` |
| `src/test/java/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptTokenizerTest.kt` | remove | 100644 | `910726c9b0f70af28cd51bb773347b79e62d1b83` |
| `src/test/java/com/cattailsw/nanidroid/runtime/dialogue/SurfaceInteractionProtocolTest.kt` | remove | 100644 | `0aea52d498fb4b7c042f8e9ea6baf1c7c88d9873` |
| `src/test/java/com/cattailsw/nanidroid/runtime/stage/BubbleRegionPublicationTest.kt` | remove | 100644 | `c65a874dd796159553c653bbf1ac232f0273ff66` |
| `src/test/java/com/cattailsw/nanidroid/runtime/stage/BubbleScrollMemoryTest.kt` | remove | 100644 | `15b5bc12e873bb09abaa0b7a0f4f5bde1d993381` |
| `src/test/java/com/cattailsw/nanidroid/runtime/stage/PhysicalClickSequencerTest.kt` | remove | 100644 | `dea55f90aff14d0bab2dae875703504563299285` |
| `src/test/java/com/cattailsw/nanidroid/runtime/stage/StageEnvironmentTest.kt` | remove | 100644 | `67e5d6ecab70cfc64cf83824612efb8ff01368c1` |
| `src/test/java/com/cattailsw/nanidroid/runtime/stage/StageInputRouterTest.kt` | remove | 100644 | `94edb0d0eb2d63824a3c0919bcafb6edf7049935` |
| `src/test/java/com/cattailsw/nanidroid/runtime/stage/SurfaceSizingPropertyTest.kt` | remove | 100644 | `7d26f00e2b0b04ff68e3707350ec7a73069c4ee5` |
| `src/test/java/com/cattailsw/nanidroid/runtime/stage/SurfaceTransformPxTest.kt` | remove | 100644 | `16b1597767ca579d29c79c86c423a5d9010cc05b` |
| `src/test/java/com/cattailsw/nanidroid/surface/CollisionGeometryTest.kt` | remove | 100644 | `9c0c625a789814175280662546ea2e5306023c39` |
| `src/test/java/com/cattailsw/nanidroid/surface/SurfaceParserRecoveryTest.kt` | remove | 100644 | `ac75fda44393b19c509721fa06bca83f91a696a8` |
| `src/test/java/com/cattailsw/nanidroid/surface/SurfaceSelectorTest.kt` | remove | 100644 | `acf55d4caa46163cabe0af2dafdaf32ddefb60ff` |
| `src/test/java/com/cattailsw/nanidroid/surface/SurfaceSourceDecoderTest.kt` | remove | 100644 | `0d2ce83dfac026daecb8fa4964965ba23cd09d5a` |
| `src/test/resources/ghost-fixtures/bancho/collisionex.txt` | remove | 100644 | `82ab98cf4c739190fcd2dc417985876b175d7b54` |
| `src/test/resources/ghost-fixtures/nanika-atsume/surfaces.txt` | remove | 100644 | `ce65069222a6614d61fb31b200f8bcc074c942dc` |
| `src/test/resources/ghost-fixtures/snake-otacon/surfaces.txt` | remove | 100644 | `85a5de4f3578f763817fd0a1036479efe4990066` |
| `tools/check_repository_hygiene.py` | remove | 100644 | `ac4744c672dc1d3a26f79dd846423e6f0d82e715` |
| `tools/normalize_satori_source_encoding.py` | remove | 100644 | `2361265c769ec267dd3e448684865bdee9b0c000` |
| `tools/test_compose_stage_layout_contract.py` | remove | 100644 | `863f52c0069fb03eeaf94830b193dfe45a3f768d` |
| `tools/test_compose_stage_retirement_contract.py` | remove | 100644 | `c7ff25dc4ce17962f049c7492f8deeaa90f7f392` |
| `tools/test_ghost_runtime_composition_root.py` | remove | 100644 | `aec6b12c059786854e51d077c85e19cbfcdeb0dd` |
| `tools/test_kotlin_compose_renderer_contract.py` | remove | 100644 | `1787f360f5840eb49af43c47a005d91bbaf71a30` |
| `tools/test_kotlin_echo_shiori_contract.py` | remove | 100644 | `e74ca2a3c8b9fcf868d1d079240f811d7f13d071` |
| `tools/test_kotlin_foreground_nar_import_contract.py` | remove | 100644 | `3285ce09f7960b24d2d52a220e3d14b1a6475322` |
| `tools/test_kotlin_ghost_discovery_contract.py` | remove | 100644 | `6b41b69518069e64ccdda3d7cf22c749dd13c960` |
| `tools/test_kotlin_ghost_domain_contract.py` | remove | 100644 | `0c02914203d08f8efad62b729c302ff89cfd385f` |
| `tools/test_kotlin_legacy_archive_runtime_absence.py` | remove | 100644 | `59a8530a7d8bc23024f298999d3003dd70dc959d` |
| `tools/test_kotlin_not_supported_shiori_contract.py` | remove | 100644 | `d5c149e1e731c62b438809815d19bc11105f0016` |
| `tools/test_kotlin_presentation_frame_contract.py` | remove | 100644 | `420a021824cb22a4ba7819e690a60abb0a7830bb` |
| `tools/test_kotlin_renderer_contract.py` | remove | 100644 | `268735305cbf5a4ae50b7ef66523267e6ec6070e` |
| `tools/test_kotlin_setup_contract.py` | remove | 100644 | `1ba4ac677bcadd768dbf52f15ec1329a8fc8602e` |
| `tools/test_kotlin_shiori_contract.py` | remove | 100644 | `6de3e526fb847ef757f5d10c08b85aba89415903` |
| `tools/test_kotlin_shiori_factory_contract.py` | remove | 100644 | `b932bd447d95aef47e58afe7f8b2a9b56b3c7463` |
| `tools/test_kotlin_shiori_response_contract.py` | remove | 100644 | `13650c126eeaefa492cbc34253d556a654ba2e44` |
| `tools/test_kotlin_small_utilities_contract.py` | remove | 100644 | `94400e4df846a60232ba0b736de9243c72c47cd5` |
| `tools/test_kotlin_surface_catalog_contract.py` | remove | 100644 | `eebf9b27794fbdf0d21e2821b5eca7bf22d38ebf` |
| `tools/test_kotlin_surface_reader_contract.py` | remove | 100644 | `677147bc9f913596e81954fc37a29c249e31697f` |
| `tools/test_native_shiori_contract.py` | remove | 100644 | `98aa30173412e1daed1ed9362960f822acf0d0a1` |
| `tools/test_normalize_satori_source_encoding.py` | remove | 100644 | `a5e2cdff681eac7674cd7ac7a984d11564b98919` |
| `tools/test_satori_include_casing.py` | remove | 100644 | `3d6f946a46e71db3afcc7d1b915111f6269fd9c6` |
| `tools/test_surface_definition_compose_contract.py` | remove | 100644 | `4a43e7f78d57b098f3d15061bc82cace454aedb2` |
| `tools/test_update_entrypoint_artifacts.py` | remove | 100644 | `d12a67697049fe478b75ff097637b227ea1046aa` |
| `tools/test_verify_phase1_shipped_state_audit.py` | remove | 100644 | `ce46a05066dffe47ae12a2b3e41876659ace9e9f` |
| `tools/test_view_server_lifecycle_kotlin_contract.py` | remove | 100644 | `2059cb9fac6e1986f0bcad781ad52eaf6ee0beb1` |
| `tools/verify_environment.py` | remove | 100644 | `39c77c70757f9cc79faac071c849e55ea6afabaa` |
| `tools/verify_phase1_shipped_state_audit.py` | remove | 100644 | `1b58abcd57a0d864a325939e674051de66b51772` |

## Complete committed source transfers

All 505 selected frozen-source blobs transfer with their Git mode and raw-byte SHA-256. Supplemental manifest and fresh candidate instructions are listed separately below.

| Path | Disposition | Mode | Source blob | SHA-256 |
| --- | --- | --- | --- | --- |
| `.gitattributes` | replace | 100644 | `49eb1a8648065b74d8a4682cf60094905e322f79` | `8c02f991d94d534449e43efa26ffb3bb9e0eea38a859e75b1b860d1f42ff9cba` |
| `.github/pull_request_template.md` | replace | 100644 | `0e6b497e2feeb2dda7da33a4464122263adbe3d9` | `b0f87de39739c8ecbe5d3308d0dda17adbc86eb028059b7a5f6175cce368214c` |
| `.github/workflows/android-build.yml` | replace | 100644 | `4b7c75abc380e10d1af00b7827b099e1cb24389f` | `b103b8cb433ad8cabd4c20cbcacf8ba0757fac7fb66934791b99b24d90e59bcb` |
| `.gitignore` | replace | 100644 | `e1d89d673bacd33d3d249fe2e9e1f232cb3d4ab1` | `ee786d0d3dfbbd62e759d8bddcddffa2dbf2c14f3652ceeaefcad4727a6fc6cf` |
| `README.md` | add | 100644 | `7580b2950eb4f77f4b6a614bb17258d5a7e2a09f` | `5d349e119696283647065fd33c30d55cc154069e1f364347507826aa094518e3` |
| `app/.gitignore` | add | 100644 | `42afabfd2abebf31384ca7797186a27a4b7dbee8` | `5c3174b74edc6f2b9e0743594b8bd3c7f31e4f4405758cc9872b3fe1de1d28b4` |
| `app/build.gradle.kts` | add | 100644 | `e22a65dfc1e94b63ef252b2d43f2bc7d305e6b7c` | `5309d4dd2ae8982eb9ca087a284b7d16ea1af87ee96a895263370dc5d1ba5ace` |
| `app/src/androidTest/AndroidManifest.xml` | add | 100644 | `cda38c92c69966d9eb7bbfb660fd0ed9d397efc3` | `ef52559fb955fe4a9b431ad3e209d8d8f08ecbaf8d2643313c6d4390102c5e9e` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/StartupRecoveryTest.kt` | add | 100644 | `13354c18f8b5ab651d1b7a7f8662009c36cbfd57` | `a8d78ac7b5def379244a77601e74f136e72794417a456ff6322733106ac24673` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/WalkingSkeletonInstrumentationTest.kt` | add | 100644 | `d395e345a5153f8c02a166507b889103265b1aed` | `4543083c8b53997db3797fda8065e35f3facf069ea493791db029fa2aab1ec1a` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/corpus/Milestone5CorpusTest.kt` | add | 100644 | `468f92df30ee91b6e13ac4c021df526a5d37d2a8` | `5cf217727f1edac3fadb7253ca21c6b92f27cf6b3ce3372f3aee4a58bde06aa7` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/engine/NativePersistenceTest.kt` | add | 100644 | `1358e85c5d1268554e8028dcb76c35a23d7f5e1c` | `b27602fd1fbfd9b695224df3a5573c8247d6714238faa811ee34aadb20ccba61` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/engine/NativeShioriRealTest.kt` | add | 100644 | `6ad89b13f46e11c43023c2a9feb0855e8aec338a` | `0b54b16606d274e717dd9a422b4e18cbc6fd439958cbad7c181678b0b1d0c9ea` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/ghost/Milestone4CorpusSurfaceTest.kt` | add | 100644 | `99a105ff069c98beb2f12d43abadbc29d56ad96f` | `d51ff6b4790c55907b9644731357d8f0ab1cbf0821334f3924aa957c1b25707d` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/ghost/NativeProfileDirectoryDeviceTest.kt` | add | 100644 | `1d931b8524e61f35a72b5751d8f18417ae17cf03` | `1494042c8b639b0f800d92f4770c321beb53fd83f6e27978397d53afa59959bc` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/ghost/SurfaceComposerTest.kt` | add | 100644 | `0dbcae73d67d2f78e5ebef60d72727b6a2ed586e` | `d26c1e573296f647c0ef9b53ba75eb57ad3dbd50ff35119b8cdd4a55898d1cec` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/ghost/SurfaceImageLoaderTest.kt` | add | 100644 | `bf0d690102cfecba32b72d4a542defe9aaa3ee6c` | `02b202f2051e5aa2cd6a61b6f4013f265d192016fa0ad1662ee77813e0523fcc` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/install/GhostImportInstrumentationTest.kt` | add | 100644 | `9528aa48364c73fcd2e90be0f23ea733ee40ddc8` | `5ff5af9e43bd72713035b63ec9ec243acd8bedca491f954e9517cbe8677c70a3` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/install/GhostImportTestProvider.java` | add | 100644 | `38d28b953622be00e3ab6f10764a59eee1eb81c4` | `c407090c79b7b213fcd2c7d099be036389f157ded063aaa705fddde7386810f8` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/ui/AboutDialogTest.kt` | add | 100644 | `01aadc179ae21f05b6044373fddd05fe36a0662c` | `ebe27bb9c312e1f1b627c21c0c18b90f52741c0a1b860764b6e30c1b8f6203e6` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/ui/AnimatedSurfaceTest.kt` | add | 100644 | `81d361e55b82d36bc0cc756d49c290c599f28b61` | `ca4188831706027e01b06b07b7851c9872ba13fb0d6a619d4d1a0cd949dc797c` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/ui/AuthoredSurfaceUiTest.kt` | add | 100644 | `a752701807f36a64ef6a578df91bcec2d85e6042` | `7fff88f8f5a5567b76dc1124705bf2b8e104c9b79bba73b05d7a1caad30a38ee` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/ui/CharacterGestureTest.kt` | add | 100644 | `e087441686b5a1cd8e9d5e1424e72c0f6b18907c` | `b5c784e3bb08cd6de39551370aed12479594134ee4f13aa56fac96bf2b0163ad` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/ui/GhostImportUiTest.kt` | add | 100644 | `bee96ccf0664312326ef6e79761a2b4385878df2` | `edc8639c43d89f5ada6c1bc14bb6ca19cb78c2954ffd78738624a7a688aca739` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/ui/GhostInteractionUiTest.kt` | add | 100644 | `b10914043ccd21d9bca38ae40216cb6806088f4a` | `cfbcbcbf930d55e1849d2be8dea36c90a8a626d3e74726d332a28f3bc689c538` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/ui/GhostStageAlwaysLayersTest.kt` | add | 100644 | `62094ab1ed12b23a09d12843a527bd63a427e9f6` | `dcd5a7e3adf28ba4958bcc34f8ba15202d35a7a7439475e4b697d73a034a8a5d` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/ui/GhostStageTest.kt` | add | 100644 | `96e8f55f90b5b33946ae8580143f1c25d85bd4e6` | `752b29e12755fabf6f2729c89390ecf27d710c1f89a2ce77f1a86880488b60ec` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/ui/Milestone4CorpusDynamicUiTest.kt` | add | 100644 | `d823ed3996fe30d0d96b6b24a3531b16256a5f50` | `41f0c5c4af3089d962dd921ee8e4025e38fef527508c87736d373b12c585b3c3` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/ui/NativeRotationTest.kt` | add | 100644 | `e9d4544c818ea63ae0be55402b7e13ef0f591d98` | `c5376362313745e0f02683209113f4f1d9c52a0a9f48170704ae7dacff82f350` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/ui/NativeTalkProbeTest.kt` | add | 100644 | `e7f2f281519ab4bf60f21aea693aabe461c31d91` | `4318b29f9122a13f3fb32d263b02ced85e1c9e4e986ca8a9ca3a8e6f9f79d751` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/ui/RealGhostInteractionTest.kt` | add | 100644 | `70b46f3bf110054a9822d2c3efc52f396c463177` | `c156514dab45dae54b8c3c7f0234ae8d91eb7fcc30a650882c727b98c64b2044` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/ui/RealSatoriDoubleTapUiTest.kt` | add | 100644 | `31a60111a6ed1fdc015747e5ffcc36fe3ff585f5` | `c6c6d1c0b12771a69833ac9c99afdc8374a0ca531af4d1fa2a22e6072fc3df89` |
| `app/src/androidTest/java/com/cattailsw/nanidroid/ui/StagePolishTest.kt` | add | 100644 | `a955d0034563c625e9c95098cdc8cba8e9f7a54a` | `90bdbe21a459d1400d94624870ca7373061a45330125bc890cc212d847110c51` |
| `app/src/main/AndroidManifest.xml` | add | 100644 | `4a4463e7be2f70ce11e4e1f72efa73155ab32281` | `da03c17b43797623dfe6fb75eb4d5491a22328974fc95fbf748fd20751f7c14d` |
| `app/src/main/assets/nanidroid.zip` | add | 100644 | `f01abd7497107a075e7e6955741b11165b8bf277` | `2ebf24a2be8255011c5e004459a58dd1317eb7b12a55adcbe6b8b2977c89dc2d` |
| `app/src/main/assets/notices/androidx-apache.txt` | add | 100644 | `e454a52586f29b8ce8a6799163eac1f875e9ac01` | `809fa1ed21450f59827d1e9aec720bbc4b687434fa22283c6cb5dd82a47ab9c0` |
| `app/src/main/assets/notices/bundled-ghost.txt` | add | 100644 | `e54ea9b4ba50d04c53ca1e0f01bb3e967ab84c17` | `969a69b634c43a04a62fc47a6cbb873a4a5241791a92aa9b712f73334f91a5d0` |
| `app/src/main/assets/notices/commons-codec-LICENSE.txt` | add | 100644 | `ff9ad4530f5716304e042cbac72cecd7c40f0e0e` | `b1d2870f1a00e4d7f56576e5f0870cba109e041f8af66866cf5b499478e654e7` |
| `app/src/main/assets/notices/commons-codec-NOTICE.txt` | add | 100644 | `d3b64e9e3008ab61bb1ad3e155bf514773a7b08d` | `b64933ee1d36d14659156223a2604edadb60bffdd465d5368ff422d7689db5fb` |
| `app/src/main/assets/notices/commons-compress-LICENSE.txt` | add | 100644 | `b447376016c314574ee4d4b3d3ebd6eaf2972f07` | `51b88fd3e9e24edcdce1dd145a4fca2dcd12911407f00b06dfa6ff600f9733c1` |
| `app/src/main/assets/notices/commons-compress-NOTICE.txt` | add | 100644 | `fa969b3ce6ca657974f25411dbe3b273950121dc` | `0d98967f0ab328af8d9d536aa9a5c684b5dd665657245f6ee6b5915affe811c0` |
| `app/src/main/assets/notices/commons-io-LICENSE.txt` | add | 100644 | `c8118641cddd58f5b8f175c4ec91b8e69b65e97c` | `c1d1a38c99c48ccad170890ade6066e2e240bb6ce59861d4eb7f672eddd2a4c6` |
| `app/src/main/assets/notices/commons-io-NOTICE.txt` | add | 100644 | `2a4682551b1f9b07147b82f7435661396f5267b8` | `b54b0db6c617273efff79ba193bb01b05eadcddfff55c24063b38b2acef714c3` |
| `app/src/main/assets/notices/commons-lang3-LICENSE.txt` | add | 100644 | `ff9ad4530f5716304e042cbac72cecd7c40f0e0e` | `b1d2870f1a00e4d7f56576e5f0870cba109e041f8af66866cf5b499478e654e7` |
| `app/src/main/assets/notices/commons-lang3-NOTICE.txt` | add | 100644 | `9c0ea0be638b9b0ea11598bd6b20f42a23856434` | `64bc8696af3f6c770521412b85d3a733fa44b2ef5b0f4bd739e0c4d02eca492a` |
| `app/src/main/assets/notices/kawari-mt19937.txt` | add | 100644 | `7f9931e8060cf5e1d3398b8494517abb49280b65` | `339a66906223fcc5d7e4d053091e682930774a0010f7d1de12939dbe1cb152d6` |
| `app/src/main/assets/notices/kawari.txt` | add | 100644 | `04b86611bcc1cc5273cf080448745d0b8db3bce3` | `b6423d75ba37b6a6fd65e4db3ba4f61f387798ed27018ca8d04e2246227e9689` |
| `app/src/main/assets/notices/kotlin-stdlib-boost.txt` | add | 100644 | `127a5bc39ba030c7cb99cc0aedc4f280ffe27310` | `8d8291caf1cee26d23acf3eb67c9f9a2d58f1c681b16a4fbe8cbfb9e3c0b5a9b` |
| `app/src/main/assets/notices/kotlin-stdlib-copyright.txt` | add | 100644 | `8dc8226f64f07dc5134d3674371cbfe109465cd9` | `8f1a1700adb5490604c6094fb137bf73f6c28d3ee554b58f3ff8ef975a79672d` |
| `app/src/main/assets/notices/kotlin-stdlib-gwt.txt` | add | 100644 | `d645695673349e3947e8e5ae42332d0ac3164cd7` | `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30` |
| `app/src/main/assets/notices/kotlin-stdlib-threetenbp.txt` | add | 100644 | `bbed3563e938dd26bdc2daeb5f5cccdd0f03eb61` | `d1bc53b493a3ab387b42717ed5c4b1976a5048996f81154278100bff86d39331` |
| `app/src/main/assets/notices/satori.txt` | add | 100644 | `d3a04bc2a77d5df1d42097d27c7152552f584111` | `1826dd506afcb4f89abce4d51ce488df1db7a2cc59e8e72783ee879e4f346d29` |
| `app/src/main/assets/notices/yaya-mt19937.txt` | add | 100644 | `24916886fae410c825b2499d57168fb640f0c52c` | `7dbcfaf05e0c664f6c3d89843065c04da8f22a84fd1a744e4380bc53acd6caa8` |
| `app/src/main/assets/notices/yaya.txt` | add | 100644 | `7716b63908f965f88c2b9744c4178ace96bdc4ce` | `e9c83997436d25c295aa311aa836a7873a06d45ddd6bd78b005ed465bcc00904` |
| `app/src/main/jni/CMakeLists.txt` | add | 100644 | `55af158bb3565039ff8a40f81bfe72205deeb553` | `1eeb1bfbbe22e00d1af8124d0581f12bb36c9623b7821c5e5c0e24bc5e39811f` |
| `app/src/main/jni/_/Dialog.cpp` | add | 100644 | `d48595e8f1beec6e273ddaed94795d13db32e549` | `0ad9ecf5ce47771c2095eef5c4b13081b5e9f6c5e56e14694f24d1f1a8f05bfb` |
| `app/src/main/jni/_/Dialog.h` | add | 100644 | `d023fa4f1bae863f6a342c9eeca21fd207311dae` | `6db83f996cb17ef1c176b3b4e0e0d3c1c07bc67d7daccb4bc66d3167f3393148` |
| `app/src/main/jni/_/FMO.h` | add | 100644 | `e84dc53c9bf0b1a70e7f9f1146d729753eb57ce3` | `059edbc1ae23dfeacff3d46fa703998d2af8ca986b805850406a7aa2df4fdad2` |
| `app/src/main/jni/_/Font.cpp` | add | 100644 | `c448f93af880ff24dc6ef842ff3df0dd1546f8d5` | `2068a7a80a2a3616d6441627676fcc59a85696237febbfa40c9c69d5387acd78` |
| `app/src/main/jni/_/Font.h` | add | 100644 | `c4c650d1784dcd2b7be67ba269e652c5e291ce46` | `a88340e826716581bce245bb1783af97798a942e3b18b76e44d5253a51e86d80` |
| `app/src/main/jni/_/Sender.cpp` | add | 100644 | `7e630396eea84e14996d3e619cb2b7cd79b931a6` | `b7b410d2407e00d006dfd7f86b25975c2186f394767829eb4d38c6ce62de1807` |
| `app/src/main/jni/_/Sender.h` | add | 100644 | `bb0f4c6630ff3c42508bd3e6ef2d624594a294e5` | `072506b31b18f8e2bd8ae729b3ab4fde9531bd06836c725e90cfc44cc3085f47` |
| `app/src/main/jni/_/Thread.cpp` | add | 100644 | `efa9bc339c63cba9e1bdda1f06f731175bd9aa13` | `f15001ff5a27656d3106eee13bcecfae0acfa5162961a39b3ed05c5abfbda23a` |
| `app/src/main/jni/_/Thread.h` | add | 100644 | `69b0ace79cf5bd15a854052f5d10aabfb67cbbba` | `5fc819ed69ab9ca56b076d22d6d99cfef3801b34a2990ea8d24c1ebed3698774` |
| `app/src/main/jni/_/Utilities.cpp` | add | 100644 | `e81855e54d76d75f38bde4f2d17265eb95b9d908` | `844fc5f9c581b49f88409068892c9e735817b57374b5ace74d0e8b8d69df2aa3` |
| `app/src/main/jni/_/Utilities.h` | add | 100644 | `1675efeb60308e19030d09e47509deea06b1f68a` | `4821eebdbf1622e01118b5b416c6269ff42dc322b4ea7a134299cbc55984bf10` |
| `app/src/main/jni/_/Win32.cpp` | add | 100644 | `1286ac3f37b1aab57e1870f12139fdca42eabe1e` | `fe0afc46ca6b580c264e6eb3c454e431c4fe3b56a45e3e972a9fe6c76de4daa1` |
| `app/src/main/jni/_/Win32.h` | add | 100644 | `eb8e7e06a8ddb52a7f781ab9036525ce5b60d0ec` | `30dd3def1dfc51dca79f08796d37edb61f20d6d62e115d0099b12beab12dd3fe` |
| `app/src/main/jni/_/Window.cpp` | add | 100644 | `9d553ac88a981bf78d32910e511438abaeb342a4` | `cbde2f063bac67fcdc1e53952284dd35978b6c22febdbc56fdebad4bb6e6df98` |
| `app/src/main/jni/_/Window.h` | add | 100644 | `6a7ede087c0c131edde1a63a9da7b0b4bfe64969` | `5486ac0db219f5d480f2c290807ed249ce26a2c9639c70589e84cc906cc62fcd` |
| `app/src/main/jni/_/calc.cpp` | add | 100644 | `e1844ca69dbec7da8619bbbfd3fcda30424514f1` | `6f5cad4e9f28cac2e3fc7798894775fbbe6d5fe30bf139194021d1c0d75d6ad7` |
| `app/src/main/jni/_/calc_float.cpp` | add | 100644 | `396277703ed95efc97cc80110ae1f223675d7ee8` | `71e71ffd221126638e5d91183788bee1af36cb0b6e8cf3b591ff1e6af279bf41` |
| `app/src/main/jni/_/simple_stack.h` | add | 100644 | `7fbbcf28812cf13dabaf24ccf7bb1aba4a27c0fa` | `f27be71f6b80fee474b94b86fd8fb27a86bf098aecc039da9803f3e262b03608` |
| `app/src/main/jni/_/source-literal-manifest.json` | add | 100644 | `74b35daf95622c7362b0f694432ae51d2daf90ed` | `fe0f52663b82012fc5528969904e953d8e1b10ddae75a514df4e47135e500c60` |
| `app/src/main/jni/_/stltool.cpp` | add | 100644 | `4db2340bdaf67b6ca5eaae6dcd1f357bd0c368c5` | `328f9f6d2f26938c777952d80e77f0c4f03941a90b4770cb94665f6c1a4ddb89` |
| `app/src/main/jni/_/stltool.h` | add | 100644 | `bd3506c1b5cdce35fc7d2cc35d87a2ac38210f4b` | `dfd3cf2c8ad980916ad7f559c2bab42ba5ba2e0b6622c8b46f8afaf7e944ba62` |
| `app/src/main/jni/kawari8/Android.mk` | add | 100644 | `458563c8ade200fbd8149d28f818603398147a4c` | `58ed7c4091bf7451ea796913d6d82ab632c13606d73413bd59800407f4f8e0ca` |
| `app/src/main/jni/kawari8/bcc.mak` | add | 100644 | `077fe41670701581084f0298af77375dbeb453a0` | `9e9568f517ba6d146fdda288180e75a04dfd51162c0ac7f02d236947775d39d6` |
| `app/src/main/jni/kawari8/config.h` | add | 100644 | `e9a402cf0e85542f9834f778b03e4a76088d487f` | `db5ad9851a7588960a13e2fc79e952b3b15b37467a59605dd6d43e25cb573908` |
| `app/src/main/jni/kawari8/depend.mak` | add | 100644 | `d8ea590d72392fea1b22a71e3ef3bb5b2a67380e` | `3affe08248336e36d9aa111b3785da783db4d1b5e78e97d1e9d476f8a93fa50c` |
| `app/src/main/jni/kawari8/files.mak` | add | 100644 | `3ea39a0ee3888c4c2be0f40ae5f7da06a7e47048` | `ee349e8dec4eaf82c6dc74facda98da2cb37efc7ea85abf83e9bfd65acfc1152` |
| `app/src/main/jni/kawari8/gcc-mach.mak` | add | 100644 | `a9d1fe4391aad07efe60ca6939134f2c7824a1eb` | `5a4cf7a872f6778dfe771323c958d34f202cfd5de2ae28197b8c98089a70af30` |
| `app/src/main/jni/kawari8/gcc.mak` | add | 100644 | `dff6f89da535a9bda559ab4edb3253977a94f136` | `9a304038702ac72a8d72be7442e0fd4e077d4b59f8bfcf8b606977464c3ecd5e` |
| `app/src/main/jni/kawari8/include/old/shiori_posix.h` | add | 100644 | `a8b592ed3aa8f5938da1e83a3b03a4c6af397ab2` | `b6bdce6fadd7cd44836ca6ca53d5ca4ce7353bc4c26017373e6387df0c391fcf` |
| `app/src/main/jni/kawari8/include/shiori.h` | add | 100644 | `2f08f4394ce9efbde5d36e1d15e62c588bed7c36` | `7a1a2e4ad7935039deb864f65da76c72b960d708f7e032f501b42dbdd53cce7a` |
| `app/src/main/jni/kawari8/include/shiori_object.h` | add | 100644 | `5ba2116b4ff13f6cb6c39725f6ac69996f408f72` | `9e33ff02fbffeee4940ad3f8a65a8722967375e8b34614cc96ac3cf5945c5663` |
| `app/src/main/jni/kawari8/kawari_jni.cpp` | add | 100644 | `5746ad5ffa18859e9bbf6724aad06f7c2de856a5` | `1234140368a3800726b4283ae45c1ea979423d2772c96f80801e862033ce0563` |
| `app/src/main/jni/kawari8/kis/kis_base.h` | add | 100644 | `7fc0f739f45bdf75960dfd0481a2819a297ff772` | `7c6c4f70321a875360ed920fb2da54991d3c1de6f4a25361536e77c40d7ced51` |
| `app/src/main/jni/kawari8/kis/kis_communicate.cpp` | add | 100644 | `b975ee4293865ca800d732251bface8b7101839d` | `ffe53d06ac538d87eb319352708713492e1bda9e07735d2bd6f8a0ad197c3292` |
| `app/src/main/jni/kawari8/kis/kis_communicate.h` | add | 100644 | `25068e5f348349e28f1bb5685e0d57ba7937f1c9` | `429123cc8f7d9ef4ce435bf37bca35defbca16f7edbbd9f5434ff587f92a769d` |
| `app/src/main/jni/kawari8/kis/kis_config.h` | add | 100644 | `dd8e4aaf4c96fff5aa9181bc325dbf8e277941c3` | `d26cae0280e9f9398660b60a1f23b2bd3a0dcf572335740d7b88f67910dee757` |
| `app/src/main/jni/kawari8/kis/kis_counter.cpp` | add | 100644 | `dbb97baaca81e83bf60f567cb7f051a697ae9403` | `faec9a5cd35ad20535a7aa2b9fad0c1e8004b2924b46ad5a3b7caf23506a2f05` |
| `app/src/main/jni/kawari8/kis/kis_counter.h` | add | 100644 | `07d64843572756f244ef73cfca0e110d77f1df1f` | `c4112bbf3b8020c342223002d71c0f8dde7a1e42b06f661f896800efa63ec6bf` |
| `app/src/main/jni/kawari8/kis/kis_date.cpp` | add | 100644 | `2f73c8e76bef7b2926bf945bbd533ec004ca235b` | `ee6bb12599cc7634b999de3709000f64639f60534a462f3f1d243c8b79438dbe` |
| `app/src/main/jni/kawari8/kis/kis_date.h` | add | 100644 | `db795bd20181d57f72e95601a0da9f2d764e3b63` | `9e915191cda56d9ed951219c0449d7ced71e2a6381761cb8baa8ccd62a3ddbda` |
| `app/src/main/jni/kawari8/kis/kis_dict.cpp` | add | 100644 | `5ad1eadbced119a3b9c8da5874ce72944115c918` | `8eff9805d89a7d31a57e05735c32989c22b3c3c33735b1c0d44bccbabc2e829a` |
| `app/src/main/jni/kawari8/kis/kis_dict.h` | add | 100644 | `b3f202ce859b504ebb70002a30c34866e5c1b049` | `ad674a7315d9ae1354c3db7a6dafd41e16c57a1b5885dd61fbe0cdcfb7935bbd` |
| `app/src/main/jni/kawari8/kis/kis_echo.cpp` | add | 100644 | `7096c968ceb94f2ad23366ce3c3d751bdbbf9456` | `807b8b20fcd705cac1d6ad557332af374c5be5e4663504cff265d07e2ffb06e4` |
| `app/src/main/jni/kawari8/kis/kis_echo.h` | add | 100644 | `9dcba3637a15b19846ccdc025df188ec404a3351` | `aea0c80644db47e512c7156d3dce1a49d80c3a463728ee4f8aef03f92ea3eb01` |
| `app/src/main/jni/kawari8/kis/kis_escape.cpp` | add | 100644 | `491f1f4d6aee822433158307eafd8b32c358e19c` | `47f0557e3cfcecfc4ff6cb255bddd30cff64b8fe820c9e8789cf62e44326e0ff` |
| `app/src/main/jni/kawari8/kis/kis_escape.h` | add | 100644 | `cb27367f4121f629535629f1389061cc06345071` | `2f4b6f9b7076e2854b936086433e019f87be530892268c6e8e98625210411f58` |
| `app/src/main/jni/kawari8/kis/kis_file.cpp` | add | 100644 | `0eacfae431c775bcfb963db065165bfd87caf19a` | `ac52d3c575bbe29ba6b3b71dcdf7cb4301be7afee55ebd67f95f2cf7db461ea4` |
| `app/src/main/jni/kawari8/kis/kis_file.h` | add | 100644 | `d9fb62c851cbd85ca4a0d46926dd24e4913aecf5` | `05c39375d294665b4bb0493dccebb6b8d7a2c0b6ce1f98f4ceb08a7094bf8e2f` |
| `app/src/main/jni/kawari8/kis/kis_help.cpp` | add | 100644 | `c8ff2fae1cb257d6e058dab2a11df276e9517b77` | `2107e57a34d4be9c4f4d384ae4089f037469b9b0ea62ce6034dc154a90911bc7` |
| `app/src/main/jni/kawari8/kis/kis_help.h` | add | 100644 | `2f58c42934977277159c780bfac72d923e198285` | `33582fcac6ada1c0d4023e1ae5c6e1e0a18da1d064c03954367d56f3fa4f4ff6` |
| `app/src/main/jni/kawari8/kis/kis_math.h` | add | 100644 | `d4e3915f45fa8d810ae953ba7f6fa4816a42e357` | `d25426504327a5da732ff701aab0704198148ce38360c43d33f41686fd2cd6d6` |
| `app/src/main/jni/kawari8/kis/kis_saori.cpp` | add | 100644 | `d8173b82ca912fd94cf2bba8fbcfd45b127385f0` | `a18f2e0bf11f4b2e8d067beb0c242395616a1d2eec5320e533db4d90eea9da0a` |
| `app/src/main/jni/kawari8/kis/kis_saori.h` | add | 100644 | `a10c5950e49f305917195ae436df9e099d63dcbb` | `d6278c85789aaf0cabcbf1e700baae7a8af21e4cab4d4add6f38994127fbbf31` |
| `app/src/main/jni/kawari8/kis/kis_split.cpp` | add | 100644 | `7272f26115cb35f65020b467514a1cac8d9b537a` | `8b5dfd4d7f1a7a2380006b0ad04c60c74b31516c9f2c51ef773866fdc2a890be` |
| `app/src/main/jni/kawari8/kis/kis_split.h` | add | 100644 | `8fc26fe8c3673b8b93987a8cf5cb8f167ba1868c` | `cc7bfccb9b75ba6d86da485bcc078b11ba5ebb9da17bc995e281aa7722712f35` |
| `app/src/main/jni/kawari8/kis/kis_string.cpp` | add | 100644 | `2ab199ffa3cbfe7760ad23e206d2ef02417f919d` | `42fea50b72f1a02870d72ca62e22159c3ad99c16c44d46e731afb69d2643d6e7` |
| `app/src/main/jni/kawari8/kis/kis_string.h` | add | 100644 | `51061c746613f705d0d047617a7ef24fc13d3ed9` | `8ca4428a00c7e372cbbb16b5d1beb3f0cbfa9bf1982cbce293c5d09556c64a72` |
| `app/src/main/jni/kawari8/kis/kis_substitute.cpp` | add | 100644 | `f53783302449e408162ecaa18540bcb6e824bfa9` | `ccc0f60d69eb90a1e010a2a8268a5309136668ffbe3a64511b055f4e6c812a0a` |
| `app/src/main/jni/kawari8/kis/kis_substitute.h` | add | 100644 | `527c60f8a02c7de0dd8f9c7d85b18f683c8d2ff8` | `91726b152d70d9d98b143aca298c2744f5d054b812dd247e14d2648624fed51c` |
| `app/src/main/jni/kawari8/kis/kis_system.cpp` | add | 100644 | `3590fd69ab490e4d013d84a36db4aa2ff135d63c` | `1c323ae1248dbda175f682ea2b03fc46a7f382d1aaa3550ae9ff21be70bef35e` |
| `app/src/main/jni/kawari8/kis/kis_system.h` | add | 100644 | `ca14dc1e5afc160691875ffda65fdf43b6f84361` | `b2e1d5eb708cfe49d727c057e967bf68361f67a3aebe3c3359a5a41e802d338e` |
| `app/src/main/jni/kawari8/kis/kis_urllist.cpp` | add | 100644 | `ab619c513ab904be6f2aec0897595f9cafb52714` | `b0ba63c13ed335c19d80fb121a7cae93d4f05b2d86d3411721435d0314059c2a` |
| `app/src/main/jni/kawari8/kis/kis_urllist.h` | add | 100644 | `16efb0fc53e5c6ba43787459a9dd899b5c8a917e` | `e15dd5f2198e520c68b9d6a03249c443d353f2d6be287dc053b38f030d901dbe` |
| `app/src/main/jni/kawari8/kis/kis_xargs.cpp` | add | 100644 | `6b73a40356c7e62046cf76ce5f8de47cd981221a` | `321a3482d644937219000bc5c255ced80e83c12dd28dd061d366c71d1d8df349` |
| `app/src/main/jni/kawari8/kis/kis_xargs.h` | add | 100644 | `7456a91aa4e67387cc9f3882b06d91ca17bc5095` | `3a5ba6f7109259f8464100bed08b38dfb286e9e3bd5ec560439b93df82425aff` |
| `app/src/main/jni/kawari8/libkawari/kawari_code.cpp` | add | 100644 | `af1cad28d2fb71d6b2bb1242756b3f1c552f4cd0` | `ac6dfa0cd15d0490f74d7789e6335be481c33f208eb970d0c4824a7e7b972709` |
| `app/src/main/jni/kawari8/libkawari/kawari_code.h` | add | 100644 | `fee212b70737060149c6d53dfe5e77949407ce29` | `e203cbc22e227a5aab42fc577a553ad2b22dd9ef8b15f5b506c102e4c729cd12` |
| `app/src/main/jni/kawari8/libkawari/kawari_codeexpr.cpp` | add | 100644 | `4bca57b02fc2312abf473d0c46726426412bc07e` | `92c813b3478de8fad56054ece38fcb37dab5028414e9276c0d41acc20d899f0b` |
| `app/src/main/jni/kawari8/libkawari/kawari_codeexpr.h` | add | 100644 | `043637600d18739a7ea51433bd483900c19caabd` | `4a36af75799596146268283523f6980862af5ff59ab3c06baa430d73dcbb1714` |
| `app/src/main/jni/kawari8/libkawari/kawari_codekis.cpp` | add | 100644 | `643edb031d4ff55bf1a923571fc381635350d2d9` | `2726fb22e02f2154965cb7223478100e61e937803b5ae1e8c3a2d476aceb09e8` |
| `app/src/main/jni/kawari8/libkawari/kawari_codekis.h` | add | 100644 | `05029bd489e10d73b2793ec390083d8d4063c246` | `94069e95d2b02a9609794aa9e6eb46dd0ce6080641aa8158d8d9a21e5dc3e24e` |
| `app/src/main/jni/kawari8/libkawari/kawari_codeset.cpp` | add | 100644 | `90a7a19c16451abbc8bd6a8da810bea7fbd8dc66` | `9456e2fd6c08211407cff042e35b7f675b67fa7fb6e7300cc4533fe53b3af469` |
| `app/src/main/jni/kawari8/libkawari/kawari_codeset.h` | add | 100644 | `77c9819b4275202d3b7f58c763a226e048273717` | `cde45b81bbd1cd60a6f76d3a932600546c5d0dc71b11ad31869a1318ebd6abd5` |
| `app/src/main/jni/kawari8/libkawari/kawari_compiler.cpp` | add | 100644 | `85ee3286c4e12229a054f177154523f6b59e1c8c` | `28ead6accd063d6cb803c99c168f12291a04a3bcc6ca9e076aa406f44c002417` |
| `app/src/main/jni/kawari8/libkawari/kawari_compiler.h` | add | 100644 | `388a5a82c59f3e0f65f8824d81cfafab900116ad` | `bc5f12cdc9550a73853b7a8120c3f01ad7c82fb244ed2cae938bde8a6b7fdf16` |
| `app/src/main/jni/kawari8/libkawari/kawari_crypt.cpp` | add | 100644 | `fb2fe4347c638fcbd75afbab79a96879754d6caa` | `edf0085fafd9e1a2ecea757a5cc8e57af38e76a4fc0d38d1665c9e4703b30f57` |
| `app/src/main/jni/kawari8/libkawari/kawari_crypt.h` | add | 100644 | `ddc4fe8cbd9abecb004cc7936970364564cc6e25` | `ca429b868d56b800441c60bc89e93a27d4c3595cfbb5b4df5a3a426a9ad336be` |
| `app/src/main/jni/kawari8/libkawari/kawari_dict.cpp` | add | 100644 | `33e331436f12409087fef9e9bbad60bd9990490d` | `395962b49aed86fd94b53a8b25f9d28fc12483e3daf655c9c4ac48415023fe3e` |
| `app/src/main/jni/kawari8/libkawari/kawari_dict.h` | add | 100644 | `5030f016eb8774ed6e94fa9cfcb29e0dd4e761b9` | `90c0deb59ccc86273c6691634d622eb7ac11746e50e503e3b5f1375021330c23` |
| `app/src/main/jni/kawari8/libkawari/kawari_engine.cpp` | add | 100644 | `038645184f44a46a93e19bc719f4e3626c623940` | `dcb8b4ed34292e4a6cf2cf3dd8bbcab36e6068dd375badd83ca6b1ca7b268568` |
| `app/src/main/jni/kawari8/libkawari/kawari_engine.h` | add | 100644 | `d29585737a0c076bbc821fbaaf9f7f1378ba4227` | `4a9620b69315e6a69f8a3a76b9aef68f53a14efa5c03d7035a87bbce6032375f` |
| `app/src/main/jni/kawari8/libkawari/kawari_lexer.cpp` | add | 100644 | `1fac92a530fbaf0983d75769f639edd976e42854` | `b373226db926e943b4acc72a7ac010053abe53a1dcdd6f4890b01f66e0124f09` |
| `app/src/main/jni/kawari8/libkawari/kawari_lexer.h` | add | 100644 | `62ace6db5d99db5fcd7b7ecf343e581ea4f643fe` | `5889dbf82ba1a47eaef6c47d6546c96cf043465a5f9eef90b185680701b05c12` |
| `app/src/main/jni/kawari8/libkawari/kawari_log.cpp` | add | 100644 | `6f1b6bc52525d8a5b56266b9a42f12b18df500db` | `28ad97790380e0798461782db056cdf5a938296f46cb65ee70870b138054dd98` |
| `app/src/main/jni/kawari8/libkawari/kawari_log.h` | add | 100644 | `876383cecdefcda02c1ee21c1e43dbcbe9f83023` | `e86cf061becb00abc7245ca08f783748b29cf9cc194fcf49ae6af99ffcc2dba9` |
| `app/src/main/jni/kawari8/libkawari/kawari_ns.cpp` | add | 100644 | `5ee44e29356157308a516b8a2edfd9fc0a2d1746` | `274f64cb184c921ce1518651256d24987e3b2a6cb0ca882b53f923eb26bf49ee` |
| `app/src/main/jni/kawari8/libkawari/kawari_ns.h` | add | 100644 | `bd7f4616d0537c16bc47861491e944895b110de9` | `22ebd8202f206e27e3f332d32518a8f24ad61579ea57a32c6c2f10b449ddc48a` |
| `app/src/main/jni/kawari8/libkawari/kawari_rc.cpp` | add | 100644 | `bb66d89472a45338338a81a74cfe8fc04d4f5cc2` | `25a5c712986685450742a43db8e00aa35854298dc528a56e4e445941a624b831` |
| `app/src/main/jni/kawari8/libkawari/kawari_rc.h` | add | 100644 | `5448d580d9acec17d4c3b15d0c88001918f63345` | `44cb0dea99b31af9140608111349f92bd18b4616de745210cc8652be938e7c07` |
| `app/src/main/jni/kawari8/libkawari/kawari_rc.sjis` | add | 100644 | `000271e1c47e3d5ad692002df8e964a07cd9d8a4` | `315333035a7c3717fda29fa89f5490f6c2bbdfb56bff2147f2391309ec717cee` |
| `app/src/main/jni/kawari8/libkawari/kawari_rc_sjis_encoded.h` | add | 100644 | `1a464d43e4e004ce543715fbf8cbb8b1a6475506` | `a9d52998b79927b2709135b5d3b0258d17a5a7b397ac425aa4fddecca3e94427` |
| `app/src/main/jni/kawari8/libkawari/kawari_version.h` | add | 100644 | `91fb62caadb1e5b57ee3447aa85b54f7ec12e767` | `9b83318b987eaecb5268be9077e8c5523f8f9edbf2d256fbc7feda0dd040823b` |
| `app/src/main/jni/kawari8/libkawari/kawari_vm.cpp` | add | 100644 | `9e66001b6031c3d3223138a7a9f6ebf30723ea1b` | `6bbb3cefdcbf9880477380d0fc6b246a8c131149933cbfd931bc385bd88df9b4` |
| `app/src/main/jni/kawari8/libkawari/kawari_vm.h` | add | 100644 | `fc207594f65d7aff6362c4c9c66aa17d949db30a` | `b68934c85a3d4d7d9d43c3674366170f05723d921eb89c73e391ddd735e6340b` |
| `app/src/main/jni/kawari8/libkawari/wordcollection.h` | add | 100644 | `bc5a39d75aad1e7ba074de92fc40553feb3d90b9` | `f088a79ccec6ba59ef114b10eff10e6bfa5f934458f3a1ff952090f3158aeacf` |
| `app/src/main/jni/kawari8/makedepend.rb` | add | 100644 | `908706085519d8b9178ada29ab296294d697a5fd` | `3d69a190d1b7fde5e06704f7a7f4e88644586f40f108e8190693144856dcbf3a` |
| `app/src/main/jni/kawari8/misc/_dirent.cpp` | add | 100644 | `bc244617b12d28c6d12526b91d8237f7dcbf1054` | `8de6aa4fe5590ae003f8a142d931d786d781fda4fcb3287622b599eba3090a55` |
| `app/src/main/jni/kawari8/misc/_dirent.h` | add | 100644 | `e11298bb1e3753d93535e3e73778c60ee654bbb8` | `661e775712dcd55e4ee1b17921a3045c6e9b07a216e58930616d9e5589708617` |
| `app/src/main/jni/kawari8/misc/base64.cpp` | add | 100644 | `a5e105d5047f12b726b778585ac995c373225513` | `aa1099ca83c26d9be3884410b74803be1ea2a569a663e865c8cec999917bd0f1` |
| `app/src/main/jni/kawari8/misc/base64.h` | add | 100644 | `f77a0916779369edd5a01c1b0929cda0f39507d6` | `7d3c176267a4c58c0e6d5f23c10ef2bc921462b019ac22d8a02172d05299f68c` |
| `app/src/main/jni/kawari8/misc/l10n.cpp` | add | 100644 | `f9d7846207b6c0860b4ab5205c570099b38311d1` | `b13240a3144e6a23321b7cbe8546e1bcf47229a285b365f9a8fdbb5df34b9923` |
| `app/src/main/jni/kawari8/misc/l10n.h` | add | 100644 | `215fe597f4d3b9b627339319a2e07a1e4d64c933` | `23dddcc52093656e0ca38be3a06675da9ad4fb525ebee4a08cfcd4a34d70826a` |
| `app/src/main/jni/kawari8/misc/misc.cpp` | add | 100644 | `3a4ae0922127a4aae8d708c7cfffea3cf4687a22` | `7df894c99d1607124d8d054c495323595a41ae7fa827adf800f53bf8e4df571f` |
| `app/src/main/jni/kawari8/misc/misc.h` | add | 100644 | `f93287df8341628b6473e5f65a644f2afa714983` | `a3b1fdedc66bd5b56f164440f28be621634cf45afc0cc9debc7e80bc39f7aeaa` |
| `app/src/main/jni/kawari8/misc/mmap.h` | add | 100644 | `674ee2d7826f15067f1c400244cd114858dfd7e1` | `1b01226667f2b7688809d8efe06098c81c2307152142d5c94bed26afaba02577` |
| `app/src/main/jni/kawari8/misc/mt19937ar.cpp` | add | 100644 | `c0b959d65d8dcd80ad311e5d4bf18d7e7f59adfc` | `97276628d8f392971f40e2ed662ff807c57fbe7a901721036a334296f2537b94` |
| `app/src/main/jni/kawari8/misc/mt19937ar.h` | add | 100644 | `c8c513abc35d353a062059b5a1b25e6f47dfcd48` | `216c9b9b657c29eb7a43b437c12f06fd72182ba86d0c38d28f473a4ab5aae098` |
| `app/src/main/jni/kawari8/misc/phttp.cpp` | add | 100644 | `f4252c7ea20d126c28cdd874f6eee969d7127606` | `412eeea3066a0d3f904059f89f2e8823113ac618eddc9860046c834e3c050e69` |
| `app/src/main/jni/kawari8/misc/phttp.h` | add | 100644 | `05cde29611c439f94d5ebc1a6d0db14cc4c3c6eb` | `a66d00ca23ad439ad38405c8235715070f4328280748dcc760a3c7b0050a730d` |
| `app/src/main/jni/kawari8/saori/old/saori_libdl.cpp` | add | 100644 | `1989cef65e4c640cdc987db1ebcb9f3ba5ae1e1c` | `3e7ef2642b947fa9f1e2fa5374cb3cc968f59fc7dad5ffa671ea5236cfe8ca49` |
| `app/src/main/jni/kawari8/saori/old/saori_libdl.h` | add | 100644 | `2639cf151bea65674174088f4e3c2e0104d6f143` | `04b6d0d3e87dc60d2c3b8ac0f3cc5d913f61a29c9c555e513511fd98cae1ac9a` |
| `app/src/main/jni/kawari8/saori/old/saori_win32.h` | add | 100644 | `983a664889a526c92dce0ae176ef7479c79fd637` | `7e3dbf80f5207edbb991b69f1127e993600ef11c4ce0dc5aa039086ac2a89c7c` |
| `app/src/main/jni/kawari8/saori/saori.cpp` | add | 100644 | `cb7eb57f962539c187219c01f598e1159a0fc20b` | `e3c4550ef9cb414a2640835cd760c3a1ce37aee0dcc24746048d58ef51428f95` |
| `app/src/main/jni/kawari8/saori/saori.h` | add | 100644 | `e2712c83a5a3fbd6d7af644b873c4a8fdb8f4850` | `5b8473b97ea5f4dabfff85dd570c605ba62a4ad12509e47d0847dc787b0e981f` |
| `app/src/main/jni/kawari8/saori/saori_java.cpp` | add | 100644 | `7ee13a492a0c06eefd98aaeb3b7a4fcdfc097cc7` | `aa3d112b8377c49fc8fedd6a1c6a2786029cdfebffb0af7c6f15b6226e132393` |
| `app/src/main/jni/kawari8/saori/saori_java.h` | add | 100644 | `7169479382fbe3cc22276925e31106f7de219f23` | `220bedd9279cd3d2dba306d558926c3f2690c7fac8362027ded24cba3966c84c` |
| `app/src/main/jni/kawari8/saori/saori_module.cpp` | add | 100644 | `834bf6fbd013b10d52a3fa9f66619dfb9fdb15b9` | `9dba310321023e3f68b517927de86158b2d178de69a6a247d051ec941e50a307` |
| `app/src/main/jni/kawari8/saori/saori_module.h` | add | 100644 | `af0e4fb6d648f314eaf299ee5b2b103f72e8e59b` | `899fe636e465dc6bdf064fc49ea5c52a598094c4c0671aaf265edd22f9c71ca2` |
| `app/src/main/jni/kawari8/saori/saori_native.cpp` | add | 100644 | `5a15de0e1f6a5ef09d2abb8f21af796a0ac0991b` | `e776a862a4fdba1d5efd806fd7e05a8c54edc13ec0f376cf541df673a9100f83` |
| `app/src/main/jni/kawari8/saori/saori_native.h` | add | 100644 | `6dadc5b1e49a68a6b7941c6eee830bf522d72b17` | `f9b6698461ae23ca2c8e4dcde8dac607a914d369df470f5b89b417d1097e71d6` |
| `app/src/main/jni/kawari8/saori/saori_python.cpp` | add | 100644 | `fc07806e554b83c93ec8673696f5a87506a89e12` | `c1986be347082c57703a28d64b4bf2d9d6cfc4dd69283e569ca916f42a983a5e` |
| `app/src/main/jni/kawari8/saori/saori_python.h` | add | 100644 | `1264d88c9e5124d34778712bc3639101e5e3dc70` | `6605df114399de3d78d6697210173871b5cc269d611610f670e0dd03455da057` |
| `app/src/main/jni/kawari8/saori/saori_unique.cpp` | add | 100644 | `8f6e8f26a73640a9f7628fd60b20a2ff3658b084` | `0a122c507a845ac8f565fc14275663e223e9c5bba9adbe60845e3ba3ee1fcb49` |
| `app/src/main/jni/kawari8/saori/saori_unique.h` | add | 100644 | `cdc46064dbf0c7eb93a123745f1c50235397bfca` | `565a69e8144889a5d120d4448170b33e318644a9803af7a1644ff62a6a562869` |
| `app/src/main/jni/kawari8/shiori/kawari_shiori.cpp` | add | 100644 | `32780f49c90272070e0661ebe8e9021dffbdda79` | `7b254ad9367b6c84a6ffafda3caad3de0c02459fad416976f9e584c1e8de18d7` |
| `app/src/main/jni/kawari8/shiori/kawari_shiori.h` | add | 100644 | `aa1d103936cce5ae07a94fb471f3845c217afdc4` | `02130944d0c39d569ec14de11e54026a0b4eecff2555b75cfa9403c4ac4e57a7` |
| `app/src/main/jni/kawari8/shiori/old/shiori_posix.cpp` | add | 100644 | `abfadb346890b3549e916e276c59ab4c215009f9` | `3582b47ddb21f19001db1bb4f478ce88fa357225fe87c90e13f661cf69c03730` |
| `app/src/main/jni/kawari8/shiori/py_shiori.cpp` | add | 100644 | `a20776c1a48ec41057d99d0ad7df5b795a1f3537` | `a38f90ef48bb75bb251614e7c60b156a29aa4f10c01b2804dc5a842db3701808` |
| `app/src/main/jni/kawari8/shiori/py_shiori.h` | add | 100644 | `da003a831b4cf0661404a3750d7a9069854ca724` | `e635e1a9fc93e9c910ec6e08356a2d8c3101b72de0556f04d9b7ec771aa74a43` |
| `app/src/main/jni/kawari8/shiori/shiori.cpp` | add | 100644 | `6c9df0ff16e8ca98d165da7ce590b18cd37cb820` | `e7c5734fa5b4fa7cc91d0b23b921f2646b9038a6abbc5cc69c2fc119be5d36aa` |
| `app/src/main/jni/kawari8/shiori/shiori_object.cpp` | add | 100644 | `63578416a9aad55f7f7adbfef90c61106b82ae92` | `f44a3ffe981da3c031eca62000eb9dff0ac2abbeb69a3ee9ecceb5d22521101d` |
| `app/src/main/jni/kawari8/sjis2ascii.rb` | add | 100644 | `bce92c891d0dd293ab1763588d046a96c5fddfc5` | `f58029aed0ab2aa4a070075d6669236d091c9f4d40a4a3ffa4ce67cdac7d92b1` |
| `app/src/main/jni/kawari8/tool/kawari_decode2.cpp` | add | 100644 | `c495cd6285730f03313f0f3597ff3509f962672b` | `7a072a70d9cf2c4104819bc9b9e7951eaa72216bd0b117f7d7f8a24a46d0db84` |
| `app/src/main/jni/kawari8/tool/kawari_encode.cpp` | add | 100644 | `e6ab7698f7b46cdd724eb7b5cb47a8b2acebc900` | `1f750db9c2d1c2c9aeccaf3fc70ac8a218990cfa27bbcee7abafce16b4c02196` |
| `app/src/main/jni/kawari8/tool/kawari_encode2.cpp` | add | 100644 | `1ffea73751c20df447c30893741d29ee0fc35580` | `6f7901ebf9a6962c54433166cb74f0b16a92e617fd146712e1c661f2a4490eca` |
| `app/src/main/jni/kawari8/tool/kawari_kosui.h` | add | 100644 | `6000519e6dd3279f9d043f3d1c780c9278f71424` | `0c73fbf15a3e3b0c073ce85ed0e92de26975f201522c1a1c4e774408e43199e8` |
| `app/src/main/jni/kawari8/tool/kdb.cpp` | add | 100644 | `90afc6cc0d86a8e5a8abcd6ca9230e009a2bb957` | `3aedc9158a190933b1d72123e6892a55917609e542bc62253d86c8d2cb462831` |
| `app/src/main/jni/kawari8/tool/kdb.h` | add | 100644 | `06c321cd5bd5ea87690b40d78e2a01b04c804056` | `775a94ad56bb8af8279531a8e90331842ab0642a9a49654fe4d6cbddb7bd37c4` |
| `app/src/main/jni/kawari8/tool/kosui.cpp` | add | 100644 | `e5ab96c8638b95753107c4af6752596c6dc4366d` | `81fd3cc1e9b9fdbed4ff33eaa480fd5b8dc94e93ad9f7c25cf7946cf1fd1053f` |
| `app/src/main/jni/kawari8/tool/kosui_base.h` | add | 100644 | `4705f5002883bceb30fe6ae9b8cd72f59128629a` | `ccae0ac24ce79d6a4b7c1fbf0839034fd7026471f7600195833d64922ea7136f` |
| `app/src/main/jni/kawari8/tool/kosui_dsstp.cpp` | add | 100644 | `5291d0883d3f53a0ea9042c3f6d708f30cfaea54` | `1783693ee7bbba3d3fa0d870a758ffd8f25d710c4cc0c7f01bd178e22c84c33d` |
| `app/src/main/jni/kawari8/tool/kosui_dsstp.h` | add | 100644 | `72bbc13fd6fcdc3cfc2405396d4d60b927e501e2` | `a5da6d4714d3726858a625bebab6f779a1de5c5174d336f83d4ff27a2f16baf8` |
| `app/src/main/jni/kawari8/tool/logserver.cpp` | add | 100644 | `85c17750d44a3235d19781172aeed923e2dc0c43` | `504b2bf9a8987ed5e5374ca8f97b54d92c678a01937700688c732f36446e09f6` |
| `app/src/main/jni/kawari8/vc_kawari/vc_kawari.dsp` | add | 100644 | `fd2141d8bb872c95912adad6044ed43e3d65aa6d` | `d941911cb13c2256685d18e1c4b86edb2f071baf2a13df01dcf8fe523f7fd496` |
| `app/src/main/jni/kawari8/vc_kawari/vc_kawari.dsw` | add | 100644 | `874d7c7a548dd993c081a77ebd393ef4d1aaba95` | `a798dbcfe29af3183c52343b3ed98319c73f61877c524b71d6d3579a482d423a` |
| `app/src/main/jni/kawari8/vc_kawari/vc_kawari.sln` | add | 100644 | `4395aba4fee467063e173978e46dbf9eeebdb779` | `092514dd0adcf9536f6ed70abc949a31efa968b834ec94bb93d9c27aeb11ad3f` |
| `app/src/main/jni/kawari8/vc_kawari/vc_kawari.vcproj` | add | 100644 | `b448a44faed6073cbccc010cb90bb27a5d1496da` | `7a47ef58884340320ee773dd5045760a5a57393f1631f2eeb36df467fa78696b` |
| `app/src/main/jni/kawari8/vc_kawari/vc_kosui.dsp` | add | 100644 | `001582458467acc90dd16cdb526aa69be888fa20` | `beec7a92dfa1ba04e4b192df40f82d45a34c0944aac9e0384f5aa317444e47e3` |
| `app/src/main/jni/kawari8/vc_kawari/vc_kosui.dsw` | add | 100644 | `229e1af892a3fd5923a59ff5ec957ebb3692db8d` | `68948bc64bccb161b9b12a33a936047cb7e3f279720c398e16bd766df24e90c4` |
| `app/src/main/jni/kawari8/vc_kawari/vc_kosui.sln` | add | 100644 | `bd257f128d4505dc2b5b1d63873ac8e4883332b9` | `01429acb3687f0abadd686db4bea6d61443638ad8240b66d0a68c48f4a2dcb66` |
| `app/src/main/jni/kawari8/vc_kawari/vc_kosui.vcproj` | add | 100644 | `7ee79ca7e74d5a97c017eaf217d29f7322d44bb7` | `9e9de87908e917eec36b88fdf879fc90fb4f7cb35245bbf61ad786ae4879a7b1` |
| `app/src/main/jni/kawari8/win32jvm.def` | add | 100644 | `c70a0574aefd79a07c914260b3ef836594bbae4c` | `c04e85aeedcf89ab78f2204460e3bd9fd46d9abf9c41f6d42f23864feb849a64` |
| `app/src/main/jni/satori/Android.mk` | add | 100644 | `0f96c2fe2a28de77e13cac30f9d6cff78d70172d` | `3e7807e3a9819842974ce8e43fd0bd3b009d4cfe815837dcf9ebbdc759a94f06` |
| `app/src/main/jni/satori/Families.h` | add | 100644 | `b1cbd7efbbd9cf25c5b25cf66ffa5e04326c8c53` | `dad40aa1798265c3e0280371622b6f2dd5dd4f079a1b21997890ef23d48edc11` |
| `app/src/main/jni/satori/Family.h` | add | 100644 | `f0d8b843dafd47195c3ec77e739baa0a5ab0ef56` | `d504208b85b25dd9cc0a5ecefb6ec78fc39b83d9e80e5380bc69a2382325ab0e` |
| `app/src/main/jni/satori/OverlapController.h` | add | 100644 | `7943b72201d0664bd9d15dd084cce20663f3c11a` | `ef6c13cf8dbc12c2313d40656b809ea54454f488ef411f68f0f113514b0789ed` |
| `app/src/main/jni/satori/SSTPClient.cpp` | add | 100644 | `0360b8d4362f51ea8095e4e7efc5cf90c0212125` | `7830a36f421789f85ba2d7de5a6cebd7b9547814582c50c8741fb6d6249db8c1` |
| `app/src/main/jni/satori/SSTPClient.h` | add | 100644 | `8682e66e452669bf4914652bc24a97407380c500` | `3e5262a3d3acc741b6d3a5cddcdf9c12c8d904bbf0cacc4208a3de523d46b4ef` |
| `app/src/main/jni/satori/SakuraCS.cpp` | add | 100644 | `e6c8fb26f261916ccbe3bc4fa50c4bdfea8729cd` | `fad9e2d61edc29abd29d164c9dd42f224a7b798d348de086f95e430e89fbc69d` |
| `app/src/main/jni/satori/SakuraCS.h` | add | 100644 | `faf25936ac328c8d55d524c63f5acac6758edd0c` | `a8e08517115040c4c208a6d7ca25cedf2f24a74e7bfabe88da46c7cecb4b663a` |
| `app/src/main/jni/satori/SakuraClient.cpp` | add | 100644 | `ea464afa112f049109ae89b5809eaf5b67528574` | `57023dba41b7daddb66ce06d1ae9f773e593ab529b0d2b508fc4ed0ca131fc3e` |
| `app/src/main/jni/satori/SakuraClient.h` | add | 100644 | `df94f20a34280614bf0bfcc685b897d1064b3c2b` | `1cd0c48e539cca9672f380443cd9250795e97ea0a5a0d250012667ad6de0c707` |
| `app/src/main/jni/satori/SakuraDLLClient.cpp` | add | 100644 | `4d7a18a40a276006947b9dd23f11880cb4c10b2c` | `0355a9e6ff28a94392700e5030c96bd35940a3755162366e20788d7e40fae4a1` |
| `app/src/main/jni/satori/SakuraDLLClient.h` | add | 100644 | `58f4cb9c07476772365a543d5099cd618d72cd08` | `5a20d0c2a01f95b4466d9ae3f9675880d0c69ef2a130dbed015c9cf92399f7f5` |
| `app/src/main/jni/satori/SakuraDLLHost.cpp` | add | 100644 | `0f59d54510c30fe48fdadd5c91fa68f509b55c33` | `14abb80d748785043e987862f1866978cfbe0b023418f963d7ae4ab352420855` |
| `app/src/main/jni/satori/SakuraDLLHost.h` | add | 100644 | `979e821ba51a1c26a60d664c8f18fcf068dbe9e6` | `5a201ba571851a39e0a6d22a119b6695780e4ecd773dd3267b6cbb069677d270` |
| `app/src/main/jni/satori/SaoriClient.cpp` | add | 100644 | `55dd548a8b858e93cca8c80247b706bd8ec42ba9` | `3ba7b35a92798dfd4b0def6e3b43b25987567e15fdb43c7c80424bea67e44b9d` |
| `app/src/main/jni/satori/SaoriClient.h` | add | 100644 | `2f4033dbde3c3f127a50ca40be60fd619ed4fd47` | `b08493d1cd0c760e73405dd335b1ae1fd72b23a771eb519d84073585616b9268` |
| `app/src/main/jni/satori/SaoriHost.cpp` | add | 100644 | `16f021e63ded8517768956a9ea6387a34984a2bb` | `aca7f631b46a01bf696c2ac56c881160501ab04ac4ef9a1693c79e9069f658f1` |
| `app/src/main/jni/satori/SaoriHost.h` | add | 100644 | `b0c29c5fa3cb6f6183cb0d48cd0cffbbc7826a65` | `1fdd9b5eae123ac6d3988c1b931fd88152c93b6fd47d9f02ec026bbdb8e6fac2` |
| `app/src/main/jni/satori/Selector.h` | add | 100644 | `6e940414000de550974f08821bd0a15365fe167d` | `508301a262214d3d7758a57c7dbe6218487d92891da48730b1900ca12935c5e6` |
| `app/src/main/jni/satori/ShioriClient.cpp` | add | 100644 | `e23cc62c3cdcfc428a9572c03a79a3b45cf5cae1` | `809af363f69880f7796636d3840d534996452b7f03c45c62197306539117abcf` |
| `app/src/main/jni/satori/ShioriClient.h` | add | 100644 | `33848035db087c893d4b6d4427ac110222ca849d` | `c329b6837a6d001ea3746841ebf841dd1570c3cff74f27b7e8134a4b16939938` |
| `app/src/main/jni/satori/TimeCommands.cpp` | add | 100644 | `995256e44cfd81b4c1addadfa3b31cedbdba5d76` | `87d8df8a6a482a5a01877f40241a9e9a82044e2f4c7b52fd4241c890e674ee2e` |
| `app/src/main/jni/satori/WinMain.cpp` | add | 100644 | `65637fbf53fc4c07534f8a2decf3c6c092eb1740` | `57d482c85f2c0a06a72ccaeeb16a28e5903d8f3a11e7c6adfab655923f49f7fc` |
| `app/src/main/jni/satori/console_application.h` | add | 100644 | `83306c7f95e04c4b1c9871b36c4e3cc06e40d20d` | `af6ce40295186d3e5618854c13ef8df9dd6cb99d7c375d5eb80efef4f713cf20` |
| `app/src/main/jni/satori/index.html` | add | 100644 | `5ec6aaed118ce52e6d32c185d4103b3623a5849e` | `f95d2c3d29a865c105f1ad9cda393f372e52cd54f6d2477d83e2d087b1cf69e8` |
| `app/src/main/jni/satori/main.cpp` | add | 100644 | `fd180dd903e20a95f1819e10bcb6ce83d3e1e180` | `c0c19edc6e6c30ea17251195c30ac8cfc966a329b8720fa3e3ca2f50b1f8870d` |
| `app/src/main/jni/satori/makefile.cygwin` | add | 100644 | `97320ce35e4fd2cbdabffeb7a8028731ddab9c34` | `75ef457908714e57042a4fe7d0839a653bd2ad4b8b0a519744aaf97457ce3bce` |
| `app/src/main/jni/satori/makefile.posix` | add | 100644 | `75685e792950b050483f8553e3d5da60e3c6270d` | `bfc2e4331a7ec2515a6a5907b6f01008e87572ef4c32199c11f0a99313ab028e` |
| `app/src/main/jni/satori/posix_utils.h` | add | 100644 | `976da17907c89c22de5afd3a1a90e1e77c385491` | `dcfca5eded9d3a270e584b0ce8ce1d3a67133ecb56a58ffe6651c1fe4bd0228c` |
| `app/src/main/jni/satori/satori.cpp` | add | 100644 | `7e3668b3dbe8cfe677caaf22068d244a8b15869c` | `e73206b90b5a4c03799cdd0c83248180bf3bc8a45547e8fc0627d33996944733` |
| `app/src/main/jni/satori/satori.dsp` | add | 100644 | `04abe552d5c146923930cbb6e4ed09e975dc4f71` | `375e26c667c5b772948a37c0cc39aeb95425a93f9e0263697af10b0c643aaa89` |
| `app/src/main/jni/satori/satori.dsw` | add | 100644 | `ad06dbf13123e59db757f830bf6301ad51f6de18` | `d74736df8c03961b2b5df0d9c2480d05995bbb9f22d88aadb2a118057162cc17` |
| `app/src/main/jni/satori/satori.h` | add | 100644 | `5427476168e911e290babee4f7df2822532686cc` | `ed99f31e8a9f6ee523d436420e3c5bfb92a63ae98bcdb4367d92083ba552a899` |
| `app/src/main/jni/satori/satoriFMO.cpp` | add | 100644 | `67868fccf9aff09a9f45f8045911ee7820688465` | `fb66ed6d9911fda6dd61981f79bd171021febb3603a0b0c7198cd01360d60df1` |
| `app/src/main/jni/satori/satoriTranslate.cpp` | add | 100644 | `9bd4978f10248168aad694b701c8ce02863bdf55` | `197cd6a35512f3e7d6f1e5fae5429dacacf16b87aeb8f5e18038b02b769a7f90` |
| `app/src/main/jni/satori/satori_AnalyzeRequest.cpp` | add | 100644 | `19f3dae0e0ae34de091942870c04a15621299961` | `ebecf5759227323b8e0eb7cfa83f70029c36918839e62f2b9688fe650443d47c` |
| `app/src/main/jni/satori/satori_CreateResponce.cpp` | add | 100644 | `6c9ace5d2be76387222d3441688d4e8c242e47e5` | `df14181cff86c296c90ec3fca5c211bc8369af4e1444cc4547a8f6a100562e05` |
| `app/src/main/jni/satori/satori_EventOperation.cpp` | add | 100644 | `61251b722e06399e3e08503db2674643c4357608` | `c491c89f894d4287d1660f70545b6251a2ca8103adfbfa6c09a535eb2c868586` |
| `app/src/main/jni/satori/satori_Kakko.cpp` | add | 100644 | `fdb3beebf07feaf81c73a0c2a2f1132f5e110810` | `4692b88e8327ee0d1cedf2012006767b04250268f6ae2db5fa2d76ab116e23e3` |
| `app/src/main/jni/satori/satori_jni.cpp` | add | 100644 | `625d37c3d8f8be07480814a2b3ee0a19fb230344` | `17a4aac520bcfa07a9f5041cc60b4990a6c574655a52619612fb280b93f4b61a` |
| `app/src/main/jni/satori/satori_load_dict.cpp` | add | 100644 | `fa420e5bcf51c7168b3d2616c43eaa558102b078` | `ee2e2abbd2cc6cbf0e8999eebec81f216c058455dd675727ae17766c30d602e1` |
| `app/src/main/jni/satori/satori_load_dict.h` | add | 100644 | `3ae0ff808576070147a6f792d3be3e60071d169c` | `806b47ae45ac3c037dd216ada537c4280d2aef1a484a594be90b6a7f2f013181` |
| `app/src/main/jni/satori/satori_load_unload.cpp` | add | 100644 | `8e7e0197ff578f0122db5f68be52836510eaea9c` | `c3f22cdc4e63dd20e6c6646b1570382259b8e30c16f6ad9491b58c0e0478e406` |
| `app/src/main/jni/satori/satori_sentence.cpp` | add | 100644 | `9e22c5651104a9803f49e713db350a1a5b556ff2` | `ba119659dd225f2e04485af00cd84b7fc10575c8f99b7ffab06700a2c399faae` |
| `app/src/main/jni/satori/satori_test.dsp` | add | 100644 | `66eeb422f07d951ad7deb32823b0806338535281` | `b8c01826c966b15b93f9ec722c49a931fcae1d73560ebc0deda082509bbf3261` |
| `app/src/main/jni/satori/satori_tool.cpp` | add | 100644 | `9bc38cb101c67ee190d1b8a2510857c77fc32bf8` | `10d757b2db294d1c75441eec8d0a1578759ed6be88774f925676978e77402e2d` |
| `app/src/main/jni/satori/satorite.dsp` | add | 100644 | `522222227dc7c989255bad04decd1c0e92a28f1a` | `338542a1261bc8b94a963701cd4f32adbc44a5c682d7e88281be5372b0d06cc8` |
| `app/src/main/jni/satori/shiori_plugin.cpp` | add | 100644 | `3563def11679b50162865306d2d6109d32c3fb45` | `dff1dc81556345870ab9a232fa4ed430b27cff2f691c2926e786b5f1da70bdcc` |
| `app/src/main/jni/satori/shiori_plugin.h` | add | 100644 | `14f6da4b355e42292feb9b439ee963d98302481c` | `83f74ced4d60eff8acfdd403504f5bc1936b5ade7167243bc502bb5535020cb3` |
| `app/src/main/jni/satori/source-literal-manifest.json` | add | 100644 | `7705bb30e986cd482f21bb87d31d18aea20dd318` | `baaa391ee6232cb13fe8a9274800251dcc504502ead3a0cc1e733cfc7e344812` |
| `app/src/main/jni/satori/ssu.cpp` | add | 100644 | `60b19f7b6d212c116353a2deebc8c3f22808dd60` | `8408e7367263dc2151f39e6f4aab878c08d967a6f44f868a9c5d554dc949e3a0` |
| `app/src/main/jni/satori/ssu.dsp` | add | 100644 | `dc544a9f10f738e28254192a56f684fe7ec77233` | `9fcf34f199b320aa31315e204976747390ac4a3d42e2943732d4528bc3b1c963` |
| `app/src/main/jni/satori/ssu_anchor.cpp` | add | 100644 | `e7787ebe8ce7990bea9a57129493cd7f85f14087` | `dad06b2d38e77e78e3ed3d11a8f4142623058eb6ff603ab5be425bfad27d7e52` |
| `app/src/main/jni/satori/test/characters.ini` | add | 100644 | `923deb744ce023ddb207073497fbed6d22717dd4` | `242c6c29cae80e2f1f227f643a7a7d9cee8083512e7e08a99e5cb5bded611fdf` |
| `app/src/main/jni/satori/test/dic1.txt` | add | 100644 | `5347ed0f5727a025096de6ee4d5904d4dbd036db` | `c7e4683a5756205a9fea3661d428c81aceec86bd23e2893c46abb056ae8c5099` |
| `app/src/main/jni/satori_compat.h` | add | 100644 | `8f37aaeadcd49b2cb78d501375095d2dfed966ab` | `4473aa2b77f7e7e83aef6972d8577b6a41dc90cfdeee8731869e548d1b848d1b` |
| `app/src/main/jni/satori_license.txt` | add | 100644 | `f4e44f571ad3645a09fe65f3288183dc934881f9` | `f07679e7d288eda3d03f0444db29fd5250de9a9231b03c7657d665826265cd74` |
| `app/src/main/jni/yaya/.clang-format` | add | 100644 | `72ab1f2d6def819caad043b9c5ec5474857ec7a9` | `b9eb257a6135b57a505c468eb14db67ec64739196cc1255ce650d7bbcea27001` |
| `app/src/main/jni/yaya/.gitignore` | add | 100644 | `2e400d07f8234c6b79cc0ad64ef093b619ae11e6` | `efa6f7be94370b691aa345444e475c988cc358637903eb58b13f2c5a91c73bb4` |
| `app/src/main/jni/yaya/LICENSE` | add | 100644 | `e7e702f8529aef92aafd22a45ea2fda7e6487128` | `835cb88da811df138ad7fbadac695471999143dd751517bfa349c8acb1d09bc4` |
| `app/src/main/jni/yaya/android_charset.cpp` | add | 100644 | `1c21682dd4dd95644673f626fab68136fc3cb133` | `b01484071a33a9b165aaedf8fa958a5fdaebfc45047f7cca83174ce6b4bca597` |
| `app/src/main/jni/yaya/android_charset.h` | add | 100644 | `892d8f09d9605e897707864da6b70cfc0e6aefe8` | `e512f5fc6793cb09a06df58cdbe919ff1ff6d9a7f600113cc39abdf6f863ff27` |
| `app/src/main/jni/yaya/aya5.cpp` | add | 100644 | `b99a115cf56ae9b164058ce2207febce305fbec7` | `02ee400156528de3819857b95e9925da549a2c4c77ce35ce1d6426af95e9d17d` |
| `app/src/main/jni/yaya/aya5.h` | add | 100644 | `668832d46b25ec52ef62af6802f040ad36efe123` | `c7dfbb5d54e76397273bd04dee495d3c2112388287b5c2853161b6d01296cf69` |
| `app/src/main/jni/yaya/aya_profile.cpp` | add | 100644 | `7d575065103d13d7e20d8946a5c5b7aa2cf0086f` | `e311f0829cd2760b9fa7da95bd25bb1f500a3c11c49e00e40ceafdc1cecb42f4` |
| `app/src/main/jni/yaya/ayavm.cpp` | add | 100644 | `16cf10b82c1a048a9f3d952866781c59272b6d18` | `da7f00db6c9c6daea38b830b98fc7df82ec7610ec725e5b8d4e8bdbd8fa0d117` |
| `app/src/main/jni/yaya/ayavm.h` | add | 100644 | `a0383bd11c5f3637e4db153b49b36e5b3c6cb15a` | `be65b80783a6afa4a24697b2402993998b69ce81a1a4eb0058b21f0fcd54e7d7` |
| `app/src/main/jni/yaya/basis.cpp` | add | 100644 | `acea18f8cc4c33c4799b394626fb41b0028b957a` | `205568824b7bd0548b45f560f78e2bc4c8184834fd222b34f3d1152a1acc4581` |
| `app/src/main/jni/yaya/basis.h` | add | 100644 | `c2eff0c12e0c853d4893b6f0af9889b33be2ee00` | `8bab0b83e3c2c13aac9f3730b66bd497de2571e6707a0ce06b1fa7428cb29b7c` |
| `app/src/main/jni/yaya/ccct.cpp` | add | 100644 | `5ec808d7aadfb6d107b2e7233f1bbcbd741c88d5` | `c2a921d46287c6a068ce3c669e57b9ef660fa7df49bd611ed3ee1ffb092f883c` |
| `app/src/main/jni/yaya/ccct.h` | add | 100644 | `ebe05d57b2a08f922dad97040b21d9f669d8a9e6` | `d94d8ca114f956a0fe072b6cc43ec97b4a2e95474e4fb592c439dba87870426f` |
| `app/src/main/jni/yaya/cell.h` | add | 100644 | `07982d40d0053c069258bee54f8bce8e506dfd60` | `ed03b1b76b981b4e3e6672a5533e5bf5cbcdc5ba834d3f399911f227019208d9` |
| `app/src/main/jni/yaya/comment.cpp` | add | 100644 | `8753889e49375c20c9926714d90ec23367fc85e6` | `ff5401f463fddf3ba5a52927dbb7fe63baa6f2437d00fa2bb672b24bfca99bf5` |
| `app/src/main/jni/yaya/comment.h` | add | 100644 | `9fcb07318b51905ca3fab657b47e004282376663` | `d4e69e253095a65164d51495e615c0a4ca41d8692688785d3d822d3eb816bec4` |
| `app/src/main/jni/yaya/cpp.hint` | add | 100644 | `1dff590bb08ba3ee41cdb87a1c8132a6679193da` | `35e26c8e59cbfbbf414c0426ef6eb3a8e54537a1e2187da16b58f296643f54fe` |
| `app/src/main/jni/yaya/crc32.c` | add | 100644 | `cffc96402f27be6f20f83ebc38b678759d620046` | `a741e6637c93a3ecc99e80c13d52c50a0993c637a8bc84c68965c2e08e997785` |
| `app/src/main/jni/yaya/crc32.h` | add | 100644 | `91e25e4fea0e88407cfd67a46c9ee6a5980a82fe` | `f638003629988d6065beb7f505b5ecb07d2cfc687d2220f82289481110940705` |
| `app/src/main/jni/yaya/deelx.h` | add | 100644 | `ff6939c32f0a17c9a804ad3b44e5cc633313a086` | `dca4f2b7dbfe0bc3ac9dfa2ff19c0b97813987c15a2b201b95f49529e225b9f5` |
| `app/src/main/jni/yaya/dir_enum.cpp` | add | 100644 | `692497123cd03b1d75c6657d28286dd8d71e5410` | `ed04ed7cc072725201800d55a9771bc46bca1aa5fc10eda2269a14aad1d0f1f2` |
| `app/src/main/jni/yaya/dir_enum.h` | add | 100644 | `933ddaad200d55cb7d58471b3f0f4c1e207a3259` | `3bf8f2da5a5ff91eeffdd419f662fe386df6946f166ce097112d0c20d3bf9ddd` |
| `app/src/main/jni/yaya/duplevinfo.cpp` | add | 100644 | `70116310c079fe6ef6eed816210f98980ef69a44` | `fff4abc99593f93ee50eeb77f9f6569bd3a93e7537fcb087c43f2872df684c88` |
| `app/src/main/jni/yaya/em-post.js` | add | 100644 | `29ba28243e65ed71312ca2c8babf12b77b509bc6` | `cc36962e7091534bbe3cba1e0b84faf6228113f6a799813d0527c24835e9d981` |
| `app/src/main/jni/yaya/em-pre.js` | add | 100644 | `0f951632b073ee4741f1cd744f5c68882ecf38a5` | `e2abedbceec22064e585c524a1d2861975e054c0182a9215d90b5e74cb29896c` |
| `app/src/main/jni/yaya/file.cpp` | add | 100644 | `91f74b85b2684f993d364cdff6a2eb9069498a04` | `aa0560be9ac3bf5f6f4127558bb4df679bd6431c5c20bb57cbc6e7cd185cd55d` |
| `app/src/main/jni/yaya/file.h` | add | 100644 | `956625c8d0ce69c2096f8c20456cb1dab29ef531` | `6c3c4249d8968345c5d51e3aee4c330397f3d00e80771f1c51ff180e7ee3c259` |
| `app/src/main/jni/yaya/file1.cpp` | add | 100644 | `72788c6908fd29f3cff031281c7888b24a7af79b` | `bf26bc64c5ec9fd23d8f73aa0f5844936763ad8f0793e6f560657a81666987b8` |
| `app/src/main/jni/yaya/fix_unistd.h` | add | 100644 | `3a6bb5481294a9ac409cd657ef6ac576692e3ecd` | `039c892ba9eeed6ccb87df6492b931e69dba32ec050eeda30f7d5a46b725e6c8` |
| `app/src/main/jni/yaya/function.cpp` | add | 100644 | `c65054591fd88d8691755aee9e0ea0e8731a54a0` | `cc6baa88496fbeca52bf0ef44513f02921e51de3f0e28e0af1653ad70bbc6437` |
| `app/src/main/jni/yaya/function.h` | add | 100644 | `a32726b43c4f7ef00cebd27d4562ddbcd441b3fe` | `c1399d411dba2069662495cef6fe21afbd26af3c4b9ca988df13e38a3ed78806` |
| `app/src/main/jni/yaya/global.h` | add | 100644 | `5ca98b06fd5534a53b6893e2bf069c25318b22c5` | `bc0d208c0dca5aa97b6dfe1fb7e0923b671f9370c11b3a535284a498d9fd76d9` |
| `app/src/main/jni/yaya/globaldef.h` | add | 100644 | `69f0802effa471da34659633163624963763a827` | `3814bd8013e4c1cf458e72c991a5725270e070f0eda2bc3201aae841e1dc7388` |
| `app/src/main/jni/yaya/globalvariable.cpp` | add | 100644 | `f48e02aae2b61766b7bd3b123c4b89dd6de90e25` | `d2ffab2c9502a061a1cdbf9025b8ff018b237b6d5da3a694e3e093bc86326189` |
| `app/src/main/jni/yaya/lib.cpp` | add | 100644 | `fcc99fb74c85b6be552b479f8d174f68739cfcbe` | `290052415f162e1ec7f3595316664923cb56ea792ee934ba052ce7c96899486c` |
| `app/src/main/jni/yaya/lib.h` | add | 100644 | `50a3e67ebc0974538f544073c51dbc89dd813e70` | `f46ca8f6b19468da2cc31196c7a85faf23710da092ebb60dfcc32995f14d3fcd` |
| `app/src/main/jni/yaya/lib1.cpp` | add | 100644 | `b531684ec22a766ccf3eb5a7a75f178a11a0ef87` | `eab09de9c711cf2c868c030ad86f04f6ba1fe1b3afa89a0024c1ed932a12ae6b` |
| `app/src/main/jni/yaya/localvariable.cpp` | add | 100644 | `e4dbd5ec7e59c64869392aa63e356f393497b4fb` | `445afeb4f6015c139999c65702fe5c4012c20d88899827b65e84a406c7cdb6c6` |
| `app/src/main/jni/yaya/log.cpp` | add | 100644 | `308b87c3aad4e8a2fd78cdc36707420dfad35ff8` | `99ae39813860158eb0180c5725cb9d45305ea1720acb6fbc5004c98f13261de8` |
| `app/src/main/jni/yaya/log.h` | add | 100644 | `0940e103a608b255a6c01c9c7a5e88b3715d5c39` | `de00f4d7e90c16686c95b96153cfc6852b2b916cd5abbbed6f3ec4adb0f18a78` |
| `app/src/main/jni/yaya/logexcode.cpp` | add | 100644 | `45ea7762d7397b1dcf0d35d30b02aacdfda26a92` | `dabaf72438f4a07ee23cc459fb728c9dfce53acd08bb591d08b85e086c1f0eb5` |
| `app/src/main/jni/yaya/logexcode.h` | add | 100644 | `ca41129304d81aeab0875df63496a343bfeddba5` | `18cf88b932b93286a5e7693e286cf8069f01324c52b608f1d1e60d60c9e99511` |
| `app/src/main/jni/yaya/make_aya.bat` | add | 100644 | `af61f8e38b3b9f1a18600a03e4c46b499cfb45d5` | `19ea546c379dd959120d6c557166757e857de2647e215c070f0df59eb3a8c603` |
| `app/src/main/jni/yaya/makefile.emscripten` | add | 100644 | `dc6e1f8e4949490a814f3940e134aa9bcf06497c` | `bebf2f7fe92d3f7476a6a7c7128dadec0947a60abb7951dfce041c8466064276` |
| `app/src/main/jni/yaya/makefile.fc6` | add | 100644 | `f0a9e6cdb3c0047b5685381122742c62745e25ec` | `7e3aba5da6732dd7947a001ebee430085ba5e49f93873600eb9662b35b62e662` |
| `app/src/main/jni/yaya/makefile.freebsd` | add | 100644 | `96c19283361871f8fe4686499ac725a7a9b6615e` | `f073bb7b79c15b0a685c07959057654ba5c09bf0ccc4b3a8027e3e9c51fbdc4b` |
| `app/src/main/jni/yaya/makefile.linux` | add | 100644 | `9d2b1bacc7b4017c1eb7d29c556e2ceaf2b82611` | `0af683849c3b42d48f09694e32be5420f883aa24bee6830e59cda9dc45fd8042` |
| `app/src/main/jni/yaya/makefile.mingw32` | add | 100644 | `7170394a14386a76cc65936573db692992eec646` | `f1b1fc286ed2ba1c6b8065be5112cd23a1d0ac79ecff02cc25c3acf26a571868` |
| `app/src/main/jni/yaya/makefile.posix` | add | 100644 | `e94e5f56cd724410f7489b3acf603d9d88cd99ab` | `a4b019680d4c75cce15bd9e3b638921b95813b7725610b9e53593536be63cb25` |
| `app/src/main/jni/yaya/manifest.cpp` | add | 100644 | `8f5617fed2d9d644706cb2531ba7681aa9925bcc` | `826bcc1d624939ff8b6cabd90bac653b744531009f63f647cc72da91b7805cb0` |
| `app/src/main/jni/yaya/manifest.h` | add | 100644 | `c84458f5de85b8aa5c4a7554b5aa8d186fd36729` | `c04eca268540cd88028d9887cbb153ea014095bf497a1e81cdfe28c226b19497` |
| `app/src/main/jni/yaya/md5.h` | add | 100644 | `c1f4d5178dd44fa7b5b9dfe06b1716b92266a8fd` | `25896e0792fedb8ac30a15e2eba4640219b0950cbf5ea0b4e2fa88ca6678b440` |
| `app/src/main/jni/yaya/md5c.c` | add | 100644 | `0d654e5f95c362231239ae45d1fecaf75e733ac6` | `d0dffdab052bb193f5098eae3625a2e40ae424dba4cf888f212b68e2ee5b1323` |
| `app/src/main/jni/yaya/messages.cpp` | add | 100644 | `cc9af071ba1c5ae574b2b922aafc40afea62062d` | `93e42ad7d3fa1f2e11acd4409390db6c502f47d949fcb475d921dd0eba7b7aeb` |
| `app/src/main/jni/yaya/messages.h` | add | 100644 | `e186d1f405a0608a3a603bdbaafad953adc04f5b` | `6dbf9bca9b253b562d3ab33bf91f241d746ae8fc064596a9ef8191646989bb0c` |
| `app/src/main/jni/yaya/misc.cpp` | add | 100644 | `70ef671baee286c2e9db7c175491f6f07cb74c9d` | `0ba4a01a33d5f417d90f26e08ab00c16ca1cce42cab7f8e7c9e590a8353b187d` |
| `app/src/main/jni/yaya/misc.h` | add | 100644 | `4f3089ecbc199f44481039bc423d3a342c734dca` | `a7a4562f8c5432ac727a38fddb27280432af1c9c428c2cde4fd7ecadb283d038` |
| `app/src/main/jni/yaya/mt19937ar.cpp` | add | 100644 | `ba579881e606c159351de55d395fd06d70fd8e77` | `2cbea0c33cc8a53446354a2c38cf3ea557ba874b7b4d11d98dfb08960a39e466` |
| `app/src/main/jni/yaya/mt19937ar.h` | add | 100644 | `c59146d5168ac0044baeba27a89d538798a1db76` | `e40eb505bd359848b560a21b97606be6811c484dfb6a9ed535e8adce37461a63` |
| `app/src/main/jni/yaya/parser0.cpp` | add | 100644 | `f59c9148d4e4e594adfd5d75622c30053723845e` | `e6670bd94e43f62e569cb81b42b7da040b442feedf09a4f8cd85b2a993641bef` |
| `app/src/main/jni/yaya/parser0.h` | add | 100644 | `2563e9923f58c4b550a744711c1af7d85aef00ab` | `6c7ff031db885000dab5a26ba9a7798627b52d20b2aecc2f967c9e8c9bfbe0eb` |
| `app/src/main/jni/yaya/parser1.cpp` | add | 100644 | `21d15e7b74b7b87b5a16a96102e5d3cbbda7739d` | `ad059311981a5011aaebd53a77e160599a5ca1b7a2029dc64217a41222b15d03` |
| `app/src/main/jni/yaya/parser1.h` | add | 100644 | `04b7e4ca598a194501d10ab6ae98d83c7b4b7d4d` | `1b407c3d12aa12f4b164cfe417a5fb8c7a58ef3c31ffe03f67903b42dc8b2f91` |
| `app/src/main/jni/yaya/posix_utils.cpp` | add | 100644 | `aad27c7ee85171f5501a455381d6747a4d10d62f` | `80715dbc61b6433694eb865881fa311d55e8759cb65c5baaf69598b8d9793a77` |
| `app/src/main/jni/yaya/posix_utils.h` | add | 100644 | `285f53356206bd01ee110b8609e465ef14939a90` | `484c5504935b92010aa0e5ec396d95265acc2a80e7e302d7c392d935f6d87576` |
| `app/src/main/jni/yaya/readme-original.txt` | add | 100644 | `e730638ad2581f227f275adc8e19624a2eba778b` | `9b0342290aa600a41fc0023fa5008da451c5fdacdf91a5495c60da8dab570537` |
| `app/src/main/jni/yaya/readme.txt` | add | 100644 | `b653a27fa7e8cec0c79686dd0ceb116be39eb652` | `fdd9fb5b10d1c76cf97fdf495f718d47e47d863bb09bca1add3cc0ea75247ea2` |
| `app/src/main/jni/yaya/resource.h` | add | 100644 | `ff5678d66cc78ebbe45136488a5c411bc4e577d5` | `18adcbe9297c91b7578d4da7d3d3c90257832980ae69c25aa2b43aa48f875636` |
| `app/src/main/jni/yaya/selecter.cpp` | add | 100644 | `f34e89ac2e595555a3c1a1221da217dc17ab0914` | `e184264b3ede0f1640d1269f0469914ac797faf3798b917d3e490d705df668e7` |
| `app/src/main/jni/yaya/selecter.h` | add | 100644 | `89e45a69cce5afc3a5971e892ebe66e896a17e24` | `203436a35f65e6a7d8be969db18a99632693660d91d82450a4c8366e8b84d04e` |
| `app/src/main/jni/yaya/sha1.c` | add | 100644 | `a3bea6aea4742d1f94a1e825f94167d78c3ab379` | `9714a877277c9c07f554812c69478de4bfaa497088511f83d1c37ef9f3a4929e` |
| `app/src/main/jni/yaya/sha1.h` | add | 100644 | `c5042e23282cbc54ac2a1c91121ffa5c7f6beb9e` | `e042aa1896154eeb84d2b1eaae7c3698f48ac94bf964c513aca4e4c675252271` |
| `app/src/main/jni/yaya/stdafx.cpp` | add | 100644 | `3cb92cdaa217f4a66fbe1cb378867d1194621a95` | `3911bc00321515d463116008db5b519f72b86be6562779ca4384d7f190ae629a` |
| `app/src/main/jni/yaya/stdafx.h` | add | 100644 | `3ad0e060c5f95bf6c0c6a9f2fe8583a09c1cc52f` | `d8d1387e0f9d552c901b2e7677301876fadd302a8c767a61074e3317751d7dfc` |
| `app/src/main/jni/yaya/sysfunc.cpp` | add | 100644 | `6e22a89308388b5fd3c3b067eb3763a281177c0b` | `2d62d84a602c1470050ea639fad5b36f79d1be5cc5dc935a5e23921ab26dc70c` |
| `app/src/main/jni/yaya/sysfunc.h` | add | 100644 | `e9dc0627ad22eb7204b50acbf00b6c387e86a46d` | `151c6f7d7c72b0ea063801f0c4502e52e80c5b87e7b18b822474b42d52e57899` |
| `app/src/main/jni/yaya/timer.h` | add | 100644 | `c2eea0f4fed929e1b73360219830fc7c11ed4989` | `4b30182a755e1a0416bd43fe23c847a39ad86a9789d18ac7a0aeff47057c31e8` |
| `app/src/main/jni/yaya/value.cpp` | add | 100644 | `4508907b35b684f8ca42f64b781deef1b94930c8` | `3257b77c5f451a6712a54cdeae6a60039e4b544f828bc216fed5b322f1f9c6e0` |
| `app/src/main/jni/yaya/value.h` | add | 100644 | `c1530e2b50ee6d1178ffff6f3761a40463d7688d` | `7c617717ca9d91ddf861f7ead797a86b7376bc00a3ffd15679911cfba9f379be` |
| `app/src/main/jni/yaya/valuesub.cpp` | add | 100644 | `05f4e817630293ea83c1ea91ef487d843c264659` | `2b593579e3d51ca1fa683313483d62d9557d485cfc56d91957010f6e1783fdbf` |
| `app/src/main/jni/yaya/variable.cpp` | add | 100644 | `1535e7fad5772b54578c0fe861c57fceb91ee2b5` | `fa579b92a1ab85936daef88f5dd1b47933bc4ec4115716275eb17022f81a8077` |
| `app/src/main/jni/yaya/variable.h` | add | 100644 | `c56df4f51706609ea9280cb3312811ebb14d1bac` | `4fdefbc6a58ded164210377d76fa19be0bd94e20ea85cd618a754ce012d9543a` |
| `app/src/main/jni/yaya/wsex.cpp` | add | 100644 | `de64e9ed5a86c7b9d42cfa8f09357a82fbe4c232` | `289d9dac2e5711f7e0b4410512ce63a64064abeb70f85554243a1c955b44e3de` |
| `app/src/main/jni/yaya/wsex.h` | add | 100644 | `e6a4b6afb02835fc2ad774d92b474752f2edfda7` | `2c4536d49e1263e8656e941af2659efcbdc1d2623c4d3c8b0e685cf99dc7e736` |
| `app/src/main/jni/yaya/yaya_jni.cpp` | add | 100644 | `c3bdddbe5f667656263c407ad97ab8798fb58c61` | `42fe54c2a7c522dc00d19a8b7343b31426541127fde232ffc33dde42f198bab6` |
| `app/src/main/jni/yaya/yayad.py` | add | 100644 | `a3833bd013c2cb6cd25f6b07c5b57695142b059c` | `50c7aaad9f873234b586c097c728e85c6d49a53af9518850fa97bb02c08c19f9` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/MainActivity.kt` | add | 100644 | `6f3b4b54d7a0e77e3a932a2f7797b1b3b7106782` | `5c054c50607aeea8f7c1c16beca7822753df3d19485cd7356321af4bf8c8a3ff` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/NanidroidApplication.kt` | add | 100644 | `e0303637eb2b4f24d07355c6067e174e575b3e46` | `eb8364d2cac585ef7c373128e406177ffeee0b78b393abf8fdb8fb8676f6078b` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/data/BootStateStore.kt` | add | 100644 | `f94dfb924469fbd98125def11e28c923d84a5009` | `752a6892537bc98703337d13236aad6f458782915ab6d1047cdf19f94f3adacd` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/data/LastGhostStore.kt` | add | 100644 | `3f527b5a99da9b4cd5c211eb07b8e59e5168ffd9` | `4de31361e462bb4804645c0486edd9888e092f28670dca88e83117e36e6c905f` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/data/PreferencesBootStateStore.kt` | add | 100644 | `421883e413d01224a48649f7f6145b9b92113e06` | `4f02aa3729ac7a6b6c43102a70792231ec6d1b1a05f773cb8ce276ec05196443` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/data/PreferencesLastGhostStore.kt` | add | 100644 | `b0c916ce1bb67c9d4314bb57766a619f831b0e27` | `c9021839544b5284bfd07f8b3cf662d300d119a1fcd9d48931bd2ef3bedeee91` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/engine/BuiltInShiori.kt` | add | 100644 | `8fd096959066c2e607ba8ba51014eb8820a80c12` | `83e5f5b68569f561c37061d52037702605f4eacd82b03cf84cacf95f80f27c61` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/engine/EngineSelector.kt` | add | 100644 | `f1ec63bed0b7d5e85bdfc7a11f989112b78c47cf` | `e73aeff628d33992b9b76d321fc953037c99a4f250e4fe45176b96813d772016` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/engine/NativeShioriHost.kt` | add | 100644 | `9795faa5ae40f5d508dc010aaa37ea68c24a34d1` | `b99a7bdd83238a11532b60e1b57ccdda1920dfa396be6f563d0f3fd1b75797ee` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/engine/NativeShutdownResult.kt` | add | 100644 | `4b02149ff9a1613d5b94654101f31cbc3b0fb174` | `766e9fecc238cbc04f23923b9d73d464b00c2f65077b22b2c8be6793ec51d185` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/engine/ShioriCodec.kt` | add | 100644 | `2caefd41a9adaff91e426d003fc63e65176bca77` | `c19beb9fff62f57b300bdb43ae4bfc756c018263060b411822e1e8b1e4c72cd8` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/engine/ShioriEngine.kt` | add | 100644 | `346e4535345014f348c97856238ad6563488b66a` | `58d59d8fcf754dab0e5c895fd4ac68f78134893593df854ea741a57e95bed22a` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/engine/ShioriEvent.kt` | add | 100644 | `a7284da30ce32b44abdcd02c7eae5fe94d7ad856` | `fc91e599f8731d8ce4530107102b8f476f60bd7252debc09fd545604f8a1d8d7` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/engine/ShioriReply.kt` | add | 100644 | `e049ff757195e567d9c485472719d3bfa0b0ea87` | `c5eb39d175604339baf18a717aef0f0101f9e865a8eab624aee1c902c416196b` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/engine/UnsupportedShiori.kt` | add | 100644 | `9448df5c3d9e6629002d0bf58d8c28945ad45d24` | `19adf647a7a9c9e34b3b5e5d434e3fb807d13ab3b8078d250700017e045dedeb` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ghost/BundledGhost.kt` | add | 100644 | `832e96060b12f61f2ba07906e0193b72f285c1d8` | `dcfd43fc09487ad167b06946fe022733bf34034c6080c94849abce6cc612f7b3` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ghost/BundledGhostRepository.kt` | add | 100644 | `0a8a9528f81389197391fb65d2715bf5a3cff1f0` | `3ecbf81dde72ca03e2f2f3c737e4d8cd7bdaf8c91e93c1a8ad2ca0e4bc2fe149` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ghost/DescriptorReader.kt` | add | 100644 | `3a8b03340eb2249b4ca49557128b87a58914731b` | `091d36e5a0d251e00e7b61dc239ea98efe8630b30a74ae96801cbeba222f7d3b` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ghost/GhostDescriptor.kt` | add | 100644 | `698062964c6de6036f3e4a4578bcf97a514f2d2b` | `3975c6eb0cb32ef556505ec87ab9f59ab8fb82a696bf2f27897ab478215fc177` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ghost/InstalledGhostRepository.kt` | add | 100644 | `34c578096910fdb75849d4bc1947920fbbe78ba9` | `a2a3243d68c2c1b5eed5761d24f4b8a950713f2336b1e1a00f213593ffb9d788` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ghost/NativeProfileDirectory.kt` | add | 100644 | `a9535621ec8815bce5f9e73b1da10cc2e739b5a1` | `9c0468aff8d97c6570938c0900902a7620b00ef25f1a4177a91d96bd3655c4a1` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ghost/ShellCatalog.kt` | add | 100644 | `5891599ee930210edbb0e8c77df8d47f3e59dae3` | `01ee9c5319d6e4d7f14686f80b0d1f9bd7036dbbbad69129878121b3f1f23f3d` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ghost/ShellFiles.kt` | add | 100644 | `4c7dc10930bb33317773198e0f4ae827ffaab38e` | `c3a2b50ace21331c3bb1ccdcc6e48953a8a984171bea7c59feb48be2b5da81af` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ghost/SurfaceCollisionHitTest.kt` | add | 100644 | `444da6f6df114f6b9a9f0998dc765f8d53123a58` | `5f6e12bf0adff2bd0afaaa1f123a22c839d3fd7cb923297f723d43e870039b56` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ghost/SurfaceComposer.kt` | add | 100644 | `ec25dc723b9db66dd71fe5f00c6ee0aae04871df` | `3d1228dfd6014becd08df19cb25b2e939ae147bc6b72af916d8e1749f1af8b59` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ghost/SurfaceDefinition.kt` | add | 100644 | `fa6e3e86a42273f75d01fe6963be8b6be861593d` | `710bb14774021060989904eba8aa461d4f9ced45e91fac2fa265507da06ee043` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ghost/SurfaceDefinitions.kt` | add | 100644 | `778064874083430b47f4db5b7f69bfac18d6e42d` | `df1934aabe30c38e0d2bbd547face85cb592100c412b23e6fbaa81d2db10663c` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ghost/SurfaceFileName.kt` | add | 100644 | `74f1abebc227eee71a89093e906085d2ea0996ee` | `7fe529d1c8001b16646f9f67fe3fc7873aa7c338044129aaec7e5453eb7a6b50` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ghost/SurfaceImageLoader.kt` | add | 100644 | `a166871fd32f57cfca59e635da2732a8558daf2e` | `f0499d52d076292ca02df793981d6d44bca0b02e969d2fcf12b358000f257742` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/install/ArchivePath.kt` | add | 100644 | `bcd1e4f353d80b2604b4b1335a434c7f3fd5f571` | `e069f799d36502c1a4461b629b28cb1bf421142e391b229e939a487c244c0c9a` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/install/CentralDirectoryEntryLimit.kt` | add | 100644 | `9dbaa08b8933ae29c425a2e8b6dbe5eafb87b5a4` | `9cf31a1ad19afa0d54af3e1a53ac6c62738506491d82b2cbc57839a8717479eb` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/install/GhostImporter.kt` | add | 100644 | `2d08e4c6997a3ecc66df9515dfec645d909264f3` | `63a567ceaddd27a4e961d6e46d6ecf92ebd5fdc71eb54ebec2e071df27d8e0da` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/install/ImportAttemptStore.kt` | add | 100644 | `b3bb799cebfc8f077dad3a651a498b469171d9d5` | `37045625a7afc8f700ba77966f05cc1b2d04cab8575e4a5278200f0b36ebbeba` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/install/ImportCoordinator.kt` | add | 100644 | `541ad04436bbbbfe0a39531e31a04da04d12f513` | `850d98fff1f38fe6d42cabc827a7e7b9bb6fa075c86ef7c678e5c36b0e0546f1` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/install/ImportModels.kt` | add | 100644 | `6a45df3fd8484cf96ca7bb391d7ad62d149e2537` | `ecaad45a26a2603ceac0659a573ff8e3438110bd3d8105aa9a0b11e257a189d3` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/install/ImportStaging.kt` | add | 100644 | `90f880b5596bdbcf47edfcf3be7dea2b09889077` | `ff3ef46efcefef7bb734955bb238bd047bcac9158d614695451db6d05e9d4309` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/install/ImportState.kt` | add | 100644 | `fc7b3049631997d8e101580975d8066088916059` | `989c9c5a8d11a00f320838d64cd3c9f2f482723384fd4ac664fec6b821b9d3e2` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/install/NarArchiveReader.kt` | add | 100644 | `3896d867b42b42fee69c6c908bc70f879ea8d28c` | `cbbed364be6eec9e2de158a649eafb8338cb31ad4cc339e7fd00035076355cd9` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/runtime/GhostRuntime.kt` | add | 100644 | `6a22839d8acaaf90f5566eedc56c23b0f5d456b9` | `ac02b2a5545200d1f57a1f4ae83da6cdb50988b7c80ade7ba5854192f2f41dfe` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/runtime/InteractionToken.kt` | add | 100644 | `9be7cc160f62aa3766a632a3af20c8bfab2d0205` | `3b944e6042da77928b17846fc2379d59b5972a5008bed67e08ee5e93370a78aa` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/runtime/NativePort.kt` | add | 100644 | `3789bd59c721d7004849d16e9fe4289e5a716c93` | `17c57bb18e2fc68ab9aef77b1f8aa6f6c93d88dbe3e193ccf43282636f22668c` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/runtime/PlaybackFrame.kt` | add | 100644 | `03693fa03e6ecedfa7ef59e2e2d7f57618a4b102` | `654dd6bd945c388dfd0779a38f1ac65fd68b49fb98d17f3e0d58c5591029afb3` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/runtime/RenderedMoveGeometry.kt` | add | 100644 | `3237eacec9ec09f03a5c51eec72166c5ce7d37fc` | `9df54fd6fef3a9f169fa7dcd5830a6a9c84d81b3d9eefd4f8720c5625499915c` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/runtime/ScriptInteraction.kt` | add | 100644 | `e36fb9247b6268dc4639825ed7036113e5f78778` | `4412cd335d332a167130ee090724baf6d852ef0ff82ad6f1dbb32ec1b2ac9865` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/runtime/ScriptPlayer.kt` | add | 100644 | `a17c17c42d4486bf708108c50e1c66f36d8a9127` | `d19bc5ae7d5958782d93d3c9c016f13d7bc24d820c89aea2ff5dd4abbf2101d3` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/runtime/SpeakerFrame.kt` | add | 100644 | `52235a636146cc85c2c4dd5ad876e23cfaf71cde` | `257bc4ea8d899774220bdacd7163fdf571bf2f5bf6fc06f8f71072a182798d80` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/runtime/StageState.kt` | add | 100644 | `82aaadc5083eb2adb5e589b54f95c216d23c9f44` | `6e4921e9ecacb0de4c35c02293a44fdb8fd4a766343ee982bfa68b06f609f381` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/runtime/SurfaceAnimator.kt` | add | 100644 | `95b58038c92d112a1cc1eaf0ae1e7ee47639fe0c` | `03b704a07c1a81f7795d34219d2a5fb5dde716c2ee56851b9053573ac9388702` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/shiori/Kawari.kt` | add | 100644 | `a2f3adbb83203226d3749543ad1786bb39bcdded` | `d1c91ff3ae2bc722191fb5ef8119dceffdb90f7e306154d360b8eb50808c3324` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/shiori/SatoriShiori.kt` | add | 100644 | `6aff15eb1f4710a0ae56e67136ed4f8983fb1406` | `4f2ca0ecddc01bf7116be59d79645258cba76607e1fdceec2081532628c9313f` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/shiori/YayaShiori.kt` | add | 100644 | `8efb0475a976fcd858630cef0519c69a6f3b63a5` | `dd4718854f5f1f224d6d7785521d22f5c041ea5560dbdcc1e55d8cebfbaf0b37` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ui/AboutDialog.kt` | add | 100644 | `ec96895baec0cde698e8a8914d9f855679de5b64` | `4deddf651d30877f55c89259f2127de9bdacdc544f435ca5abb05d4a912a7135` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ui/AnimatedSurface.kt` | add | 100644 | `a551c92b1030b3ef88e09f7bffa6407314f915ba` | `a45f5fb6e9fafb76618c5e5eb74febc38305d8936d24b89d69d61f844308facd` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ui/BalloonContent.kt` | add | 100644 | `baf902f544aa3e397181f0389231511bfdb82811` | `5e594aaac07cea8e98a743e59cf9dfbb5efa1f2298eca3f01cc3a0cd1710e5f9` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ui/BalloonLinks.kt` | add | 100644 | `738ab15e24af468e42ff483993e6c578a880fa0a` | `81e506fdc72d40f8e9160ec137591449c9ce524dfb712a417ffb9e9fed0315cb` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ui/GhostInputDialog.kt` | add | 100644 | `24f387a20e17c379d77958f26cba1adfdd6dd3bc` | `6a313181de53be585adcb0089b31c9569d0702d23c66f679ff9e5638f1a85b2a` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ui/GhostStage.kt` | add | 100644 | `d59e4c97387afdce1365179aebc5f1ffaa08a18a` | `943980f62b84376fca8c607b5c1ebeb09cc54135df7a35849549ec172ce23f4b` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ui/StageGeometry.kt` | add | 100644 | `725b2e1c46a4a02af45ea5cb350bc0cf92724122` | `306763df7a2bdadfe480bb434445dde7c6ce93f8ab12ed6cf3a680f7198b15bc` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ui/StageViewModel.kt` | add | 100644 | `67cdeeca689a27cc2d5d4ea30ac139f404a6aceb` | `d52b10203329c86e39759a308b7b3f15eb9c0c102e77fe9ea01587a5571ae18c` |
| `app/src/main/kotlin/com/cattailsw/nanidroid/ui/Theme.kt` | add | 100644 | `dd00d776971d0c38e0e752d828cdfe665c7b93cf` | `f7745e1ea89d5447d3c31853a6b69339472d34233dee429f1b72ee11d326614a` |
| `app/src/main/res/drawable/ic_launcher_background.xml` | add | 100644 | `07d5da9cbf141911847041df5d7b87f0dd5ef9d4` | `ed423c73a6f40a4d2909f0901e60527b3a807cd59e1b5593bcaae1808b1c6321` |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | add | 100644 | `2b068d11462a4b96669193de13a711a3a36220a0` | `01d1a6a6c1234eb7fe270d097eb283d72b9c95ae5118886f1b6573aad280f1f7` |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | add | 100644 | `6f3b755bf50c6b03d8714a9c6184705e6a08389f` | `88f7653499ef524126ea5018a99baf9cc3269e7e584d6205dfdd76db39f39cc0` |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | add | 100644 | `6f3b755bf50c6b03d8714a9c6184705e6a08389f` | `88f7653499ef524126ea5018a99baf9cc3269e7e584d6205dfdd76db39f39cc0` |
| `app/src/main/res/mipmap-hdpi/ic_launcher.webp` | add | 100644 | `c209e78ecd372343283f4157dcfd918ec5165bb3` | `dd00996198640ed28fbc09cdcd7a3807cf8707f3eb255b659634da3ca6a6ff01` |
| `app/src/main/res/mipmap-hdpi/ic_launcher_round.webp` | add | 100644 | `b2dfe3d1ba5cf3ee31b3ecc1ced89044a1f3b7a9` | `1ed73f5341a69d3b41c7e02e126803f50cc8c3284adf4bbb737f0c93577aef07` |
| `app/src/main/res/mipmap-mdpi/ic_launcher.webp` | add | 100644 | `4f0f1d64e58ba64d180ce43ee13bf9a17835fbca` | `846219e6f72fe9a6c104ca8919cbee36a101e7d2ff8da9da67b689a5888f060d` |
| `app/src/main/res/mipmap-mdpi/ic_launcher_round.webp` | add | 100644 | `62b611da081676d42f6c3f78a2c91e7bcedddedb` | `4e2c58b91de01130e6479e00cbbaaf6e77cd961dd2c8e303cf13cd077fa92632` |
| `app/src/main/res/mipmap-xhdpi/ic_launcher.webp` | add | 100644 | `948a3070fe34c611c42c0d3ad3013a0dce358be0` | `398340dad816fc9a6338cb151a8cf1e45b926f9bfb70628b24c4bd2523cc94d4` |
| `app/src/main/res/mipmap-xhdpi/ic_launcher_round.webp` | add | 100644 | `1b9a6956b3acdc11f40ce2bb3f6efbd845cc243f` | `91b490aef86574901137f0a252272e1f1add99c1ad709e0696ed68929b88d261` |
| `app/src/main/res/mipmap-xxhdpi/ic_launcher.webp` | add | 100644 | `28d4b77f9f036a47549d47db79c16788749dca10` | `58ae87fa0c5b5d1562d27fd648d2c061553fe20e3ed570bde588162d01ea7a27` |
| `app/src/main/res/mipmap-xxhdpi/ic_launcher_round.webp` | add | 100644 | `9287f5083623b375139afb391af71cc533a7dd37` | `3009fad079f5772f30ecd767f98924367fbe0f81c30048d672d4fda2d5ca7d12` |
| `app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp` | add | 100644 | `aa7d6427e6fa1074b79ccd52ef67ac15c5637e85` | `f98fef5bc3bfe5b65692c40ad1cbae2bec4faf9f1b249c397626740db71b62d8` |
| `app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp` | add | 100644 | `9126ae37cbc3587421d6889eadd1d91fbf1994d4` | `5faf033745c8c882c43bb1b372d4e4a5890caec63d9892a2e756c954108b85dc` |
| `app/src/main/res/values/strings.xml` | add | 100644 | `e0ff90761156ee8d5dc7c19983bbf9c3c331c385` | `b9ee2202295d38791e3b12334140245d8f7a22bd5608102e0a66b52592cfbb5e` |
| `app/src/main/res/values/themes.xml` | add | 100644 | `174123413db2c756da126a984d07d0d1df168448` | `e93019db4bedc4311d36d344f1b6a0df28571aafd75d482a84db589fd49bfd2f` |
| `app/src/main/res/xml/data_extraction_rules.xml` | add | 100644 | `b853a8d2452fe340b2e07d1899f3437e49fb123b` | `cb029b35db0e976c087100424e16e14a6256b46962398887520e51508c3b5842` |
| `app/src/test/java/com/cattailsw/nanidroid/data/PreferencesLastGhostStoreTest.kt` | add | 100644 | `46a40dccc8b0cb6f8b2cf9fa3676e39d1970f2cb` | `fd95ee64db8a60b5bea1de09c33977f468f4c8f48b2e05ddc82104cfc3db8d2a` |
| `app/src/test/java/com/cattailsw/nanidroid/engine/BuiltInShioriTest.kt` | add | 100644 | `28738e82bf2dec7cdf450f53b89550570a4a80fe` | `22724e964ce71bd669684fa7bea0da4343f4e00b5066a129171da0dfa3e28b7a` |
| `app/src/test/java/com/cattailsw/nanidroid/engine/EngineSelectorTest.kt` | add | 100644 | `49abcbe7f15c87dd0e783c6b1e4793c7a94f3683` | `9b3415a4dae370d7e09e9b40d3f537bc3f545b50162291313e2deb19f679b958` |
| `app/src/test/java/com/cattailsw/nanidroid/engine/NativeShioriHostTest.kt` | add | 100644 | `d032400ea1f926d2be33aea550192983c235646c` | `580b5edcb606a42d9651ad9cb5e1649dd9a5bedf6e1185dd7b1db99416cdce9a` |
| `app/src/test/java/com/cattailsw/nanidroid/engine/ShioriCodecTest.kt` | add | 100644 | `fb8df801639b178346eda3f0cf28173f0808e4cf` | `4a7ae40860fd30c0c8d4582856868fc510084b50d5b11eb82feada2e6c66ce4b` |
| `app/src/test/java/com/cattailsw/nanidroid/engine/UnsupportedShioriTest.kt` | add | 100644 | `0d6697a4775ce72a54068da4e830898e555fe832` | `78e75306ecd695b08f2b1924288d32c8e50f6619ad25236ab9a7a5a635d25c67` |
| `app/src/test/java/com/cattailsw/nanidroid/ghost/BundledGhostRepositoryTest.kt` | add | 100644 | `488f234004cdd6e8f32e55573ac0b68cc7e2a777` | `46a1ea9c8d22b973fc17c7843608676c0024167afdc1c68b5ca950d939e53bda` |
| `app/src/test/java/com/cattailsw/nanidroid/ghost/DescriptorReaderTest.kt` | add | 100644 | `e2373ec020355d2489d6dd7848bd892c757a036d` | `632ae6d4fce64463eb1353c2261c5703120730ecefb39279527e0ea0c7229abb` |
| `app/src/test/java/com/cattailsw/nanidroid/ghost/InstalledGhostRepositoryTest.kt` | add | 100644 | `d3aee4a137619afebaf99021a74fca684cfe3e20` | `051b06f344c1d1d462eb5952791741c015a58604942f26b56834682f78d43829` |
| `app/src/test/java/com/cattailsw/nanidroid/ghost/NativeProfileDirectoryTest.kt` | add | 100644 | `2c9fa980bda51364e5061c3a175dd119cc55ad4b` | `18f60e2081d7f197799f8e6bdb41dc32492e129069222b6a3a752c6ecd27a6c4` |
| `app/src/test/java/com/cattailsw/nanidroid/ghost/SurfaceCollisionHitTestTest.kt` | add | 100644 | `027d03fef9249b514b7d7c039f3c29b7b57d4f72` | `483e2ebf3d706ab6ee40af75872d39979e18e754090156db57ba1cf5d5afd94b` |
| `app/src/test/java/com/cattailsw/nanidroid/ghost/SurfaceDefinitionsTest.kt` | add | 100644 | `bc45f850175cede93f367f18c722486f1b848fb4` | `8baee025bffa2eeff8003cc7f230ee46fd4f45828e6502246ddb6280510ab2be` |
| `app/src/test/java/com/cattailsw/nanidroid/install/ArchivePathTest.kt` | add | 100644 | `6e61720c28f72d5ae80e9edf7930e382c7b6ead8` | `33da345f9b4ec8fcb6a7a6aa3b4f7dee98fc270baa602741316863ea1ad1525d` |
| `app/src/test/java/com/cattailsw/nanidroid/install/GhostImporterTest.kt` | add | 100644 | `549bfc319ff89a2d329af60beca57238b5fa2377` | `31dcd275629371184c926b0b16e4b7f7c5dac664e7de1eb446ade923f924b0c8` |
| `app/src/test/java/com/cattailsw/nanidroid/install/ImportCoordinatorTest.kt` | add | 100644 | `f38a4346b77d334c267ca3bcf10bfca106cd03bd` | `89155e28a3df683deca9cd8e8b72ec9d05e8540582c33d123e69b129189b71ab` |
| `app/src/test/java/com/cattailsw/nanidroid/install/ImportStagingTest.kt` | add | 100644 | `6515315f8bb8df5ffcf6d952957ae7778746efbc` | `6dd4fe8d52b711ead316505e99a790d84192ad280bfa28dadbe64f21270e203c` |
| `app/src/test/java/com/cattailsw/nanidroid/install/NarArchiveReaderTest.kt` | add | 100644 | `35578d6e34b7b61e0537ea42ab6f23c14bbbb33d` | `ca6c39656b34ef0525d42ecd625d72a41ec6d699d07e3fdd28f43a852fbca670` |
| `app/src/test/java/com/cattailsw/nanidroid/runtime/GhostImportEventsTest.kt` | add | 100644 | `d7e8108d43c2f605fe2100e4dafd45b0b9324dcb` | `af3f32f19b39a6a292d9bfb3f9c7c39f4ceb96599203dfa016fd3c4e3a2fd268` |
| `app/src/test/java/com/cattailsw/nanidroid/runtime/GhostInteractionTest.kt` | add | 100644 | `4ba6b5fda5d9c2295deeb6dc9eb1d65b7fa68250` | `eff6c69e450f69d702393ed2417835bb3f9d2e6f8a200a109e9b601ec9eb4c93` |
| `app/src/test/java/com/cattailsw/nanidroid/runtime/GhostRuntimeTest.kt` | add | 100644 | `44d861f9d70349ce3897fba8b329e1eea07bb3e3` | `24c7a6ad10e03c2a353c69dd9fe7a03fcaf5b96e4ddf6e06143f4ef79609c4e5` |
| `app/src/test/java/com/cattailsw/nanidroid/runtime/GhostSwitchTest.kt` | add | 100644 | `b0789df7a0aecdf531ecd6c6695b792c114975c5` | `eebfd67725af9b7bfb93cdfedcbf38f1c8bde3714a75996f5b874def65aabee9` |
| `app/src/test/java/com/cattailsw/nanidroid/runtime/ScriptPlayerTest.kt` | add | 100644 | `4a4daf001c48618854122dda11f7c790ec0f4ec9` | `54a96ab9fc8695122ac3f3841ef607af2cd24340da0d3b904748bc6f96dfadb9` |
| `app/src/test/java/com/cattailsw/nanidroid/runtime/SurfaceAnimatorTest.kt` | add | 100644 | `1fb5ad321ff8f3b00aa039396b907dc400de495f` | `5e32d395630dff0129a7268996df801f84ea95de53f8c3ac2703f360573a5e55` |
| `app/src/test/java/com/cattailsw/nanidroid/ui/BalloonLinksTest.kt` | add | 100644 | `9e0565613f395b0d36b23c0d3c512c933df7e1a5` | `836bc7aed672f8481998085336606d3b02d8dd77706d8d94feb35fbd9828a184` |
| `app/src/test/java/com/cattailsw/nanidroid/ui/StageGeometryTest.kt` | add | 100644 | `d871b2e77d7d8750d09d827a54d0a76a20f53ab1` | `4591ee40174eb0bc7df31d8dd5be0cb75e9898ca97ec330f260b3c7adf9a4ed4` |
| `app/src/test/java/com/cattailsw/nanidroid/ui/StageViewModelTest.kt` | add | 100644 | `c550999ab218875b09256e67b1eee1a8ffedd302` | `9f5d7932e5cd498d9c9e4d081ce08197bb53aceea607d6432077386305353b45` |
| `app/src/test/resources/nanidroid.zip` | add | 100644 | `f01abd7497107a075e7e6955741b11165b8bf277` | `2ebf24a2be8255011c5e004459a58dd1317eb7b12a55adcbe6b8b2977c89dc2d` |
| `build.gradle.kts` | replace | 100644 | `bb92a46fa18cf09dbd09ec4971e72bb2d6f6d0c6` | `432ce5820bca46b9d91ddff8cb8dde64ea26b455b637e96c3e0f059cfeeb4f56` |
| `docs/extra-corpus-scan-evidence.md` | add | 100644 | `74b3ef397505dc933f59b8f992b3cd6167627e2b` | `12ac5936732a5e97879881cf71a67309ad6a2bbc5d851294dc55e906b73105c0` |
| `docs/implementation-handoff.md` | add | 100644 | `d843c87a78e34c8d5da6845747924824cc0b8ae5` | `bb0cade50a40c614b0b6eb20a54f076d5afaca553c8dc86dd66498524d26b24a` |
| `docs/milestone-1-evidence.md` | add | 100644 | `0f28a7584a8c25d48a8d22ef6c45d73754c013fd` | `c40c9e569d71831562aae50e76dc5f48f136ec916d53a16bf5ce0d12d7981980` |
| `docs/milestone-2-evidence.md` | add | 100644 | `78392672f73c0c8787d255f93487e197c2e24eec` | `fcd16116981234fe6ba9296983524d88a1548e694ce156231f939eba2cafb6c9` |
| `docs/milestone-3-evidence.md` | add | 100644 | `bb0e4ee14299d3bc676920eaa432f0c9380d6094` | `5432c0b4f7453bbd599e51d10ae9c7c222c547e9820c99c355642a877b45052a` |
| `docs/milestone-4-evidence.md` | add | 100644 | `9fcc8a72129268b93d7fc2634376de717cc3b49e` | `1e02408407015a670a7b375bb055390e456437f68375b0400602765c0c37b2ce` |
| `docs/milestone-5-evidence.md` | add | 100644 | `3cf177cee689c49cedb00d80b94e897905ee750e` | `d5f587d2bf9b7566ea6728d224eeb8ce5e2dab72e82d16aed220e830992e10de` |
| `docs/native-persistence-evidence.md` | add | 100644 | `310dc6eff731825bcb2eef815480bd705264aa85` | `ba03067cee610dbdbfd075a1431ae0cf2deec76fe02be7872cadd0f5ca421713` |
| `docs/review/2026-09-24-rendering-clock-review-fixes.md` | add | 100644 | `72e83dff630674f91d35df0e2a0071047582b59c` | `e8fef6000f851d9b3e117add63e993c1802cb4010871d648993974394e6cc453` |
| `docs/review/2026-09-25-import-followup-triage.md` | add | 100644 | `8cfcdcc5c3444fe1793747d9ce1cbdc4498cd4ca` | `454be2d166313ad8275d386d738ecfa00472016b30a7b02127289e3a6687bf0c` |
| `docs/review/2026-09-26-milestone-4-corrective-evidence.md` | add | 100644 | `37fe7f8c35dd66a4ed38de28333f3c04bf577fac` | `4a708ca7bd9950dd9b14678aedd6e6a864f90ff371b1fa0435f0d6d2b496bd72` |
| `docs/review/2026-09-27-aya5-device-probe.md` | add | 100644 | `7618b530c2c2b8de4a727576878bcacbbb34b408` | `7bd2d5e3abceeef4b57846521a4281cb8aa86a9964c11f89e10d4f3fa07c6ed4` |
| `docs/review/2026-09-27-gesture-coordinate-evidence.md` | add | 100644 | `ab451b59d56fc0ca080a7bac5a4dbf5abd32d58f` | `928938e27f14bf1c98e297914a834fae8025abe552c0b9c5bf89718bd3f49225` |
| `docs/review/2026-10-02-replacement-review-map.md` | add | 100644 | `de919fd1d6b08418d8fc7def6621da877cc205e1` | `7ce415fe084eb64a663a6f4277668108f6bc25dfe5ade54d465c652a74601a2e` |
| `docs/review/2026-10-02-replacement-review.md` | add | 100644 | `2550976cdf18c3dab980584c24d8c61f23533ee7` | `e1215adcbcf8532c6fbf900d78dbaa4c98bdb0c516f8007cb9451e793673a85d` |
| `docs/scaffold-verification.md` | add | 100644 | `3190d93918062552e8c4806e1c0d4b250d792b56` | `18a98292a75c17f4053fb5da15aad683f92759213c61d1e9782eb4a25f280ab9` |
| `docs/superpowers/plans/2026-09-22-walking-skeleton.md` | add | 100644 | `fa74e5c1d60e8852f295642f4f69f6520d4df5d2` | `4b1618825aacd37446f280975b435f5473498769df48fc697aa46b2f657b17a6` |
| `docs/superpowers/plans/2026-09-23-native-engines.md` | add | 100644 | `e1767a5c5e52b845301d16158295c4579036d9aa` | `b4228144158e34eaa9d6ed65d8e396d764dafeb0303d0cba31e7fa38b1f4bf94` |
| `docs/superpowers/plans/2026-09-23-native-state-persistence.md` | add | 100644 | `c7932eaf7ddecef8ac71e279e641df04638059c0` | `080a8cf1915a7d4079313ffc06e48425e6eb665efebe5106568b35c7aaa01b10` |
| `docs/superpowers/plans/2026-09-25-milestone-4-surfaces-interactions.md` | add | 100644 | `831c69ba6d3cb3a58717b9574e59d2592f3d54cb` | `4d33e3db7c98eac3d6c434d7d7c9f4a54f8351ffb7abf2120a72b63eb0713d0b` |
| `docs/superpowers/plans/2026-09-27-milestone-5-corpus-polish.md` | add | 100644 | `e3890ddc59380b3eb9e0bf4bdd0150e903cb901f` | `3540e457a0636ac93413121f5e1f75f8272efee68ff22acc19062980846be5eb` |
| `docs/superpowers/plans/2026-09-29-milestone-5-real-ghost-ux-acceptance.md` | add | 100644 | `cd1e1874b382d6b4fcc471818d8f44668f0d6813` | `20e5c073de996a6814dbcab9e797b286903460d726cc00173aab6923db1faef4` |
| `docs/superpowers/plans/2026-10-02-replacement-integration.md` | add | 100644 | `ca934754c9b464290ad4c16eeda4dd7def57a6f6` | `f2018f37f6c34f5d26547361fbc2691afec1162c1ff39e47f6defcba1733bceb` |
| `docs/superpowers/specs/2026-09-22-blind-recreation-design.md` | add | 100644 | `1373c2ac69482df05c8b18c5a59fe79dcb037832` | `ad5938df7d9f02a78069ccc704bf7609d8b71aed487860007213dc796c4c456c` |
| `docs/superpowers/specs/2026-09-22-nanidroid-baseline-spec.md` | add | 100644 | `ff75fa6831a6c1e4ea13c169eec4d8016b80ce99` | `581bdb1106d83a0fb6d420c22fedc3cb2b10d5c802c9844bf72b5367817717a8` |
| `docs/superpowers/specs/2026-09-22-native-copy-manifest.json` | add | 100644 | `f07f1fd8222868e45845e5ddce5a4f6a527d7842` | `ec9fcd37e4297170d04ccb4041b95b3eaa1ad47cea3ba91c8b6360212f2dc470` |
| `docs/superpowers/specs/2026-09-23-native-state-persistence-design.md` | add | 100644 | `85a8e2ffd7546e54c3e120d19e4cacb40c88e75d` | `1814993de511d6fa05f4b90eda7542412d2777207f866c617814d0b7f7b59197` |
| `docs/testing.md` | replace | 100644 | `f20eea0cee8fa715b98864ae71570862732a53a2` | `29161bca9a9733072d594a8c15ca2854057c8f3e8876135984f463f21913f61c` |
| `docs/testing/2026-09-30-m5-acceptance-reconciliation.md` | add | 100644 | `54591f15fe0b2831d69d54457ef731ca50bf64e0` | `7caf96304bca8873023ab72dbc4f1402b32430a000399a5a5924f80b3557d428` |
| `docs/testing/2026-10-01-m5-focused-acceptance-packet.md` | add | 100644 | `0c3f76374edef231c07a3834a4b311900a5a23ec` | `21d7b70d23ed99d42f7faf4924064f16bdb62813d73d69a7a46d5c31f05925b6` |
| `docs/testing/earthquake-rendering-and-cadence.md` | add | 100644 | `76892eaa06174b67ec23e80e8516731ef5c4aa23` | `5b5b3c8489f7e3168a048753ff77526bd733856420dd4900449ad769cfb90487` |
| `docs/testing/extra-corpus-manifest.json` | add | 100644 | `a01d38304f0409351536b741b12706190dfd7e36` | `756fb9369b2f6569a1f9dc5f10098f1da14e20262895bf745d412d90c2431191` |
| `docs/testing/lobo-rendering-reproduction.md` | add | 100644 | `c6417dc3f77154af54b2c375ff2214253a80d024` | `567077861631b8859d990dd0336d17e95575dc6f74f29927ddf80cb985059cd4` |
| `docs/testing/milestone-5-corpus-inventory.md` | add | 100644 | `26a05a032c752ab29409b53187b81f2dbff06c30` | `5cd3b4ed555ba44744bc4d60c1c94133e349ca5d52ea7fc524a09b43b0048c7e` |
| `docs/testing/milestone-5-corpus.json` | add | 100644 | `4ea994d21ac4c4d5f4483f8cf6ae03072375b442` | `652acabd41607de61205b824aaf6376cabbd1f7388ad5f58c5deb772a3202825` |
| `docs/testing/milestone-5-ux-scenarios.md` | add | 100644 | `4eb9c91c32c6935d4a70bce163b655500995d164` | `7638b14dac61cd033dce693df5b59793b8cb3a55d66d8a5d26034af26d935b55` |
| `docs/testing/native-persistence-fixtures.md` | add | 100644 | `776bcebf18e3689280c2f039fdfb75c34cae05ce` | `67ce274e0c12fee788632fe7fff29b37dc59981b03c913180904b54e1f5334da` |
| `docs/testing/notice-inventory.md` | add | 100644 | `80ed23562c82f27d4a65690b1bd2f81a69685b3b` | `89f0356cf410bfaf87ea82540c0cfff26cd3ce95e590758c8cf108e38019c00a` |
| `gradle.properties` | replace | 100644 | `32d72a9399f3352f538bb845a29873d9edc42560` | `3e2a08f6ddaea24aac70dfce7f069eb0aa3edee3103d6cc3479653552e949c75` |
| `gradle/libs.versions.toml` | replace | 100644 | `76228548b0d9d0ac4c01bbe8b30be16eeddd8b39` | `69d16eacaba2199609fa5e3027373d48b8f93ade2a9eb3341f5da08dbc29f1c3` |
| `gradle/wrapper/gradle-wrapper.jar` | replace | 100644 | `f6b961fd5a86aa5fbfe90f707c3138408be7c718` | `381dff8aa434499aa93bc25572b049c8c586a67faff2c02f375e4f23e17e49de` |
| `gradle/wrapper/gradle-wrapper.properties` | replace | 100644 | `c16a8a4a05e519a92f6e938e67c8b430f4ca2bd0` | `1e3d87ddfd70069f68afa0c9ddcaab26913c3baf3d809447b0362d2d49f57325` |
| `gradlew` | replace | 100644 | `cccdd3d517fc5249beaefa600691cf150f2fa3e6` | `8c4c04dd98db1f00d49456dd162418a39312c5cb13d6865d783deb483bd1ed22` |
| `gradlew.bat` | replace | 100644 | `f9553162f122c71b34635112e717c3e733b5b212` | `2f18fc6abd50803de7b3a225038d284268904c9d13caa6cf81f99365dc876479` |
| `inputs.json` | add | 100644 | `b89443dfe76ce5bd2d5bd72af15160a29ad194a0` | `a5bbb02539498558aaf73df052412c0f8b553416385622c9103bcb5ba75a44e8` |
| `settings.gradle.kts` | replace | 100644 | `8b3142137b4741344153fb3636a20f6238201c9c` | `0941d34c0214bc8708bf08f88b30e7d93ae20b56de998c5df57f9586bcab56e4` |
| `tools/test-import-process-death.ps1` | add | 100644 | `db7990810181a5db2aec18086b59b7e58484c1f0` | `6fdc89c6b7ded152c7b34dc76d4d1d2463ba1d4ea7ade311f4e217b613b87247` |
| `tools/test-milestone5-corpus.ps1` | add | 100644 | `409ea15a236c7cd4921a91f9839eb502fad88cc8` | `b67f90af7eef579ac52645848c4d192f24a75be0d37e6b047449fc32d9e55a69` |
| `tools/test-native-persistence.ps1` | add | 100644 | `a95ed3f552739b2bfc1381c6fae575ea09ce3afa` | `10ee47ee0202008dd22bbf757d18fa3a90f906eaa8c17168a87ce0e276c5df25` |

Additional explicit entries: `AGENTS.md` replaces target with freshly authored instructions (SHA-256 `0293433c94997c2110611019ddac7b994093f4eeca2a690d11fd89f654ad519f`); `docs/review/2026-10-02-integration-manifest.md` adds this committed supplemental audit (identity recorded after commit). `LICENSE.txt` is preserved unchanged from target; its SHA-256 is recorded after permitted license materialization. No other path is selected.

## Native relocation proof

All 297 source bytes match their approved SHA-256 pins. Two are exact target Git blobs; the other 295 reproduce the target Git blob IDs using only CRLF→LF on approved recreation bytes. This is a provenance proof, not permission to alter native bytes: the candidate retains pinned recreation bytes unchanged. Target native path inventory exactly matches the manifest at its referenceRevision.

| Target path | Candidate path | Target blob | Proof |
| --- | --- | --- | --- |
| `jni/_/calc_float.cpp` | `app/src/main/jni/_/calc_float.cpp` | `8957d2982d01e910b828445a6359e54dcf5b7e57` | CRLF-to-LF reproduces target blob |
| `jni/_/calc.cpp` | `app/src/main/jni/_/calc.cpp` | `173775d1c03bab9ae78e8fd8f68ac7e226d2e42e` | CRLF-to-LF reproduces target blob |
| `jni/_/Dialog.cpp` | `app/src/main/jni/_/Dialog.cpp` | `0063ce29e5b32c45fd39282ebf5e17f4b25d1187` | CRLF-to-LF reproduces target blob |
| `jni/_/Dialog.h` | `app/src/main/jni/_/Dialog.h` | `e900db308191e8abe7a2a9ebc87f802c9460e9fa` | CRLF-to-LF reproduces target blob |
| `jni/_/FMO.h` | `app/src/main/jni/_/FMO.h` | `d88254034c90612119e0e73e75d14773a09bfd6a` | CRLF-to-LF reproduces target blob |
| `jni/_/Font.cpp` | `app/src/main/jni/_/Font.cpp` | `8b00f6c851bd68148cd9b9c7ddb9c9a6041e3e5f` | CRLF-to-LF reproduces target blob |
| `jni/_/Font.h` | `app/src/main/jni/_/Font.h` | `68257005b9f15bb16a1520efd262c7c91abb5459` | CRLF-to-LF reproduces target blob |
| `jni/_/Sender.cpp` | `app/src/main/jni/_/Sender.cpp` | `14a3ef2d4fe50e3edc4a6040801dd57f2e3ef3a7` | CRLF-to-LF reproduces target blob |
| `jni/_/Sender.h` | `app/src/main/jni/_/Sender.h` | `411b0d59071fc5ea1e243ada89fa042dcbd91315` | CRLF-to-LF reproduces target blob |
| `jni/_/simple_stack.h` | `app/src/main/jni/_/simple_stack.h` | `e2763e0f5539a59ec6a6148c82398037c4602082` | CRLF-to-LF reproduces target blob |
| `jni/_/source-literal-manifest.json` | `app/src/main/jni/_/source-literal-manifest.json` | `40989d5f9923b5686dcc6c48fe535da6aff5abf6` | CRLF-to-LF reproduces target blob |
| `jni/_/stltool.cpp` | `app/src/main/jni/_/stltool.cpp` | `dbb5534b05e0332ef813c1e0555f579aaacd852a` | CRLF-to-LF reproduces target blob |
| `jni/_/stltool.h` | `app/src/main/jni/_/stltool.h` | `4ff5ac688b593e108fc6f4eab491f0ceebaaf2e8` | CRLF-to-LF reproduces target blob |
| `jni/_/Thread.cpp` | `app/src/main/jni/_/Thread.cpp` | `b6910d644fec907a0cbce46948a233673e435608` | CRLF-to-LF reproduces target blob |
| `jni/_/Thread.h` | `app/src/main/jni/_/Thread.h` | `306b92845e891505cdf607d8fbb634e8374d4f6f` | CRLF-to-LF reproduces target blob |
| `jni/_/Utilities.cpp` | `app/src/main/jni/_/Utilities.cpp` | `13555e182ded4f24086545e0f03d42707f29134c` | CRLF-to-LF reproduces target blob |
| `jni/_/Utilities.h` | `app/src/main/jni/_/Utilities.h` | `3d0614c27bd756a89992556f77b02a8017591ba7` | CRLF-to-LF reproduces target blob |
| `jni/_/Win32.cpp` | `app/src/main/jni/_/Win32.cpp` | `5920dec1c0f9dfe7198cfe8e57ce7a1deb0218cf` | CRLF-to-LF reproduces target blob |
| `jni/_/Win32.h` | `app/src/main/jni/_/Win32.h` | `af1fdf7a126f05901d2520f1d63306da9a0a19f9` | CRLF-to-LF reproduces target blob |
| `jni/_/Window.cpp` | `app/src/main/jni/_/Window.cpp` | `5f2e4811269dad79c1913237877e2232765ff506` | CRLF-to-LF reproduces target blob |
| `jni/_/Window.h` | `app/src/main/jni/_/Window.h` | `d9888efaa00413584ea0696cd5de649640fe0e2a` | CRLF-to-LF reproduces target blob |
| `jni/CMakeLists.txt` | `app/src/main/jni/CMakeLists.txt` | `55af158bb3565039ff8a40f81bfe72205deeb553` | exact |
| `jni/kawari8/Android.mk` | `app/src/main/jni/kawari8/Android.mk` | `7dcecf3bb6795f09fc857c084cd9e8587e0ce4dc` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/bcc.mak` | `app/src/main/jni/kawari8/bcc.mak` | `2b1e94669c33f9c3c887bf21cd95e610ab2338c8` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/config.h` | `app/src/main/jni/kawari8/config.h` | `44c98a399217d8181fa5268e8a3d0b7db6c9c5bd` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/depend.mak` | `app/src/main/jni/kawari8/depend.mak` | `9ede604b560bf1abc7b651345dddd1a2e3cc61ee` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/files.mak` | `app/src/main/jni/kawari8/files.mak` | `abf8c166b6ee681adbeca261a9bbcc1aed1dc92f` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/gcc-mach.mak` | `app/src/main/jni/kawari8/gcc-mach.mak` | `aca557a56b2ae49bc283c318862d63e6361c0866` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/gcc.mak` | `app/src/main/jni/kawari8/gcc.mak` | `fe6682eb550a391d17338a46c902361bdf73b017` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/include/old/shiori_posix.h` | `app/src/main/jni/kawari8/include/old/shiori_posix.h` | `33ba62bb5bd6dac0d06be4322a522032037fbbc8` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/include/shiori_object.h` | `app/src/main/jni/kawari8/include/shiori_object.h` | `20c3e6ead2a0391acd2fe65b7c4db0a92d71c8f0` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/include/shiori.h` | `app/src/main/jni/kawari8/include/shiori.h` | `e94d44705705df519409cf753773a9e5ecb7a74f` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kawari_jni.cpp` | `app/src/main/jni/kawari8/kawari_jni.cpp` | `b5f27d9e6572cc321017fc71fc35a1546eb4077c` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_base.h` | `app/src/main/jni/kawari8/kis/kis_base.h` | `1d628820a8fc49d33a69b1b069dc13ce856dfb7b` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_communicate.cpp` | `app/src/main/jni/kawari8/kis/kis_communicate.cpp` | `708e10af02ea36fe72de2820a02fdbfbfd6ea0e1` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_communicate.h` | `app/src/main/jni/kawari8/kis/kis_communicate.h` | `d42cb1c4171ea5326112593bca91a1c67a7fa244` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_config.h` | `app/src/main/jni/kawari8/kis/kis_config.h` | `9fae8a9a571254b31b114f5c5ab60f6d66a5461f` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_counter.cpp` | `app/src/main/jni/kawari8/kis/kis_counter.cpp` | `d4b081dbe88651938ec8a8f26644478c638f7428` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_counter.h` | `app/src/main/jni/kawari8/kis/kis_counter.h` | `257b75b51241f3a3a46945eadf09c1fcf89ea066` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_date.cpp` | `app/src/main/jni/kawari8/kis/kis_date.cpp` | `26e1c32a37c4fdf8e3902ac9c03ab3889edd5d7e` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_date.h` | `app/src/main/jni/kawari8/kis/kis_date.h` | `4d75d1412e76b977a2139395b1ded4fbad844f8e` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_dict.cpp` | `app/src/main/jni/kawari8/kis/kis_dict.cpp` | `11455d2ec3312da338b79ac1e8cbdaca4389f13a` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_dict.h` | `app/src/main/jni/kawari8/kis/kis_dict.h` | `1156864b4427b4a91826ae08dbdc47d4d432fcfa` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_echo.cpp` | `app/src/main/jni/kawari8/kis/kis_echo.cpp` | `f1bf9d9d42411d3b9b720a8180ee6345425587f5` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_echo.h` | `app/src/main/jni/kawari8/kis/kis_echo.h` | `e7927f9e63474ce03f7e1fc97004969f5aeb7289` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_escape.cpp` | `app/src/main/jni/kawari8/kis/kis_escape.cpp` | `f1e2ccfdeeb98cee2936096ad30ba102f3c68b2d` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_escape.h` | `app/src/main/jni/kawari8/kis/kis_escape.h` | `75c2039d823bab11abf877202078dae8366b2c8f` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_file.cpp` | `app/src/main/jni/kawari8/kis/kis_file.cpp` | `0e51fdfe3132bf44bb088bfdf231b308a87c6739` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_file.h` | `app/src/main/jni/kawari8/kis/kis_file.h` | `2f9e55e306fa3e97de669f175747da744765228c` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_help.cpp` | `app/src/main/jni/kawari8/kis/kis_help.cpp` | `7ca64488dac4960a5433ee5061e1bd5ee734b191` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_help.h` | `app/src/main/jni/kawari8/kis/kis_help.h` | `576b1c9ac2ddcc79255d4ba49240dac2cf913f8d` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_math.h` | `app/src/main/jni/kawari8/kis/kis_math.h` | `a23359419f8ac28b8420182adaa3a8adc99091e5` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_saori.cpp` | `app/src/main/jni/kawari8/kis/kis_saori.cpp` | `eaf7d599224d79bebd491e0c85856f3427cf8605` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_saori.h` | `app/src/main/jni/kawari8/kis/kis_saori.h` | `42b86336414095a28de5f8bfc8becb9573486aab` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_split.cpp` | `app/src/main/jni/kawari8/kis/kis_split.cpp` | `9b8ff03e919f3192bf85cfb9c6eb85cc62ed0a41` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_split.h` | `app/src/main/jni/kawari8/kis/kis_split.h` | `89814d6ee3ff2248440d44fb39ed638e061a7722` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_string.cpp` | `app/src/main/jni/kawari8/kis/kis_string.cpp` | `dcd96971111b3706f25de9e35d19247736f5e276` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_string.h` | `app/src/main/jni/kawari8/kis/kis_string.h` | `4def8c01704292e4e0399f8c495b01c59fbb880c` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_substitute.cpp` | `app/src/main/jni/kawari8/kis/kis_substitute.cpp` | `d04374721fb877dfccb27ef015139db1fefb842e` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_substitute.h` | `app/src/main/jni/kawari8/kis/kis_substitute.h` | `7bb82963ea2c94df09d3250c1c354b1b3dd5f8dc` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_system.cpp` | `app/src/main/jni/kawari8/kis/kis_system.cpp` | `3bf7d5f610a1ad35039a8823cb88e33af5c8da56` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_system.h` | `app/src/main/jni/kawari8/kis/kis_system.h` | `8d8fea71a1b49bc252a23e18586e832929f4bab8` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_urllist.cpp` | `app/src/main/jni/kawari8/kis/kis_urllist.cpp` | `e0019e76cf5947c8d4e7d269d3dc9115fc0f79bb` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_urllist.h` | `app/src/main/jni/kawari8/kis/kis_urllist.h` | `8cab90d225a38c2ee31b0386b2ed855f01dbb9d5` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_xargs.cpp` | `app/src/main/jni/kawari8/kis/kis_xargs.cpp` | `12bc5d228bf8d4eed02d20bc5cc3e0a841b70218` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/kis/kis_xargs.h` | `app/src/main/jni/kawari8/kis/kis_xargs.h` | `b32b0ce7dcebc6bba5d06008acae36d398fecfb8` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_code.cpp` | `app/src/main/jni/kawari8/libkawari/kawari_code.cpp` | `39a05cc29b22c76e43554f4cc98669411c051cc4` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_code.h` | `app/src/main/jni/kawari8/libkawari/kawari_code.h` | `b4b2bf3949be360c10b6c73b9c6c873ae97e79bb` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_codeexpr.cpp` | `app/src/main/jni/kawari8/libkawari/kawari_codeexpr.cpp` | `4f0aa1c008cf906439a27b46d871ec2a8e8aec94` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_codeexpr.h` | `app/src/main/jni/kawari8/libkawari/kawari_codeexpr.h` | `fd8a46d4c5e4a5a6d3f2c4ff1ced0f44dd939cd0` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_codekis.cpp` | `app/src/main/jni/kawari8/libkawari/kawari_codekis.cpp` | `c875e766c6a2501d4d52cbd7da78df40c9bf21a9` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_codekis.h` | `app/src/main/jni/kawari8/libkawari/kawari_codekis.h` | `1cdf339e6ffeb21ed38b99548b2b1360afac2ebf` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_codeset.cpp` | `app/src/main/jni/kawari8/libkawari/kawari_codeset.cpp` | `1f2ea22172dcd0dc77df5f0408465d2444c24fdb` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_codeset.h` | `app/src/main/jni/kawari8/libkawari/kawari_codeset.h` | `f6a061235fcd82f22cc0c2eb174f23822d29a7f6` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_compiler.cpp` | `app/src/main/jni/kawari8/libkawari/kawari_compiler.cpp` | `3173ebc24ca78eb9bfb327dcd870cd04e0bf6c32` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_compiler.h` | `app/src/main/jni/kawari8/libkawari/kawari_compiler.h` | `e5b6ee9f3f611a9cce78f8f426fbc05d595972a5` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_crypt.cpp` | `app/src/main/jni/kawari8/libkawari/kawari_crypt.cpp` | `e699cc6d130c62ef581ada950033b7aca3dc6748` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_crypt.h` | `app/src/main/jni/kawari8/libkawari/kawari_crypt.h` | `42cb78b969ad5a4c17efcaa87e25e24368794195` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_dict.cpp` | `app/src/main/jni/kawari8/libkawari/kawari_dict.cpp` | `488f9b33a195190b24fe6b6c8bba08740560da93` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_dict.h` | `app/src/main/jni/kawari8/libkawari/kawari_dict.h` | `ba6b8c71a9db4b51fad39818681c01daa431349a` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_engine.cpp` | `app/src/main/jni/kawari8/libkawari/kawari_engine.cpp` | `7ca4a5cad061d0bbcbdeef3590134f4b0eec88dd` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_engine.h` | `app/src/main/jni/kawari8/libkawari/kawari_engine.h` | `63df569893c2b1cb46b690f5539a471fe8354e9a` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_lexer.cpp` | `app/src/main/jni/kawari8/libkawari/kawari_lexer.cpp` | `0f3f872ef367ab4238b2dd51b885ff22e177908c` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_lexer.h` | `app/src/main/jni/kawari8/libkawari/kawari_lexer.h` | `db8da1ec65b6f10a3fede006b83e23208f9f8de8` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_log.cpp` | `app/src/main/jni/kawari8/libkawari/kawari_log.cpp` | `bd5bf1931686ecdc583013a8ec3b6cca9a9edfb2` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_log.h` | `app/src/main/jni/kawari8/libkawari/kawari_log.h` | `dead37647b582ffb5080911c020450fe073baeee` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_ns.cpp` | `app/src/main/jni/kawari8/libkawari/kawari_ns.cpp` | `afae405762f2633974a9aa6e57e0fadb75d787a0` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_ns.h` | `app/src/main/jni/kawari8/libkawari/kawari_ns.h` | `e310e3d3757919ce96118ba5061038246c2c0e01` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_rc_sjis_encoded.h` | `app/src/main/jni/kawari8/libkawari/kawari_rc_sjis_encoded.h` | `f495b38e23164f61a1b4b9026ad11e03b6557573` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_rc.cpp` | `app/src/main/jni/kawari8/libkawari/kawari_rc.cpp` | `6ac27b8c73539562e4aba649028fbd976de43599` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_rc.h` | `app/src/main/jni/kawari8/libkawari/kawari_rc.h` | `3948c37391592d922df59ddca78eeedad461189d` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_rc.sjis` | `app/src/main/jni/kawari8/libkawari/kawari_rc.sjis` | `af8062c92203ef38aa3e7d61a9bba0d6f102716d` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_version.h` | `app/src/main/jni/kawari8/libkawari/kawari_version.h` | `35ef33b3332a209f0a5c38186a3d2b6ccc295997` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_vm.cpp` | `app/src/main/jni/kawari8/libkawari/kawari_vm.cpp` | `6ed3539bdd74004b4dee1f0253046cad99184e91` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/kawari_vm.h` | `app/src/main/jni/kawari8/libkawari/kawari_vm.h` | `fa707d5fde564906b7844492426111486cc9a1fe` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/libkawari/wordcollection.h` | `app/src/main/jni/kawari8/libkawari/wordcollection.h` | `8c569f62634938377ca8d8a56924c2fa42103e23` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/makedepend.rb` | `app/src/main/jni/kawari8/makedepend.rb` | `998fc53b9bd6d90d10372e6f9094c04c3a9c727b` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/misc/_dirent.cpp` | `app/src/main/jni/kawari8/misc/_dirent.cpp` | `514273d9ace03654ea01625819edbf4d235a6791` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/misc/_dirent.h` | `app/src/main/jni/kawari8/misc/_dirent.h` | `8484f5eace3d2b58581a1b32c396093b19cb0e99` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/misc/base64.cpp` | `app/src/main/jni/kawari8/misc/base64.cpp` | `e70931f4af10117de9f5e9c780c8a124d1d03524` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/misc/base64.h` | `app/src/main/jni/kawari8/misc/base64.h` | `95b5736ec036ff32d0bcb5afec94e7211c20a97e` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/misc/l10n.cpp` | `app/src/main/jni/kawari8/misc/l10n.cpp` | `62107078cc17b340c14649f820854edcc06b8a1c` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/misc/l10n.h` | `app/src/main/jni/kawari8/misc/l10n.h` | `a53d8c5ed69f218d18dbceb299f2281202aa5153` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/misc/misc.cpp` | `app/src/main/jni/kawari8/misc/misc.cpp` | `2407d29d758521553e88daa055ba454c617811f7` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/misc/misc.h` | `app/src/main/jni/kawari8/misc/misc.h` | `dc5e182d20b4ca69d74fa08cf6a9693a77a041dc` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/misc/mmap.h` | `app/src/main/jni/kawari8/misc/mmap.h` | `f5ba35f8eb9d9a4a2a94619f12127e530e97e626` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/misc/mt19937ar.cpp` | `app/src/main/jni/kawari8/misc/mt19937ar.cpp` | `84ad9c238ce9b1bb919764190e674b51c91499f6` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/misc/mt19937ar.h` | `app/src/main/jni/kawari8/misc/mt19937ar.h` | `2423332989e7c4cb8d15700ec8e575b1be18ad3c` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/misc/phttp.cpp` | `app/src/main/jni/kawari8/misc/phttp.cpp` | `4a7ce0ba9a06594f70941a5d1fddce02798b730b` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/misc/phttp.h` | `app/src/main/jni/kawari8/misc/phttp.h` | `60b09ad924ed31c660ec892350cdb64e9c9b16ce` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/saori/old/saori_libdl.cpp` | `app/src/main/jni/kawari8/saori/old/saori_libdl.cpp` | `fd581f092068657dbbc0ded510b882cdbf7b0a31` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/saori/old/saori_libdl.h` | `app/src/main/jni/kawari8/saori/old/saori_libdl.h` | `367970a2c9350fdc4021034c52e6fa7d8d8a63a3` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/saori/old/saori_win32.h` | `app/src/main/jni/kawari8/saori/old/saori_win32.h` | `03fa8cc8afa7146a61c8ff550a7e4749f23fd438` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/saori/saori_java.cpp` | `app/src/main/jni/kawari8/saori/saori_java.cpp` | `db87d8f5c754f8b103c0072ced1c3323e4ef86d1` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/saori/saori_java.h` | `app/src/main/jni/kawari8/saori/saori_java.h` | `70d623edbc90761be66711c9d3d4a88b5435c324` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/saori/saori_module.cpp` | `app/src/main/jni/kawari8/saori/saori_module.cpp` | `7a65bb3bc11de6695389c63ca123d3ae8101321d` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/saori/saori_module.h` | `app/src/main/jni/kawari8/saori/saori_module.h` | `ab94842c5ad307de5469334445164ef2fcc05a86` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/saori/saori_native.cpp` | `app/src/main/jni/kawari8/saori/saori_native.cpp` | `9edba4c400055dccbab4d337837340cca2ac29dd` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/saori/saori_native.h` | `app/src/main/jni/kawari8/saori/saori_native.h` | `9b0e1764a858f28093fc279429062424ebedcb97` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/saori/saori_python.cpp` | `app/src/main/jni/kawari8/saori/saori_python.cpp` | `a861dd181b2200a2d1b0210deb812e79e8c0e796` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/saori/saori_python.h` | `app/src/main/jni/kawari8/saori/saori_python.h` | `71e3f59c5533e418e25325b4c722a3346c94ccb5` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/saori/saori_unique.cpp` | `app/src/main/jni/kawari8/saori/saori_unique.cpp` | `bc88e2a4b62d082890f38ee0475d69df4203d6e9` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/saori/saori_unique.h` | `app/src/main/jni/kawari8/saori/saori_unique.h` | `2e5f94586b655da6619ba1bc0a63f66f26be25de` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/saori/saori.cpp` | `app/src/main/jni/kawari8/saori/saori.cpp` | `3a0826d6a6b077a5abf2331bf1d2150975f97b6b` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/saori/saori.h` | `app/src/main/jni/kawari8/saori/saori.h` | `8269c0c1f3378c1b252eecbb33b0d586bb064c5a` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/shiori/kawari_shiori.cpp` | `app/src/main/jni/kawari8/shiori/kawari_shiori.cpp` | `8bfb28ef5cf49a79c0e80dfd3ca5c0cd7229957a` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/shiori/kawari_shiori.h` | `app/src/main/jni/kawari8/shiori/kawari_shiori.h` | `d4e41bdcc88eaa5a5c84f08bc220f3596b4ff8c4` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/shiori/old/shiori_posix.cpp` | `app/src/main/jni/kawari8/shiori/old/shiori_posix.cpp` | `758385cdcafee1779407310302463845924ff554` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/shiori/py_shiori.cpp` | `app/src/main/jni/kawari8/shiori/py_shiori.cpp` | `e71ee25f24065cc33126917769b8fe2d62dbae78` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/shiori/py_shiori.h` | `app/src/main/jni/kawari8/shiori/py_shiori.h` | `c67320c1a2b7d8a21975116d086c5b4a61aa9f9d` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/shiori/shiori_object.cpp` | `app/src/main/jni/kawari8/shiori/shiori_object.cpp` | `2ef84c6a6f3a96f2859d973370ae4776b4478ee5` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/shiori/shiori.cpp` | `app/src/main/jni/kawari8/shiori/shiori.cpp` | `7d38cc531460cbec75ef90aa375d4b679731aa05` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/sjis2ascii.rb` | `app/src/main/jni/kawari8/sjis2ascii.rb` | `d4ec61a365e3b4796bc399bd509ec086bd9e1204` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/tool/kawari_decode2.cpp` | `app/src/main/jni/kawari8/tool/kawari_decode2.cpp` | `4a80e5d15202faed3d0ed83ae67d8aba8ca543c0` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/tool/kawari_encode.cpp` | `app/src/main/jni/kawari8/tool/kawari_encode.cpp` | `decb91e3bacc823d6d5d9dd36a7d5423d4ea38f1` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/tool/kawari_encode2.cpp` | `app/src/main/jni/kawari8/tool/kawari_encode2.cpp` | `fb7123585cd71b18f8e879f3850b822efbddbe4e` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/tool/kawari_kosui.h` | `app/src/main/jni/kawari8/tool/kawari_kosui.h` | `dd0bd8d4f2ee5ace28bc5045bfd4badf1d947682` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/tool/kdb.cpp` | `app/src/main/jni/kawari8/tool/kdb.cpp` | `e22cc58662bf6092657069ab4bbf658f9a882aac` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/tool/kdb.h` | `app/src/main/jni/kawari8/tool/kdb.h` | `ac5b9c99ca98e8af0e50354aac87d1c117ba2880` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/tool/kosui_base.h` | `app/src/main/jni/kawari8/tool/kosui_base.h` | `97212ecae0cbef67953bc39aabcd1da1c29f4598` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/tool/kosui_dsstp.cpp` | `app/src/main/jni/kawari8/tool/kosui_dsstp.cpp` | `9ba8791dff45e6a2e60fbeda3ebb3f9c1035de90` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/tool/kosui_dsstp.h` | `app/src/main/jni/kawari8/tool/kosui_dsstp.h` | `6f846136b186ddef7f3a295e59c04050c668c684` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/tool/kosui.cpp` | `app/src/main/jni/kawari8/tool/kosui.cpp` | `b394a809f07f979f7eca26029cc7c4de3b8b61f1` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/tool/logserver.cpp` | `app/src/main/jni/kawari8/tool/logserver.cpp` | `880920b9a66db27a6d8d53cf806f8297fe7251df` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/vc_kawari/vc_kawari.dsp` | `app/src/main/jni/kawari8/vc_kawari/vc_kawari.dsp` | `95c560c6dd3522a9be5f8c764025701f1ccf6faf` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/vc_kawari/vc_kawari.dsw` | `app/src/main/jni/kawari8/vc_kawari/vc_kawari.dsw` | `7cbb124c8e6efd9c6d68b669fd93a130c8fd1a7d` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/vc_kawari/vc_kawari.sln` | `app/src/main/jni/kawari8/vc_kawari/vc_kawari.sln` | `c5673bc496c635120eebfa09ed7d62761ba2020b` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/vc_kawari/vc_kawari.vcproj` | `app/src/main/jni/kawari8/vc_kawari/vc_kawari.vcproj` | `505d8d8e86d9d8089648882fbb17be34393fa2e1` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/vc_kawari/vc_kosui.dsp` | `app/src/main/jni/kawari8/vc_kawari/vc_kosui.dsp` | `3fcb01992d5d4c44857eec03a2df89d75d1b5f3f` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/vc_kawari/vc_kosui.dsw` | `app/src/main/jni/kawari8/vc_kawari/vc_kosui.dsw` | `261220131d134bf5105d2fd7df18824035079042` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/vc_kawari/vc_kosui.sln` | `app/src/main/jni/kawari8/vc_kawari/vc_kosui.sln` | `2d756c29a5a5c78dca24216916d1a98c91e5fa1d` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/vc_kawari/vc_kosui.vcproj` | `app/src/main/jni/kawari8/vc_kawari/vc_kosui.vcproj` | `ae728a67c056866d3d84664b737759b323c13b3c` | CRLF-to-LF reproduces target blob |
| `jni/kawari8/win32jvm.def` | `app/src/main/jni/kawari8/win32jvm.def` | `af8e94633890b743933fbe3a62693cc0f1411ac8` | CRLF-to-LF reproduces target blob |
| `jni/satori_compat.h` | `app/src/main/jni/satori_compat.h` | `83f507ddc465f6aa4283a328275a72551ca0d3ed` | CRLF-to-LF reproduces target blob |
| `jni/satori_license.txt` | `app/src/main/jni/satori_license.txt` | `3358c0545f47b74c84ba6993aa918d49bc071d29` | CRLF-to-LF reproduces target blob |
| `jni/satori/Android.mk` | `app/src/main/jni/satori/Android.mk` | `4a3c7b88c52c38ca51f356b92128511d7de13723` | CRLF-to-LF reproduces target blob |
| `jni/satori/console_application.h` | `app/src/main/jni/satori/console_application.h` | `a0a474020f866a1a4a9ce88da4f468270e3ed754` | CRLF-to-LF reproduces target blob |
| `jni/satori/Families.h` | `app/src/main/jni/satori/Families.h` | `7cd22d296a3ada6038e2e7b638d84a588e674983` | CRLF-to-LF reproduces target blob |
| `jni/satori/Family.h` | `app/src/main/jni/satori/Family.h` | `9158d9a1bc6f41d5b11f6f4dbdb4efbca574eba6` | CRLF-to-LF reproduces target blob |
| `jni/satori/index.html` | `app/src/main/jni/satori/index.html` | `65b33be88cf5ac25c3b399b544a4ebf5c2f31395` | CRLF-to-LF reproduces target blob |
| `jni/satori/main.cpp` | `app/src/main/jni/satori/main.cpp` | `70a1d8aa1ba4aa18ec983d9bacaa670d226c9b2c` | CRLF-to-LF reproduces target blob |
| `jni/satori/makefile.cygwin` | `app/src/main/jni/satori/makefile.cygwin` | `a7df8e52d93f676de88586f7d0250b2c3b374c78` | CRLF-to-LF reproduces target blob |
| `jni/satori/makefile.posix` | `app/src/main/jni/satori/makefile.posix` | `e6982c4f2bf89d4312b1c8803ae259c2bdb0a6c1` | CRLF-to-LF reproduces target blob |
| `jni/satori/OverlapController.h` | `app/src/main/jni/satori/OverlapController.h` | `23f1e7353890a90386a55d18b619f9aba37a98b1` | CRLF-to-LF reproduces target blob |
| `jni/satori/posix_utils.h` | `app/src/main/jni/satori/posix_utils.h` | `1beb445969593f3269a58332ce1d964d44d40744` | CRLF-to-LF reproduces target blob |
| `jni/satori/SakuraClient.cpp` | `app/src/main/jni/satori/SakuraClient.cpp` | `79d7bb6d7c01b3dc916bebef1b0b7cb6e64d37c0` | CRLF-to-LF reproduces target blob |
| `jni/satori/SakuraClient.h` | `app/src/main/jni/satori/SakuraClient.h` | `cf3627661a19b62066c17dc838386ba207fc3ed1` | CRLF-to-LF reproduces target blob |
| `jni/satori/SakuraCS.cpp` | `app/src/main/jni/satori/SakuraCS.cpp` | `280255bc77a4a3350bd6519790fc50d7dc83ce05` | CRLF-to-LF reproduces target blob |
| `jni/satori/SakuraCS.h` | `app/src/main/jni/satori/SakuraCS.h` | `051dfb63483bf17716630b24b4e0a8030c725a1b` | CRLF-to-LF reproduces target blob |
| `jni/satori/SakuraDLLClient.cpp` | `app/src/main/jni/satori/SakuraDLLClient.cpp` | `2cdc392838303ef0570ebec305d30cf8d01af23c` | CRLF-to-LF reproduces target blob |
| `jni/satori/SakuraDLLClient.h` | `app/src/main/jni/satori/SakuraDLLClient.h` | `380fbec016062a209a07f0f75793f30a62b42c68` | CRLF-to-LF reproduces target blob |
| `jni/satori/SakuraDLLHost.cpp` | `app/src/main/jni/satori/SakuraDLLHost.cpp` | `c4025baf298ab68ac7721f7cdff0abe8360a67e7` | CRLF-to-LF reproduces target blob |
| `jni/satori/SakuraDLLHost.h` | `app/src/main/jni/satori/SakuraDLLHost.h` | `cbb0cd2ea3b25f78f4b8acac4a758a817bed9a56` | CRLF-to-LF reproduces target blob |
| `jni/satori/SaoriClient.cpp` | `app/src/main/jni/satori/SaoriClient.cpp` | `eb5d49d883f08380b851734897949b9fb04c8d5e` | CRLF-to-LF reproduces target blob |
| `jni/satori/SaoriClient.h` | `app/src/main/jni/satori/SaoriClient.h` | `b25660564d18d9fe66defba6e7f3c470f4d3e9e3` | CRLF-to-LF reproduces target blob |
| `jni/satori/SaoriHost.cpp` | `app/src/main/jni/satori/SaoriHost.cpp` | `58a491b0fc62ae6dfa951fca41430ccabe286b35` | CRLF-to-LF reproduces target blob |
| `jni/satori/SaoriHost.h` | `app/src/main/jni/satori/SaoriHost.h` | `699f0c2b1523b087dad8fae7a1b3fc5cface7dcb` | CRLF-to-LF reproduces target blob |
| `jni/satori/satori_AnalyzeRequest.cpp` | `app/src/main/jni/satori/satori_AnalyzeRequest.cpp` | `8d42401ee0c1cfe24305b35a390ed773f97fdea4` | CRLF-to-LF reproduces target blob |
| `jni/satori/satori_CreateResponce.cpp` | `app/src/main/jni/satori/satori_CreateResponce.cpp` | `2b12b16c1f51993a5347bf3f727c53e780c971d6` | CRLF-to-LF reproduces target blob |
| `jni/satori/satori_EventOperation.cpp` | `app/src/main/jni/satori/satori_EventOperation.cpp` | `b80fe27ae0d11fe42953fd3eadd166b223a31dc4` | CRLF-to-LF reproduces target blob |
| `jni/satori/satori_jni.cpp` | `app/src/main/jni/satori/satori_jni.cpp` | `f76937e6aa7a35a16ac500e705842c5b8982c104` | CRLF-to-LF reproduces target blob |
| `jni/satori/satori_Kakko.cpp` | `app/src/main/jni/satori/satori_Kakko.cpp` | `ac7a468734b882f51903d32f2fead47b7e380f88` | CRLF-to-LF reproduces target blob |
| `jni/satori/satori_load_dict.cpp` | `app/src/main/jni/satori/satori_load_dict.cpp` | `263aa853a95b84bb015e9ebfbefc91b13415f622` | CRLF-to-LF reproduces target blob |
| `jni/satori/satori_load_dict.h` | `app/src/main/jni/satori/satori_load_dict.h` | `d5b1421ada533765d7ba9c26e671bf38a2e2d5d0` | CRLF-to-LF reproduces target blob |
| `jni/satori/satori_load_unload.cpp` | `app/src/main/jni/satori/satori_load_unload.cpp` | `4b4147717934fd5bdb9fa432a78d039d73a6f59e` | CRLF-to-LF reproduces target blob |
| `jni/satori/satori_sentence.cpp` | `app/src/main/jni/satori/satori_sentence.cpp` | `4cb60af37403cf0fada080bcca242c45253bdd8e` | CRLF-to-LF reproduces target blob |
| `jni/satori/satori_test.dsp` | `app/src/main/jni/satori/satori_test.dsp` | `9479b5b0ac2c5e8f0f43d9230fcec2702f1ed7e9` | CRLF-to-LF reproduces target blob |
| `jni/satori/satori_tool.cpp` | `app/src/main/jni/satori/satori_tool.cpp` | `813868db145d7fd99748136c74863b61063ef966` | CRLF-to-LF reproduces target blob |
| `jni/satori/satori.cpp` | `app/src/main/jni/satori/satori.cpp` | `0460a5bee64a8176b9d908d668874f6705fdcee9` | CRLF-to-LF reproduces target blob |
| `jni/satori/satori.dsp` | `app/src/main/jni/satori/satori.dsp` | `947af031bdfd82047e9e95e591f3b5e65fab3e76` | CRLF-to-LF reproduces target blob |
| `jni/satori/satori.dsw` | `app/src/main/jni/satori/satori.dsw` | `d7b298a507e37913cb32f174b65497f2c0e2e564` | CRLF-to-LF reproduces target blob |
| `jni/satori/satori.h` | `app/src/main/jni/satori/satori.h` | `05a6ff47716cf3de2b1f3e65b94d26e254d369f9` | CRLF-to-LF reproduces target blob |
| `jni/satori/satoriFMO.cpp` | `app/src/main/jni/satori/satoriFMO.cpp` | `7a492194442a0f6823c35a8925d0fee5ceb4471e` | CRLF-to-LF reproduces target blob |
| `jni/satori/satorite.dsp` | `app/src/main/jni/satori/satorite.dsp` | `51fda25a64d8814aad4aa6a688a2615e890852b5` | CRLF-to-LF reproduces target blob |
| `jni/satori/satoriTranslate.cpp` | `app/src/main/jni/satori/satoriTranslate.cpp` | `4900969685759fb1c4ab4abfe0c01bffafd0887f` | CRLF-to-LF reproduces target blob |
| `jni/satori/Selector.h` | `app/src/main/jni/satori/Selector.h` | `59a1b990198c5502ba1748f0e124ce1572292457` | CRLF-to-LF reproduces target blob |
| `jni/satori/shiori_plugin.cpp` | `app/src/main/jni/satori/shiori_plugin.cpp` | `7f0807df06807906ddaf2a32c23484c2b5be9393` | CRLF-to-LF reproduces target blob |
| `jni/satori/shiori_plugin.h` | `app/src/main/jni/satori/shiori_plugin.h` | `ce15b232578a4794af9a09f14fa4495ca1b086f1` | CRLF-to-LF reproduces target blob |
| `jni/satori/ShioriClient.cpp` | `app/src/main/jni/satori/ShioriClient.cpp` | `018172df3806565461d704288a8d82db074937cb` | CRLF-to-LF reproduces target blob |
| `jni/satori/ShioriClient.h` | `app/src/main/jni/satori/ShioriClient.h` | `e9077f662adccfbc4b1f70938ea1611d5a33870f` | CRLF-to-LF reproduces target blob |
| `jni/satori/source-literal-manifest.json` | `app/src/main/jni/satori/source-literal-manifest.json` | `c4bd0722d4ff770d85cf7375f40bf006fa8fb3f4` | CRLF-to-LF reproduces target blob |
| `jni/satori/SSTPClient.cpp` | `app/src/main/jni/satori/SSTPClient.cpp` | `8ee2e25b246077749712f2a78be0671c13397431` | CRLF-to-LF reproduces target blob |
| `jni/satori/SSTPClient.h` | `app/src/main/jni/satori/SSTPClient.h` | `eb302e5a6fa2c6a542ac9cdbc773a9516addd734` | CRLF-to-LF reproduces target blob |
| `jni/satori/ssu_anchor.cpp` | `app/src/main/jni/satori/ssu_anchor.cpp` | `8863757e014fda985a6b6ab73f4e32066d2f61dd` | CRLF-to-LF reproduces target blob |
| `jni/satori/ssu.cpp` | `app/src/main/jni/satori/ssu.cpp` | `ca001e565b87a8d2653c2f9ff29fed1ed03756a6` | CRLF-to-LF reproduces target blob |
| `jni/satori/ssu.dsp` | `app/src/main/jni/satori/ssu.dsp` | `75d899c82cd1bc6b79c4a7ddd2fa4e60b399fdc7` | CRLF-to-LF reproduces target blob |
| `jni/satori/test/characters.ini` | `app/src/main/jni/satori/test/characters.ini` | `15de9bf3616c3e9b45a48f789ab59158a6ad1d82` | CRLF-to-LF reproduces target blob |
| `jni/satori/test/dic1.txt` | `app/src/main/jni/satori/test/dic1.txt` | `d0ce79b0684dd2828a5563ef1bd551df32be133e` | CRLF-to-LF reproduces target blob |
| `jni/satori/TimeCommands.cpp` | `app/src/main/jni/satori/TimeCommands.cpp` | `d36680c10d33758ee2d571dcac0f1bb6c5043a60` | CRLF-to-LF reproduces target blob |
| `jni/satori/WinMain.cpp` | `app/src/main/jni/satori/WinMain.cpp` | `f6051c91dc8d3d652d1f2129a94763083024636b` | CRLF-to-LF reproduces target blob |
| `jni/yaya/.clang-format` | `app/src/main/jni/yaya/.clang-format` | `cfd3c54b98263174c62ea8c504737c7aab3ff04c` | CRLF-to-LF reproduces target blob |
| `jni/yaya/.gitignore` | `app/src/main/jni/yaya/.gitignore` | `2e400d07f8234c6b79cc0ad64ef093b619ae11e6` | exact |
| `jni/yaya/android_charset.cpp` | `app/src/main/jni/yaya/android_charset.cpp` | `d6473460cedbc833b08ebb3aa57b348ddaacbc05` | CRLF-to-LF reproduces target blob |
| `jni/yaya/android_charset.h` | `app/src/main/jni/yaya/android_charset.h` | `e927cb23e34c6489ad66c95024864c6903f69fee` | CRLF-to-LF reproduces target blob |
| `jni/yaya/aya_profile.cpp` | `app/src/main/jni/yaya/aya_profile.cpp` | `fa10de5f3ac7ce686e0ea8cbb25cf5d39a1b4beb` | CRLF-to-LF reproduces target blob |
| `jni/yaya/aya5.cpp` | `app/src/main/jni/yaya/aya5.cpp` | `045ea4f4ab5f6dd40a7930669f626e09055279ba` | CRLF-to-LF reproduces target blob |
| `jni/yaya/aya5.h` | `app/src/main/jni/yaya/aya5.h` | `ac5fe2d74b7477dedeb5157a67e224197c091bbb` | CRLF-to-LF reproduces target blob |
| `jni/yaya/ayavm.cpp` | `app/src/main/jni/yaya/ayavm.cpp` | `9e2a64e574c2d1afceb684ae1d4f6119580b6fc4` | CRLF-to-LF reproduces target blob |
| `jni/yaya/ayavm.h` | `app/src/main/jni/yaya/ayavm.h` | `e60151f4f75e5d258753f6301e7633559f42ba6d` | CRLF-to-LF reproduces target blob |
| `jni/yaya/basis.cpp` | `app/src/main/jni/yaya/basis.cpp` | `bd2acb0ea519008a35bbf4112f32da62485b852b` | CRLF-to-LF reproduces target blob |
| `jni/yaya/basis.h` | `app/src/main/jni/yaya/basis.h` | `86dbfa5116e6dc98d9de5259ca95bfac57eebc28` | CRLF-to-LF reproduces target blob |
| `jni/yaya/ccct.cpp` | `app/src/main/jni/yaya/ccct.cpp` | `2c5d81ed10c7574e58ea6edd20272ffaec165a95` | CRLF-to-LF reproduces target blob |
| `jni/yaya/ccct.h` | `app/src/main/jni/yaya/ccct.h` | `66682be73fe1751997ca7583ed0d5216ebd4be09` | CRLF-to-LF reproduces target blob |
| `jni/yaya/cell.h` | `app/src/main/jni/yaya/cell.h` | `ccd09906a2f7cf15f74eab9cd805f8ac738c6063` | CRLF-to-LF reproduces target blob |
| `jni/yaya/comment.cpp` | `app/src/main/jni/yaya/comment.cpp` | `f100d12e52011bba902e368609a9cf6ac79afe91` | CRLF-to-LF reproduces target blob |
| `jni/yaya/comment.h` | `app/src/main/jni/yaya/comment.h` | `162bc7263f9cc5271e0d4cfa450c593572091dd2` | CRLF-to-LF reproduces target blob |
| `jni/yaya/cpp.hint` | `app/src/main/jni/yaya/cpp.hint` | `76a5928d2631dbfb4f09380cfc7b573a9a4c6b5f` | CRLF-to-LF reproduces target blob |
| `jni/yaya/crc32.c` | `app/src/main/jni/yaya/crc32.c` | `b8ab7462754684ae608e501812ee1c631ae3aa3f` | CRLF-to-LF reproduces target blob |
| `jni/yaya/crc32.h` | `app/src/main/jni/yaya/crc32.h` | `3ba080eee711064039e24a3298f61a524f1a55ca` | CRLF-to-LF reproduces target blob |
| `jni/yaya/deelx.h` | `app/src/main/jni/yaya/deelx.h` | `4ece822b71153f28cf55c7dd8c035e6b9ecacdb8` | CRLF-to-LF reproduces target blob |
| `jni/yaya/dir_enum.cpp` | `app/src/main/jni/yaya/dir_enum.cpp` | `3f44819f1bde694a69cc9b598c286d50dccecb5a` | CRLF-to-LF reproduces target blob |
| `jni/yaya/dir_enum.h` | `app/src/main/jni/yaya/dir_enum.h` | `21780811e2ac016e517c7cc9cdedb8654215112d` | CRLF-to-LF reproduces target blob |
| `jni/yaya/duplevinfo.cpp` | `app/src/main/jni/yaya/duplevinfo.cpp` | `c901e789e77fa62ab8e5018700eef881d0c76eb6` | CRLF-to-LF reproduces target blob |
| `jni/yaya/em-post.js` | `app/src/main/jni/yaya/em-post.js` | `140c44fda76b0ffe654a846124459bc75c2f5277` | CRLF-to-LF reproduces target blob |
| `jni/yaya/em-pre.js` | `app/src/main/jni/yaya/em-pre.js` | `9b99db7fb8d2d89b43e0b9698cf4cfb613a33e56` | CRLF-to-LF reproduces target blob |
| `jni/yaya/file.cpp` | `app/src/main/jni/yaya/file.cpp` | `b98872d872da01e81fa61bd3c896fa15a2c0282f` | CRLF-to-LF reproduces target blob |
| `jni/yaya/file.h` | `app/src/main/jni/yaya/file.h` | `cef03221e693a79f7c239cd5a65236d7c65aee65` | CRLF-to-LF reproduces target blob |
| `jni/yaya/file1.cpp` | `app/src/main/jni/yaya/file1.cpp` | `fd77c6819ee9573d3111105e18991f75def4fa82` | CRLF-to-LF reproduces target blob |
| `jni/yaya/fix_unistd.h` | `app/src/main/jni/yaya/fix_unistd.h` | `ce55dfcdb6ad45e380bb2ea56411c809066c5a4f` | CRLF-to-LF reproduces target blob |
| `jni/yaya/function.cpp` | `app/src/main/jni/yaya/function.cpp` | `fc58fba788cd369aae4bdb2d16928eea93c3619a` | CRLF-to-LF reproduces target blob |
| `jni/yaya/function.h` | `app/src/main/jni/yaya/function.h` | `a0aa1eaf8c8baff9c82bb4ab9a8921e3014745bd` | CRLF-to-LF reproduces target blob |
| `jni/yaya/global.h` | `app/src/main/jni/yaya/global.h` | `fe5f38fcfefa337bb8fd84fa608efc9f32267b57` | CRLF-to-LF reproduces target blob |
| `jni/yaya/globaldef.h` | `app/src/main/jni/yaya/globaldef.h` | `5ed68a725c97748e0878d333d0134fd74c1c667a` | CRLF-to-LF reproduces target blob |
| `jni/yaya/globalvariable.cpp` | `app/src/main/jni/yaya/globalvariable.cpp` | `91e707551681edae23a128826d8487b0bfadaf69` | CRLF-to-LF reproduces target blob |
| `jni/yaya/lib.cpp` | `app/src/main/jni/yaya/lib.cpp` | `3ca9b4a685d95132fb55820cb5eae45a1a154ba9` | CRLF-to-LF reproduces target blob |
| `jni/yaya/lib.h` | `app/src/main/jni/yaya/lib.h` | `30e2b48d63e193e7ac0584fb6fe5d93b0e60251b` | CRLF-to-LF reproduces target blob |
| `jni/yaya/lib1.cpp` | `app/src/main/jni/yaya/lib1.cpp` | `d4e278cabad25bd9b9dd39412dc283d9b9e5fd68` | CRLF-to-LF reproduces target blob |
| `jni/yaya/LICENSE` | `app/src/main/jni/yaya/LICENSE` | `7716b63908f965f88c2b9744c4178ace96bdc4ce` | CRLF-to-LF reproduces target blob |
| `jni/yaya/localvariable.cpp` | `app/src/main/jni/yaya/localvariable.cpp` | `f1d80dd9bfa89cdcdfe8e57b90234faafcea510c` | CRLF-to-LF reproduces target blob |
| `jni/yaya/log.cpp` | `app/src/main/jni/yaya/log.cpp` | `a4d4c15a5d81eaf0d20663bb5b638492ff5c0d19` | CRLF-to-LF reproduces target blob |
| `jni/yaya/log.h` | `app/src/main/jni/yaya/log.h` | `6c4e54140e2d802e553d177a48ea27f81bff2801` | CRLF-to-LF reproduces target blob |
| `jni/yaya/logexcode.cpp` | `app/src/main/jni/yaya/logexcode.cpp` | `1e8423f5b81174a207f0415bf14049a55c3718f8` | CRLF-to-LF reproduces target blob |
| `jni/yaya/logexcode.h` | `app/src/main/jni/yaya/logexcode.h` | `fd488e867b16176f974cb64b66c7533038dafc4f` | CRLF-to-LF reproduces target blob |
| `jni/yaya/make_aya.bat` | `app/src/main/jni/yaya/make_aya.bat` | `aa2f17ad7b07e28aa4919f96f3bdf6448eac7d8d` | CRLF-to-LF reproduces target blob |
| `jni/yaya/makefile.emscripten` | `app/src/main/jni/yaya/makefile.emscripten` | `46465f874a255355fde07db35888024ee9af41cb` | CRLF-to-LF reproduces target blob |
| `jni/yaya/makefile.fc6` | `app/src/main/jni/yaya/makefile.fc6` | `c51c121643f239d57c36c00826954c6709f8539c` | CRLF-to-LF reproduces target blob |
| `jni/yaya/makefile.freebsd` | `app/src/main/jni/yaya/makefile.freebsd` | `2707ee39e4cdb1ba2354329e215a15f71d5b887a` | CRLF-to-LF reproduces target blob |
| `jni/yaya/makefile.linux` | `app/src/main/jni/yaya/makefile.linux` | `8336c87a1b744c06ea914700b41f5bea1c76ab9c` | CRLF-to-LF reproduces target blob |
| `jni/yaya/makefile.mingw32` | `app/src/main/jni/yaya/makefile.mingw32` | `22b9520cfbd182232c1c6da91a634f17b05b5484` | CRLF-to-LF reproduces target blob |
| `jni/yaya/makefile.posix` | `app/src/main/jni/yaya/makefile.posix` | `d6b9bcf42424a7cabb7aa6bc5f036aa616d61b81` | CRLF-to-LF reproduces target blob |
| `jni/yaya/manifest.cpp` | `app/src/main/jni/yaya/manifest.cpp` | `55954b08f19a4fc65700b4a7dcec3101d6b75186` | CRLF-to-LF reproduces target blob |
| `jni/yaya/manifest.h` | `app/src/main/jni/yaya/manifest.h` | `509ff698c8bd77ac24cd240648adca95223d7e4a` | CRLF-to-LF reproduces target blob |
| `jni/yaya/md5.h` | `app/src/main/jni/yaya/md5.h` | `7411800513d81cbab489031458b080cf163dafa7` | CRLF-to-LF reproduces target blob |
| `jni/yaya/md5c.c` | `app/src/main/jni/yaya/md5c.c` | `76a6e4608367345817884c565935315d1f07de2c` | CRLF-to-LF reproduces target blob |
| `jni/yaya/messages.cpp` | `app/src/main/jni/yaya/messages.cpp` | `ce299ee1fd618f3f6214ba051f669eb00d681124` | CRLF-to-LF reproduces target blob |
| `jni/yaya/messages.h` | `app/src/main/jni/yaya/messages.h` | `c63d94e44099d5237abe079c044c91bcca2bfa4e` | CRLF-to-LF reproduces target blob |
| `jni/yaya/misc.cpp` | `app/src/main/jni/yaya/misc.cpp` | `fd11769f58760d84377b159e9e41fd471a9c4bfd` | CRLF-to-LF reproduces target blob |
| `jni/yaya/misc.h` | `app/src/main/jni/yaya/misc.h` | `8a69fb464a0272246140c9337c0c141176248af7` | CRLF-to-LF reproduces target blob |
| `jni/yaya/mt19937ar.cpp` | `app/src/main/jni/yaya/mt19937ar.cpp` | `b39f55484893d9cbf6be77828ad4f501819955b9` | CRLF-to-LF reproduces target blob |
| `jni/yaya/mt19937ar.h` | `app/src/main/jni/yaya/mt19937ar.h` | `b67ac8ec8b74a84bf5b0664c4945f4a92b222606` | CRLF-to-LF reproduces target blob |
| `jni/yaya/parser0.cpp` | `app/src/main/jni/yaya/parser0.cpp` | `f23b69e0fcd102edb88f23c34b74f640347c13ff` | CRLF-to-LF reproduces target blob |
| `jni/yaya/parser0.h` | `app/src/main/jni/yaya/parser0.h` | `e45bbec380a80f161cbbab26f76594e18a8203f6` | CRLF-to-LF reproduces target blob |
| `jni/yaya/parser1.cpp` | `app/src/main/jni/yaya/parser1.cpp` | `0ad9db6f5a23622f473c5aaa5ad3e69eb7184f18` | CRLF-to-LF reproduces target blob |
| `jni/yaya/parser1.h` | `app/src/main/jni/yaya/parser1.h` | `7fec0741809037b8d9bc9ad6424c49c3e298b029` | CRLF-to-LF reproduces target blob |
| `jni/yaya/posix_utils.cpp` | `app/src/main/jni/yaya/posix_utils.cpp` | `406c89663e259f53d0046d8e8488f3e09510dbaa` | CRLF-to-LF reproduces target blob |
| `jni/yaya/posix_utils.h` | `app/src/main/jni/yaya/posix_utils.h` | `124255dfb8248d8160d04b98a51811ac9e6a975e` | CRLF-to-LF reproduces target blob |
| `jni/yaya/readme-original.txt` | `app/src/main/jni/yaya/readme-original.txt` | `f569b00e3b9cea0a2be7915d4dcc5656c30d553f` | CRLF-to-LF reproduces target blob |
| `jni/yaya/readme.txt` | `app/src/main/jni/yaya/readme.txt` | `ecbd54f8e4b59dea644b909bc19052c34fca3b4c` | CRLF-to-LF reproduces target blob |
| `jni/yaya/resource.h` | `app/src/main/jni/yaya/resource.h` | `79ba9f7bfede31962c15126a11fe8238e1da33f5` | CRLF-to-LF reproduces target blob |
| `jni/yaya/selecter.cpp` | `app/src/main/jni/yaya/selecter.cpp` | `f312b559c63dc48d8a18abb328ce1e95ac51d5ea` | CRLF-to-LF reproduces target blob |
| `jni/yaya/selecter.h` | `app/src/main/jni/yaya/selecter.h` | `a0586f22356effcb3ad1de35f91112a6f95f9b2d` | CRLF-to-LF reproduces target blob |
| `jni/yaya/sha1.c` | `app/src/main/jni/yaya/sha1.c` | `6febd0ec2b03070012464018a9c538abed8d2149` | CRLF-to-LF reproduces target blob |
| `jni/yaya/sha1.h` | `app/src/main/jni/yaya/sha1.h` | `1978f5f438f89bc8ffe5847dfb5fe49d409f5fb9` | CRLF-to-LF reproduces target blob |
| `jni/yaya/stdafx.cpp` | `app/src/main/jni/yaya/stdafx.cpp` | `a1f786439194a115927f0d8ebf4da90fda71f862` | CRLF-to-LF reproduces target blob |
| `jni/yaya/stdafx.h` | `app/src/main/jni/yaya/stdafx.h` | `dd99fbb63746c55e3c425275427ec5bc530eef89` | CRLF-to-LF reproduces target blob |
| `jni/yaya/sysfunc.cpp` | `app/src/main/jni/yaya/sysfunc.cpp` | `6780975cdf9880d98d53cdaaa542419213fd210f` | CRLF-to-LF reproduces target blob |
| `jni/yaya/sysfunc.h` | `app/src/main/jni/yaya/sysfunc.h` | `7c14d2a51aef49b28b84e9c99efd51c59bc13179` | CRLF-to-LF reproduces target blob |
| `jni/yaya/timer.h` | `app/src/main/jni/yaya/timer.h` | `20ee01dd03da225db37153d65fa43fef5aeecbd8` | CRLF-to-LF reproduces target blob |
| `jni/yaya/value.cpp` | `app/src/main/jni/yaya/value.cpp` | `827adb9d335c735fec893baa29123d5e053676e3` | CRLF-to-LF reproduces target blob |
| `jni/yaya/value.h` | `app/src/main/jni/yaya/value.h` | `21c11b31a41713bf907734ced04757b2f7df219c` | CRLF-to-LF reproduces target blob |
| `jni/yaya/valuesub.cpp` | `app/src/main/jni/yaya/valuesub.cpp` | `729cebe96a88629bb162dad60a3a85405d70f032` | CRLF-to-LF reproduces target blob |
| `jni/yaya/variable.cpp` | `app/src/main/jni/yaya/variable.cpp` | `dac71b13075f75a15b68878d8020b2c691ef878d` | CRLF-to-LF reproduces target blob |
| `jni/yaya/variable.h` | `app/src/main/jni/yaya/variable.h` | `0222a4286f7e30e38ff93c9c0de1ecae5f10f3a0` | CRLF-to-LF reproduces target blob |
| `jni/yaya/wsex.cpp` | `app/src/main/jni/yaya/wsex.cpp` | `30c069f03138fef90c44dc0b7ee9b23fa58a581d` | CRLF-to-LF reproduces target blob |
| `jni/yaya/wsex.h` | `app/src/main/jni/yaya/wsex.h` | `7d05e8c1d9f506b58b50ea1ceb97926bf39aa893` | CRLF-to-LF reproduces target blob |
| `jni/yaya/yaya_jni.cpp` | `app/src/main/jni/yaya/yaya_jni.cpp` | `97bdc3561f888967d3f7ca2ca4b5392c8f401411` | CRLF-to-LF reproduces target blob |
| `jni/yaya/yayad.py` | `app/src/main/jni/yaya/yayad.py` | `02fbedc708e75ea08e6ba8f9a359f376d553ef35` | CRLF-to-LF reproduces target blob |
