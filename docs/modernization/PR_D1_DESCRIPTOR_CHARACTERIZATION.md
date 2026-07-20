# PR D1 — Descriptor parser characterization

## Scope

This slice characterizes the existing `DescReader` boundary:

```text
synthetic descriptor bytes -> metadata key/value map
```

It does not change the parser or copy its logic into a test helper. The only
runtime-specific harness setting makes unimplemented Android framework methods
return defaults in local JVM tests. For this slice those calls are limited to
timing/logging; assertions cover only the returned metadata map.

All fixtures are original synthetic byte sequences embedded directly in
`DescReaderCharacterizationTest`. They do not depend on community ghosts,
network access, ambient storage, wall-clock time, or randomness.

## Fixture manifest

The hashes identify the exact raw byte sequences, independently of source-file
encoding or test-result rendering.

| Fixture | Provenance | Raw encoding and line endings | SHA-256 | Expected semantic outcome | Classification |
| --- | --- | --- | --- | --- | --- |
| `shift_jis` | Synthetic | Default Shift-JIS, CRLF | `249a6a72e3228a9193d5ec787f51d136c48701e94ad519ddb4f0c56225898cca` | `name=猫`, `sakura.name=さくら` | Required invariant |
| `utf8_bom` | Synthetic | UTF-8 declaration and BOM, CRLF | `87dcf73f2e913730769a2f2d730180c02da98afc26a29c5301058b9cc18e8af5` | Non-ASCII metadata is decoded as UTF-8 | Required invariant |
| `utf8_no_bom` | Synthetic | UTF-8 declaration without BOM, LF | `4e25947b0d9cd59c8a4bbc9c4432420a93fa13ae56da703336e9f6925635d01f` | Non-ASCII metadata is decoded as UTF-8 | Required invariant |
| `lf` | Synthetic | ASCII-compatible bytes, LF | `285a790e7fafa75f9a24b04a57f0bd3766202b6270eeb622626e60b0484aa9bd` | Same metadata map as `crlf` | Required invariant |
| `crlf` | Synthetic | ASCII-compatible bytes, CRLF | `efbc8332340260a373759e27b4a473d62f957e0faef3c3120e9b4f3841aea9f2` | Same metadata map as `lf` | Required invariant |
| `legacy_pairs` | Synthetic | ASCII-compatible bytes, CRLF | `2651cb94336e2ed7fa3111cf433f5094f44328f4932fc9ec6be633e9f1b72f43` | Last duplicate wins; missing- and extra-comma lines are ignored | Legacy-observed |
| `unsupported` | Synthetic | Unsupported declaration followed by Shift-JIS, LF | `fbd12fc0a0c394a6fc359b3a1633676e0b7816988e58b89ece38f0111be28e54` | Parser falls back to Shift-JIS | Legacy-observed |
| `empty` | Synthetic | Zero bytes | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | Parser throws `NullPointerException` | Legacy-observed; not required |
| `truncated` | Synthetic | Incomplete Shift-JIS sequence | `f8f7c99ac56d05f7d666f8b71dc7fdb03c5331e7f37993ce61cd7918ffa45a12` | Decoder supplies U+FFFD as the value | Legacy-observed |

The legacy observations are migration evidence, not approval of the behavior.
In particular, empty input failure must not be treated as a compatibility
requirement when the parser is redesigned.

## Verification

Run after the frozen legacy-native artifacts have been generated:

```text
docker compose -f .devcontainer/compose.yaml run --rm dev \
  ./gradlew --no-daemon testDebugUnitTest
```

The command emits the normal Gradle JUnit XML and HTML reports under
`build/test-results/testDebugUnitTest/` and
`build/reports/tests/testDebugUnitTest/`.
