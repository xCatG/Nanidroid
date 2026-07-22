# Modernization behavior ledger

Every modernization PR must update this ledger when it changes a classification
or introduces a new observable behavior. “Legacy-observed” does not mean a
behavior is safe or required.

| Area | Initial classification | Modernization rule |
| --- | --- | --- |
| Package name and upgrade identity | Required invariant | Preserve `com.cattailsw.nanidroid` unless a release decision explicitly changes it. |
| Ghost metadata and shell discovery | Required invariant | Characterize before translation. |
| Descriptor decoding and metadata pairs | Required migration invariant with legacy exceptions | Preserve default Shift-JIS, declared UTF-8, non-ASCII metadata, and LF/CRLF equivalence during mechanical parser replacement. This migration guard does not decide the long-term supported ghost/NAR formats or encodings. Duplicate-key replacement, ignored extra-comma lines, unsupported-charset fallback, malformed-byte replacement, and empty-input failure are legacy-observed only and require an explicit product decision before changing. |
| Sakura Script parsing and event order | Required migration invariant with legacy exceptions | Preserve core speaker routing, cumulative text, surface/animation command order, newline/clear handling, and quick-session whole-line output during mechanical replacement. Choice labels continuing into balloon text and unsupported-tag consumption are legacy-observed only; compare semantic traces, not incidental view refreshes or timing. |
| Kawari and Satori SHIORI responses | Required invariant | Use licensed fixtures and byte-level differential tests. |
| JNI class names and exported symbols | Required invariant through native migration | Keep Java façades stable until the native ABI is frozen. |
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
