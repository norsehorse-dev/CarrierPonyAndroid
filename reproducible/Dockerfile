# syntax=docker/dockerfile:1
# Reproducible-build harness for CarrierPony (foss flavor).
#
# Mirrors F-Droid's buildserver closely enough to produce a byte-identical foss
# release APK: Ubuntu, Temurin JDK 17, Android SDK (platform 36 + build-tools
# 36.0.0). CarrierPony is pure Kotlin/JVM, so there is no NDK, Rust or cargo
# here, unlike the PassPony/VaultPony harnesses.
#
# Build and run as linux/amd64 to match F-Droid's amd64 buildserver (Rosetta
# handles it on Apple Silicon).
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl wget unzip git ca-certificates gnupg && \
    mkdir -p /etc/apt/keyrings && \
    wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public \
        | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb noble main" \
        > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && apt-get install -y --no-install-recommends temurin-17-jdk && \
    rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk

# Android SDK. CMDLINE_TOOLS_VERSION only installs packages, it does not affect
# output bytes; bump it to the current build from developer.android.com if
# sdkmanager cannot see platform 36. BUILD_TOOLS/PLATFORM must match what AGP
# 9.2.1 (and therefore F-Droid) resolves; confirm against F-Droid's build log
# if the reproducibility comparison ever flags a resources/dex diff.
ARG CMDLINE_TOOLS_VERSION=13114758
ARG BUILD_TOOLS_VERSION=36.0.0
ARG PLATFORM=android-36
RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools && \
    curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" -o /tmp/cmdtools.zip && \
    unzip -q /tmp/cmdtools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools && \
    mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest && \
    rm /tmp/cmdtools.zip
ENV PATH="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${PATH}"
RUN yes | sdkmanager --licenses >/dev/null && \
    sdkmanager --install "platform-tools" "platforms;${PLATFORM}" "build-tools;${BUILD_TOOLS_VERSION}" >/dev/null

WORKDIR /build
COPY build.sh /usr/local/bin/build.sh
RUN chmod +x /usr/local/bin/build.sh
ENTRYPOINT ["/usr/local/bin/build.sh"]
