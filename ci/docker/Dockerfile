FROM ghcr.io/cirruslabs/android-sdk:36-ndk

ARG GRADLE_VERSION=8.8
ARG NODE_VERSION=24.13.0

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        curl \
        python3 \
        ninja-build \
        make \
        g++ \
        xz-utils \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz" -o /tmp/node.tar.xz \
    && tar -xJf /tmp/node.tar.xz -C /opt \
    && ln -s "/opt/node-v${NODE_VERSION}-linux-x64" /opt/node \
    && rm /tmp/node.tar.xz

ENV PATH="/opt/node/bin:${PATH}"

RUN curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt/gradle \
    && rm /tmp/gradle.zip

ENV PATH="/opt/gradle/gradle-${GRADLE_VERSION}/bin:${PATH}"

RUN yes | sdkmanager --licenses > /dev/null 2>&1 || true \
    && sdkmanager "build-tools;34.0.0"

WORKDIR /workspace
