# PR D9b1a — NAR archive inventory policy

D9b1a is a pure structural boundary over caller-supplied central-directory
records. It performs no ZIP or file I/O, descriptor parsing, extraction, live
tree inspection, refresh, or event delivery, and is not wired into `NarUtil`.

The validator accepts root `install.txt` or exactly one uniform
single-component wrapper. It rejects ambiguous, mixed, and deeper layouts;
absolute, drive/UNC, backslash, dot, empty, control, and malformed-Unicode
paths; exact duplicates; case/NFC collisions including implicit directories;
and file/directory prefix collisions.
Collision keys are `NFC(lowercase(NFC(value)))` for both full paths and
implicit prefixes.

Before Unicode validation or normalization, raw names are capped at 4,096
UTF-16 code units. Normalized paths retain NFC case for output and use
lower-casing only for collision detection. Every raw name and supplied central
ordinal, CRC, method, declared size, and compressed size is preserved.
The ordinal must equal its zero-based central-list position. CRC, sizes, and
method may use `-1` for unknown; known CRC is unsigned 32-bit, known sizes are
nonnegative, and known methods are stored (`0`) or deflated (`8`). A stripped
wrapper-root directory is retained for identity but marked non-installable with
no relative output path.
The aggregate declared size is `-1` when any entry size is unknown.
Every central-record getter is read exactly once into an immutable snapshot.
A getter runtime failure becomes `INVALID_ENTRY_METADATA`.

Limits are 10,000 entries, depth 32, 1,024 UTF-8 bytes per relative path,
255 bytes per component, 64 KiB declared `install.txt`, 128 MiB declared per
entry, 512 MiB declared total, and 1,000:1 declared expansion. Declared ZIP
metadata remains an untrusted early hint; later slices must enforce actual
descriptor bytes, source identity, and streamed extraction limits.

The focused suite includes a deterministic 512-case hostile-path corpus using
seed `0x4e415244396231L`. D9b1a does not make live installation safe.
