# RvPort AI 迁移执行器镜像
# 独立于 backend/Dockerfile 构建：pi agent + 迁移技能 + qemu-user-static + RISC-V 交叉工具链。
# 构建示例（受限网络）：
#   docker build -t rvport-ai-executor:local -f backend/executor/Dockerfile \
#     --build-arg APT_URI=http://mirrors.internal/ubuntu \
#     --build-arg NPM_REGISTRY=https://registry.npmmirror.com .
# 注意：构建上下文是仓库根（COPY backend/executor/* 需要）。
# 技能不挂载、不联网获取：skills/ 目录随仓库入库，在此复制进镜像；改技能后需重建本镜像。

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
        gcc g++ \
        make cmake \
        python3 python3-pip python3-venv \
    && rm -rf /var/lib/apt/lists/*

# node（tar 直装，同 backend/Dockerfile 模式）
RUN curl -fsSL --retry 5 -o /tmp/node.tar.xz "${NODEJS_MIRROR}/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz" \
    && tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 \
    && rm -f /tmp/node.tar.xz

# pi agent（版本钉死；registry 可换内网；npm 不读 env 代理，走 config）
# 不装 pi-subagents / pi-mcp-adapter：二者要求新版 pi（@earendil-works 1.2+），与当前钉的版本不兼容，
# 且安装时要联网克隆最新代码，构建不可复现。子代理能力待代理升级时另行接入。
ARG NPM_PROXY=""
RUN npm config set registry "${NPM_REGISTRY}" \
    && if [ -n "${NPM_PROXY}" ]; then npm config set proxy "${NPM_PROXY}" && npm config set https-proxy "${NPM_PROXY}"; fi \
    && npm install -g --ignore-scripts "${PI_PACKAGE}" \
    && npm cache clean --force

# wxai provider 扩展（env 引用 key，不落盘）
COPY backend/executor/wxai-provider.mjs /root/.pi/agent/extensions/wxai-provider.mjs

# 迁移技能随源码入库、随镜像发布（不做独立的 git 版本管理，也不挂载技能卷）。
# 目录保持 pi 的技能布局：/root/.pi/agent/skills/<技能名>/SKILL.md。技能目录须可写——其脚本会在自身目录下
# 写运行期文件。来源与许可见 skills/ 下的 LICENSE、NOTICE 与 README。
COPY backend/executor/skills/ /root/.pi/agent/skills/

# 迁移技能的知识库查询脚本（riscv-migrate/scripts/run_query.sh）运行时会执行
# `python3 -m pip install -r requirements-mcp.txt`。Ubuntu 24.04 的系统 Python 受 PEP 668 保护，
# 运行时这一步必然失败（externally-managed-environment）。这里在构建时把依赖直接装好，
# 脚本里的安装就成了空操作，不必在镜像里解除系统 Python 的保护。
# --break-system-packages 只作用于这一次构建期安装；PIP_INDEX_URL 可换内网源。
ARG PIP_INDEX_URL=https://pypi.org/simple
RUN python3 -m pip install --no-cache-dir --break-system-packages \
        -i "${PIP_INDEX_URL}" -r /root/.pi/agent/skills/riscv-migrate/scripts/requirements-mcp.txt

# 入口适配脚本
COPY backend/executor/entrypoint.sh /opt/rvport/entrypoint.sh
RUN chmod +x /opt/rvport/entrypoint.sh \
    && git config --global --add safe.directory '*'

ENV PATH="/usr/local/bin:${PATH}"

# 构建期自检：任一失败中断构建（qemu-user-static 包内为 *-static 命名，做兼容链接）。
# 含技能存在性检查：入口脚本以 --skill 加载 riscv-migrate，缺失则每次迁移都会失败。
RUN ln -sf /usr/lib/binfmt.d /usr/bin/.binfmt-alias 2>/dev/null || true \
    && for q in qemu-riscv64; do ln -sf /usr/bin/${q}-static /usr/bin/${q}; done \
    && pi --version \
    && qemu-riscv64 --version | head -1 \
    && riscv64-linux-gnu-gcc --version | head -1 \
    && make --version | head -1 \
    && cmake --version | head -1 \
    && python3 --version \
    && test -f /root/.pi/agent/skills/riscv-migrate/SKILL.md \
    && test -f /root/.pi/agent/skills/LICENSE \
    && echo 'int main(){return 0;}' > /tmp/t.c \
    && gcc -O2 -o /tmp/t-x86 /tmp/t.c \
    && riscv64-linux-gnu-gcc -static -o /tmp/t-rv /tmp/t.c \
    && qemu-riscv64 /tmp/t-rv \
    && rm -f /tmp/t.c /tmp/t-x86 /tmp/t-rv

WORKDIR /app
ENTRYPOINT ["/opt/rvport/entrypoint.sh"]
