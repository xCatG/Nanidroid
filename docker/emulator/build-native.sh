#!/usr/bin/env bash
set -euo pipefail

readonly SOURCE_ROOT="${SOURCE_ROOT:-/workspace}"
readonly REQUESTED_BUILD_ROOT="${BUILD_ROOT:-/tmp/nanidroid-emulator-build}"
readonly BUILD_ROOT="$(readlink -m -- "${REQUESTED_BUILD_ROOT}")"
readonly OUTPUT_ROOT="${OUTPUT_ROOT:-/out}"
readonly NDK_ROOT="${ANDROID_NDK_HOME:-/opt/android-ndk-r14b}"
readonly CMAKE_BUILD_ROOT="${BUILD_ROOT}/cmake-arm64-build"
readonly NARFS_CMAKE_BUILD_ROOT="${BUILD_ROOT}/narfs-cmake-arm64-build"
readonly STAGE_ROOT="${OUTPUT_ROOT}/.native-stage"
readonly STAGE_NATIVE_ROOT="${STAGE_ROOT}/native"
readonly NARFS_STAGE_ROOT="${STAGE_ROOT}/narfs"
readonly NATIVE_ROOT="${OUTPUT_ROOT}/native"
readonly CONTRACT_REPORT="${OUTPUT_ROOT}/native-contract.json"
readonly STATIC_REPORT="${OUTPUT_ROOT}/narfs-static-contract.json"
readonly NARFS_REPORT="${OUTPUT_ROOT}/narfs-jni-contract.json"
readonly READELF="${NDK_ROOT}/toolchains/aarch64-linux-android-4.9/prebuilt/linux-x86_64/bin/aarch64-linux-android-readelf"
readonly STRIP="${NDK_ROOT}/toolchains/aarch64-linux-android-4.9/prebuilt/linux-x86_64/bin/aarch64-linux-android-strip"

if [[ -z "${OUTPUT_ROOT}" || "${OUTPUT_ROOT}" == "/" ]]; then
  echo "refusing unsafe output root: ${OUTPUT_ROOT}" >&2
  exit 2
fi
case "${BUILD_ROOT}" in
  /tmp/*) ;;
  *)
    echo "refusing build root outside /tmp: ${BUILD_ROOT}" >&2
    exit 2
    ;;
esac
if [[ ! -f "${SOURCE_ROOT}/jni/CMakeLists.txt" ]]; then
  echo "source root does not contain jni/CMakeLists.txt: ${SOURCE_ROOT}" >&2
  exit 2
fi

rm -rf "${BUILD_ROOT}" "${STAGE_ROOT}"
rm -rf "${NATIVE_ROOT}"
rm -f "${CONTRACT_REPORT}" "${STATIC_REPORT}" "${NARFS_REPORT}"
mkdir -p "${BUILD_ROOT}" "${STAGE_NATIVE_ROOT}/arm64-v8a" "${NARFS_STAGE_ROOT}/arm64-v8a"
trap 'rm -rf "${STAGE_ROOT}"' EXIT

# Preserve the read-only checkout and its historical case assumptions by
# compiling a disposable native-only copy with compatibility aliases.
cp -a "${SOURCE_ROOT}/jni" "${BUILD_ROOT}/jni"
mkdir -p "${BUILD_ROOT}/test/native"
cp \
  "${SOURCE_ROOT}/test/native/narfs_link_probe.c" \
  "${SOURCE_ROOT}/test/native/narfs_sha256_link_probe.c" \
  "${SOURCE_ROOT}/test/native/narfs_stage_link_probe.c" \
  "${BUILD_ROOT}/test/native/"
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
  -DCMAKE_ARCHIVE_OUTPUT_DIRECTORY="${CMAKE_BUILD_ROOT}/static/arm64-v8a" \
  -DCMAKE_RUNTIME_OUTPUT_DIRECTORY="${CMAKE_BUILD_ROOT}/static/arm64-v8a" \
  -DCMAKE_TOOLCHAIN_FILE="${NDK_ROOT}/build/cmake/android.toolchain.cmake" \
  -DANDROID_NDK="${NDK_ROOT}" \
  -DANDROID_TOOLCHAIN=gcc \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-21 \
  -DANDROID_STL=gnustl_static
cmake --build "${CMAKE_BUILD_ROOT}" -- -j2

cmake \
  -S "${BUILD_ROOT}/jni/narfs" \
  -B "${NARFS_CMAKE_BUILD_ROOT}" \
  -G "Unix Makefiles" \
  -DCMAKE_BUILD_TYPE= \
  -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \
  -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="${NARFS_STAGE_ROOT}/arm64-v8a" \
  -DCMAKE_ARCHIVE_OUTPUT_DIRECTORY="${NARFS_CMAKE_BUILD_ROOT}/static/arm64-v8a" \
  -DCMAKE_TOOLCHAIN_FILE="${NDK_ROOT}/build/cmake/android.toolchain.cmake" \
  -DANDROID_NDK="${NDK_ROOT}" \
  -DANDROID_TOOLCHAIN=gcc \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-21 \
  -DANDROID_STL=gnustl_static \
  -DNANIDROID_BUILD_NARFS_FULL_JNI_CANDIDATE=ON \
  -DNANIDROID_BUILD_NARFS_STAGE_CANDIDATE=ON \
  -DNANIDROID_BUILD_NARFS_SHA256_CANDIDATE=ON
cmake --build "${NARFS_CMAKE_BUILD_ROOT}" --target narfs_full -- VERBOSE=1

"${STRIP}" --strip-unneeded \
  "${STAGE_NATIVE_ROOT}/arm64-v8a/libkawari8.so" \
  "${STAGE_NATIVE_ROOT}/arm64-v8a/libsatoriya.so"

python3 "${SOURCE_ROOT}/tools/inspect_narfs_jni.py" inspect \
  --dso "${NARFS_STAGE_ROOT}/arm64-v8a/libnarfs.so" \
  --readelf "${READELF}" --evidence "${NARFS_CMAKE_BUILD_ROOT}" \
  --abi arm64-v8a --api android-21 --build-system cmake \
  --profile full \
  --output "${STAGE_ROOT}/narfs-jni-contract.json"
cp "${NARFS_STAGE_ROOT}/arm64-v8a/libnarfs.so" \
  "${STAGE_NATIVE_ROOT}/arm64-v8a/"

python3 "${SOURCE_ROOT}/tools/inspect_emulator_native.py" \
  "${STAGE_NATIVE_ROOT}" \
  --readelf "${READELF}" \
  --cmake-cache "${CMAKE_BUILD_ROOT}/CMakeCache.txt" \
  --ndk-root "${NDK_ROOT}" \
  --project-root "${SOURCE_ROOT}" \
  --output "${STAGE_ROOT}/native-contract.json"
python3 "${SOURCE_ROOT}/tools/inspect_narfs_static.py" inspect \
  --project-root "${SOURCE_ROOT}" \
  --archive "${CMAKE_BUILD_ROOT}/static/arm64-v8a/libnarfs_core.a" \
  --probe "${CMAKE_BUILD_ROOT}/static/arm64-v8a/narfs_core_link_probe" \
  --readelf "${READELF}" \
  --abi arm64-v8a \
  --api android-21 \
  --build-system cmake \
  --build-evidence "${CMAKE_BUILD_ROOT}" \
  --output "${STAGE_ROOT}/narfs-static-contract.json"

# Publish only a fully inspected pair. This root is separate from every
# artifacts/legacy native path consumed by the frozen debug build.
mv "${STAGE_NATIVE_ROOT}" "${NATIVE_ROOT}"
mv "${STAGE_ROOT}/native-contract.json" "${CONTRACT_REPORT}"
mv "${STAGE_ROOT}/narfs-static-contract.json" "${STATIC_REPORT}"
mv "${STAGE_ROOT}/narfs-jni-contract.json" "${NARFS_REPORT}"
rm -rf "${NARFS_STAGE_ROOT}"
rmdir "${STAGE_ROOT}"
trap - EXIT

echo "ARM64 emulator native artifact:"
cat "${CONTRACT_REPORT}"
cat "${STATIC_REPORT}"
cat "${NARFS_REPORT}"
