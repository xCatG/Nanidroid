#!/usr/bin/env bash
set -euo pipefail

readonly SOURCE_ROOT="${SOURCE_ROOT:-/workspace}"
readonly BUILD_ROOT="${BUILD_ROOT:-/tmp/nanidroid-narfs-jni}"
readonly OUTPUT_ROOT="${OUTPUT_ROOT:-/out}"
readonly NDK_ROOT="${ANDROID_NDK_HOME:-/opt/android-ndk-r14b}"
readonly INSPECTOR="${SOURCE_ROOT}/tools/inspect_narfs_jni.py"

case "${BUILD_ROOT}" in
  /tmp/*) ;;
  *) echo "refusing build root outside /tmp: ${BUILD_ROOT}" >&2; exit 2 ;;
esac
if [[ -z "${OUTPUT_ROOT}" || "${OUTPUT_ROOT}" == "/" ]]; then
  echo "refusing unsafe output root: ${OUTPUT_ROOT}" >&2
  exit 2
fi
if [[ ! -f "${SOURCE_ROOT}/jni/narfs/narfs_jni.c" ]]; then
  echo "candidate sources are missing from ${SOURCE_ROOT}" >&2
  exit 2
fi

rm -rf "${BUILD_ROOT}"
mkdir -p "${BUILD_ROOT}/test/native" "${OUTPUT_ROOT}"
rm -f "${OUTPUT_ROOT}"/narfs-jni-*.json
cp -a "${SOURCE_ROOT}/jni" "${BUILD_ROOT}/jni"
cp "${SOURCE_ROOT}/test/native/narfs_link_probe.c" "${BUILD_ROOT}/test/native/"
cp "${SOURCE_ROOT}/test/native/narfs_sha256_link_probe.c" "${BUILD_ROOT}/test/native/"
ln -s Sender.h "${BUILD_ROOT}/jni/_/sender.h"
ln -s Utilities.h "${BUILD_ROOT}/jni/_/utilities.h"
ln -s satori.h "${BUILD_ROOT}/jni/satori/Satori.h"

gcc -std=c99 -Wall -Wextra -Werror -fsanitize=address,undefined \
  "${SOURCE_ROOT}/jni/narfs/narfs_utf.c" \
  "${SOURCE_ROOT}/test/native/narfs_utf_test.c" \
  -o "${BUILD_ROOT}/narfs_utf_test"
"${BUILD_ROOT}/narfs_utf_test"
gcc -std=c99 -Wall -Wextra -Werror -fsanitize=address,undefined \
  "${SOURCE_ROOT}/jni/narfs/narfs_sha256.c" \
  "${SOURCE_ROOT}/test/native/narfs_sha256_test.c" \
  -o "${BUILD_ROOT}/narfs_sha256_test"
"${BUILD_ROOT}/narfs_sha256_test"

build_lane() {
  local abi="$1" api="$2" triple="$3" arm_mode="$4"
  local ndk_build="${BUILD_ROOT}/ndk-${abi}"
  local cmake_build="${BUILD_ROOT}/cmake-${abi}"
  local ndk_log="${ndk_build}/build.log"
  local readelf="${NDK_ROOT}/toolchains/${triple}-4.9/prebuilt/linux-x86_64/bin/${triple}-readelf"
  local report="${OUTPUT_ROOT}/narfs-jni-${abi}"
  mkdir -p "${ndk_build}"

  "${NDK_ROOT}/ndk-build" \
    'TARGET_CXX=$(TARGET_CC)' \
    NDK_PROJECT_PATH="${ndk_build}" \
    NDK_OUT="${ndk_build}/obj" \
    NDK_LIBS_OUT="${ndk_build}/libs" \
    APP_BUILD_SCRIPT="${BUILD_ROOT}/jni/narfs/Android.mk" \
    NDK_APPLICATION_MK="${BUILD_ROOT}/jni/Application.mk" \
    APP_MODULES="narfs narfs_sha256_link_probe" \
    APP_PLATFORM="${api}" APP_ABI="${abi}" \
    NDK_TOOLCHAIN_VERSION=4.9 NANIDROID_NARFS_JNI_CANDIDATE=1 \
    V=1 2>&1 | tee "${ndk_log}"

  python3 "${INSPECTOR}" inspect \
    --dso "${ndk_build}/obj/local/${abi}/libnarfs.so" \
    --readelf "${readelf}" --evidence "${ndk_log}" \
    --abi "${abi}" --api "${api}" --build-system ndk-build \
    --output "${report}-ndk-build.json"

  cmake_args=(
    -S "${BUILD_ROOT}/jni/narfs" -B "${cmake_build}" -G "Unix Makefiles"
    -DCMAKE_BUILD_TYPE= -DCMAKE_EXPORT_COMPILE_COMMANDS=ON
    -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="${cmake_build}/native/${abi}"
    -DCMAKE_ARCHIVE_OUTPUT_DIRECTORY="${cmake_build}/static/${abi}"
    -DCMAKE_TOOLCHAIN_FILE="${NDK_ROOT}/build/cmake/android.toolchain.cmake"
    -DANDROID_NDK="${NDK_ROOT}" -DANDROID_TOOLCHAIN=gcc
    -DANDROID_ABI="${abi}" -DANDROID_PLATFORM="${api}"
    -DANDROID_STL=gnustl_static -DNANIDROID_BUILD_NARFS_JNI_CANDIDATE=ON
  )
  if [[ -n "${arm_mode}" ]]; then
    cmake_args+=("-DANDROID_ARM_MODE=${arm_mode}")
  fi
  cmake "${cmake_args[@]}"
  cmake --build "${cmake_build}" \
    --target narfs narfs_sha256_link_probe -- VERBOSE=1

  local expected_sha_api actual_sha_api archive probe
  expected_sha_api=$'narfs_sha256_final\nnarfs_sha256_init\nnarfs_sha256_update'
  for archive in \
      "${ndk_build}/obj/local/${abi}/libnarfs_sha256.a" \
      "${cmake_build}/static/${abi}/libnarfs_sha256.a"; do
    actual_sha_api="$("${readelf}" --syms "${archive}" \
      | awk '$5 == "GLOBAL" && $7 != "UND" {print $8}' \
      | grep '^narfs_sha256_' | sort -u)"
    [[ "${actual_sha_api}" == "${expected_sha_api}" ]] \
      || { echo "unexpected sha256 API in ${archive}" >&2; exit 1; }
  done
  for probe in \
      "${ndk_build}/obj/local/${abi}/narfs_sha256_link_probe" \
      "${cmake_build}/sha256/narfs_sha256_link_probe"; do
    "${readelf}" --file-header "${probe}" | grep -q 'ELF Header'
    ! "${readelf}" --syms "${probe}" \
      | grep 'UND' | grep -q 'narfs_sha256_'
  done

  python3 "${INSPECTOR}" inspect \
    --dso "${cmake_build}/native/${abi}/libnarfs.so" \
    --readelf "${readelf}" --evidence "${cmake_build}" \
    --abi "${abi}" --api "${api}" --build-system cmake \
    --output "${report}-cmake.json"
  python3 "${INSPECTOR}" compare \
    "${report}-ndk-build.json" "${report}-cmake.json" \
    --output "${report}-parity.json"
}

build_lane armeabi android-9 arm-linux-androideabi thumb
build_lane arm64-v8a android-21 aarch64-linux-android ""

echo "Validated private narfs JNI candidate reports:"
find "${OUTPUT_ROOT}" -maxdepth 1 -type f -name 'narfs-jni-*.json' -printf '  %f\n' | sort
