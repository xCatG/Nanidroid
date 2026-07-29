# Exported component, intent, URI, and network audit

Date: 2026-07-28

## Scope and method

Reviewed the production `AndroidManifest.xml` and active Kotlin sources under
`src/` for component exposure, cold/warm inbound intents, nested-intent
forwarding, URI grants, `PendingIntent` mutability, and cleartext/network
configuration.  Test-only manifests and the frozen `legacy/` reference tree
were not treated as production entry points.

## Current alignment

| Area | Evidence | Assessment |
| --- | --- | --- |
| Exported components | `Nanidroid` is explicitly exported only for `MAIN/LAUNCHER` and `ACTION_VIEW`; `NanidroidService` and `Preferences` are explicitly non-exported.  There are no production receivers or providers. | Aligned. The public activity is required for launch/deep links; private work stays in the service. |
| Inbound archive intent | The manifest only declares `https` `VIEW` filters for `.nar`/`.zip`. `IncomingNarIntent.isApprovedDownload` requires `ACTION_VIEW`, HTTPS, non-empty host, and a `.nar`/`.zip` path. `Nanidroid.handleIncomingIntent` uses that validator after cold initialization and from `onNewIntent`, which calls `setIntent` first. | Aligned. No caller-controlled `file:` path is converted to a private file. |
| Intent forwarding | No active source reads an `Intent` extra and forwards it with `startActivity`, `startService`, or `sendBroadcast`. Internal activity/service launches are explicit class intents. | No nested-intent redirection found. |
| URI grants/content access | No production provider, `grantUriPermissions`, `FLAG_GRANT_*`, `ContentResolver.openInputStream`, or persisted URI grant use was found. The current install surface intentionally accepts remote HTTPS only; SAF/content-URI import remains a separately specified future feature. | No URI-grant exposure in the current product surface. |
| PendingIntent | Both notification content intents target `Nanidroid` explicitly and combine `FLAG_UPDATE_CURRENT` with the compatibility `FLAG_IMMUTABLE` constant on API 23+. | Aligned. No mutable `PendingIntent` found. |
| Network transport | `NetworkUtil` uses `HttpsURLConnection`, rejects non-HTTPS URLs, retains platform TLS verification, and disables automatic redirects. The manifest has no HTTP deep-link filter or cleartext opt-in. | Import/update transport is HTTPS-only. |

## Findings and follow-up decisions

1. **Medium — legacy telemetry and browser links can still use HTTP outside the
   archive-import boundary.** Some help/feedback string resources and legacy
   analytics/ACRA dependencies contain HTTP endpoints.  Adding
   `usesCleartextTraffic="false"` now is not proven behavior-preserving because
   the retained legacy telemetry libraries may still depend on cleartext.
   Decision: defer a manifest-wide cleartext denial until telemetry/privacy
   disposition is decided; do not claim archive transport is affected, because
   active archive/update code already rejects HTTP.

2. **Medium — archive host policy is intentionally broad.** HTTPS plus a
   `.nar`/`.zip` path permits any HTTPS host. The installer validation pipeline
   remains responsible for archive safety. Pinning or allowlisting hosts would
   change the established third-party ghost distribution model, so no source
   change is made without product direction.

3. **Low — external browser launches are implicit.** Help, feedback, and
   documentation actions intentionally use `ACTION_VIEW`; they do not forward a
   caller-supplied nested intent or grant URI access. Their HTTP URLs belong to
   the cleartext/browser-link decision above.

4. **Required before adding SAF import — specify grants first.** A future
   `content:` implementation must constrain accepted authorities/types as
   appropriate, stream from the granted descriptor, avoid forwarding the
   original intent, and release any persistable grant deterministically. Do not
   reintroduce shared-storage or `file:` handoff.

## Verification

The source-level security contract in
`tools/test_target36_security_contract.py` covers the component exposure,
HTTPS-only deep-link validation on cold and warm delivery, immutable pending
intents, and the HTTPS network stack.  This audit introduces documentation only
and does not alter product behavior.
