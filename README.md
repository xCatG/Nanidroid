# Nanidroid

An Android recreation of Nanidroid for Android 12 and newer, using Kotlin and
Compose. The application ID remains `com.cattailsw.nanidroid`. The accepted subset
includes the bundled Nanidroid ghost, document-picker NAR import, ghost selection,
retained Satori/Kawari/YAYA engines, supported SakuraScript dialogue and
interactions, surfaces/animations, and offline About/license notices.

The [approved baseline](docs/superpowers/specs/2026-09-22-nanidroid-baseline-spec.md)
and [design](docs/superpowers/specs/2026-09-22-blind-recreation-design.md) define the
supported subset. M5 was [accepted with exceptions](docs/testing/2026-10-01-m5-focused-acceptance-packet.md).
External balloon skins, Markdown, network updates and broad native compatibility
are outside this candidate. LOBO Pixel setting-cycle coverage and intermittent
Compose wrong-thread/keyboard failures remain accepted unresolved risks.

## Build and test

Use JDK 17, Android SDK platform 37, NDK 28.2.13676358 and CMake 3.22.1.
Configure the SDK through your local environment or an untracked `local.properties`.
The committed wrapper pins Gradle 9.3.1 and its distribution checksum.

```sh
bash ./gradlew testDebugUnitTest lint assembleDebug assembleDebugAndroidTest assembleRelease
```

On Windows use `./gradlew.bat`. APKs are under `app/build/outputs/apk/`;
reports are under `app/build/reports/`. Release output is unsigned and is not a
release-readiness claim. [Testing guidance](docs/testing.md) identifies fixture
requirements and historical results. Use disposable emulators with explicit
serials for focused flows; do not treat fixture skips as passes. Current candidate
build/device verification belongs to the replacement plan's Task 4.

## Data and accepted design decisions

Imports are fresh installs through the system document picker. The app validates
bounded ZIP/NAR contents, stages privately, refuses existing destinations, and
publishes without merging or overwriting installed ghosts. App-private ghost
directories include native state; completed writes survive orderly close.
Automatic legacy discovery, settings/save conversion and migration are absent.
Old external files are left untouched and are not imported. Reinstalling a NAR
does not recover its saved state. This policy follows the author's 2026-10-03
decision in the [replacement plan](docs/superpowers/plans/2026-10-02-replacement-integration.md).

The approved built-in balloons use original ivory panels, brown outlines and
speaker-pointing tails, retaining existing scrolling, choices, links and
accessibility. Artwork uses the approved logical 320 dpi reference: shorter-window
buckets below 400 dp / below 600 dp / 600 dp and above target 1 / 1.5 / 2; convert
once by current density / 2, then cap by combined width and font-aware available
height. The baseline records this exception to the historical no-upscale rule.
These decisions are restated here so untracked exploratory designs, previews and
measurement artifacts are not active prerequisites.

## Integration and distribution

The [integration manifest](docs/review/2026-10-02-integration-manifest.md) records
the frozen recreation history, target base, explicit path dispositions and checks.
Historical evidence retains its source/APK/device provenance; ignored raw logs
and private fixtures are not included and are not current passing results.

Version code 1/name 1.0, published version history, signing identity, Play enrollment
and store eligibility remain unverified release prerequisites. A differently signed
build cannot update an old installed copy. A fresh installation may require
uninstalling that copy, which can remove app-owned data; leaving old external
files untouched is not an uninstall-preservation guarantee. Recovering the original
signing identity or a valid Play App Signing/upload-key route is the author's
separate release work. No push, merge or release is implied by this local candidate.

## Attribution and licenses

Nanidroid and its bundled ghost are attributed to CatTail Software LLC; the
original project is [xCatG/Nanidroid](https://github.com/xCatG/Nanidroid).
The integration retains the target `LICENSE.txt` verbatim, all 297 pinned native
files and their embedded notices, the unchanged bundled archive, and 19 offline
notice assets. [Notice inventory](docs/testing/notice-inventory.md) records their
provenance. This inventory is not a legal-compliance determination.
