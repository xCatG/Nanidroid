#!/usr/bin/env bash
set -euo pipefail

# Current JNI verification lane.  It intentionally reads only the current
# native inputs and never contributes files to the frozen Ant build root.
readonly SOURCE_ROOT="${SOURCE_ROOT:-/workspace}"
readonly BUILD_ROOT="${MODERN_NATIVE_BUILD_ROOT:-/tmp/nanidroid-modern-native-build}"
readonly OUTPUT_ROOT="${OUTPUT_ROOT:-/out}"
readonly NATIVE_ABI=armeabi
readonly NATIVE_API=android-9
readonly NATIVE_STAGE="${OUTPUT_ROOT}/.modern-native-stage"
readonly NDK_NATIVE_ROOT="${OUTPUT_ROOT}/native-ndk-build"
readonly CMAKE_NATIVE_ROOT="${OUTPUT_ROOT}/native-cmake"
readonly GRADLE_NATIVE_ROOT="${OUTPUT_ROOT}/native"
readonly CMAKE_BUILD_ROOT="${BUILD_ROOT}/cmake-build"
readonly NARFS_CMAKE_BUILD_ROOT="${BUILD_ROOT}/narfs-cmake-build"
readonly NARFS_NDK_LIBS_ROOT="${BUILD_ROOT}/libs-narfs"
readonly NDK_BUILD_LOG="${BUILD_ROOT}/ndk-build.log"
readonly NARFS_NDK_BUILD_LOG="${BUILD_ROOT}/narfs-ndk-build.log"
readonly READELF="${ANDROID_NDK_HOME}/toolchains/arm-linux-androideabi-4.9/prebuilt/linux-x86_64/bin/arm-linux-androideabi-readelf"
readonly STRIP="${ANDROID_NDK_HOME}/toolchains/arm-linux-androideabi-4.9/prebuilt/linux-x86_64/bin/arm-linux-androideabi-strip"

case "${BUILD_ROOT}" in /tmp/*) ;; *) echo "refusing modern native build root outside /tmp: ${BUILD_ROOT}" >&2; exit 2;; esac
[[ -f "${SOURCE_ROOT}/jni/CMakeLists.txt" ]] || { echo "missing current jni/CMakeLists.txt" >&2; exit 2; }

rm -rf "${BUILD_ROOT}" "${NATIVE_STAGE}" "${NDK_NATIVE_ROOT}" "${CMAKE_NATIVE_ROOT}" "${GRADLE_NATIVE_ROOT}"
rm -f "${OUTPUT_ROOT}"/native-*.json "${OUTPUT_ROOT}"/narfs-*.json
mkdir -p "${BUILD_ROOT}" "${NATIVE_STAGE}/native-ndk-build/${NATIVE_ABI}" "${NATIVE_STAGE}/native-cmake/${NATIVE_ABI}"
trap 'rm -rf "${NATIVE_STAGE}"' EXIT

# A native-only current snapshot is sufficient and prevents modern app
# resources/Kotlin sources from leaking into the historical Ant lane.
cp -a "${SOURCE_ROOT}/jni" "${BUILD_ROOT}/jni"
mkdir -p "${BUILD_ROOT}/test/native"
cp "${SOURCE_ROOT}/test/native/narfs_link_probe.c" \
   "${SOURCE_ROOT}/test/native/narfs_sha256_link_probe.c" \
   "${SOURCE_ROOT}/test/native/narfs_stage_link_probe.c" "${BUILD_ROOT}/test/native/"
ln -s Sender.h "${BUILD_ROOT}/jni/_/sender.h"
ln -s Utilities.h "${BUILD_ROOT}/jni/_/utilities.h"
ln -s satori.h "${BUILD_ROOT}/jni/satori/Satori.h"

"${ANDROID_NDK_HOME}/ndk-build" NDK_PROJECT_PATH="${BUILD_ROOT}" \
  APP_BUILD_SCRIPT="${BUILD_ROOT}/jni/Android.mk" NDK_APPLICATION_MK="${BUILD_ROOT}/jni/Application.mk" \
  APP_PLATFORM=android-9 APP_ABI=armeabi APP_CPPFLAGS="-frtti -fexceptions -fpermissive" \
  NDK_TOOLCHAIN_VERSION=4.9 V=1 2>&1 | tee "${NDK_BUILD_LOG}"
"${ANDROID_NDK_HOME}/ndk-build" 'TARGET_CXX=$(TARGET_CC)' NDK_PROJECT_PATH="${BUILD_ROOT}" \
  NDK_OUT="${BUILD_ROOT}/obj-narfs" NDK_LIBS_OUT="${NARFS_NDK_LIBS_ROOT}" \
  APP_BUILD_SCRIPT="${BUILD_ROOT}/jni/narfs/Android.mk" NDK_APPLICATION_MK="${BUILD_ROOT}/jni/Application.mk" \
  APP_MODULES=narfs_full APP_PLATFORM=android-9 APP_ABI=armeabi NDK_TOOLCHAIN_VERSION=4.9 \
  NANIDROID_NARFS_FULL_JNI_CANDIDATE=1 NANIDROID_NARFS_STAGE_CANDIDATE=1 NANIDROID_NARFS_SHA256_CANDIDATE=1 \
  V=1 2>&1 | tee "${NARFS_NDK_BUILD_LOG}"

cmake -S "${BUILD_ROOT}/jni" -B "${CMAKE_BUILD_ROOT}" -G "Unix Makefiles" \
  -DCMAKE_BUILD_TYPE= -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \
  -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="${CMAKE_BUILD_ROOT}/native/${NATIVE_ABI}" \
  -DCMAKE_ARCHIVE_OUTPUT_DIRECTORY="${CMAKE_BUILD_ROOT}/static/${NATIVE_ABI}" \
  -DCMAKE_RUNTIME_OUTPUT_DIRECTORY="${CMAKE_BUILD_ROOT}/static/${NATIVE_ABI}" \
  -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" -DANDROID_NDK="${ANDROID_NDK_HOME}" \
  -DANDROID_TOOLCHAIN=gcc -DANDROID_ABI="${NATIVE_ABI}" -DANDROID_PLATFORM="${NATIVE_API}" -DANDROID_STL=gnustl_static -DANDROID_ARM_MODE=thumb
cmake --build "${CMAKE_BUILD_ROOT}" -- -j2
cmake -S "${BUILD_ROOT}/jni/narfs" -B "${NARFS_CMAKE_BUILD_ROOT}" -G "Unix Makefiles" \
  -DCMAKE_BUILD_TYPE= -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \
  -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="${NARFS_CMAKE_BUILD_ROOT}/native/${NATIVE_ABI}" \
  -DCMAKE_ARCHIVE_OUTPUT_DIRECTORY="${NARFS_CMAKE_BUILD_ROOT}/static/${NATIVE_ABI}" \
  -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" -DANDROID_NDK="${ANDROID_NDK_HOME}" \
  -DANDROID_TOOLCHAIN=gcc -DANDROID_ABI="${NATIVE_ABI}" -DANDROID_PLATFORM="${NATIVE_API}" -DANDROID_STL=gnustl_static -DANDROID_ARM_MODE=thumb \
  -DNANIDROID_BUILD_NARFS_FULL_JNI_CANDIDATE=ON -DNANIDROID_BUILD_NARFS_STAGE_CANDIDATE=ON -DNANIDROID_BUILD_NARFS_SHA256_CANDIDATE=ON
cmake --build "${NARFS_CMAKE_BUILD_ROOT}" --target narfs_full -- VERBOSE=1
"${STRIP}" --strip-unneeded "${CMAKE_BUILD_ROOT}/native/${NATIVE_ABI}/libkawari8.so" "${CMAKE_BUILD_ROOT}/native/${NATIVE_ABI}/libsatoriya.so"

cp "${BUILD_ROOT}/libs/${NATIVE_ABI}/libkawari8.so" "${BUILD_ROOT}/libs/${NATIVE_ABI}/libsatoriya.so" "${NATIVE_STAGE}/native-ndk-build/${NATIVE_ABI}/"
cp "${BUILD_ROOT}/obj-narfs/local/${NATIVE_ABI}/libnarfs.so" "${NATIVE_STAGE}/native-ndk-build/${NATIVE_ABI}/"
cp "${CMAKE_BUILD_ROOT}/native/${NATIVE_ABI}/libkawari8.so" "${CMAKE_BUILD_ROOT}/native/${NATIVE_ABI}/libsatoriya.so" "${NARFS_CMAKE_BUILD_ROOT}/native/${NATIVE_ABI}/libnarfs.so" "${NATIVE_STAGE}/native-cmake/${NATIVE_ABI}/"

python3 "${SOURCE_ROOT}/tools/inspect_native_contract.py" inspect "${NATIVE_STAGE}/native-ndk-build" --build-system ndk-build --build-evidence "${NDK_BUILD_LOG}" --project-root "${BUILD_ROOT}" --readelf "${READELF}" --abi "${NATIVE_ABI}" --api "${NATIVE_API}" --compiler gcc-4.9 --stl gnustl_static --arm-mode thumb --ndk r14b --ndk-root "${ANDROID_NDK_HOME}" --include-current-narfs --output "${NATIVE_STAGE}/native-ndk-build.json"
python3 "${SOURCE_ROOT}/tools/inspect_native_contract.py" inspect "${NATIVE_STAGE}/native-cmake" --build-system cmake --build-evidence "${CMAKE_BUILD_ROOT}" --cmake-cache "${CMAKE_BUILD_ROOT}/CMakeCache.txt" --project-root "${BUILD_ROOT}" --readelf "${READELF}" --abi "${NATIVE_ABI}" --api "${NATIVE_API}" --compiler gcc-4.9 --stl gnustl_static --arm-mode thumb --ndk r14b --ndk-root "${ANDROID_NDK_HOME}" --include-current-narfs --output "${NATIVE_STAGE}/native-cmake.json"
python3 "${SOURCE_ROOT}/tools/inspect_native_contract.py" compare "${NATIVE_STAGE}/native-ndk-build.json" "${NATIVE_STAGE}/native-cmake.json" --output "${NATIVE_STAGE}/native-parity.json"
python3 "${SOURCE_ROOT}/tools/inspect_narfs_static.py" inspect --project-root "${BUILD_ROOT}" --archive "${BUILD_ROOT}/obj/local/${NATIVE_ABI}/libnarfs_core.a" --probe "${BUILD_ROOT}/obj/local/${NATIVE_ABI}/narfs_core_link_probe" --readelf "${READELF}" --abi "${NATIVE_ABI}" --api "${NATIVE_API}" --build-system ndk-build --build-evidence "${NDK_BUILD_LOG}" --output "${NATIVE_STAGE}/narfs-static-ndk-build.json"
python3 "${SOURCE_ROOT}/tools/inspect_narfs_static.py" inspect --project-root "${BUILD_ROOT}" --archive "${CMAKE_BUILD_ROOT}/static/${NATIVE_ABI}/libnarfs_core.a" --probe "${CMAKE_BUILD_ROOT}/static/${NATIVE_ABI}/narfs_core_link_probe" --readelf "${READELF}" --abi "${NATIVE_ABI}" --api "${NATIVE_API}" --build-system cmake --build-evidence "${CMAKE_BUILD_ROOT}" --output "${NATIVE_STAGE}/narfs-static-cmake.json"
python3 "${SOURCE_ROOT}/tools/inspect_narfs_static.py" compare "${NATIVE_STAGE}/narfs-static-ndk-build.json" "${NATIVE_STAGE}/narfs-static-cmake.json" --output "${NATIVE_STAGE}/narfs-static-parity.json"
python3 "${SOURCE_ROOT}/tools/inspect_narfs_jni.py" inspect --dso "${BUILD_ROOT}/obj-narfs/local/${NATIVE_ABI}/libnarfs.so" --readelf "${READELF}" --evidence "${NARFS_NDK_BUILD_LOG}" --abi "${NATIVE_ABI}" --api "${NATIVE_API}" --build-system ndk-build --profile full --output "${NATIVE_STAGE}/narfs-jni-ndk-build.json"
python3 "${SOURCE_ROOT}/tools/inspect_narfs_jni.py" inspect --dso "${NARFS_CMAKE_BUILD_ROOT}/native/${NATIVE_ABI}/libnarfs.so" --readelf "${READELF}" --evidence "${NARFS_CMAKE_BUILD_ROOT}" --abi "${NATIVE_ABI}" --api "${NATIVE_API}" --build-system cmake --profile full --output "${NATIVE_STAGE}/narfs-jni-cmake.json"
python3 "${SOURCE_ROOT}/tools/inspect_narfs_jni.py" compare "${NATIVE_STAGE}/narfs-jni-ndk-build.json" "${NATIVE_STAGE}/narfs-jni-cmake.json" --output "${NATIVE_STAGE}/narfs-jni-parity.json"

mv "${NATIVE_STAGE}/native-ndk-build" "${NDK_NATIVE_ROOT}"
mv "${NATIVE_STAGE}/native-cmake" "${CMAKE_NATIVE_ROOT}"
cp -a "${NDK_NATIVE_ROOT}" "${GRADLE_NATIVE_ROOT}"
for report in native-ndk-build native-cmake native-parity narfs-static-ndk-build narfs-static-cmake narfs-static-parity narfs-jni-ndk-build narfs-jni-cmake narfs-jni-parity; do mv "${NATIVE_STAGE}/${report}.json" "${OUTPUT_ROOT}/${report}.json"; done
rmdir "${NATIVE_STAGE}"
trap - EXIT
echo "current native verifier lane complete"
