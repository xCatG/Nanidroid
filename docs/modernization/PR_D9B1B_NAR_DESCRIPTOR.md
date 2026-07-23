# PR D9b1b — NAR install descriptor policy

D9b1b adds a pure parser for caller-supplied descriptor bytes. After enforcing
the input cap, the byte array is cloned through a package-visible test seam.
The parser performs no archive, file, install-root, live tree, extraction,
refresh, or event work and remains unwired from `NarUtil`.

Actual descriptor input is capped at 64 KiB. Text defaults to Shift_JIS. A
UTF-8 BOM takes precedence over a first-line charset declaration; without a
BOM, only a declaration on line one selects another supported charset.
Malformed or unmappable input and unsupported charset names have distinct
typed errors. Returned metadata records the effective canonical charset, so a
BOM conflicting with `charset,Shift_JIS` reports `charset,UTF-8`.

Every nonblank line requires a comma. Values may contain later commas. Keys are
case-insensitive and every duplicate is rejected; `charset` anywhere except
line one is invalid. Metadata and the returned model are immutable.

`type`, `name`, and `directory` are required. Only `ghost` is supported; the
official non-ghost type list returns `UNSUPPORTED_TYPE`, while unknown tokens
return `INVALID_TYPE`. Only exact `refresh,1` is unsupported. Every other value
means refresh disabled and is accepted.

Official compound install prefixes (`balloon`, `headline`, `plugin`,
`calendar.skin`, and `calendar.plugin`, optionally followed by digits) are
recognized. Their `directory`, `source.directory`, `refresh`, and
`refreshundeletemask` directives are never silently accepted: exact compound
`refresh,1` returns `UNSUPPORTED_REFRESH`, and every other recognized compound
directive returns `UNSUPPORTED_COMPOUND_INSTALL`. Unknown custom dotted keys
remain ordinary metadata.

The descriptor directory is validated even when a forced ID overrides it.
Targets are NFC-normalized safe single components capped at 255 UTF-8 bytes.
D9b1b does not resolve an install root or make live installation safe.
