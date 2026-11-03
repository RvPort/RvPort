# RvPort 前端

[English](README.md) | 简体中文

前端提供项目、源码、运行环境、迁移分析、构建验证和结果报告界面。采用 React、TypeScript、Vite、Ant Design、TanStack Query 和 Zustand；依赖版本由 `pnpm-lock.yaml` 固定。

## 构建与运行

所有命令从新项目根目录执行。宿主机只需 Docker，不在宿主机安装 Node.js 或 pnpm。

```powershell
docker build -t rvport-frontend:local -f frontend/deploy/docker/Dockerfile frontend
docker compose up -d frontend
```

先按 [安装指南](../docs/安装指南.md) 准备根目录 `.env` 并构建后端。默认访问 `http://localhost:8081`；Nginx 将 `/api/` 转发至 `backend:8080`。

## 开发与验证

```powershell
docker build -t rvport-frontend-dev:local -f frontend/deploy/docker/Dockerfile.dev frontend
docker run --rm -p 5173:5173 -e VITE_API_PROXY_TARGET=http://host.docker.internal:8080 rvport-frontend-dev:local
```

开发服务器地址为 `http://localhost:5173`。上例的后端端口应与 `.env` 一致。构建和检查使用独立容器，依赖及产物不写入宿主机源码目录：

```powershell
docker run --rm --memory=4g --cpus=2 rvport-frontend-dev:local pnpm lint
docker run --rm --memory=4g --cpus=2 rvport-frontend-dev:local pnpm typecheck
docker run --rm --memory=6g --cpus=2 -e NODE_OPTIONS=--max-old-space-size=4096 rvport-frontend-dev:local pnpm test
```

本次迁移按用户要求暂不验收前端覆盖率，以上 lint、类型检查、普通单元测试和生产构建仍需通过。后续恢复覆盖率验收时，使用现有入口：

```powershell
docker run --name rvport-frontend-coverage --memory=6g --cpus=2 -e NODE_OPTIONS=--max-old-space-size=4096 rvport-frontend-dev:local pnpm test:coverage
docker cp rvport-frontend-coverage:/app/coverage ./.migration-reports/frontend-coverage
docker rm rvport-frontend-coverage
```

保存报告后再删除本次创建的已停止容器。`pnpm build` 只构建静态资源；类型检查需要单独执行 `pnpm typecheck`。覆盖率统计包括全部 `src` 可执行 TS/TSX 和 `scripts`，排除类型声明、单元测试与测试环境辅助文件，整体行/分支门槛为 90%。受影响模块也需审查同一报告，不能只看整体比例。

## 目录与约定

- `src/api/`：按领域拆分的 API 客户端。
- `src/pages/`、`src/components/`：页面与公共组件。
- `src/types/`、`src/store/`、`src/utils/`：类型、状态与通用辅助逻辑。
- `deploy/`：Docker 和 Nginx 部署配置。
- `docs/`：前端详细规范，当前包含样式指南。
- `openspec/`：独立的前端规范、进行中变更与历史归档。

界面规范见 [AGENTS.md](AGENTS.md) 和 [样式规范](docs/STYLE_GUIDE.zh-CN.md)。测试随领域源码放置；API 变更同步更新契约测试和后端说明。
