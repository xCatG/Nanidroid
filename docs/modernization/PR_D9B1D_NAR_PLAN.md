# PR D9b1d — diagnostic NAR install plans

D9b1d composes the inventory, descriptor, and bounded ZIP-preflight slices into
public diagnostic `validate` and `verify` operations. It performs no staging,
extraction, target traversal, install-root writes, refresh, or live mutation.

Source bytes are bounded at 544 MiB and SHA-256 hashed before and after ZIP
work. C's central preflight runs before `ZipFile`; enumeration independently
stops at 10,001 and must match the preflight count. The descriptor is opened
from the exact validated owner and ordinal and is actually capped at 64 KiB.

The immutable plan binds source length and digest, every central ordinal and
identity field, normalized mapping, immutable descriptor, wrapper, canonical
install root, and lexical target child. Verification reopens and compares the
same identity. Mismatch and semantic errors remain authoritative over close
failures.

These public plans are diagnostics only and carry no extraction authority.
Doubled path-based hashing detects ordinary replacement races but cannot defeat
a malicious same-UID ABA replacement across separate file opens.
D9b1e must introduce fresh app-private staging and a retained verified session
without changing that public contract.
