#!/usr/bin/env bash
set -euo pipefail

readonly SOURCE_ROOT="${SOURCE_ROOT:-/workspaces/Nanidroid}"
readonly OUTPUT_ROOT="${OUTPUT_ROOT:-${SOURCE_ROOT}/artifacts/emulator/apk}"
readonly APK="${SOURCE_ROOT}/build/outputs/apk/emulator/Nanidroid-emulator.apk"
readonly LEGACY_NATIVE_ROOT="${SOURCE_ROOT}/artifacts/legacy/native"
readonly ARM64_NATIVE_ROOT="${SOURCE_ROOT}/artifacts/emulator/native"
readonly PROJECT_CACHE_ROOT="${PROJECT_CACHE_ROOT:-${GRADLE_USER_HOME:-/tmp/nanidroid-gradle}/emulator-project-cache}"
readonly BUILD_TOOLS_ROOT="${ANDROID_SDK_ROOT}/build-tools/${ANDROID_BUILD_TOOLS_VERSION:?ANDROID_BUILD_TOOLS_VERSION is required}"
readonly AAPT="${BUILD_TOOLS_ROOT}/aapt"
readonly APKSIGNER="${BUILD_TOOLS_ROOT}/apksigner"
readonly ZIPALIGN="${BUILD_TOOLS_ROOT}/zipalign"

cd "${SOURCE_ROOT}"
rm -rf "${OUTPUT_ROOT}"
mkdir -p "${OUTPUT_ROOT}" "${PROJECT_CACHE_ROOT}"

./gradlew --no-daemon --project-cache-dir "${PROJECT_CACHE_ROOT}" assembleEmulator

if [[ ! -f "${APK}" ]]; then
  echo "Gradle completed without producing the expected emulator APK: ${APK}" >&2
  exit 1
fi
"${APKSIGNER}" verify "${APK}"
"${ZIPALIGN}" -c 4 "${APK}"

python3 tools/verify_emulator_apk.py \
  "${APK}" \
  --aapt "${AAPT}" \
  --legacy-root "${LEGACY_NATIVE_ROOT}" \
  --arm64-root "${ARM64_NATIVE_ROOT}" \
  --output "${OUTPUT_ROOT}/native-payload.json"

cp "${APK}" "${OUTPUT_ROOT}/Nanidroid-emulator.apk"
sha256sum "${OUTPUT_ROOT}/Nanidroid-emulator.apk" >"${OUTPUT_ROOT}/Nanidroid-emulator.apk.sha256"

echo "Emulator APK contains the exact additive armeabi + arm64-v8a payload:"
cat "${OUTPUT_ROOT}/native-payload.json"
