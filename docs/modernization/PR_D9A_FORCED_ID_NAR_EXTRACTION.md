# PR D9a: bounded trusted forced-ID NAR extraction

## Scope and security decision

D9 was split after two independent adversarial design reviews:

- D9a is a production-free positive characterization of one bounded, trusted
  forced-ID extraction path; and
- D9b is the required security-fix slice for hostile archives, untrusted ids,
  limits, collision handling, and atomic installation.

D9a adds exactly one JVM test. It does not change production source, Android
components, resources, assets, dependencies, SDK/ABI configuration, native
code, or device tests. The test invokes the public
`NarUtil.readNarArchive(archive, temporaryRoot, "seed-ghost")` path. A
`TemporaryFolder` supplies separate source and destination directories, so
there is no `/mnt/sdcard`, external user data, network, device, lifecycle,
sleep, or random dependency.

The first design proposed a reflected test of the private wrapped-archive
helpers. Both reviewers rejected it: it bypassed the public metadata path,
would false-green public orchestration defects, and would pin vulnerable
private structure against a later secure redesign. D9a therefore makes no
claim about wrapped archives, descriptor-selected installation, or private
helper names.

## Required positive invariant

For one fixed, trusted id and one bounded, collision-free unwrapped archive
containing only safe relative ASCII entry names:

- extraction returns `true`;
- the destination has exactly one top-level entry, `seed-ghost`;
- `install.txt` declares the deliberately different
  `directory,descriptor-ghost`, but no `descriptor-ghost` destination appears;
- the complete sorted directory/file tree beneath `seed-ghost` is exact;
- nested path components are neither flattened nor stripped;
- every text entry retains its exact US-ASCII bytes; and
- the nested four-byte payload remains exactly `00 7f 80 ff`, with SHA-256
  `89273d2f70b93285bb7ddb4bcee86a5347ca7159352e3cbdd20c23e9d1e507d3`.

The runtime-generated archive contains these five files, deliberately not in
descriptor-first order:

```text
ghost/master/data/payload.bin
readme.txt
ghost/master/descript.txt
install.txt
shell/master/descript.txt
```

No archive or other binary fixture is checked into the repository.

## TDD and mutation evidence

RED commit `4929cfc` changed only the Python contract for the exact JVM
allowlist. The 13 build-script tests produced exactly two expected failures:
the D9a source was missing and Gradle did not allowlist it.

GREEN commit `380b546` added the single test source and its exact Gradle
allowlist entry. The focused JVM execution passed without a device.

Five temporary production mutations calibrated the single exact oracle:

1. routing output to the descriptor's `descriptor-ghost` id failed the
   top-level destination assertion;
2. enabling one-level stripping for the forced-ID branch failed the exact
   tree assertion;
3. skipping `readme.txt` failed the exact tree assertion;
4. truncating one byte from every copied entry failed exact content; and
5. returning `false` after successful extraction failed the success assertion.

Every mutation was restored before GREEN and regression runs. The final
production diff is empty.

## D9b security obligations

The positive D9a fixture must not be interpreted as approval of the current
extractor. The existing behavior ledger classification remains
“Insecure; must not preserve.” D9b must begin with failing security
specifications and then change production.

At minimum, D9b must define and test:

- rejection or containment of archive `..`, absolute, backslash, drive-prefix,
  UNC, malformed-encoding, and canonical-path escapes;
- validation of caller-supplied destination ids, including the `tid` boundary;
- pre-existing symlinks and other filesystem redirections;
- raw, normalized, case-policy, duplicate, and file/directory collisions;
- bounded entry count, path depth/name length, per-entry bytes, total streamed
  bytes, and compression ratio;
- staging outside the selectable ghost tree, full validation before commit,
  atomic publication, cleanup after failure/interruption, and no refresh of a
  partial installation;
- propagated read/write/close errors rather than swallowed partial-copy
  failures; and
- deterministic bounded malformed/fuzz fixtures with recorded seeds.

The `tid == null` metadata/wrapped path currently creates a temporary file
under hard-coded `/mnt/sdcard/nar`. Its future test seam must inject a temporary
location or introduce a pure validated extraction plan. Supported NAR layouts,
descriptor ambiguity, unsupported archive types, overwrites/upgrades, storage
migration, URI ownership, HTTP policy, and permission persistence remain
explicit product/security decisions.

## Final validation

Tooling passed 66/66: 13 Windows build/source-set contracts and 53 pinned-Linux
APK, DEX, native, and path contracts. The seven JVM suites passed 32/32 with no
failures or errors.

The frozen legacy build and CMake/native parity passed:

```text
Nanidroid-debug.apk
bytes: 1117847
sha256: 46e2ad28e6cacdef26f197c9cc8f2ac6e5220b1ec181cbde471ed34154372f09
```

The standard Gradle pipeline passed JVM, APK, package, required-entry, and
native-equivalence checks. The final matched device pair used:

```text
Nanidroid-debug-androidTest.apk
bytes: 14246
sha256: 0e46b43ee89c67b09f59017318908eed36b7718ae4f17e780dc1a9b5c6fb105c

Nanidroid-emulator.apk
bytes: 2964298
sha256: ccfb0f6adb95691e43366429d605663aecfdf3975d6213899418bc7b870e7aae
```

The ARM64 native contract and exact additive `armeabi` plus `arm64-v8a`
payload passed unchanged. The configured API 36.1 emulator ran the unchanged
D7 device regressions:

```text
SurfaceAnimationExecutionCharacterizationTest:..
SurfaceRenderingCharacterizationTest:..
Time: 0.566
OK (4 tests)
```

## Reproduction

```powershell
python -m unittest tools.test_build_scripts
docker compose -p nanidroid-d9 -f .devcontainer/compose.yaml run --rm dev python -m unittest tools.test_compare_apk_contracts tools.test_inspect_android_test_apk tools.test_inspect_emulator_native tools.test_inspect_legacy_apk tools.test_inspect_native_contract tools.test_verify_apk_native_payload tools.test_verify_emulator_apk
docker compose -f docker/legacy/compose.yaml run --rm build
docker compose -f docker/legacy/compose.yaml run --rm emulator-native
docker compose -p nanidroid-d9 -f .devcontainer/compose.yaml run --rm dev bash -lc "./docker/gradle/build.sh && ./docker/emulator/build.sh"
```

Install the emulator and AndroidTest APKs from the final combined invocation,
then require both ADB success and `OK (4 tests)` while rejecting
`FAILURES!!!`, as documented in D8.
