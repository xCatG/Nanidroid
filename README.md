# Nanidroid

An Android recreation of Nanidroid for Android 12 and newer, using Kotlin and
Compose. The application ID remains `com.cattailsw.nanidroid`. Current functionality
includes the bundled Nanidroid ghost, document-picker NAR import, ghost selection,
retained Satori/Kawari/YAYA engines, supported SakuraScript dialogue and
interactions, surfaces/animations, and offline About/license notices.

The bundled archive owns the characters' dialogue, tap mapping and menu choices,
while retaining the original artwork. Tap 郭嘉榆 for an introduction and help with importing,
switching, touch and dialogue controls. Tap ドロイド君 for Ghosts, About and licenses,
and app tools. The first boot explains both roles; later boots give a short
reminder. Dialogue uses English for `en`, Traditional Chinese for Chinese Hant
or TW/HK/MO locales, and Japanese otherwise; explicit Hans uses Japanese.
The dialogue locale is selected at application process startup. Device language
changes apply to dialogue after restarting the app process. Android dialog labels
use normal resource selection. Imported ghosts keep their own interactions.

Existing built-in installs refresh only recognized previous shipped dictionaries
and add missing English, Japanese and Traditional Chinese tables. Customized
dictionaries, activation markers, saved state and unknown files are preserved.
Customized old tables may lack newer interactions; the installed content remains
authoritative. Ghosts and About SakuraScript commands are available to any active
ghost; developer commands require the verified default ghost and debug policy.
Host dialog requests are delivered once, in script order, when the stage is active
and its current modal has closed. Replacing the dialogue or changing the ghost
invalidates pending requests from the old dialogue.

Debug builds also offer a small Debug tools dialog with the existing Show touch
bounds toggle and Close button. Both entry and execution require the installed
application's debuggable flag. Event injection, arbitrary script execution,
request logs and native diagnostics are outside this initial panel.

The [approved baseline](docs/superpowers/specs/2026-09-22-nanidroid-baseline-spec.md)
and [design](docs/superpowers/specs/2026-09-22-blind-recreation-design.md) record the
replacement's supported subset. The replacement phase is complete; this checkout
is the authoritative implementation for ongoing development, including requested
new features, native changes and build changes. Historical plans, exclusions and
manifests are context rather than permanent development gates.
M5 was [accepted with exceptions](docs/testing/2026-10-01-m5-focused-acceptance-packet.md).
External balloon skins, Markdown, network updates and broad native compatibility
were outside that acceptance scope. LOBO Pixel setting-cycle coverage and intermittent
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
serials for focused flows; do not treat fixture skips as passes. For each change,
run relevant validation and record current evidence separately from historical results.
The [default ghost design](docs/superpowers/specs/2026-10-11-built-in-shiori-cleanup-design.md) and [acceptance record](docs/testing/2026-10-11-built-in-shiori-cleanup.md) describe archive-owned dialogue, host commands, verification and device evidence. Device language changes still require a process restart
to reload built-in dialogue; this cleanup does not add live dialogue translation.

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

## Development history and distribution

The [integration manifest](docs/review/2026-10-02-integration-manifest.md) records
the frozen recreation history, target base, explicit path dispositions and checks.
Historical evidence retains its source/APK/device provenance; ignored raw logs
and private fixtures are not included and are not current passing results.

The original Google Play signing key has been recovered. The published
Play listing was at versionCode 6 / versionName 0.2.2; this rewrite starts at
versionCode 7 / versionName 0.3.0, and every later upload must raise versionCode.
A differently signed build cannot update an old installed copy. A fresh installation may require
uninstalling that copy, which can remove app-owned data; leaving old external
files untouched is not an uninstall-preservation guarantee. Play App Signing,
upload-key and release CI setup are the author's separate release work.

## Attribution and licenses

Nanidroid and its bundled ghost are attributed to CatTail Software LLC; the
original project is [xCatG/Nanidroid](https://github.com/xCatG/Nanidroid).
The replacement integration retained the target `LICENSE.txt` verbatim, all 297
pinned native files and their embedded notices, the bundled artwork and readme,
and 19 offline notice assets. [Notice inventory](docs/testing/notice-inventory.md) records their
provenance. This inventory is not a legal-compliance determination.
