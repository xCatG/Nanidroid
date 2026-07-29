# PR F9: Kotlin surface catalog boundary

`SurfaceManager` is now the Kotlin owner of the installed shell's surface
catalog.  It retains the existing Java-facing API while making three concerns
explicit:

1. the catalog resolves exact and speaker-default (`0` / `10`) surface ids;
2. `SurfaceReader` remains the legacy parser that publishes parsed surfaces;
3. `ShellSurface` remains the Android drawable compositor until the Compose
   renderer can consume a platform-neutral surface-definition model.

This is intentionally not a `SakuraView` port.  The next slices can extract
parser output and frame selection from `ShellSurface`, then have Compose render
those immutable states.  The old Java file is retained under `legacy/src` only
for the frozen Ant reference build; Gradle compiles the Kotlin production source.
