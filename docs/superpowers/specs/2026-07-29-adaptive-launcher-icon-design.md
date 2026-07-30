# Adaptive launcher icon design

## Goal

Modernize Nanidroid's launcher icon support without redesigning the existing
character-and-Android artwork.

## Approach

- Retain every existing density- and locale-qualified `ic_launcher.png`
  resource, including Japanese locale assets, as resource/localization
  compatibility artifacts rather than a pre-Android-8 runtime fallback.
- Add adaptive-icon resources under `mipmap-anydpi-v26`.
- Use a dedicated foreground bitmap rendition of the existing icon, inset into
  Android's adaptive-icon safe zone so the full Nanidroid label and artwork are
  visible when launchers apply circular or squircle masks.
- Supply a matching green background layer.
- Point `application.android:icon` at the new `mipmap` resource. The app's
  runtime baseline is API 31+, where resource qualifiers resolve the adaptive
  icon; the retained legacy PNGs remain packaged compatibility artifacts.

## Scope and compatibility

The icon art itself is not redesigned. The adaptive version may appear slightly
smaller because of the required safe inset; this is intentional to avoid mask
clipping. No application code or screen UI changes are included.

## Verification

- Inspect resource resolution and manifest references.
- Build the debug APK with the project-supported Gradle command.
- Confirm that the retained density- and locale-qualified PNG compatibility
  artifacts, including Japanese assets, remain present and the adaptive XML is
  packaged for the API 31+ runtime baseline.
