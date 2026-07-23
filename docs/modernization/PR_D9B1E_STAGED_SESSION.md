# PR D9b1e — retained staged NAR authority

D9b1e adds package-private extraction authority without changing D's public
diagnostic API. A private-constructor `NarStagedSource` is atomically claimable
once and has no production factory in this slice.

Successful staged validation or verification retains the exact open ZIP and
owner-bound central-entry objects. The package-only session opens only the
exact installable, non-directory plan entry at its original ordinal.

Session close is idempotent, makes the session unusable, closes the ZIP before
deleting staging, and reports cleanup failure. Every failed staged operation
closes any opened ZIP and deletes the claimed file while preserving the
primary typed failure.

This slice creates no staged copy, writes no archive or target bytes, and
contains no extraction loop or manager integration. D9b2 alone may add a
create-new app-private copy and mint the capability only after its writer has
closed.
