# PR G2: Platform-neutral surface definition

This slice exposes the parser's existing `ShellSurface` semantics as immutable
Kotlin data: surface geometry, collision areas, animations, frame offsets, and
element layers. The mapper deliberately performs no bitmap decode and creates
no Android `Drawable`.

The legacy compositor continues to render the shell while a later Compose
renderer consumes `SurfaceDefinition`. This creates a verifiable handoff from
the legacy parser/catalog to a modern presentation layer without changing
existing ghost behavior during the migration.
