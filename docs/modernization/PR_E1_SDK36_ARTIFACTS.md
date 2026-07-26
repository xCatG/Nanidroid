# PR E1: API 36 compile substrate and distributable artifacts

## Scope

This PR moves only the compiler API surface to Android API 36. It deliberately
preserves `minSdk 9` and `targetSdk 13`; runtime compatibility behavior,
storage, networking, manifest and service changes belong to the subsequent
target-SDK compatibility PR.

The removed Apache HTTP APIs are available through the explicit
`org.apache.http.legacy` compile bridge. This is transitional build debt, not a
network-security endorsement: the bridge must be removed when callers migrate
to supported HTTPS APIs.

## Reproducible build boundary

The devcontainer installs `platforms;android-36`, plus an API-15 **test-only**
facade for historical `android.test.*` characterization sources, and Build Tools 36.0.0. It
also provisions bundletool 1.18.2 using a SHA-256 pinned in the Dockerfile.
The Gradle build emits both the debug APK and debug AAB, verifies/signs the APK,
uses bundletool to validate the AAB, and writes `artifact-integrity.json`.

That JSON manifest is intentionally limited to each artifact's filename, byte
length and SHA-256. It is deterministic for a fixed pair of bytes and gives a
reviewer or CI consumer an unambiguous integrity record without claiming that
Android packages themselves are reproducible byte-for-byte.

## Required checks

1. `python3 -m unittest discover -s tools -p 'test_*.py'`
2. Legacy APK build, then devcontainer `./docker/gradle/build.sh`
3. `bundletool validate --bundle` via the pinned jar
4. Inspect the uploaded APK, AAB and `artifact-integrity.json` together.

The frozen Ant artifact remains the behavioral reference during this build-only
step. The existing APK parity report continues to compare stable app metadata
and packaged native payload; the AAB is separately syntax-validated because it
is a different package format.

The legacy JVM characterizations compile in this PR but are not executed by the
artifact task: they depend on removed `android.test` local-JVM stubs and native
runtime behavior. Their execution moves to the API-36 emulator lane in the
target-SDK validation PR, where they can observe real Android APIs. The Python
contract tests, including artifact metadata tests added here, remain executed
on every CI run.
