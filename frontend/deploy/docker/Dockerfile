ARG NODE_IMAGE=node:22.23.2-alpine
ARG NGINX_IMAGE=nginx:1.27-alpine
FROM ${NODE_IMAGE} AS builder

# corepack 的 Node fetch 不读取代理环境变量；受限网络可通过 PNPM_TARBALL_URL
# 直接指定 pnpm 包地址（例如镜像源），或保持默认由 corepack 安装。
ARG PNPM_VERSION=11.15.1
ARG PNPM_TARBALL_URL=
RUN if [ -n "${PNPM_TARBALL_URL}" ]; then \
        apk add --no-cache curl >/dev/null \
        && curl -fsSL --max-time 300 -o /tmp/pnpm.tgz "${PNPM_TARBALL_URL}" \
        && mkdir -p /usr/local/lib/pnpm-pkg \
        && tar -xzf /tmp/pnpm.tgz -C /usr/local/lib/pnpm-pkg --strip-components=1 \
        && chmod +x /usr/local/lib/pnpm-pkg/bin/pnpm.cjs /usr/local/lib/pnpm-pkg/bin/pnpx.cjs \
        && ln -s /usr/local/lib/pnpm-pkg/bin/pnpm.cjs /usr/local/bin/pnpm \
        && ln -s /usr/local/lib/pnpm-pkg/bin/pnpx.cjs /usr/local/bin/pnpx \
        && pnpm --version; \
    else \
        corepack enable; \
    fi

WORKDIR /app

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
ARG NPM_REGISTRY=https://registry.npmjs.org
RUN --mount=type=cache,id=rvport-frontend-pnpm,target=/pnpm/store \
    pnpm install --frozen-lockfile --registry=${NPM_REGISTRY} --store-dir=/pnpm/store

COPY . .
RUN pnpm build

FROM ${NGINX_IMAGE} AS runner

COPY --from=builder /app/dist /usr/share/nginx/html
COPY deploy/nginx/default.conf.template /etc/nginx/templates/default.conf.template

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget --quiet --spider http://127.0.0.1/ || exit 1

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
