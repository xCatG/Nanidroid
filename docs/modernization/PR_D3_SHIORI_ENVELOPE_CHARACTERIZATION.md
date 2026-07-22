# PR D3 — SHIORI response-envelope characterization

## Scope

This slice characterizes the existing production boundary:

```text
synthetic raw response bytes
  -> JNIShiori.modResponseWithCharSet
  -> ShioriResponse(BufferedReader)
  -> decoded text and semantic envelope
```

An inert test subclass exposes the protected decoder and makes every native
entry point fail if called. The tests then pass the decoded text to the real
response parser. They do not copy either implementation into the harness.

No production Java, C++, resource, manifest, JNI, native toolchain, or APK
contract behavior is changed by this slice.

## Fixture manifest and provenance

All fixtures are original synthetic protocol text embedded in
`ShioriEnvelopeCharacterizationTest`. They contain only protocol scaffolding,
invented sender names, and the short words `猫` and `さくら`; they are not copied
from a ghost, dictionary, engine response, or other third-party work. Every
test recalculates SHA-256 over the complete encoded byte sequence before
production code sees it. Line endings are CRLF throughout.

| Fixture | Encoding | SHA-256 | Semantic result | Classification |
| --- | --- | --- | --- | --- |
| Declared UTF-8, status 200, `Value: \h猫:ready\e` | UTF-8 | `3c1653c2d86c04fe71e0be4802b4f6ff92206b08f633c36beab160daf0053421` | Exact decoded text; sender, charset, status, and value including its second colon | Required migration invariant |
| Declared Shift-JIS, status 200, `Value: \hさくら\e` | Shift-JIS | `0a5a71d641198f90f47eaf10a7a70227034bf687ebae440d5412471a02d867a8` | Exact non-ASCII decoded value | Required migration invariant |
| Status 204, no `Value` | US-ASCII | `66a2fbd2c3c43c5208b4eeb1c5e381ca3894b398a0ba20512e82d8d4cd869af4` | Status and absent value preserved | Required migration invariant |
| Status 400, no `Value` | US-ASCII | `5b39a1cbf8d32b2ad69413d08601ee6260b4def86335d0219f574c301caf76f6` | Status and absent value preserved | Required migration invariant |
| Status 500, no `Value` | US-ASCII | `7e60e98dcccc0f6ade2f0e1046193036d2e3c7a12dd5e397dadaf352c0959933` | Status and absent value preserved | Required migration invariant |
| Duplicate `Sender` plus lower-case `sender` | US-ASCII | `80f2e4a4237d43551646fedae0dde5ce2c401ad41540ad6450a887c036bde5a1` | Last exact-case duplicate wins; differently cased key remains distinct | Legacy-observed; not required |
| Malformed response header | US-ASCII | `99997cf5a309a81b7acb95da3516e7aaf3a0323cc061ff3934a56a0a5f19de59` | Status remains 500 and protocol object remains null | Legacy-observed; not required |
| `Sender:MySender` without a space | US-ASCII | `29053bae20237169e87e33e40e148f13d1bde289896c2e4c1967237403c6ad13` | Parser stores `ySender` | Legacy-observed bug; not required |
| `Charset: UTF-8` without a following CRLF | US-ASCII | `dbfabe1afac086fbc15674bed87102f75103ce9867d435b722ccbdcdaaa7ef03` | Decoder throws `StringIndexOutOfBoundsException` | Legacy-observed bug; not required |
| Unsupported declared charset with ASCII-only body | US-ASCII | `e66239bc9c2537cd336b4791f02e5077b4ecd6f242cf395da369cd9da1b33708` | Platform-default fallback is deterministic for this ASCII fixture | Legacy-observed; not required |

The unsupported-charset case deliberately does not freeze platform-default
decoding for non-ASCII bytes. That result can vary by runtime. Likewise, the
tests do not assert `Hashtable` iteration order or `ShioriResponse.toString()`.

## TDD evidence

### Red

1. Adding `ShioriEnvelopeCharacterizationTest` while the D1+D2 allowlist was
   unchanged caused `verifyCharacterizationTestIsolation` to fail before test
   execution and name the new file as the sole unexpected source.
2. After temporarily admitting the source, the required UTF-8 case
   intentionally expected `\hDOG:ready\e`. All eight D3 tests executed and
   that single semantic assertion failed against the decoded and parsed
   `\h猫:ready\e` value.

### Green

The value expectation was restored to the observed production result. All
eight D3 tests pass in the API 15 JVM harness, alongside the unchanged D1 and
D2 characterizations.

### Refactor

Fixture encoding and hashing, response parsing, and the status/no-value matrix
share small test helpers. The harness uses `Charset.forName`, explicit
`try`/`catch`, and ordinary readers so it remains compatible with the pinned
API 15 compile surface. It asserts the raw response header and status code but
does not claim `ProtocolVersion` field values from Android default-return
stubs.

## Harness isolation

The executable exact-source allowlist now contains only:

- `DescReaderCharacterizationTest.java`
- `SakuraScriptCharacterizationTest.java`
- `ShioriEnvelopeCharacterizationTest.java`

Any missing expected source, or any fourth Java or Kotlin source under
`src/test/` or `test/jvm/`, fails `verifyCharacterizationTestIsolation`. Every
generated app task matching `test*UnitTest` depends on that guard, so a future
unit-test variant inherits the same fail-closed boundary.

The normal container pipeline still compiles both ARM `armeabi` engines,
checks ndk-build/CMake facts and JNI exports, packages their exact payload, and
compares the APK contract. D3 does not load or execute those ARM libraries on
the x86 Linux host.

## Non-goals and remaining native work

This slice does not characterize:

- actual Kawari or Satori engine-generated response bytes;
- concrete Kawari request encoding or Satori request rewriting;
- native load, unload, global state, concurrency, ownership, or memory safety;
- `ShioriFactory`, ghost event/request construction, or Sakura Script runner
  integration;
- protocol-spec conformance or a long-term response/error policy;
- fixes for truncated headers, malformed charset declarations, case-sensitive
  keys, duplicate keys, or platform-default decoding;
- Kotlin translation, source moves, dependencies, SDK/target/ABI changes, or a
  replacement for Android's `ProtocolVersion` type.

Actual Kawari/Satori differential coverage remains a later D slice. It needs
small licensed, provenance-recorded engine inputs and raw response hashes in an
Android device/emulator harness, or a separately justified host-native harness.
Until then, the behavior ledger must not claim the native engines themselves
are characterized.

## Verification

Focused characterization:

```text
./gradlew testDebugUnitTest \
  --tests com.cattailsw.nanidroid.ShioriEnvelopeCharacterizationTest
```

The standard container pipeline runs D1, D2, and D3, requires fresh JUnit XML,
assembles the APK, and executes the frozen APK/native parity gates:

```text
docker compose -f .devcontainer/compose.yaml run --rm dev \
  ./docker/gradle/build.sh
```
