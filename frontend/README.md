# RvPort frontend

English | [简体中文](README.zh-CN.md)

The frontend provides project, source, execution-environment, migration-analysis, build-validation, and result-report interfaces. It uses React, TypeScript, Vite, Ant Design, TanStack Query, and Zustand. Dependency versions are pinned in `pnpm-lock.yaml`.

## Build and run

Run all commands from the repository root. The host only needs Docker; do not install Node.js or pnpm on the host.

```powershell
docker build -t rvport-frontend:local -f frontend/deploy/docker/Dockerfile frontend
docker compose up -d frontend
```

First prepare the root `.env` and build the backend as described in the [installation guide](../docs/installation.md). The default URL is `http://localhost:8081`. Nginx forwards `/api/` to `backend:8080`.

## Development and validation

```powershell
docker build -t rvport-frontend-dev:local -f frontend/deploy/docker/Dockerfile.dev frontend
docker run --rm -p 5173:5173 -e VITE_API_PROXY_TARGET=http://host.docker.internal:8080 rvport-frontend-dev:local
```

The development server is at `http://localhost:5173`. Match the example's backend port to `.env`. Run builds and checks in separate containers so dependencies and outputs are not written to the host source directory:

```powershell
docker run --rm --memory=4g --cpus=2 rvport-frontend-dev:local pnpm lint
docker run --rm --memory=4g --cpus=2 rvport-frontend-dev:local pnpm typecheck
docker run --rm --memory=6g --cpus=2 -e NODE_OPTIONS=--max-old-space-size=4096 rvport-frontend-dev:local pnpm test
```

Frontend coverage acceptance is currently deferred by project decision; lint, type checking, ordinary unit tests, and production builds remain required. To run the existing coverage entry point:

```powershell
docker run --name rvport-frontend-coverage --memory=6g --cpus=2 -e NODE_OPTIONS=--max-old-space-size=4096 rvport-frontend-dev:local pnpm test:coverage
docker cp rvport-frontend-coverage:/app/coverage ./.migration-reports/frontend-coverage
docker rm rvport-frontend-coverage
```

Save reports before removing the stopped container created for the run. `pnpm build` builds static assets only; run `pnpm typecheck` separately. Coverage includes all executable TS/TSX in `src` and `scripts`, excluding declarations, unit tests, and test-environment helpers. Overall line/branch gates are 90%; affected modules must also be reviewed in the same report.

## Layout and conventions

- `src/api/`: domain-specific API clients.
- `src/pages/`, `src/components/`: pages and shared components.
- `src/types/`, `src/store/`, `src/utils/`: types, state, and shared utilities.
- `deploy/`: Docker and Nginx deployment configuration.
- `docs/`: detailed frontend guidance, currently the style guide.
- `openspec/`: independent frontend specifications, active changes, and historical archives.

See [UI conventions](AGENTS.en.md) and the [style guide](docs/STYLE_GUIDE.md). Keep tests close to their domain source. Update contract tests and backend documentation alongside API changes.
