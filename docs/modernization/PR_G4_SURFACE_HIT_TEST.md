# PR G4: Platform-neutral surface hit test

Collision selection now operates on `SurfaceDefinition` rather than an
Android-`Rect` cache inside `SakuraView`. The Kotlin function preserves the
legacy `Rect.contains` contract: left/top are included; right/bottom are not.

`SakuraView` still dispatches the interaction, but Compose can use the exact
same pure hit-test next when ownership of pointer input moves across the
renderer boundary.
