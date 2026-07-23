# PR D8: deterministic ghost handoff characterization

## Scope and decision

D8 freezes the smallest deterministic `SScriptRunner` handoff boundary before
ghost-switching code is mechanically changed. It adds two JVM
characterization tests and no production source, manifest, resource, asset,
SDK, ABI, native, dependency, plugin, or device-test changes.

The tests cover:

1. the outgoing ghost's transition request, synchronous quick-script rendering,
   and handoff callback; and
2. explicit replacement of an existing ghost by a returning ghost.

Filesystem discovery, preferences, Activity orchestration, asynchronous
replacement, manager/view rebinding, and native-engine ownership remain outside
this slice.

## Required migration invariants

For a manual transition from a ghost whose metadata name is
`Old Ghost Metadata` to `Next Sakura`:

- the outgoing ghost receives `OnGhostChanging`;
- its references are exactly
  `[Next Sakura, manual, null, /ghosts/next]`;
- the quick response `\_qSwitching\e` renders `Switching`; and
- rendering occurs before exactly one `ghostSwitchScriptComplete()` callback.

For explicit replacement:

- assigning the initial outgoing ghost is silent;
- the outgoing ghost has create count 1 and the replacement has create count 2,
  pinning the count decision to the replacement;
- the runner retains the outgoing identity across the transition callback;
- the returning replacement, not the outgoing ghost, receives
  `OnGhostChanged`; and
- its references are exactly `[Old Ghost Metadata, null]`, even though the
  outgoing Sakura display name is the distinct `Old Sakura Display`.

## Deterministic JVM harness

The same-package fake `Ghost` bypasses descriptors, files, surfaces, SHIORI
native engines, and create-count persistence. It returns one fixed SHIORI
response and records only event recipient, name, and references. Test-local
inert Sakura, Kero, and balloon views satisfy the production runner's
`updateUI()` path; the Sakura balloon records non-empty rendered text.

The fake's `unload()` is deliberately inert because unload ownership and order
are deferred. Cleanup does not reflect into runner fields. While the callback
and fake remain installed it drains the queue through public `stop()` behavior,
then clears callbacks and managers. A tracked fake has both test-only names
suppressed before `setGhost(null)`. If another suite left an unknown named
ghost, cleanup first installs a null-name, count-2 neutral fake, then clears it.
This avoids the production null-replacement dereference through public APIs.
The second test deliberately leaves an untracked named fake and invokes cleanup
before its behavior scenario, proving this order-independent path. The focused
suite passed with a no-cache execution, and the full process-singleton JVM
suite passed afterward.

The exact JVM source allowlist gains only:

```text
test/jvm/com/cattailsw/nanidroid/GhostSwitchingCharacterizationTest.java
```

No AndroidTest allowlist or DEX marker changes were needed because the observed
handoff is synchronous and deterministic under the existing Android
default-return JVM harness.

## TDD and mutation evidence

RED commit `31db81e` changed only the Python contract for the Gradle source-set
allowlist. The 13 focused build-script tests then produced two expected
failures: the D8 source did not exist and Gradle did not allowlist it.

GREEN commit `ce6c5d1` added the exact Gradle entry and two behavior tests.
The first focused execution exposed a test-harness omission: production script
rewriting asks the ghost for Sakura, Kero, and user names. Adding inert fake
values completed the harness without changing production.

Five temporary production mutations calibrated the oracles:

1. moving `nextPath` from reference 3 to reference 2 failed the exact outgoing
   request trace;
2. moving `changingPending = true` after synchronous SHIORI dispatch failed
   because the handoff callback was missing; and
3. consulting the outgoing create count (1) instead of the replacement count
   (2) failed only the replacement-routing test;
4. substituting the outgoing Sakura display name for its distinct ghost
   metadata name failed only the replacement assertion; and
5. clearing the current ghost immediately after transition completion failed
   only the continuous handoff-to-replacement scenario.

Every mutation was restored. The final `SScriptRunner.java` worktree hash and
index blob are both
`83b77beb153b0edd82f88541db3d1fa0ad1ad9f2`; the production diff is empty.

## Final validation

Tooling passed 66/66: 13 Windows build/source-set contracts and 53 pinned-Linux
APK, DEX, native, and path contracts. The six JVM suites passed 31/31 with no
failures or errors.

The frozen legacy build passed and reproduced:

```text
Nanidroid-debug.apk
bytes: 1117844
sha256: bf5f31c62be83e05e8df90b8ef2fb223837329b3ed01b3a6d79566b1e53126e8
```

The standard Gradle pipeline passed JVM, debug APK, AndroidTest APK, package,
required-entry, and native-equivalence checks. The same-invocation device pair
used:

```text
Nanidroid-debug-androidTest.apk
bytes: 14248
sha256: 7af927a03dd3841841cb059e7419069be838134985c6697252e791bbfc7099a0

Nanidroid-emulator.apk
bytes: 2964297
sha256: cdeb7efec5bc51c1bd73902e098b4f510444a5c9cd7ca9a99b1a99e639c64d69
```

The ARM64 lane retained the exact API 21, NDK r14b, GCC 4.9 native contract.
The emulator APK retained byte-identical `armeabi` and `arm64-v8a` native
libraries. On the configured API 36.1 emulator, the unchanged D7 device
regression suite reported:

```text
SurfaceAnimationExecutionCharacterizationTest:..
SurfaceRenderingCharacterizationTest:..
Time: 0.301
OK (4 tests)
```

Acceptance checked ADB exit status, required `OK (4 tests)`, and rejected
`FAILURES!!!`.

## Explicit deferrals

D8 does not characterize:

- fresh replacement and `OnFirstBoot`;
- the generic runner-stop callback and its ordering relative to handoff;
- old-ghost unload timing, order, exceptions, or cross-host native ownership;
- `GhostMgr` discovery, directory enumeration, descriptors, or preferences;
- `Nanidroid` Activity/AsyncTask orchestration, lifecycle, view/manager
  rebinding, or persistence;
- invalid ghost ids, null recipients, errors, empty/no-script responses, or
  callback-absent behavior;
- filesystem installation, archive extraction, NAR intents, or network I/O;
- wall-clock waits, delayed handlers, races, or random behavior.

Those require separately bounded tests and, for unload and installation,
explicit product/security decisions.

## Reproduction

```powershell
python -m unittest tools.test_build_scripts
docker compose -p nanidroid-d8 -f .devcontainer/compose.yaml run --rm dev python -m unittest tools.test_compare_apk_contracts tools.test_inspect_android_test_apk tools.test_inspect_emulator_native tools.test_inspect_legacy_apk tools.test_inspect_native_contract tools.test_verify_apk_native_payload tools.test_verify_emulator_apk
docker compose -f docker/legacy/compose.yaml run --rm build
docker compose -f docker/legacy/compose.yaml run --rm emulator-native
docker compose -p nanidroid-d8 -f .devcontainer/compose.yaml run --rm dev bash -lc "./docker/gradle/build.sh && ./docker/emulator/build.sh"
```

Install and verify the APKs produced by the final combined devcontainer
invocation. Debug artifacts built in different ephemeral containers may have
different valid signing keys and cannot be mixed:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5554 uninstall com.cattailsw.nanidroid.test
& $adb -s emulator-5554 uninstall com.cattailsw.nanidroid
& $adb -s emulator-5554 install --bypass-low-target-sdk-block artifacts\emulator\apk\Nanidroid-emulator.apk
if ($LASTEXITCODE -ne 0) { throw "D8 emulator target install failed" }
& $adb -s emulator-5554 install --bypass-low-target-sdk-block artifacts\gradle\Nanidroid-debug-androidTest.apk
if ($LASTEXITCODE -ne 0) { throw "D8 AndroidTest install failed" }
$result = & $adb -s emulator-5554 shell am instrument -w com.cattailsw.nanidroid.test/android.test.InstrumentationTestRunner 2>&1
$instrumentExit = $LASTEXITCODE
$result
$text = $result -join "`n"
if ($instrumentExit -ne 0 -or $text -notmatch 'OK \(4 tests\)' -or $text -match 'FAILURES!!!') {
    throw "D8 instrumentation acceptance failed"
}
```
