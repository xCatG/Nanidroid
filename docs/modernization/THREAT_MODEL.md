# Modernization threat model

## Assets

- Installed ghost, shell, and balloon files
- User preferences and selected ghost state
- Application-private storage
- Native process integrity
- Network and device identifiers
- Signing and upgrade identity

## Untrusted inputs

- NAR/ZIP archives from local files, content URIs, HTTP, or HTTPS
- Ghost descriptor, surface, dictionary, and script files
- SHIORI responses and Sakura Script commands
- External intents and URI grants
- Existing files migrated from shared external storage
- Native engine input bytes

## Priority threats

1. Archive path traversal through `..`, absolute paths, backslashes, drive
   prefixes, symlinks, or canonical-path escape.
2. Resource exhaustion through entry counts, declared sizes, compression
   ratios, deeply nested paths, or malformed encodings.
3. Partial or colliding installations becoming selectable after failure.
4. Cleartext or redirected network imports delivering modified content.
5. Over-broad URI permissions or exported Android components.
6. Memory corruption, double unload, use-after-unload, or global native-state
   collisions in Kawari and Satori.
7. Leakage of identifiers, crash data, scripts, or filenames through legacy
   analytics and crash reporting.
8. Data loss during shared-storage to app-private-storage migration.

## Security testing rules

- Security defects are never promoted from “observed” to “required” behavior.
- Archive tests use an injected temporary destination.
- Fuzz/property tests use bounded inputs and record failing seeds.
- Installation is specified as atomic: failure cannot expose a partial ghost.
- Upgrade, interruption, corruption, and downgrade behavior require fixtures.
- Native tests include malformed and non-ASCII bytes plus repeated
  load/request/unload cycles.

This document identifies risks; later PRs must add failing specifications before
changing the affected behavior.
