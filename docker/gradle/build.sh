#!/usr/bin/env bash
set -euo pipefail

readonly SOURCE_ROOT="${SOURCE_ROOT:-/workspaces/Nanidroid}"
readonly OUTPUT_ROOT="${OUTPUT_ROOT:-${SOURCE_ROOT}/artifacts/gradle}"
readonly APK="${SOURCE_ROOT}/build/outputs/apk/debug/Nanidroid-debug.apk"
readonly AAB="${SOURCE_ROOT}/build/outputs/bundle/debug/Nanidroid-debug.aab"
readonly TEST_APK="${SOURCE_ROOT}/build/outputs/apk/androidTest/emulator/Nanidroid-emulator-androidTest.apk"
readonly PROJECT_CACHE_ROOT="${PROJECT_CACHE_ROOT:-${GRADLE_USER_HOME:-/tmp/nanidroid-gradle}/project-cache}"
readonly REFERENCE_REPORT="${SOURCE_ROOT}/artifacts/legacy/Nanidroid-debug.json"
readonly CMAKE_NATIVE_ROOT="${SOURCE_ROOT}/artifacts/legacy/native-cmake"
readonly BUILD_TOOLS_ROOT="${ANDROID_SDK_ROOT}/build-tools/${ANDROID_BUILD_TOOLS_VERSION:?ANDROID_BUILD_TOOLS_VERSION is required}"
readonly AAPT="${BUILD_TOOLS_ROOT}/aapt"
readonly APKSIGNER="${BUILD_TOOLS_ROOT}/apksigner"
readonly ZIPALIGN="${BUILD_TOOLS_ROOT}/zipalign"
readonly BUNDLETOOL_JAR="${BUNDLETOOL_JAR:-/opt/bundletool/bundletool-all-1.18.2.jar}"

cd "${SOURCE_ROOT}"

rm -f "${TEST_APK}" \
  "${OUTPUT_ROOT}/Nanidroid-emulator-androidTest.apk" \
  "${OUTPUT_ROOT}/Nanidroid-emulator-androidTest.json" \
  "${OUTPUT_ROOT}/Nanidroid-debug.apk" \
  "${OUTPUT_ROOT}/Nanidroid-debug.aab" \
  "${OUTPUT_ROOT}/artifact-integrity.json"

if [[ ! -f "${REFERENCE_REPORT}" ]]; then
  echo "missing legacy reference report: ${REFERENCE_REPORT}" >&2
  echo "run the docker/legacy build before the Gradle build" >&2
  exit 2
fi

mkdir -p "${PROJECT_CACHE_ROOT}"

set +e
./gradlew --no-daemon --project-cache-dir "${PROJECT_CACHE_ROOT}" \
  compileEmulatorUnitTestJavaWithJavac assembleDebug bundleDebug assembleEmulatorAndroidTest
gradle_status=$?
set -e

if (( gradle_status != 0 )); then
  echo "Gradle failed with status ${gradle_status}" >&2
  exit "${gradle_status}"
fi

if [[ ! -f "${APK}" ]]; then
  echo "Gradle completed without producing the expected APK: ${APK}" >&2
  exit 1
fi
if [[ ! -f "${AAB}" ]]; then
  echo "Gradle completed without producing the expected app bundle: ${AAB}" >&2
  exit 1
fi
if [[ ! -f "${TEST_APK}" ]]; then
  echo "Gradle completed without producing the expected test APK: ${TEST_APK}" >&2
  exit 1
fi
if [[ ! -f "${BUNDLETOOL_JAR}" ]]; then
  echo "missing pinned bundletool: ${BUNDLETOOL_JAR}" >&2
  exit 1
fi

mkdir -p "${OUTPUT_ROOT}"
"${APKSIGNER}" verify "${APK}"
"${ZIPALIGN}" -c 4 "${APK}"
java -jar "${BUNDLETOOL_JAR}" validate --bundle="${AAB}"
"${APKSIGNER}" verify "${TEST_APK}"
"${ZIPALIGN}" -c 4 "${TEST_APK}"

python3 tools/inspect_legacy_apk.py \
  "${APK}" \
  --aapt "${AAPT}" \
  --expected-target-sdk 36 \
  --output "${OUTPUT_ROOT}/Nanidroid-debug.json"

python3 tools/compare_apk_contracts.py \
  "${REFERENCE_REPORT}" \
  "${OUTPUT_ROOT}/Nanidroid-debug.json" \
  --output "${OUTPUT_ROOT}/parity.json"

python3 tools/verify_apk_native_payload.py \
  "${APK}" \
  --candidate-root "${CMAKE_NATIVE_ROOT}" \
  --output "${OUTPUT_ROOT}/native-payload.json"

python3 tools/inspect_android_test_apk.py \
  "${TEST_APK}" \
  --aapt "${AAPT}" \
  --expected-target-sdk 36 \
  --output "${OUTPUT_ROOT}/Nanidroid-emulator-androidTest.json"

cp "${APK}" "${OUTPUT_ROOT}/Nanidroid-debug.apk"
cp "${AAB}" "${OUTPUT_ROOT}/Nanidroid-debug.aab"
cp "${TEST_APK}" "${OUTPUT_ROOT}/Nanidroid-emulator-androidTest.apk"
python3 tools/write_artifact_metadata.py \
  "${OUTPUT_ROOT}/Nanidroid-debug.apk" \
  "${OUTPUT_ROOT}/Nanidroid-debug.aab" \
  --output "${OUTPUT_ROOT}/artifact-integrity.json"

echo "Gradle APK matches the frozen legacy artifact contract:"
cat "${OUTPUT_ROOT}/parity.json"
