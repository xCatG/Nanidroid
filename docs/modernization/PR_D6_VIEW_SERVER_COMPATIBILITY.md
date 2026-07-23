# PR D6 — Legacy ViewServer compatibility boundary

## Purpose

The API 36.1 debug smoke from C2 reached `Nanidroid.onCreate`, then crashed on
the main thread:

```text
java.lang.NetworkOnMainThreadException
  at java.net.InetAddress.getLocalHost(...)
  at com.android.debug.hv.ViewServer.start(...)
  at com.cattailsw.nanidroid.Nanidroid.onCreate(Nanidroid.java:161)
```

D6 prevents the bundled debug ViewServer from participating in activity
lifecycle events on API 11 and newer. Android 3.0 / Honeycomb is API 11, which
is the exact boundary encoded by `Build.VERSION_CODES.HONEYCOMB`.

The project still declares `minSdk = 9`, and the supported-minimum decision is
open. D6 therefore keeps the existing API 9–10 routing intact: activity create,
resume, and destroy still delegate to the unchanged ViewServer add, focus, and
remove operations. This is compatibility preservation, not a claim that the
legacy server is a supported debugging interface.

## Implementation boundary

`ViewServerLifecycle` is package-private and stateless. Its production methods
read `Build.VERSION.SDK_INT`; package-private overloads accept an SDK integer
and backend for JVM tests. The production backend is an immutable, stateless
adapter and retains no `Activity`. There is no mutable test hook.

For API 11 and newer, the SDK check returns before any backend operation. In
particular, `ViewServer.get`, its process singleton, and `ViewServer.start` are
never reached. API 9–10 retain the prior debug/release decision inside the
unchanged `ViewServer.get`: debuggable builds may use the real server and other
builds receive its no-op implementation.

The active-call-site tooling contract removes block comments before requiring
exactly one create, resume, and destroy facade call and rejecting every active
direct `ViewServer.get` call in `Nanidroid.java`. The historical commented
lifecycle sketch remains unchanged.

## TDD evidence

### RED

Commit `69cdaaf` added the exact-allowlisted compatibility tests before the
facade existed. The focused Gradle task reached Java test compilation and
failed with `ViewServerLifecycle does not exist`.

The three tests specify:

- API 9 and 10 lifecycle routing is exactly `add`, `focus`, `remove`;
- API 10 routes while API 11 does not;
- repeated API 11 and API 36 lifecycles never touch a backend whose every
  operation throws.

During review hardening, the active-call-site contract was run against a
temporary direct `ViewServer.get(...).setFocusedWindow(...)` resume mutant. It
failed on the forbidden active call, then passed after facade routing was
restored. The mutant was not committed.

### GREEN

The focused compatibility suite passed 3/3. The complete D1–D6 JVM suite
passed 29/29 across five suites, without failures, errors, or skips.

The complete Python tooling suite passed 53/53. Repository hygiene passed with
384 tracked files and seven inventoried opaque artifacts. The standard Gradle
pipeline produced a signed/aligned 2,046,006-byte APK with SHA-256
`666bd9fa126d5140f8379cedefcbc71aa9126fea41ba5d6eb76dc0a85f8cff01`.
That whole-APK digest is run provenance, not a reproducibility invariant,
because debug signing is nondeterministic. The authoritative package,
native-library, and required-entry contract remained equivalent, and both
standard native payloads were byte-identical to their frozen CMake candidates.

## API 36.1 device acceptance

The D6 APK built from this branch through the C2 emulator lane contained
exactly the frozen `armeabi` pair plus the isolated `arm64-v8a` pair. It was
installed with the documented local-only `--bypass-low-target-sdk-block`;
Android selected `primaryCpuAbi=arm64-v8a` through the x86_64 AVD's translation
layer.

The first run displayed Android's permission-review screen. Its default
notification, media, file, audio, and account grants were accepted. The
permission controller was then prevented by Android's background-activity
launch policy from automatically returning to Nanidroid, so the app was
explicitly relaunched after logs were cleared. Android's old-target warning was
recorded and dismissed.

The clean launch completed with `Status: ok`, `LaunchState: COLD`, a live
Nanidroid process, and Nanidroid as `topResumedActivity`. Android CLI layout
inspection found the running legacy UI and its debug controls. After HOME and
relaunch, Android reported a HOT launch, the same process remained alive, and
Nanidroid was resumed. The post-launch log contained none of:

```text
FATAL EXCEPTION
NetworkOnMainThreadException
ViewServer.start
```

Local ignored evidence is written to
`artifacts/emulator/d6-api36-layout.json` and
`artifacts/emulator/d6-api36.png`. Device evidence does not replace hosted CI.

## Explicit limits

D6 does not:

- modify `ViewServer.java` or move its socket work to another thread;
- verify the real ViewServer socket on an API 9 or API 10 device;
- decide whether API 9 support should remain;
- change manifests, SDK levels, resources, dependencies, build types, ABIs,
  CMake, JNI, or native sources;
- make the C2 emulator ABI additive profile a supported-ABI decision;
- address the permission-review, old-target warning, background-launch policy,
  storage, analytics, or broader lifecycle modernization surfaced by the smoke
  test.

## Reproduction

```text
./gradlew testDebugUnitTest \
  --tests com.cattailsw.nanidroid.ViewServerLifecycleCharacterizationTest

docker compose -f docker/legacy/compose.yaml run --rm build
docker compose -f .devcontainer/compose.yaml run --rm dev \
  ./docker/gradle/build.sh

docker compose -f docker/legacy/compose.yaml run --rm emulator-native
docker compose -f .devcontainer/compose.yaml run --rm dev \
  ./docker/emulator/build.sh

adb install --bypass-low-target-sdk-block \
  artifacts/emulator/apk/Nanidroid-emulator.apk
```
