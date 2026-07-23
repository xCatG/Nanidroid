# PR D9b2b1b — portable no-follow inspection core
D9b2b1b adds only the portable C99 `narfs_core` source contract. Static build
declarations, ABI/link gates, and build parity are deferred to D9b2b1c.
The core opens one trusted root and one validated target child with
`O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC`; only target `ENOENT` means absent. The
iterative fd-bound walk sorts raw UTF-8 bytes and uses a 10,001 sentinel.
Pre-open, opened, and post-open device/inode/type must match.
The visitor borrows each fd only during its callback. Symlinks, special nodes,
malformed UTF-8, unsafe names, cycles, identity swaps, syscall failures, and
all limits are typed failures. Every fd/DIR is closed once; close diagnostics
do not replace the first failure.

Pinned directory fds prevent escape, but inspection is not linearizable against
a malicious same-UID writer. A later copy slice must reopen under its lease;
D9b3 must revalidate before publication.

This PR has no JNI/Java, DSO/APK payload, hashing, copying, staging, archive,
cleanup/publication, manager, UI, or emulator integration.
