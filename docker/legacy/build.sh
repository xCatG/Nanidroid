#!/usr/bin/env bash
set -euo pipefail

readonly SOURCE_ROOT="${SOURCE_ROOT:-/workspace}"
readonly BUILD_ROOT="${BUILD_ROOT:-/tmp/nanidroid-legacy-build}"
readonly OUTPUT_ROOT="${OUTPUT_ROOT:-/out}"
readonly APK_REPORT="${OUTPUT_ROOT}/Nanidroid-debug.json"

if [[ ! -f "${SOURCE_ROOT}/AndroidManifest.xml" ]]; then
  echo "source root does not contain AndroidManifest.xml: ${SOURCE_ROOT}" >&2
  exit 2
fi

rm -rf "${BUILD_ROOT}"
mkdir -p "${BUILD_ROOT}" "${OUTPUT_ROOT}"

# Build outside the read-only source mount. This prevents Ant, ndk-build, and
# the debug keystore generator from dirtying the checkout on any host OS.
rsync -a \
  --exclude /.git/ \
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
  NDK_TOOLCHAIN_VERSION=4.9

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
  --output "${APK_REPORT}"

rm -rf "${OUTPUT_ROOT}/native"
mkdir -p "${OUTPUT_ROOT}/native/armeabi"
cp "${apk}" "${OUTPUT_ROOT}/Nanidroid-debug.apk"
cp "${BUILD_ROOT}"/libs/armeabi/*.so "${OUTPUT_ROOT}/native/armeabi/"

echo "legacy artifacts:"
find "${OUTPUT_ROOT}" -maxdepth 3 -type f -printf '  %P\n' | sort
