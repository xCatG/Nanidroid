# PR D9b1c — bounded ZIP central preflight

D9b1c adds a package-private, read-only structural check that runs before
callers allocate `ZipFile`. It performs no planning, extraction, staging,
install-root access, or live-tree mutation.

The API-9-compatible implementation uses `RandomAccessFile` to find a legal
terminal EOCD, including comments, and validates classic or ZIP64 single-disk
central-directory offsets and bounds. It rejects unsigned ZIP64 values that
cannot fit a Java `long`.

Central records are walked using their variable name, extra, and comment
lengths. Exactly 10,000 entries are accepted. A declared larger count returns
a 10,001 sentinel without walking attacker-sized metadata, allowing the next
layer to reject before opening the ZIP.
