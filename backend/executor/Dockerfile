# RvPort AI 迁移执行器镜像
# 独立于 backend/Dockerfile 构建：pi agent + 迁移技能 + qemu-user-static + RISC-V 交叉工具链。
# 构建示例（受限网络）：
#   docker build -t rvport-ai-executor:local -f backend/executor/Dockerfile \
#     --build-arg APT_URI=http://mirrors.internal/ubuntu \
#     --build-arg NPM_REGISTRY=https://registry.npmmirror.com .
# 注意：构建上下文是仓库根（COPY backend/executor/* 需要）。
# 技能构建期经 git clone 获取（SKILLS_* 构建参数）；改技能或换版本时钉新 SHA 重建本镜像。

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

# 迁移技能构建期经 git 获取（不再随源码入库）：浅克隆默认分支最新提交 → 去 .git 与运行产物。
# SKILLS_* 模板化（内网可换镜像仓库）：SKILLS_REVISION 缺省取远端默认分支最新（每次重建拉新代码）；
# 也可显式传分支名或完整 SHA——传 SHA 时等值校验（上游丢提交/改写即构建失败），传分支名取该分支最新。
# clone 走 HTTP(S) 代理（git 不读 env 的注入需要显式 http.proxy；无代理环境留空即直连）。
# 技能目录须可写——脚本会在自身目录下写运行期文件。来源与许可见仓库内 LICENSE、NOTICE 与 README。
ARG SKILLS_REPO_URL=https://github.com/zte-riscv/rvport-skills.git
ARG SKILLS_REVISION=""
ARG SKILLS_PROXY=""
# 目标 ref：空 → 远端默认分支（HEAD）；否则按指定分支/SHA 浅取
RUN REF=${SKILLS_REVISION:-HEAD} \
    && git -c http.proxy=${SKILLS_PROXY} clone --quiet --no-checkout ${SKILLS_REPO_URL} /tmp/skills \
    && git -C /tmp/skills fetch --quiet origin ${REF} --depth=1 \
    && if echo "${SKILLS_REVISION}" | grep -qE '^[0-9a-f]{40}$'; then \
         test "$(git -C /tmp/skills rev-parse FETCH_HEAD)" = "${SKILLS_REVISION}"; \
       fi \
    && git -C /tmp/skills checkout --quiet FETCH_HEAD \
    && rm -rf /tmp/skills/.git \
    && mkdir -p /root/.pi/agent/skills \
    && cp -a /tmp/skills/. /root/.pi/agent/skills/ \
    && rm -rf /tmp/skills

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
