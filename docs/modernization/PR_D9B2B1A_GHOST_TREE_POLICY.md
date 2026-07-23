# PR D9b2b1a — immutable ghost-tree baseline policy

D9b2b1a is a pure policy slice. It performs no filesystem access, copying,
staging, cleanup, overlay, publication, manager integration, or UI work.

An immutable manifest binds a validated target id, opaque storage-root identity
bytes, and an explicit `ABSENT` or `PRESENT` state. `PRESENT` with no entries is
distinct from `ABSENT`. Present entries explicitly preserve files and
directories, including empty directories. Hard links are intentionally
flattened: the later walker will copy and hash every relative file path
independently.

Archive entries and ghost-tree entries now share the same NFC normalization,
Locale.US case-collision key, Unicode/path safety, depth-32, component-255-byte,
and path-1024-byte policy. Tree manifests additionally enforce 10,000 total
nodes, 128 MiB per file, and 512 MiB total file content.

Fingerprint version 1 is SHA-256 over a fixed domain, version, target id,
storage-root identity, state, and the sorted manifest. Every variable field is
length-prefixed; entry types and file lengths are explicit. File content is
represented by a defensive copy of its SHA-256 digest. Modification times are
never included.

The model and its construction seam are package-private. A later filesystem
slice must create these inputs from app-private staging under exclusive
ownership and D9b3 must revalidate the same fingerprint immediately before
publication under the install lock.
