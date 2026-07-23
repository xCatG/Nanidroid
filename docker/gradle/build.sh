#!/usr/bin/env bash
set -euo pipefail

readonly SOURCE_ROOT="${SOURCE_ROOT:-/workspaces/Nanidroid}"
readonly OUTPUT_ROOT="${OUTPUT_ROOT:-${SOURCE_ROOT}/artifacts/gradle}"
readonly APK="${SOURCE_ROOT}/build/outputs/apk/debug/Nanidroid-debug.apk"
readonly TEST_APK="${SOURCE_ROOT}/build/outputs/apk/androidTest/debug/Nanidroid-debug-androidTest.apk"
readonly TEST_RESULTS_ROOT="${SOURCE_ROOT}/build/test-results/testDebugUnitTest"
readonly TEST_ARTIFACT_ROOT="${OUTPUT_ROOT}/test-results"
readonly PROJECT_CACHE_ROOT="${PROJECT_CACHE_ROOT:-${GRADLE_USER_HOME:-/tmp/nanidroid-gradle}/project-cache}"
readonly REFERENCE_REPORT="${SOURCE_ROOT}/artifacts/legacy/Nanidroid-debug.json"
readonly CMAKE_NATIVE_ROOT="${SOURCE_ROOT}/artifacts/legacy/native-cmake"
readonly BUILD_TOOLS_ROOT="${ANDROID_SDK_ROOT}/build-tools/${ANDROID_BUILD_TOOLS_VERSION:?ANDROID_BUILD_TOOLS_VERSION is required}"
readonly AAPT="${BUILD_TOOLS_ROOT}/aapt"
readonly APKSIGNER="${BUILD_TOOLS_ROOT}/apksigner"
readonly ZIPALIGN="${BUILD_TOOLS_ROOT}/zipalign"

cd "${SOURCE_ROOT}"

rm -f "${TEST_APK}" \
  "${OUTPUT_ROOT}/Nanidroid-debug-androidTest.apk" \
  "${OUTPUT_ROOT}/Nanidroid-debug-androidTest.json"

if [[ ! -f "${REFERENCE_REPORT}" ]]; then
  echo "missing legacy reference report: ${REFERENCE_REPORT}" >&2
  echo "run the docker/legacy build before the Gradle build" >&2
  exit 2
fi

rm -rf "${TEST_RESULTS_ROOT}" "${TEST_ARTIFACT_ROOT}"
mkdir -p "${TEST_ARTIFACT_ROOT}" "${PROJECT_CACHE_ROOT}"

set +e
./gradlew --no-daemon --project-cache-dir "${PROJECT_CACHE_ROOT}" \
  testDebugUnitTest assembleDebug assembleDebugAndroidTest
gradle_status=$?
set -e

test_result_files=()
if [[ -d "${TEST_RESULTS_ROOT}" ]]; then
  mapfile -t test_result_files < <(
    find "${TEST_RESULTS_ROOT}" -maxdepth 1 -type f -name 'TEST-*.xml' -print | sort
  )
fi
if (( ${#test_result_files[@]} > 0 )); then
  cp "${test_result_files[@]}" "${TEST_ARTIFACT_ROOT}/"
fi

if (( gradle_status != 0 )); then
  echo "Gradle failed with status ${gradle_status}; copied available JUnit XML to ${TEST_ARTIFACT_ROOT}" >&2
  exit "${gradle_status}"
fi

if (( ${#test_result_files[@]} == 0 )); then
  echo "Gradle completed without producing JUnit XML in: ${TEST_RESULTS_ROOT}" >&2
  exit 1
fi

if [[ ! -f "${APK}" ]]; then
  echo "Gradle completed without producing the expected APK: ${APK}" >&2
  exit 1
fi
if [[ ! -f "${TEST_APK}" ]]; then
  echo "Gradle completed without producing the expected test APK: ${TEST_APK}" >&2
  exit 1
fi

mkdir -p "${OUTPUT_ROOT}"
"${APKSIGNER}" verify "${APK}"
"${ZIPALIGN}" -c 4 "${APK}"
"${APKSIGNER}" verify "${TEST_APK}"
"${ZIPALIGN}" -c 4 "${TEST_APK}"

python3 tools/inspect_legacy_apk.py \
  "${APK}" \
  --aapt "${AAPT}" \
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
  --output "${OUTPUT_ROOT}/Nanidroid-debug-androidTest.json"

cp "${APK}" "${OUTPUT_ROOT}/Nanidroid-debug.apk"
cp "${TEST_APK}" "${OUTPUT_ROOT}/Nanidroid-debug-androidTest.apk"

echo "Gradle APK matches the frozen legacy artifact contract:"
cat "${OUTPUT_ROOT}/parity.json"
