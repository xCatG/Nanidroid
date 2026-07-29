# PR G1: Kotlin surface reader

`SurfaceReader` is migrated to Kotlin while remaining a narrow compatibility
parser: it reads Shift_JIS `surfaces.txt` input, discovers unlisted PNG
surfaces, and publishes `ShellSurface` instances into the catalog.

This keeps parsing separate from catalog selection and Android drawable
composition. The next renderer work can replace `ShellSurface` composition and
`SakuraView` with Compose without changing the shell-input boundary.
