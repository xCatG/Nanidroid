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
readonly NARFS_CMAKE_BUILD_ROOT="${BUILD_ROOT}/narfs-cmake-build"
readonly NARFS_NDK_LIBS_ROOT="${BUILD_ROOT}/libs-narfs"
readonly NDK_BUILD_LOG="${BUILD_ROOT}/ndk-build.log"
readonly NARFS_NDK_BUILD_LOG="${BUILD_ROOT}/narfs-ndk-build.log"
readonly STATIC_INSPECTOR="${BUILD_ROOT}/tools/inspect_narfs_static.py"
readonly NARFS_INSPECTOR="${BUILD_ROOT}/tools/inspect_narfs_jni.py"
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
  "${OUTPUT_ROOT}/narfs-jni-ndk-build.json" \
  "${OUTPUT_ROOT}/narfs-jni-cmake.json" \
  "${OUTPUT_ROOT}/narfs-jni-parity.json" \
  "${OUTPUT_ROOT}/Nanidroid-debug-native-payload.json" \
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

# Ant is the frozen Java-only reference lane. Gradle compiles modern Kotlin
# from src, while this isolated build copy restores any migrated Java sources
# without adding them back to the Gradle production source set.
readonly LEGACY_JAVA_ROOT="${BUILD_ROOT}/legacy/src"
if [[ ! -d "${LEGACY_JAVA_ROOT}" ]]; then
  echo "missing frozen Java source overlay: ${LEGACY_JAVA_ROOT}" >&2
  exit 2
fi
rsync -a "${LEGACY_JAVA_ROOT}/" "${BUILD_ROOT}/src/"

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

# API-15 aapt cannot parse foregroundServiceType. This transform is confined
# to the disposable Ant copy; the production Gradle manifest keeps dataSync.
sed -i \
  's/ android:foregroundServiceType="dataSync"//g' \
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

# Build the reviewed full-profile C-only JNI module in isolation.
# TARGET_CXX is process-scoped so the existing C++ engine build is unchanged.
"${ANDROID_NDK_HOME}/ndk-build" \
  'TARGET_CXX=$(TARGET_CC)' \
  NDK_PROJECT_PATH="${BUILD_ROOT}" \
  NDK_OUT="${BUILD_ROOT}/obj-narfs" \
  NDK_LIBS_OUT="${NARFS_NDK_LIBS_ROOT}" \
  APP_BUILD_SCRIPT="${BUILD_ROOT}/jni/narfs/Android.mk" \
  NDK_APPLICATION_MK="${BUILD_ROOT}/jni/Application.mk" \
  APP_MODULES=narfs_full APP_PLATFORM=android-9 APP_ABI=armeabi \
  NDK_TOOLCHAIN_VERSION=4.9 \
  NANIDROID_NARFS_FULL_JNI_CANDIDATE=1 \
  NANIDROID_NARFS_STAGE_CANDIDATE=1 \
  NANIDROID_NARFS_SHA256_CANDIDATE=1 \
  V=1 2>&1 | tee "${NARFS_NDK_BUILD_LOG}"

python3 "${NARFS_INSPECTOR}" inspect \
  --dso "${BUILD_ROOT}/obj-narfs/local/${NATIVE_ABI}/libnarfs.so" \
  --readelf "${READELF}" --evidence "${NARFS_NDK_BUILD_LOG}" \
  --abi "${NATIVE_ABI}" --api "${NATIVE_API}" --build-system ndk-build \
  --profile full \
  --output "${NATIVE_STAGE}/narfs-jni-ndk-build.json"

# Ant clean removes libs/, so clean before publishing the inspected bytes.
ant clean
cp "${BUILD_ROOT}/obj-narfs/local/${NATIVE_ABI}/libnarfs.so" \
  "${BUILD_ROOT}/libs/${NATIVE_ABI}/"

# Keep the Ant reference APK on the inspected ndk-build output.
ant debug

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

cmake \
  -S "${BUILD_ROOT}/jni/narfs" \
  -B "${NARFS_CMAKE_BUILD_ROOT}" \
  -G "Unix Makefiles" \
  -DCMAKE_BUILD_TYPE= \
  -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \
  -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="${NARFS_CMAKE_BUILD_ROOT}/native/${NATIVE_ABI}" \
  -DCMAKE_ARCHIVE_OUTPUT_DIRECTORY="${NARFS_CMAKE_BUILD_ROOT}/static/${NATIVE_ABI}" \
  -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" \
  -DANDROID_NDK="${ANDROID_NDK_HOME}" \
  -DANDROID_TOOLCHAIN=gcc \
  -DANDROID_ABI="${NATIVE_ABI}" \
  -DANDROID_PLATFORM="${NATIVE_API}" \
  -DANDROID_STL="${NATIVE_STL}" \
  -DANDROID_ARM_MODE="${NATIVE_ARM_MODE}" \
  -DNANIDROID_BUILD_NARFS_FULL_JNI_CANDIDATE=ON \
  -DNANIDROID_BUILD_NARFS_STAGE_CANDIDATE=ON \
  -DNANIDROID_BUILD_NARFS_SHA256_CANDIDATE=ON
cmake --build "${NARFS_CMAKE_BUILD_ROOT}" --target narfs_full -- VERBOSE=1

# Match the frozen ndk-build engine artifacts without stripping the standalone
# narfs candidate: its local symbols are part of the inspection evidence.
"${STRIP}" --strip-unneeded \
  "${CMAKE_BUILD_ROOT}"/native/"${NATIVE_ABI}"/libkawari8.so \
  "${CMAKE_BUILD_ROOT}"/native/"${NATIVE_ABI}"/libsatoriya.so

mkdir -p \
  "${STAGE_NDK_NATIVE_ROOT}/${NATIVE_ABI}" \
  "${STAGE_CMAKE_NATIVE_ROOT}/${NATIVE_ABI}"
cp \
  "${BUILD_ROOT}/libs/${NATIVE_ABI}/libkawari8.so" \
  "${BUILD_ROOT}/libs/${NATIVE_ABI}/libsatoriya.so" \
  "${STAGE_NDK_NATIVE_ROOT}/${NATIVE_ABI}/"
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
  --probe "${BUILD_ROOT}/obj/local/${NATIVE_ABI}/narfs_core_link_probe" \
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

# Inspect the exact DSOs that are promoted and packaged.
python3 "${NARFS_INSPECTOR}" inspect \
  --dso "${NARFS_CMAKE_BUILD_ROOT}/native/${NATIVE_ABI}/libnarfs.so" \
  --readelf "${READELF}" --evidence "${NARFS_CMAKE_BUILD_ROOT}" \
  --abi "${NATIVE_ABI}" --api "${NATIVE_API}" --build-system cmake \
  --profile full \
  --output "${NATIVE_STAGE}/narfs-jni-cmake.json"
python3 "${NARFS_INSPECTOR}" compare \
  "${NATIVE_STAGE}/narfs-jni-ndk-build.json" \
  "${NATIVE_STAGE}/narfs-jni-cmake.json" \
  --output "${NATIVE_STAGE}/narfs-jni-parity.json"

# Add only the inspected DSO bytes, then atomically promote all native outputs.
cp "${BUILD_ROOT}/obj-narfs/local/${NATIVE_ABI}/libnarfs.so" \
  "${STAGE_NDK_NATIVE_ROOT}/${NATIVE_ABI}/"
cp "${NARFS_CMAKE_BUILD_ROOT}/native/${NATIVE_ABI}/libnarfs.so" \
  "${STAGE_CMAKE_NATIVE_ROOT}/${NATIVE_ABI}/"
python3 "${BUILD_ROOT}/tools/verify_apk_native_payload.py" \
  "${apk}" \
  --candidate-root "${STAGE_NDK_NATIVE_ROOT}" \
  --output "${NATIVE_STAGE}/Nanidroid-debug-native-payload.json"
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
mv "${NATIVE_STAGE}/narfs-jni-ndk-build.json" "${OUTPUT_ROOT}/narfs-jni-ndk-build.json"
mv "${NATIVE_STAGE}/narfs-jni-cmake.json" "${OUTPUT_ROOT}/narfs-jni-cmake.json"
mv "${NATIVE_STAGE}/narfs-jni-parity.json" "${OUTPUT_ROOT}/narfs-jni-parity.json"
mv "${NATIVE_STAGE}/Nanidroid-debug-native-payload.json" "${OUTPUT_ROOT}/Nanidroid-debug-native-payload.json"
mv "${NATIVE_STAGE}/Nanidroid-debug.json" "${APK_REPORT}"
mv "${NATIVE_STAGE}/Nanidroid-debug.apk" "${OUTPUT_ROOT}/Nanidroid-debug.apk"

rmdir "${NATIVE_STAGE}"
trap - EXIT

echo "legacy and CMake-parity artifacts:"
find "${OUTPUT_ROOT}" -maxdepth 3 -type f -printf '  %P\n' | sort
echo "Native CMake candidate matches the frozen ndk-build contract:"
cat "${OUTPUT_ROOT}/native-parity.json"
