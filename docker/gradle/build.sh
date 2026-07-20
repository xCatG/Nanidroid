#!/usr/bin/env bash
set -euo pipefail

readonly SOURCE_ROOT="${SOURCE_ROOT:-/workspaces/Nanidroid}"
readonly OUTPUT_ROOT="${OUTPUT_ROOT:-${SOURCE_ROOT}/artifacts/gradle}"
readonly APK="${SOURCE_ROOT}/build/outputs/apk/debug/Nanidroid-debug.apk"
readonly TEST_RESULTS_ROOT="${SOURCE_ROOT}/build/test-results/testDebugUnitTest"
readonly TEST_ARTIFACT_ROOT="${OUTPUT_ROOT}/test-results"
readonly REFERENCE_REPORT="${SOURCE_ROOT}/artifacts/legacy/Nanidroid-debug.json"
readonly AAPT="${ANDROID_SDK_ROOT}/build-tools/36.0.0/aapt"
readonly APKSIGNER="${ANDROID_SDK_ROOT}/build-tools/36.0.0/apksigner"
readonly ZIPALIGN="${ANDROID_SDK_ROOT}/build-tools/36.0.0/zipalign"

cd "${SOURCE_ROOT}"

if [[ ! -f "${REFERENCE_REPORT}" ]]; then
  echo "missing legacy reference report: ${REFERENCE_REPORT}" >&2
  echo "run the docker/legacy build before the Gradle build" >&2
  exit 2
fi

./gradlew --no-daemon testDebugUnitTest assembleDebug

if [[ ! -f "${APK}" ]]; then
  echo "Gradle completed without producing the expected APK: ${APK}" >&2
  exit 1
fi

if [[ ! -d "${TEST_RESULTS_ROOT}" ]]; then
  echo "Gradle completed without producing the expected JUnit directory: ${TEST_RESULTS_ROOT}" >&2
  exit 1
fi

mapfile -t test_result_files < <(
  find "${TEST_RESULTS_ROOT}" -maxdepth 1 -type f -name 'TEST-*.xml' -print | sort
)
if (( ${#test_result_files[@]} == 0 )); then
  echo "Gradle completed without producing JUnit XML in: ${TEST_RESULTS_ROOT}" >&2
  exit 1
fi

mkdir -p "${OUTPUT_ROOT}"
mkdir -p "${TEST_ARTIFACT_ROOT}"
cp "${test_result_files[@]}" "${TEST_ARTIFACT_ROOT}/"
"${APKSIGNER}" verify "${APK}"
"${ZIPALIGN}" -c 4 "${APK}"

python3 tools/inspect_legacy_apk.py \
  "${APK}" \
  --aapt "${AAPT}" \
  --output "${OUTPUT_ROOT}/Nanidroid-debug.json"

python3 tools/compare_apk_contracts.py \
  "${REFERENCE_REPORT}" \
  "${OUTPUT_ROOT}/Nanidroid-debug.json" \
  --output "${OUTPUT_ROOT}/parity.json"

cp "${APK}" "${OUTPUT_ROOT}/Nanidroid-debug.apk"

echo "Gradle APK matches the frozen legacy artifact contract:"
cat "${OUTPUT_ROOT}/parity.json"
