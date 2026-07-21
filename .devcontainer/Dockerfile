FROM debian:bookworm-slim@sha256:7b140f374b289a7c2befc338f42ebe6441b7ea838a042bbd5acbfca6ec875818

ARG USERNAME=vscode
ARG USER_UID=1000
ARG USER_GID=1000
ARG ANDROID_COMMAND_LINE_TOOLS_VERSION=14742923
ARG ANDROID_COMMAND_LINE_TOOLS_SHA1=48833c34b761c10cb20bcd16582129395d121b27
ARG ANDROID_PLATFORM_VERSION=15
ARG ANDROID_BUILD_TOOLS_VERSION=36.0.0

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_BUILD_TOOLS_VERSION=${ANDROID_BUILD_TOOLS_VERSION}
ENV PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        ca-certificates \
        cmake \
        curl \
        file \
        git \
        jq \
        make \
        ninja-build \
        openjdk-17-jdk-headless \
        openssh-client \
        python3 \
        python-is-python3 \
        unzip \
        zip \
    && rm -rf /var/lib/apt/lists/*

# The archive name and digest are pinned to the official Android command-line
# tools download published for Android Studio Quail 2 (2026.1.2). Building this
# image downloads Android SDK tooling; users are responsible for accepting and
# complying with Google's Android SDK License Agreement.
RUN set -eux; \
    archive="/tmp/commandlinetools-linux-${ANDROID_COMMAND_LINE_TOOLS_VERSION}_latest.zip"; \
    curl --fail --location --retry 3 \
      "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_COMMAND_LINE_TOOLS_VERSION}_latest.zip" \
      --output "${archive}"; \
    echo "${ANDROID_COMMAND_LINE_TOOLS_SHA1}  ${archive}" | sha1sum --check -; \
    mkdir -p "${ANDROID_HOME}/cmdline-tools/latest"; \
    unzip -q "${archive}" -d /tmp/android-command-line-tools; \
    mv /tmp/android-command-line-tools/cmdline-tools/* "${ANDROID_HOME}/cmdline-tools/latest/"; \
    rm -rf "${archive}" /tmp/android-command-line-tools

# Building the image accepts the Android SDK licenses non-interactively, then
# installs only the API 15 compile surface used by the frozen Ant build and the
# modern Build Tools required by AGP 9.3.
RUN yes | sdkmanager --licenses >/dev/null \
    && sdkmanager \
        "platforms;android-${ANDROID_PLATFORM_VERSION}" \
        "build-tools;${ANDROID_BUILD_TOOLS_VERSION}" \
        "platform-tools" \
    && rm -rf /root/.android

RUN groupadd --gid "${USER_GID}" "${USERNAME}" \
    && useradd --uid "${USER_UID}" --gid "${USER_GID}" --create-home --shell /bin/bash "${USERNAME}" \
    && mkdir -p /workspaces/Nanidroid /home/"${USERNAME}"/.gradle \
    && chown -R "${USERNAME}:${USERNAME}" /workspaces /home/"${USERNAME}" "${ANDROID_HOME}"

WORKDIR /workspaces/Nanidroid
USER ${USERNAME}

RUN git config --global --add safe.directory /workspaces/Nanidroid \
    && git config --global core.autocrlf input

CMD ["sleep", "infinity"]
