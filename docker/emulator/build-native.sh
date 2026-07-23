#!/usr/bin/env bash
set -euo pipefail

readonly SOURCE_ROOT="${SOURCE_ROOT:-/workspace}"
readonly BUILD_ROOT="${BUILD_ROOT:-/tmp/nanidroid-emulator-build}"
readonly OUTPUT_ROOT="${OUTPUT_ROOT:-/out}"
readonly NDK_ROOT="${ANDROID_NDK_HOME:-/opt/android-ndk-r14b}"
readonly CMAKE_BUILD_ROOT="${BUILD_ROOT}/cmake-arm64-build"
readonly STAGE_ROOT="${OUTPUT_ROOT}/.native-stage"
readonly STAGE_NATIVE_ROOT="${STAGE_ROOT}/native"
readonly NATIVE_ROOT="${OUTPUT_ROOT}/native"
readonly CONTRACT_REPORT="${OUTPUT_ROOT}/native-contract.json"
readonly READELF="${NDK_ROOT}/toolchains/aarch64-linux-android-4.9/prebuilt/linux-x86_64/bin/aarch64-linux-android-readelf"
readonly STRIP="${NDK_ROOT}/toolchains/aarch64-linux-android-4.9/prebuilt/linux-x86_64/bin/aarch64-linux-android-strip"

if [[ -z "${OUTPUT_ROOT}" || "${OUTPUT_ROOT}" == "/" ]]; then
  echo "refusing unsafe output root: ${OUTPUT_ROOT}" >&2
  exit 2
fi
if [[ ! -f "${SOURCE_ROOT}/jni/CMakeLists.txt" ]]; then
  echo "source root does not contain jni/CMakeLists.txt: ${SOURCE_ROOT}" >&2
  exit 2
fi

rm -rf "${BUILD_ROOT}" "${STAGE_ROOT}"
mkdir -p "${BUILD_ROOT}" "${STAGE_NATIVE_ROOT}/arm64-v8a"
trap 'rm -rf "${STAGE_ROOT}"' EXIT

# Preserve the read-only checkout and its historical case assumptions by
# compiling a disposable native-only copy with compatibility aliases.
cp -a "${SOURCE_ROOT}/jni" "${BUILD_ROOT}/jni"
ln -s Sender.h "${BUILD_ROOT}/jni/_/sender.h"
ln -s Utilities.h "${BUILD_ROOT}/jni/_/utilities.h"
ln -s satori.h "${BUILD_ROOT}/jni/satori/Satori.h"

cmake \
  -S "${BUILD_ROOT}/jni" \
  -B "${CMAKE_BUILD_ROOT}" \
  -G "Unix Makefiles" \
  -DCMAKE_BUILD_TYPE= \
  -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \
  -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="${STAGE_NATIVE_ROOT}/arm64-v8a" \
  -DCMAKE_TOOLCHAIN_FILE="${NDK_ROOT}/build/cmake/android.toolchain.cmake" \
  -DANDROID_NDK="${NDK_ROOT}" \
  -DANDROID_TOOLCHAIN=gcc \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-21 \
  -DANDROID_STL=gnustl_static
cmake --build "${CMAKE_BUILD_ROOT}" -- -j2

"${STRIP}" --strip-unneeded \
  "${STAGE_NATIVE_ROOT}/arm64-v8a/libkawari8.so" \
  "${STAGE_NATIVE_ROOT}/arm64-v8a/libsatoriya.so"

python3 "${SOURCE_ROOT}/tools/inspect_emulator_native.py" \
  "${STAGE_NATIVE_ROOT}" \
  --readelf "${READELF}" \
  --cmake-cache "${CMAKE_BUILD_ROOT}/CMakeCache.txt" \
  --project-root "${SOURCE_ROOT}" \
  --output "${STAGE_ROOT}/native-contract.json"

# Publish only a fully inspected pair. This root is separate from every
# artifacts/legacy native path consumed by the frozen debug build.
rm -rf "${NATIVE_ROOT}"
rm -f "${CONTRACT_REPORT}"
mv "${STAGE_NATIVE_ROOT}" "${NATIVE_ROOT}"
mv "${STAGE_ROOT}/native-contract.json" "${CONTRACT_REPORT}"
rmdir "${STAGE_ROOT}"
trap - EXIT

echo "ARM64 emulator native artifact:"
cat "${CONTRACT_REPORT}"
