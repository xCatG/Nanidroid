# Adaptive launcher icon design

## Goal

Modernize Nanidroid's launcher icon support without redesigning the existing
character-and-Android artwork.

## Approach

- Retain every existing density-specific `ic_launcher.png` resource as the
  fallback for pre-Android 8.0 devices.
- Add API 26+ adaptive-icon resources under `mipmap-anydpi-v26`.
- Use a dedicated foreground bitmap rendition of the existing icon, inset into
  Android's adaptive-icon safe zone so the full Nanidroid label and artwork are
  visible when launchers apply circular or squircle masks.
- Supply a matching green background layer.
- Point `application.android:icon` at the new `mipmap` resource. Android's
  resource qualifiers choose the adaptive icon on API 26+ and the legacy PNG
  fallback elsewhere.

## Scope and compatibility

The icon art itself is not redesigned. The adaptive version may appear slightly
smaller because of the required safe inset; this is intentional to avoid mask
clipping. No application code or screen UI changes are included.

## Verification

- Inspect resource resolution and manifest references.
- Build the debug APK with the project-supported Gradle command.
- Confirm that the legacy PNG fallback remains present and the API 26 adaptive
  XML is packaged.
