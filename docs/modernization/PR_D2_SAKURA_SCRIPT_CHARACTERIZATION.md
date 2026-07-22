# PR D2 — Sakura Script semantic-event characterization

## Scope

This slice characterizes the existing production boundary:

```text
synthetic Sakura Script text -> SScriptRunner -> ordered semantic event trace
```

`SScriptRunner` runs through its existing synchronous `setNoWaitMode(true)`
path. Test-only `SakuraView`, `KeroView`, `Balloon`, and `UICallback` record
speaker text states, distinct surface-state transitions, animation starts, and
choice callbacks. The recorder suppresses duplicate surface/text states and
incidental view refreshes. It does not replace or copy the production parser.

The runner is process-static, as is its message queue. Each test clears the
queue and callbacks, installs fresh recorders, drives the retained surface ids
to the same `0`/`10` baseline, and clears the bootstrap trace before applying a
fixture. This makes method order irrelevant without changing production state
semantics.

No production Java, C++, resource, manifest, JNI, native toolchain, or APK
contract behavior is changed by this slice.

## Fixture manifest

All inputs are original ASCII-compatible synthetic strings embedded in
`SakuraScriptCharacterizationTest`. Each test recalculates the SHA-256 of the
exact UTF-8 fixture text before invoking production code.

| Fixture | SHA-256 | Ordered semantic result | Classification |
| --- | --- | --- | --- |
| `\hHi\s[120]\i[3]\uYo\s[11]\i[4]\e` | `eb3101be780c6f27d1876911986a544673a4807a1a33dbf87c132faef0bc4cf7` | Sakura text `H`, `Hi`; Sakura surface `120`; Sakura animation `3`; Kero text `Y`, `Yo`; Kero surface `11`; Kero animation `4` | Required migration invariant |
| `\hA\n[half]B\cC\e` | `d5c3ca435493d40f213b620286676313a762d113f7db94eaae96b7b9d3ca1893` | Sakura text states `A`, `A\n`, `A\nB`, `C`; `[half]` is consumed as the newline modifier | Required migration invariant |
| `\h\_qHello, world.\e` | `16116174b6633c28f55373f47a54e84e6be455ce62707009bf39872757bd82eb` | One Sakura text state: `Hello, world.` | Required migration invariant |
| `\h\s[120]\s[120]\i[3]\i[3]\e` | `6fdd70fdfa7ba5db2e83a36481a963ed516bd595b7cfeb0fc131edbb94685047` | One distinct Sakura surface transition to `120`, followed by two Sakura animation starts for `3` | Required migration invariant |
| `\hA\q[One,id1]B\q[Two,id2]\e` | `85a129feecd76e217ff9495e44e159bc7db0088a830e3aaf28f4f74ecac08687` | Sakura text `A`; choice callback `One`/`id1`, `Two`/`id2`; Sakura text then becomes `AOneBTwo` | Legacy-observed; not required |
| `\hA\4\5\6\v\_n\_V\_l[half]B\e` | `4f983c4271218d8335f2352efd2adcd138e97514f82a6b5cb5537530298c7fbc` | Unsupported tags and `_l` argument are consumed; Sakura text changes from `A` to `AB` | Legacy-observed; not required |

The choice observation records the current behavior; it does not approve the
runner's lack of a pause boundary or continued rendering of labels. Likewise,
consuming unsupported tags is migration evidence, not a long-term format
decision.

## TDD evidence

### Red

1. Adding `SakuraScriptCharacterizationTest` to the D1 branch caused
   `verifyD1TestIsolation` to reject the second JVM source before execution.
2. After replacing the one-file guard with an exact D1+D2 allowlist, the first
   real production trace intentionally expected `text:sakura:HELLO`. The test
   failed with the observed event `text:sakura:Hi`, proving the trace assertion
   detects a semantic mutation.
3. A recorder regression first called `loadAnimation` without
   `startAnimation`. It failed because the original recorder emitted the event
   at load time, exposing that deleting a production start would have escaped
   the trace.
4. Adding a nonexistent path to the expected-source list initially passed the
   isolation task. After the guard was made bidirectional, the same probe failed
   with `Missing expected JVM test sources`.

### Green

The expectation was restored to the observed production event. Six fixtures
pass through unchanged `SScriptRunner`, and the recorder contract confirms that
both Sakura and Kero animation events require a start after a load. The original
eight D1 tests remain in the same task.

### Refactor

Fixture hashing, execution, and trace comparison share one test helper. View
recorders and the callback recorder remain nested test-only helpers. Animation
recorders hold a loaded id and emit only when production subsequently calls
`startAnimation`; deleting the production start therefore removes the expected
event. A negative guard check confirms that an arbitrary third JVM source is
still rejected. A temporary `testReleaseUnitTest` task plus an unexpected source
also confirmed that a future release-named unit-test task inherits the guard;
the current Android configuration does not generate a real release unit-test
task.

## Harness isolation

Android's default-return stubs remain enabled only behind the executable
allowlist guard. The allowlist contains exactly:

- `DescReaderCharacterizationTest.java`
- `SakuraScriptCharacterizationTest.java`

Any missing expected source, or any other Java or Kotlin source under
`src/test/` or `test/jvm/`, fails `verifyCharacterizationTestIsolation`. Every
generated app task matching `test*UnitTest` depends on that guard. This project
currently generates only `testDebugUnitTest`; the task-name rule also protects
future app unit-test variants without attaching to unrelated tasks. D2 does not
turn the default-return setting into a general unit-test policy.

## Non-goals and limits

This slice does not:

- fix choice handling or unsupported-tag behavior;
- translate code to Kotlin or change the Handler/queue architecture;
- invoke SHIORI, a native engine, network access, ambient storage, time, or
  randomness;
- characterize real rendering, visibility, talking-animation refreshes, or
  animation/wait timing;
- change JNI, native toolchains, ABI/API support, NAR/storage/lifecycle/privacy
  policy, or Compose UI.

`setNoWaitMode(true)` deliberately removes timing from this semantic boundary.
Timing and host lifecycle need separate characterization before their later
roadmap slices.

## Verification

Focused characterization:

```text
./gradlew testDebugUnitTest \
  --tests com.cattailsw.nanidroid.SakuraScriptCharacterizationTest
```

The standard container pipeline runs both D1 and D2 tests, requires fresh
JUnit XML, assembles the APK, and executes the frozen APK/native parity gates:

```text
docker compose -f .devcontainer/compose.yaml run --rm dev \
  ./docker/gradle/build.sh
```
