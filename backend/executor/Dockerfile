# RvPort AI 迁移执行器镜像
# 独立于 backend/Dockerfile 构建：pi agent + qemu-user-static + RISC-V 交叉工具链。
# 构建示例（受限网络）：
#   docker build -t rvport-ai-executor:local -f backend/executor/Dockerfile \
#     --build-arg APT_URI=http://mirrors.internal/ubuntu \
#     --build-arg NPM_REGISTRY=https://registry.npmmirror.com .
# 注意：构建上下文是仓库根（COPY backend/executor/* 需要）。

ARG UBUNTU_IMAGE=ubuntu:noble
FROM ${UBUNTU_IMAGE}

# apt 源模板化（受限网络可换内网镜像）
ARG APT_URI=http://archive.ubuntu.com/ubuntu
ARG NODEJS_MIRROR=https://npmmirror.com/mirrors/node
ARG NODE_VERSION=22.23.2
ARG NPM_REGISTRY=https://registry.npmjs.com
ARG PI_PACKAGE=@mariozechner/pi-coding-agent@0.73.0

RUN echo "Types: deb" > /etc/apt/sources.list.d/rvport.sources \
    && echo "URIs: ${APT_URI}" >> /etc/apt/sources.list.d/rvport.sources \
    && echo "Suites: noble noble-updates noble-backports" >> /etc/apt/sources.list.d/rvport.sources \
    && echo "Components: main universe restricted multiverse" >> /etc/apt/sources.list.d/rvport.sources \
    && echo "Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg" >> /etc/apt/sources.list.d/rvport.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl git xz-utils \
        qemu-user-static \
        gcc-riscv64-linux-gnu g++-riscv64-linux-gnu \
    && rm -rf /var/lib/apt/lists/*

# node（tar 直装，同 backend/Dockerfile 模式）
RUN curl -fsSL --retry 5 -o /tmp/node.tar.xz "${NODEJS_MIRROR}/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz" \
    && tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 \
    && rm -f /tmp/node.tar.xz

# pi agent 与扩展（版本钉死；registry 可换内网；npm 不读 env 代理，走 config）
ARG NPM_PROXY=""
RUN npm config set registry "${NPM_REGISTRY}" \
    && if [ -n "${NPM_PROXY}" ]; then npm config set proxy "${NPM_PROXY}" && npm config set https-proxy "${NPM_PROXY}"; fi \
    && npm install -g --ignore-scripts "${PI_PACKAGE}" \
    && npm install -g --ignore-scripts npm:pi-subagents@latest npm:pi-mcp-adapter@latest \
    && npm cache clean --force

# wxai provider 扩展（env 引用 key，不落盘）
COPY backend/executor/wxai-provider.mjs /root/.pi/agent/extensions/wxai-provider.mjs

# 入口适配脚本
COPY backend/executor/entrypoint.sh /opt/rvport/entrypoint.sh
RUN chmod +x /opt/rvport/entrypoint.sh \
    && git config --global --add safe.directory '*'

ENV PATH="/usr/local/bin:${PATH}"

# 构建期自检：任一失败中断构建（qemu-user-static 包内为 *-static 命名，做兼容链接）
RUN ln -sf /usr/lib/binfmt.d /usr/bin/.binfmt-alias 2>/dev/null || true \
    && for q in qemu-riscv64; do ln -sf /usr/bin/${q}-static /usr/bin/${q}; done \
    && pi --version \
    && qemu-riscv64 --version | head -1 \
    && riscv64-linux-gnu-gcc --version | head -1 \
    && echo 'int main(){return 0;}' > /tmp/t.c \
    && riscv64-linux-gnu-gcc -static -o /tmp/t /tmp/t.c \
    && qemu-riscv64 /tmp/t \
    && rm -f /tmp/t /tmp/t.c

WORKDIR /app
ENTRYPOINT ["/opt/rvport/entrypoint.sh"]
