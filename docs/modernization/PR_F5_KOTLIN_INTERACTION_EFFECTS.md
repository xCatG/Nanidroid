# PR F5: Kotlin interaction effects

## Purpose

`SakuraScriptInteractionInterpreter` extracts the non-presentation interaction
facts that the legacy runner currently sends straight to `UICallback`:

* `\q[...]` options become a single ordered `ShowSelection` effect and their
  labels remain in the presentation script;
* `\![open,inputbox,...]` becomes an `OpenInputBox` effect and is consumed
  from the presentation script.

The result is immutable and does not contain Android views or callbacks.

## Migration boundary

The Java runner remains authoritative while choice/input effects, waits,
Shiori callbacks, and lifecycle scheduling are being brought together in a
single Kotlin runtime output. This PR is intentionally not wired into the
running app: using only the interaction extractor would not preserve the full
script control flow.

## Validation

Focused JVM tests cover choice ordering, rewritten label text, exact input ids,
and scripts without interaction commands. Repository contracts pass; hosted CI
will validate native compatibility and API-37 APK/AAB artifacts.
