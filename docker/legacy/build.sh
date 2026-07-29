#!/usr/bin/env bash
set -euo pipefail

# This entry point deliberately has two isolated build roots:
# * REFERENCE_BUILD_ROOT is the immutable pre-Kotlin Ant project.
# * docker/legacy/build-modern-native.sh builds current JNI/NarFS evidence.
# They must never be overlaid.
readonly SOURCE_ROOT="${SOURCE_ROOT:-/workspace}"
readonly REFERENCE_PROJECT_ROOT="${SOURCE_ROOT}/legacy/reference-project"
readonly REFERENCE_THIRD_PARTY_ROOT="${SOURCE_ROOT}/legacy/reference-third-party"
readonly REFERENCE_VALIDATOR="${SOURCE_ROOT}/tools/verify_legacy_reference_snapshot.py"
readonly REFERENCE_BUILD_ROOT="${BUILD_ROOT:-/tmp/nanidroid-legacy-reference-build}"
readonly OUTPUT_ROOT="${OUTPUT_ROOT:-/out}"
readonly APK_REPORT="${OUTPUT_ROOT}/Nanidroid-debug.json"
readonly APK_OUTPUT="${OUTPUT_ROOT}/Nanidroid-debug.apk"
readonly NDK_BUILD_LOG="${REFERENCE_BUILD_ROOT}/ndk-build.log"
readonly READELF="${ANDROID_NDK_HOME}/toolchains/arm-linux-androideabi-4.9/prebuilt/linux-x86_64/bin/arm-linux-androideabi-readelf"

if [[ -z "${OUTPUT_ROOT}" || "${OUTPUT_ROOT}" == "/" ]]; then
  echo "refusing unsafe output root: ${OUTPUT_ROOT}" >&2
  exit 2
fi
case "${REFERENCE_BUILD_ROOT}" in
  /tmp/*) ;;
  *) echo "refusing reference build root outside /tmp: ${REFERENCE_BUILD_ROOT}" >&2; exit 2 ;;
esac

# Check both immutable inputs before deleting any generated artifacts.
python3 "${REFERENCE_VALIDATOR}" \
  "${REFERENCE_PROJECT_ROOT}" "${REFERENCE_THIRD_PARTY_ROOT}"

mkdir -p "${OUTPUT_ROOT}"
rm -f "${APK_REPORT}" "${APK_OUTPUT}"
rm -rf "${REFERENCE_BUILD_ROOT}"
mkdir -p "${REFERENCE_BUILD_ROOT}"

# Ant receives exactly the frozen project plus the declared historical binary.
# No current manifest, resources, assets, source, libs, or JNI tree is copied.
rsync -a "${REFERENCE_PROJECT_ROOT}/" "${REFERENCE_BUILD_ROOT}/"
cp "${REFERENCE_THIRD_PARTY_ROOT}/GoogleAdMobAdsSdk-6.0.1.jar" \
  "${REFERENCE_BUILD_ROOT}/libs/GoogleAdMobAdsSdk-6.0.1.jar"

# These current scripts are build-support verifiers only.  They are named
# explicitly so copying support cannot accidentally import product inputs.
mkdir -p "${REFERENCE_BUILD_ROOT}/tools"
for tool in inspect_legacy_apk.py; do
  cp "${SOURCE_ROOT}/tools/${tool}" "${REFERENCE_BUILD_ROOT}/tools/${tool}"
done

cat >"${REFERENCE_BUILD_ROOT}/local.properties" <<EOF
sdk.dir=${ANDROID_SDK_ROOT}
ndk.dir=${ANDROID_NDK_HOME}
EOF

# The historical tree was authored on a case-insensitive filesystem.  Keep the
# snapshot bytes untouched and create compatibility aliases only in /tmp.
ln -s Sender.h "${REFERENCE_BUILD_ROOT}/jni/_/sender.h"
ln -s Utilities.h "${REFERENCE_BUILD_ROOT}/jni/_/utilities.h"
ln -s satori.h "${REFERENCE_BUILD_ROOT}/jni/satori/Satori.h"

cd "${REFERENCE_BUILD_ROOT}"
"${ANDROID_NDK_HOME}/ndk-build" \
  NDK_PROJECT_PATH="${REFERENCE_BUILD_ROOT}" \
  APP_BUILD_SCRIPT="${REFERENCE_BUILD_ROOT}/jni/Android.mk" \
  NDK_APPLICATION_MK="${REFERENCE_BUILD_ROOT}/jni/Application.mk" \
  APP_PLATFORM=android-9 APP_ABI=armeabi APP_CPPFLAGS="-frtti -fexceptions -fpermissive" \
  NDK_TOOLCHAIN_VERSION=4.9 V=1 2>&1 | tee "${NDK_BUILD_LOG}"

ant debug
apk="$(find "${REFERENCE_BUILD_ROOT}/bin" -maxdepth 1 -type f -name '*-debug.apk' -print -quit)"
if [[ -z "${apk}" ]]; then
  echo "frozen Ant build completed without producing a debug APK" >&2
  exit 1
fi
"${ANDROID_SDK_ROOT}/build-tools/25.0.3/apksigner" verify "${apk}"
"${ANDROID_SDK_ROOT}/build-tools/25.0.3/zipalign" -c 4 "${apk}"
python3 "${REFERENCE_BUILD_ROOT}/tools/inspect_legacy_apk.py" \
  "${apk}" --aapt "${ANDROID_SDK_ROOT}/build-tools/25.0.3/aapt" \
  --frozen-reference-project \
  --output "${APK_REPORT}"
cp "${apk}" "${APK_OUTPUT}"

# Current NarFS/native provenance is deliberately a distinct lane and root.
bash "${SOURCE_ROOT}/docker/legacy/build-modern-native.sh"

echo "frozen Ant reference APK: ${APK_OUTPUT}"
