FROM eclipse-temurin:8-jdk-jammy@sha256:4e6409efc7e022f46b4969fb489c12fe2869c49f176829b948874a7c8ebb4b84

ARG ANDROID_SDK_TOOLS_VERSION=25.2.5
ARG ANDROID_SDK_TOOLS_SHA1=72df3aa1988c0a9003ccdfd7a13a7b8bd0f47fc1
ARG ANDROID_BUILD_TOOLS_VERSION=25.0.3
ARG ANDROID_BUILD_TOOLS_SHA1=db95f3a0ae376534d4d69f4cdb6fad20649f3509
ARG ANDROID_PLATFORM_VERSION=15
ARG ANDROID_PLATFORM_REVISION=05
ARG ANDROID_PLATFORM_SHA1=69ab4c443b37184b2883af1fd38cc20cbeffd0f3
ARG ANDROID_PLATFORM_TOOLS_VERSION=29.0.6
ARG ANDROID_PLATFORM_TOOLS_SHA1=e95ed28330406705d47fe96bafb589be6c1f2f23
ARG ANDROID_NDK_VERSION=r14b
ARG ANDROID_NDK_SHA1=becd161da6ed9a823e25be5c02955d9cbca1dbeb
ARG CMAKE_VERSION=3.22.1
ARG CMAKE_SHA256=73565c72355c6652e9db149249af36bcab44d9d478c5546fd926e69ad6b43640

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_NDK_HOME=/opt/android-ndk-r14b
ENV CMAKE_HOME=/opt/cmake-3.22.1
ENV PATH=/opt/cmake-3.22.1/bin:/opt/android-sdk/tools:/opt/android-sdk/build-tools/25.0.3:/opt/android-ndk-r14b:${PATH}

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        ant \
        binutils \
        ca-certificates \
        curl \
        file \
        gcc \
        libc6-dev \
        make \
        python3 \
        rsync \
        unzip \
        zip \
    && rm -rf /var/lib/apt/lists/*

# These obsolete packages are installed directly because the historical SDK
# manager no longer has a stable package index. Every archive is pinned to the
# SHA-1 published in Google's SDK repository metadata.
RUN set -eux; \
    archive=/tmp/sdk-tools.zip; \
    curl --fail --location --retry 3 \
      "https://dl.google.com/android/repository/tools_r${ANDROID_SDK_TOOLS_VERSION}-linux.zip" \
      --output "${archive}"; \
    echo "${ANDROID_SDK_TOOLS_SHA1}  ${archive}" | sha1sum --check -; \
    mkdir -p "${ANDROID_SDK_ROOT}"; \
    unzip -q "${archive}" -d "${ANDROID_SDK_ROOT}"; \
    rm "${archive}"

RUN set -eux; \
    archive=/tmp/build-tools.zip; \
    staging=/tmp/build-tools; \
    curl --fail --location --retry 3 \
      "https://dl.google.com/android/repository/build-tools_r${ANDROID_BUILD_TOOLS_VERSION}-linux.zip" \
      --output "${archive}"; \
    echo "${ANDROID_BUILD_TOOLS_SHA1}  ${archive}" | sha1sum --check -; \
    mkdir -p "${staging}"; \
    unzip -q "${archive}" -d "${staging}"; \
    root="$(find "${staging}" -mindepth 1 -maxdepth 1 -type d -print -quit)"; \
    test -n "${root}"; \
    mkdir -p "${ANDROID_SDK_ROOT}/build-tools/${ANDROID_BUILD_TOOLS_VERSION}"; \
    cp -a "${root}/." "${ANDROID_SDK_ROOT}/build-tools/${ANDROID_BUILD_TOOLS_VERSION}/"; \
    chmod -R a+rX "${ANDROID_SDK_ROOT}/build-tools/${ANDROID_BUILD_TOOLS_VERSION}"; \
    rm -rf "${archive}" "${staging}"

RUN set -eux; \
    archive=/tmp/platform.zip; \
    staging=/tmp/platform; \
    curl --fail --location --retry 3 \
      "https://dl.google.com/android/repository/android-${ANDROID_PLATFORM_VERSION}_r${ANDROID_PLATFORM_REVISION}.zip" \
      --output "${archive}"; \
    echo "${ANDROID_PLATFORM_SHA1}  ${archive}" | sha1sum --check -; \
    mkdir -p "${staging}"; \
    unzip -q "${archive}" -d "${staging}"; \
    root="$(find "${staging}" -mindepth 1 -maxdepth 1 -type d -print -quit)"; \
    test -n "${root}"; \
    mkdir -p "${ANDROID_SDK_ROOT}/platforms/android-${ANDROID_PLATFORM_VERSION}"; \
    cp -a "${root}/." "${ANDROID_SDK_ROOT}/platforms/android-${ANDROID_PLATFORM_VERSION}/"; \
    chmod -R a+rX "${ANDROID_SDK_ROOT}/platforms/android-${ANDROID_PLATFORM_VERSION}"; \
    rm -rf "${archive}" "${staging}"

# NDK r14 is the last generation documented to support API 9, and it retains
# the GNU STL required by jni/Application.mk.
RUN set -eux; \
    archive=/tmp/android-ndk.zip; \
    curl --fail --location --retry 3 \
      "https://dl.google.com/android/repository/android-ndk-${ANDROID_NDK_VERSION}-linux-x86_64.zip" \
      --output "${archive}"; \
    echo "${ANDROID_NDK_SHA1}  ${archive}" | sha1sum --check -; \
    unzip -q "${archive}" -d /opt; \
    rm "${archive}"

RUN set -eux; \
    archive=/tmp/cmake.tar.gz; \
    curl --fail --location --retry 3 \
      "https://github.com/Kitware/CMake/releases/download/v${CMAKE_VERSION}/cmake-${CMAKE_VERSION}-linux-x86_64.tar.gz" \
      --output "${archive}"; \
    echo "${CMAKE_SHA256}  ${archive}" | sha256sum --check -; \
    mkdir -p "${CMAKE_HOME}"; \
    tar -xzf "${archive}" --strip-components=1 -C "${CMAKE_HOME}"; \
    rm "${archive}"

RUN set -eux; \
    archive=/tmp/platform-tools.zip; \
    curl --fail --location --retry 3 \
      "https://dl.google.com/android/repository/platform-tools_r${ANDROID_PLATFORM_TOOLS_VERSION}-linux.zip" \
      --output "${archive}"; \
    echo "${ANDROID_PLATFORM_TOOLS_SHA1}  ${archive}" | sha1sum --check -; \
    unzip -q "${archive}" -d "${ANDROID_SDK_ROOT}"; \
    chmod -R a+rX "${ANDROID_SDK_ROOT}/platform-tools"; \
    rm "${archive}"

COPY docker/legacy/build.sh /usr/local/bin/build-nanidroid-legacy
RUN chmod 0755 /usr/local/bin/build-nanidroid-legacy \
    && java -version \
    && ant -version \
    && cmake --version \
    && "${ANDROID_NDK_HOME}/ndk-build" --version \
    && "${ANDROID_SDK_ROOT}/build-tools/${ANDROID_BUILD_TOOLS_VERSION}/aapt" version

WORKDIR /workspace
ENTRYPOINT ["/usr/local/bin/build-nanidroid-legacy"]
