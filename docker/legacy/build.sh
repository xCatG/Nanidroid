#!/usr/bin/env bash
set -euo pipefail

readonly SOURCE_ROOT="${SOURCE_ROOT:-/workspace}"
readonly BUILD_ROOT="${BUILD_ROOT:-/tmp/nanidroid-legacy-build}"
readonly OUTPUT_ROOT="${OUTPUT_ROOT:-/out}"
readonly APK_REPORT="${OUTPUT_ROOT}/Nanidroid-debug.json"
readonly NDK_NATIVE_ROOT="${OUTPUT_ROOT}/native-ndk-build"
readonly CMAKE_NATIVE_ROOT="${OUTPUT_ROOT}/native-cmake"
readonly GRADLE_NATIVE_ROOT="${OUTPUT_ROOT}/native"
readonly NATIVE_STAGE="${OUTPUT_ROOT}/.native-stage"
readonly STAGE_NDK_NATIVE_ROOT="${NATIVE_STAGE}/native-ndk-build"
readonly STAGE_CMAKE_NATIVE_ROOT="${NATIVE_STAGE}/native-cmake"
readonly STAGE_GRADLE_NATIVE_ROOT="${NATIVE_STAGE}/native"
readonly CMAKE_BUILD_ROOT="${BUILD_ROOT}/cmake-build"
readonly NDK_BUILD_LOG="${BUILD_ROOT}/ndk-build.log"
readonly STATIC_INSPECTOR="${BUILD_ROOT}/tools/inspect_narfs_static.py"
readonly READELF="${ANDROID_NDK_HOME}/toolchains/arm-linux-androideabi-4.9/prebuilt/linux-x86_64/bin/arm-linux-androideabi-readelf"
readonly STRIP="${ANDROID_NDK_HOME}/toolchains/arm-linux-androideabi-4.9/prebuilt/linux-x86_64/bin/arm-linux-androideabi-strip"
readonly NATIVE_ABI=armeabi
readonly NATIVE_API=android-9
readonly NATIVE_COMPILER=gcc-4.9
readonly NATIVE_STL=gnustl_static
readonly NATIVE_ARM_MODE=thumb
readonly NATIVE_NDK=r14b

if [[ -z "${OUTPUT_ROOT}" || "${OUTPUT_ROOT}" == "/" ]]; then
  echo "refusing unsafe output root: ${OUTPUT_ROOT}" >&2
  exit 2
fi
mkdir -p "${OUTPUT_ROOT}"
rm -rf \
  "${NDK_NATIVE_ROOT}" \
  "${CMAKE_NATIVE_ROOT}" \
  "${GRADLE_NATIVE_ROOT}" \
  "${NATIVE_STAGE}"
rm -f \
  "${OUTPUT_ROOT}/native-ndk-build.json" \
  "${OUTPUT_ROOT}/native-cmake.json" \
  "${OUTPUT_ROOT}/native-parity.json" \
  "${OUTPUT_ROOT}/narfs-static-ndk-build.json" \
  "${OUTPUT_ROOT}/narfs-static-cmake.json" \
  "${OUTPUT_ROOT}/narfs-static-parity.json" \
  "${OUTPUT_ROOT}/Nanidroid-debug.apk" \
  "${APK_REPORT}"
mkdir -p "${NATIVE_STAGE}"
trap 'rm -rf "${NATIVE_STAGE}"' EXIT

if [[ ! -f "${SOURCE_ROOT}/AndroidManifest.xml" ]]; then
  echo "source root does not contain AndroidManifest.xml: ${SOURCE_ROOT}" >&2
  exit 2
fi

rm -rf "${BUILD_ROOT}"
mkdir -p "${BUILD_ROOT}"

# Build outside the read-only source mount. This prevents Ant, ndk-build, and
# the debug keystore generator from dirtying the checkout on any host OS.
rsync -a \
  --exclude /.git/ \
  --exclude /.gradle/ \
  --exclude /artifacts/ \
  --exclude /bin/ \
  --exclude /obj/ \
  --exclude '/**/build/' \
  "${SOURCE_ROOT}/" "${BUILD_ROOT}/"

cat >"${BUILD_ROOT}/local.properties" <<EOF
sdk.dir=${ANDROID_SDK_ROOT}
ndk.dir=${ANDROID_NDK_HOME}
EOF

# The original tree was maintained on a case-insensitive filesystem. Preserve
# it verbatim and add aliases only inside the disposable Linux build copy.
# These links intentionally do not use -f: BUILD_ROOT was recreated above, and
# a future real file at an alias path must fail visibly rather than be replaced.
ln -s Sender.h "${BUILD_ROOT}/jni/_/sender.h"
ln -s Utilities.h "${BUILD_ROOT}/jni/_/utilities.h"
ln -s satori.h "${BUILD_ROOT}/jni/satori/Satori.h"

# AGP 9 requires the package namespace and SDK levels in Gradle instead of the
# manifest. Ant still reads them from XML, so restore the same frozen values
# only in its build copy.
readonly LEGACY_MANIFEST="${BUILD_ROOT}/AndroidManifest.xml"
if grep -q '<uses-sdk\|^[[:space:]]*package=' "${LEGACY_MANIFEST}"; then
  echo "unexpected Ant-only metadata in the Gradle-compatible source manifest" >&2
  exit 1
fi
sed -i \
  '/<manifest xmlns:android=/a\  package="com.cattailsw.nanidroid"' \
  "${LEGACY_MANIFEST}"
sed -i \
  '/<application /i\  <uses-sdk android:minSdkVersion="9" android:targetSdkVersion="13" />' \
  "${LEGACY_MANIFEST}"

cd "${BUILD_ROOT}"

"${ANDROID_NDK_HOME}/ndk-build" \
  NDK_PROJECT_PATH="${BUILD_ROOT}" \
  APP_BUILD_SCRIPT="${BUILD_ROOT}/jni/Android.mk" \
  NDK_APPLICATION_MK="${BUILD_ROOT}/jni/Application.mk" \
  APP_PLATFORM=android-9 \
  APP_ABI=armeabi \
  APP_CPPFLAGS="-frtti -fexceptions -fpermissive" \
  NDK_TOOLCHAIN_VERSION=4.9 \
  V=1 2>&1 | tee "${NDK_BUILD_LOG}"

# Keep the Ant reference APK on the ndk-build output. The CMake candidate is
# built and parity-checked independently below, then copied to the existing
# Gradle native input only after both engines link successfully.
ant clean debug

apk="$(find "${BUILD_ROOT}/bin" -maxdepth 1 -type f -name '*-debug.apk' -print -quit)"
if [[ -z "${apk}" ]]; then
  echo "Ant completed without producing a debug APK" >&2
  exit 1
fi

"${ANDROID_SDK_ROOT}/build-tools/25.0.3/apksigner" verify "${apk}"
"${ANDROID_SDK_ROOT}/build-tools/25.0.3/zipalign" -c 4 "${apk}"

python3 "${BUILD_ROOT}/tools/inspect_legacy_apk.py" \
  "${apk}" \
  --aapt "${ANDROID_SDK_ROOT}/build-tools/25.0.3/aapt" \
  --output "${NATIVE_STAGE}/Nanidroid-debug.json"

cmake \
  -S "${BUILD_ROOT}/jni" \
  -B "${CMAKE_BUILD_ROOT}" \
  -G "Unix Makefiles" \
  -DCMAKE_BUILD_TYPE= \
  -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \
  -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="${CMAKE_BUILD_ROOT}/native/${NATIVE_ABI}" \
  -DCMAKE_ARCHIVE_OUTPUT_DIRECTORY="${CMAKE_BUILD_ROOT}/static/${NATIVE_ABI}" \
  -DCMAKE_RUNTIME_OUTPUT_DIRECTORY="${CMAKE_BUILD_ROOT}/static/${NATIVE_ABI}" \
  -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" \
  -DANDROID_NDK="${ANDROID_NDK_HOME}" \
  -DANDROID_TOOLCHAIN=gcc \
  -DANDROID_ABI="${NATIVE_ABI}" \
  -DANDROID_PLATFORM="${NATIVE_API}" \
  -DANDROID_STL="${NATIVE_STL}" \
  -DANDROID_ARM_MODE="${NATIVE_ARM_MODE}"
cmake --build "${CMAKE_BUILD_ROOT}" -- -j2

# ndk-build installs stripped release libraries. Apply the same frozen r14b
# strip tool to the CMake products before inspection, publication, and Gradle
# packaging so ignored debug-section differences do not inflate the APK.
"${STRIP}" --strip-unneeded \
  "${CMAKE_BUILD_ROOT}"/native/"${NATIVE_ABI}"/libkawari8.so \
  "${CMAKE_BUILD_ROOT}"/native/"${NATIVE_ABI}"/libsatoriya.so

mkdir -p \
  "${STAGE_NDK_NATIVE_ROOT}/${NATIVE_ABI}" \
  "${STAGE_CMAKE_NATIVE_ROOT}/${NATIVE_ABI}"
cp "${BUILD_ROOT}"/libs/"${NATIVE_ABI}"/*.so "${STAGE_NDK_NATIVE_ROOT}/${NATIVE_ABI}/"
cp "${CMAKE_BUILD_ROOT}"/native/"${NATIVE_ABI}"/*.so "${STAGE_CMAKE_NATIVE_ROOT}/${NATIVE_ABI}/"

readonly NATIVE_INSPECTOR="${BUILD_ROOT}/tools/inspect_native_contract.py"
readonly NATIVE_INSPECT_ARGS=(
  --readelf "${READELF}"
  --project-root "${BUILD_ROOT}"
  --abi "${NATIVE_ABI}"
  --api "${NATIVE_API}"
  --compiler "${NATIVE_COMPILER}"
  --stl "${NATIVE_STL}"
  --arm-mode "${NATIVE_ARM_MODE}"
  --ndk "${NATIVE_NDK}"
  --ndk-root "${ANDROID_NDK_HOME}"
)

python3 "${NATIVE_INSPECTOR}" inspect \
  "${STAGE_NDK_NATIVE_ROOT}" \
  --build-system ndk-build \
  --build-evidence "${NDK_BUILD_LOG}" \
  "${NATIVE_INSPECT_ARGS[@]}" \
  --output "${NATIVE_STAGE}/native-ndk-build.json"
python3 "${NATIVE_INSPECTOR}" inspect \
  "${STAGE_CMAKE_NATIVE_ROOT}" \
  --build-system cmake \
  --build-evidence "${CMAKE_BUILD_ROOT}" \
  --cmake-cache "${CMAKE_BUILD_ROOT}/CMakeCache.txt" \
  "${NATIVE_INSPECT_ARGS[@]}" \
  --output "${NATIVE_STAGE}/native-cmake.json"
python3 "${NATIVE_INSPECTOR}" compare \
  "${NATIVE_STAGE}/native-ndk-build.json" \
  "${NATIVE_STAGE}/native-cmake.json" \
  --output "${NATIVE_STAGE}/native-parity.json"

readonly STATIC_INSPECT_ARGS=(
  --project-root "${BUILD_ROOT}"
  --readelf "${READELF}"
  --abi "${NATIVE_ABI}"
  --api "${NATIVE_API}"
)
python3 "${STATIC_INSPECTOR}" inspect \
  --archive "${BUILD_ROOT}/obj/local/${NATIVE_ABI}/libnarfs_core.a" \
  --probe "${BUILD_ROOT}/libs/${NATIVE_ABI}/narfs_core_link_probe" \
  --build-system ndk-build \
  --build-evidence "${NDK_BUILD_LOG}" \
  "${STATIC_INSPECT_ARGS[@]}" \
  --output "${NATIVE_STAGE}/narfs-static-ndk-build.json"
python3 "${STATIC_INSPECTOR}" inspect \
  --archive "${CMAKE_BUILD_ROOT}/static/${NATIVE_ABI}/libnarfs_core.a" \
  --probe "${CMAKE_BUILD_ROOT}/static/${NATIVE_ABI}/narfs_core_link_probe" \
  --build-system cmake \
  --build-evidence "${CMAKE_BUILD_ROOT}" \
  "${STATIC_INSPECT_ARGS[@]}" \
  --output "${NATIVE_STAGE}/narfs-static-cmake.json"
python3 "${STATIC_INSPECTOR}" compare \
  "${NATIVE_STAGE}/narfs-static-ndk-build.json" \
  "${NATIVE_STAGE}/narfs-static-cmake.json" \
  --output "${NATIVE_STAGE}/narfs-static-parity.json"

# Stage and atomically promote all native outputs only after exact parity.
cp -a "${STAGE_CMAKE_NATIVE_ROOT}" "${STAGE_GRADLE_NATIVE_ROOT}"
cp "${apk}" "${NATIVE_STAGE}/Nanidroid-debug.apk"
mv "${STAGE_NDK_NATIVE_ROOT}" "${NDK_NATIVE_ROOT}"
mv "${STAGE_CMAKE_NATIVE_ROOT}" "${CMAKE_NATIVE_ROOT}"
mv "${STAGE_GRADLE_NATIVE_ROOT}" "${GRADLE_NATIVE_ROOT}"
mv "${NATIVE_STAGE}/native-ndk-build.json" "${OUTPUT_ROOT}/native-ndk-build.json"
mv "${NATIVE_STAGE}/native-cmake.json" "${OUTPUT_ROOT}/native-cmake.json"
mv "${NATIVE_STAGE}/native-parity.json" "${OUTPUT_ROOT}/native-parity.json"
mv "${NATIVE_STAGE}/narfs-static-ndk-build.json" "${OUTPUT_ROOT}/narfs-static-ndk-build.json"
mv "${NATIVE_STAGE}/narfs-static-cmake.json" "${OUTPUT_ROOT}/narfs-static-cmake.json"
mv "${NATIVE_STAGE}/narfs-static-parity.json" "${OUTPUT_ROOT}/narfs-static-parity.json"
mv "${NATIVE_STAGE}/Nanidroid-debug.json" "${APK_REPORT}"
mv "${NATIVE_STAGE}/Nanidroid-debug.apk" "${OUTPUT_ROOT}/Nanidroid-debug.apk"

rmdir "${NATIVE_STAGE}"
trap - EXIT

echo "legacy and CMake-parity artifacts:"
find "${OUTPUT_ROOT}" -maxdepth 3 -type f -printf '  %P\n' | sort
echo "Native CMake candidate matches the frozen ndk-build contract:"
cat "${OUTPUT_ROOT}/native-parity.json"
