ARG JAVA_IMAGE=eclipse-temurin:25-jdk-noble
ARG UBUNTU_IMAGE=ubuntu:noble
FROM ${JAVA_IMAGE} AS build-base

WORKDIR /workspace/backend

RUN apt-get update \
    && apt-get install -y --no-install-recommends maven ca-certificates \
    && rm -rf /var/lib/apt/lists/*

FROM build-base AS build
COPY backend/pom.xml ./pom.xml
COPY backend/src ./src
COPY backend/agent-cli ./agent-cli
ENV MAVEN_OPTS=-Xmx1536m

# 可通过构建参数切换 Maven 下载入口，默认使用官方仓库。
ARG MAVEN_MIRROR_URL=https://repo.maven.apache.org/maven2
RUN --mount=type=cache,target=/root/.m2 \
    mkdir -p /root/.m2 && \
    printf '<settings><mirrors><mirror><id>build-mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>\n' "${MAVEN_MIRROR_URL}" > /root/.m2/settings.xml && \
    mvn -B -DskipTests package

FROM build-base AS unit-deps
RUN apt-get update && apt-get install -y --no-install-recommends python3 python-is-python3 \
    python3-coverage python3-pytest python3-jsonschema python3-yaml \
    && rm -rf /var/lib/apt/lists/*

FROM unit-deps AS unit-test
ENV MAVEN_OPTS=-Xmx1536m
COPY --from=build /workspace/backend /workspace/backend
COPY tools/kb-scanner /workspace/tools/kb-scanner
COPY tools/memory-order-analyzer/python /workspace/tools/memory-order-analyzer/python
COPY tools/memory-order-analyzer/schemas /workspace/tools/memory-order-analyzer/schemas
COPY tools/memory-order-analyzer/protocols /workspace/tools/memory-order-analyzer/protocols
COPY tools/memory-order-analyzer/*.json /workspace/tools/memory-order-analyzer/
RUN --mount=type=cache,target=/root/.m2 cp -a /root/.m2 /opt/maven-cache
CMD ["mvn", "-B", "-s", "/opt/maven-cache/settings.xml", "-Dmaven.repo.local=/opt/maven-cache/repository", "clean", "verify"]

FROM ${UBUNTU_IMAGE} AS bitcode-toolchain
ARG BITCODE_LLVM_VERSION=21.1.8
ARG BITCODE_LLVM_ARCHIVE=LLVM-21.1.8-Linux-X64.tar.xz
ARG BITCODE_LLVM_SOURCE_SHA256=b3b7f2801d15d50736acea3c73982994d025b01c2f035b91ae3b49d1b575732b
ARG BITCODE_LLVM_BUNDLE_SHA256=b456e58a78fc48d9f0cf3d767762840baf0346806f71d399d1dfffff58c5b489
ARG BITCODE_LLVM_BASE_URL=https://github.com/llvm/llvm-project/releases/download/llvmorg-21.1.8

RUN apt-get update \
    && apt-get install -y --no-install-recommends aria2 ca-certificates xz-utils \
    && rm -rf /var/lib/apt/lists/*

RUN --mount=type=cache,target=/var/cache/rvport-toolchains \
    mkdir -p /opt/rvport/toolchains \
    && aria2c --allow-overwrite=true --auto-file-renaming=false --continue=true \
        --max-connection-per-server=16 --min-split-size=16M --split=16 \
        --connect-timeout=30 --timeout=60 --lowest-speed-limit=64K --max-tries=20 --retry-wait=2 \
        --dir=/var/cache/rvport-toolchains --out="${BITCODE_LLVM_ARCHIVE}" \
        "${BITCODE_LLVM_BASE_URL}/${BITCODE_LLVM_ARCHIVE}" \
    && echo "${BITCODE_LLVM_SOURCE_SHA256}  /var/cache/rvport-toolchains/${BITCODE_LLVM_ARCHIVE}" | sha256sum -c - \
    && mkdir -p /tmp/llvm-minimal \
    && tar -xJf "/var/cache/rvport-toolchains/${BITCODE_LLVM_ARCHIVE}" -C /tmp/llvm-minimal \
        "LLVM-${BITCODE_LLVM_VERSION}-Linux-X64/bin/clang" \
        "LLVM-${BITCODE_LLVM_VERSION}-Linux-X64/bin/clang++" \
        "LLVM-${BITCODE_LLVM_VERSION}-Linux-X64/bin/clang-21" \
        "LLVM-${BITCODE_LLVM_VERSION}-Linux-X64/bin/llvm-dis" \
        "LLVM-${BITCODE_LLVM_VERSION}-Linux-X64/lib/clang/21" \
    && tar --sort=name --mtime='UTC 2025-12-16' --owner=0 --group=0 --numeric-owner \
        -cJf "/opt/rvport/toolchains/${BITCODE_LLVM_ARCHIVE}" \
        -C /tmp/llvm-minimal "LLVM-${BITCODE_LLVM_VERSION}-Linux-X64" \
    && echo "${BITCODE_LLVM_BUNDLE_SHA256}  /opt/rvport/toolchains/${BITCODE_LLVM_ARCHIVE}" | sha256sum -c - \
    && rm -rf /tmp/llvm-minimal \
    && printf 'toolchainId=llvm-%s-linux-x64\nversion=%s\narchive=%s\nsha256=%s\nsourceSha256=%s\n' \
        "${BITCODE_LLVM_VERSION}" "${BITCODE_LLVM_VERSION}" "${BITCODE_LLVM_ARCHIVE}" \
        "${BITCODE_LLVM_BUNDLE_SHA256}" "${BITCODE_LLVM_SOURCE_SHA256}" \
        > /opt/rvport/toolchains/llvm-21.1.8-linux-x64.manifest

FROM ${UBUNTU_IMAGE} AS specialized-analysis-tools
ARG LLVM_DEB_VERSION=1:21.1.8~++20251221032922+2078da43e25a-1~exp1~20251221153059.70

RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl python3 python3-venv \
       cmake ninja-build g++ libgtest-dev zlib1g-dev libzstd-dev libedit-dev libcurl4-openssl-dev libxml2-dev \
    && curl --fail --show-error --location --max-time 60 https://apt.llvm.org/llvm-snapshot.gpg.key -o /usr/share/keyrings/llvm.asc \
    && echo '8b2a587ffd672c4687e7581dad4b2f6c1bb2ad6b480cd9771ba2ff48e0b8c75d  /usr/share/keyrings/llvm.asc' | sha256sum -c - \
    && echo 'deb [signed-by=/usr/share/keyrings/llvm.asc] https://apt.llvm.org/noble/ llvm-toolchain-noble-21 main' > /etc/apt/sources.list.d/llvm.list \
    && apt-get update && apt-get install -y --no-install-recommends \
       clang-21=${LLVM_DEB_VERSION} llvm-21=${LLVM_DEB_VERSION} llvm-21-dev=${LLVM_DEB_VERSION} llvm-21-tools=${LLVM_DEB_VERSION} \
       libclang-21-dev=${LLVM_DEB_VERSION} libclang-rt-21-dev=${LLVM_DEB_VERSION} \
    && rm -rf /var/lib/apt/lists/*

COPY tools/floating-point-precision-scanner /workspace/floating-point-precision-scanner
COPY tools/memory-order-analyzer /workspace/memory-order-analyzer

RUN python3 -m venv /opt/specialized-analysis-venv \
    && /opt/specialized-analysis-venv/bin/pip install --no-cache-dir --timeout 120 --retries 10 \
       setuptools==75.8.0 wheel==0.45.1 jsonschema==4.23.0 \
    && /opt/specialized-analysis-venv/bin/pip install --no-cache-dir --no-deps --no-build-isolation \
       /workspace/floating-point-precision-scanner \
    && cp -a /workspace/memory-order-analyzer/python/memory_order_analyzer /opt/specialized-analysis-venv/lib/python3.12/site-packages/ \
    && cp -a /workspace/memory-order-analyzer/schemas \
       /workspace/memory-order-analyzer/protocols /workspace/memory-order-analyzer/models \
       /workspace/memory-order-analyzer/config /workspace/memory-order-analyzer/cpp-thread-profiles \
       /workspace/memory-order-analyzer/cpp-raii-profiles /opt/specialized-analysis-venv/lib/python3.12/ \
    && cmake -S /workspace/floating-point-precision-scanner -B /tmp/fp-build -G Ninja \
       -DCMAKE_C_COMPILER=clang-21 -DCMAKE_CXX_COMPILER=clang++-21 \
       -DLLVM_DIR=/usr/lib/llvm-21/lib/cmake/llvm -DFP_SCANNER_BUILD_TESTS=OFF -DFP_SCANNER_COVERAGE=OFF \
    && cmake --build /tmp/fp-build --target fp-ir-worker --parallel 2 \
    && cmake -S /workspace/memory-order-analyzer -B /tmp/moa-build -G Ninja \
       -DCMAKE_C_COMPILER=clang-21 -DCMAKE_CXX_COMPILER=clang++-21 \
       -DLLVM_DIR=/usr/lib/llvm-21/lib/cmake/llvm -DMOA_COVERAGE=OFF \
    && cmake --build /tmp/moa-build --target moa-worker memory-order-source-worker --parallel 2 \
    && mkdir -p /opt/rvport/analysis-tools/bin \
    && cp /tmp/fp-build/fp-ir-worker /tmp/moa-build/moa-worker /tmp/moa-build/memory-order-source-worker /opt/rvport/analysis-tools/bin/ \
    && /opt/specialized-analysis-venv/bin/fp-precision-scanner version >/tmp/fp-version.json \
    && /opt/rvport/analysis-tools/bin/fp-ir-worker --version-json >/tmp/fp-worker-version.json \
    && /opt/specialized-analysis-venv/bin/python -m memory_order_analyzer doctor \
       --worker /opt/rvport/analysis-tools/bin/moa-worker >/tmp/moa-doctor.json \
    && /opt/rvport/analysis-tools/bin/moa-worker --protocol >/tmp/moa-worker-protocol.json \
    && /opt/rvport/analysis-tools/bin/memory-order-source-worker --source-analysis-protocol \
       >/tmp/moa-source-protocol.json

FROM ${JAVA_IMAGE}
ARG NODE_VERSION=22.23.2
ARG NODE_DISTRO=linux-x64
ARG NPM_VERSION=10.9.8
ARG LLVM_PACKAGE_VERSION=1:18.1.3-1ubuntu1
ARG LLVM21_DEB_VERSION=1:21.1.8~++20251221032922+2078da43e25a-1~exp1~20251221153059.70
ARG NODEJS_MIRROR=https://npmmirror.com/mirrors/node
ARG RVPORT_AGENT_CODEX_EXECUTABLE=codex
ARG RVPORT_AGENT_DEEPSEEK_HARNESS_EXECUTABLE=dsh
ARG RVPORT_AGENT_OPENCODE_EXECUTABLE=opencode
ENV KB_SCANNER_VENV=/opt/kb-scanner-venv
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
ENV OPENCODE_DISABLE_AUTOUPDATE=true
WORKDIR /app/backend

RUN --mount=type=cache,target=/root/.npm \
    apt-get update \
    && apt-get install -y --no-install-recommends \
        python3 \
        python3-clang-18=${LLVM_PACKAGE_VERSION} \
        python3-venv \
        python3-yaml \
        clang-18=${LLVM_PACKAGE_VERSION} \
        libclang-18-dev=${LLVM_PACKAGE_VERSION} \
        git \
        ca-certificates \
        curl \
        xz-utils \
    && rm -rf /var/lib/apt/lists/*

RUN curl --fail --show-error --location --max-time 60 https://apt.llvm.org/llvm-snapshot.gpg.key -o /usr/share/keyrings/llvm.asc \
    && echo '8b2a587ffd672c4687e7581dad4b2f6c1bb2ad6b480cd9771ba2ff48e0b8c75d  /usr/share/keyrings/llvm.asc' | sha256sum -c - \
    && echo 'deb [signed-by=/usr/share/keyrings/llvm.asc] https://apt.llvm.org/noble/ llvm-toolchain-noble-21 main' > /etc/apt/sources.list.d/llvm.list \
    && apt-get update && apt-get install -y --no-install-recommends \
       clang-21=${LLVM21_DEB_VERSION} llvm-21=${LLVM21_DEB_VERSION} libclang-cpp21=${LLVM21_DEB_VERSION} libllvm21=${LLVM21_DEB_VERSION} \
    && rm -rf /var/lib/apt/lists/*

RUN --mount=type=cache,target=/root/.npm \
    download_with_resume() { \
        attempts=0; \
        until curl -fL -C - --connect-timeout 30 --speed-time 30 --speed-limit 1024 \
            "$1" -o "$2"; do \
            attempts=$((attempts + 1)); \
            if [ "$attempts" -ge 10 ]; then return 1; fi; \
            sleep 2; \
        done; \
    } \
    && node_archive=/root/.npm/node-${NODE_VERSION}-${NODE_DISTRO}.tar.xz \
    && attempts=0 \
    && until download_with_resume \
            "${NODEJS_MIRROR}/v${NODE_VERSION}/node-v${NODE_VERSION}-${NODE_DISTRO}.tar.xz" \
            "$node_archive" \
        && xz -t "$node_archive"; do \
            rm -f "$node_archive"; \
            attempts=$((attempts + 1)); \
            if [ "$attempts" -ge 3 ]; then exit 1; fi; \
        done \
    && tar -xJf "$node_archive" -C /usr/local --strip-components=1 \
    && test "$(node --version)" = "v${NODE_VERSION}" \
    && test "$(npm --version)" = "${NPM_VERSION}"

COPY backend/agent-cli /opt/rvport/agent-cli
RUN --mount=type=cache,target=/root/.npm \
    cd /opt/rvport/agent-cli \
    && NODE_OPTIONS=--max-old-space-size=4096 npm ci --engine-strict --no-audit --no-fund \
       --registry=https://registry.npmjs.org --fetch-timeout=300000 --fetch-retries=3 \
    && python3 verify_lock.py /opt/rvport/agent-cli > installed-manifest.json \
    && ln -s /opt/rvport/agent-cli/node_modules/.bin/codex /usr/local/bin/codex \
    && ln -s /opt/rvport/agent-cli/node_modules/.bin/dsh /usr/local/bin/dsh \
    && ln -s /opt/rvport/agent-cli/node_modules/.bin/opencode /usr/local/bin/opencode \
    && codex --version && dsh --version && opencode --version

# 单独冻结代理依赖，避免改变 DSH 的依赖解析；只由 worker 按执行启动。
COPY backend/agent-cli/opencodex /opt/rvport/opencodex
RUN --mount=type=cache,target=/root/.npm \
    cd /opt/rvport/opencodex \
    && npm ci --engine-strict --no-audit --no-fund --registry=https://registry.npmjs.org \
       --fetch-timeout=300000 --fetch-retries=3 \
    && python3 /opt/rvport/agent-cli/verify_lock.py /opt/rvport/opencodex > installed-manifest.json \
    && python3 patch_eof.py /opt/rvport/opencodex > applied-patch.json \
    && test "$(node_modules/.bin/ocx --version)" = "opencodex 2.94.0" \
    && test "$(node_modules/.bin/bun --version)" = "1.4.4"
# LLVM 21 仅作为上传到远端 source/x86 环境的受管 bundle；KB Scanner 仍使用系统 Clang 18。
COPY --from=bitcode-toolchain /opt/rvport/toolchains /opt/rvport/toolchains
COPY --from=specialized-analysis-tools /opt/specialized-analysis-venv /opt/specialized-analysis-venv
COPY --from=specialized-analysis-tools /opt/rvport/analysis-tools /opt/rvport/analysis-tools

# tree-sitter-cmake 从源码构建，需要 Python 头文件；独立层复用上方工具安装缓存。
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3-dev \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/backend/target/rvport-backend-0.1.0-SNAPSHOT.jar /app/backend/rvport-backend.jar
COPY tools/kb-scanner /app/tools/kb-scanner

ARG KB_SCANNER_PIP_INDEX_URL=https://pypi.org/simple
RUN python3 -m venv --system-site-packages "${KB_SCANNER_VENV}" \
    && CC=clang-18 PIP_INDEX_URL="${KB_SCANNER_PIP_INDEX_URL}" PIP_DEFAULT_TIMEOUT=120 PIP_RETRIES=10 "${KB_SCANNER_VENV}/bin/python" -m pip install \
        --no-cache-dir \
        --timeout 120 --retries 10 \
        --requirement /app/tools/kb-scanner/requirements/runtime.lock

ENV RVPORT_KB_SCAN_PYTHON_COMMAND=/opt/kb-scanner-venv/bin/python
ENV RVPORT_AGENT_CODEX_EXECUTABLE=${RVPORT_AGENT_CODEX_EXECUTABLE}
ENV RVPORT_AGENT_DEEPSEEK_HARNESS_EXECUTABLE=${RVPORT_AGENT_DEEPSEEK_HARNESS_EXECUTABLE}
ENV RVPORT_AGENT_OPENCODE_EXECUTABLE=${RVPORT_AGENT_OPENCODE_EXECUTABLE}
ENV RVPORT_BITCODE_BUNDLE_PATH=/opt/rvport/toolchains/LLVM-21.1.8-Linux-X64.tar.xz
ENV RVPORT_BITCODE_BUNDLE_SHA256=b456e58a78fc48d9f0cf3d767762840baf0346806f71d399d1dfffff58c5b489
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/backend/rvport-backend.jar"]

ENV PYTHONPATH=/app/tools/kb-scanner
