# Modernization behavior ledger

Every modernization PR must update this ledger when it changes a classification
or introduces a new observable behavior. “Legacy-observed” does not mean a
behavior is safe or required.

| Area | Initial classification | Modernization rule |
| --- | --- | --- |
| Package name and upgrade identity | Required invariant | Preserve `com.cattailsw.nanidroid` unless a release decision explicitly changes it. |
| Ghost metadata and shell discovery | Required invariant | Characterize before translation. |
| Descriptor decoding and metadata pairs | Required migration invariant with legacy exceptions | Preserve default Shift-JIS, declared UTF-8, non-ASCII metadata, and LF/CRLF equivalence during mechanical parser replacement. This migration guard does not decide the long-term supported ghost/NAR formats or encodings. Duplicate-key replacement, ignored extra-comma lines, unsupported-charset fallback, malformed-byte replacement, and empty-input failure are legacy-observed only and require an explicit product decision before changing. |
| Sakura Script parsing and event order | Required migration invariant with legacy exceptions | Preserve core speaker routing, cumulative text, ordered distinct surface-state transitions, animation starts, newline/clear handling, and quick-session whole-line output during mechanical replacement. Choice labels continuing into balloon text and unsupported-tag consumption are legacy-observed only; compare semantic traces, not duplicate view refreshes or timing. |
| SHIORI response-envelope decoding and parsing | Required migration invariant with legacy exceptions | Preserve declared UTF-8/Shift-JIS decoding, non-ASCII values, conventional headers, embedded value colons, status codes, and absent values during mechanical replacement. Duplicate replacement, case-sensitive keys, no-space value truncation, malformed-header defaults, truncated-charset failure, and preservation of an ASCII body with an unsupported declaration in the pinned harness are legacy-observed only and require an explicit decision before changing. The ASCII observation does not identify the fallback decoder for non-ASCII bytes. |
| Surface definition and structural animation loading | Required migration invariant with legacy exceptions | Preserve grouped surface declarations as distinct models, numeric surface ids, normalized direct and zero-padded fallback paths, collision geometry, conventional old/new animation grammar equivalence, interval classification, ordered reset frames and waits, and Sakura/Kero fallback to surfaces `0`/`10` during mechanical replacement. Reset-frame coordinates being discarded are legacy-observed only and not required. The ASCII-compatible structural fixture does not characterize Shift-JIS decoding, image discovery/decoding, rendering, drawable composition, animation execution, timing, or random selection. |
| Kawari and Satori native engine responses | Required invariant; characterization pending | Use small licensed fixtures and raw-byte differential tests in an Android device/emulator or separately justified host-native harness. JVM envelope tests do not complete this row. |
| JNI class names and exported symbols | Required invariant through native migration | Keep Java façades stable until the native ABI is frozen. |
| Emulator smoke ABI artifact | Validation-only additive profile | Keep the frozen debug APK `armeabi`-only. The opt-in emulator artifact may add exactly `arm64-v8a` Kawari and Satori libraries built with pinned r14b/GCC 4.9 at API 21; it is not a supported-ABI product decision or a runtime-success claim. |
| API 9 device support | Product decision required | Do not select dependencies until the supported minimum SDK is approved. |
| Target SDK 13 behavior | Legacy-observed | Replace through an explicit target-SDK compatibility ladder. |
| Raw external-storage ghost directory | Intentional change required | Define app-private storage and an upgrade migration. |
| `file`, `http`, and `https` NAR intents | Product/security decision required | Validate URI ownership, transport policy, and permission persistence. |
| Archive extraction without containment/resource limits | Insecure; must not preserve | Specify traversal, collision, size, count, and atomicity tests before fixing. |
| Default-on legacy analytics/crash reporting | Privacy decision required | Inventory collection and remove, replace, or require explicit consent. |
| Native unload and cross-host ownership | Intentional fix | Specify externally visible ownership behavior before implementation. |
| Existing XML interface | Required until Compose parity | Remove only after semantics, lifecycle, accessibility, and visual sign-off. |

## Required decision records

Before the relevant implementation PR begins, record decisions for:

1. Minimum supported Android API level.
2. Target-SDK migration steps.
3. Supported ABIs.
4. Cleartext HTTP import support.
5. Storage and downgrade policy.
6. Analytics and crash-reporting policy.
7. Supported ghost/NAR formats and encodings.
