# Firebase Crashlytics setup

Nanidroid now links the Firebase Crashlytics SDK and calls the `CrashReporting` boundary at
application startup. The repository intentionally contains no Firebase credentials.

To activate reporting for an owned Firebase project:

1. Register `com.cattailsw.nanidroid` in that Firebase project.
2. Download the project-specific `google-services.json` and place it at the repository root.
3. Add and apply the Google services Gradle plugin, then build a release artifact.
4. Force a test crash only in a non-production build and verify it in the Firebase console.

Without a configuration resource, startup remains safe and Crashlytics is disabled. The legacy
ACRA Google Spreadsheet form key has been removed and must not be reintroduced.
