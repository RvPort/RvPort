# RvPort installation guide

English | [简体中文](安装指南.md)

## Environment and working directory

Install Docker Engine or Docker Desktop on the host, with Linux containers, BuildKit, and Docker Compose enabled. Building the complete backend image requires an x86_64 Linux build environment. The first build downloads the JDK, Node.js, Python, Clang/LLVM, and scanner dependencies. Compilation and testing run inside containers.

Run all commands below from the repository root. PowerShell examples resolve paths from the current directory and do not depend on a machine-specific location.

## Prepare configuration

```powershell
Copy-Item .env.example .env
```

Edit `.env` to replace the PostgreSQL, RabbitMQ, Redis, and MinIO passwords, keeping the corresponding backend settings consistent. Values such as `change_me` are placeholders. Do not commit `.env`.

Generate a 32-byte master key in a container and put the output into `RVPORT_CREDENTIAL_MASTER_KEY`:

```powershell
docker run --rm python:3.12-slim python -c "import base64,secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())"
```

This key encrypts stored credentials. Changing it for an existing deployment requires separate handling of the encrypted data. `RVPORT_CREDENTIAL_KEY_ID` identifies the key version; it is not the key itself.

| Setting | Purpose |
|---|---|
| `COMPOSE_PROJECT_NAME` | Isolates Compose networks and volumes; defaults to `rvport` |
| `POSTGRES_*`, `REDIS_*`, `RABBITMQ_*` | Database, session cache, and message queue |
| `MINIO_*`, `RVPORT_OBJECT_STORAGE_*` | Object storage and analysis artifacts |
| `RVPORT_BACKEND_PORT`, `FRONTEND_PORT` | Host ports for the API and web interface |
| `RVPORT_CREDENTIAL_MASTER_KEY` | Credential encryption master key generated for your deployment |
| `RVPORT_SPECIALIZED_ANALYSIS_*` | Specialized analysis switches and resource limits |
| `RVPORT_FLOATING_POINT_*`, `RVPORT_MEMORY_ORDER_*` | Scanner and worker paths inside the image |

Default ports are API 8080, PostgreSQL 5432, Redis 6379, RabbitMQ 5672/15672, and MinIO 9000/9001. If a host port is in use, change the corresponding setting in `.env`. Containers continue to communicate through Compose service names and internal ports.

## Build and start

Validate the Compose configuration, then build the frontend, backend, and object-storage images. The previously used official MinIO image is unavailable; this project builds pinned official source inside Docker. See [object-storage build notes](../infra/minio/README.md) for provenance, checksums, licensing, and build details.

```powershell
docker compose config --quiet
docker compose --parallel 1 build
docker compose up -d --wait --wait-timeout 300
docker compose ps
docker compose logs --tail 100 backend backend-worker
```

`backend` and `backend-worker` use the same image. The API node enables Liquibase at startup, initializing the schema in an empty database. Workers do not initialize the schema. Database extension scripts are in `backend/infra/postgres/init/`; application migrations are in `backend/src/main/resources/db/changelog/`.

The API health endpoint is `http://localhost:8080/actuator/health`; use the actual port configured in `.env`. Specialized-tool health and overall API health are separate checks. One unavailable scanner does not mean that every other feature is unavailable.

The database initialization scripts create the development administrator `admin`, with initial password `admin123` and a mandatory password change on first login. This is a public development default, not a deployment credential. Review initialization before a public release, and do not expose a deployment with the default account state unchanged.

The frontend is included in the source tree and is available at `http://localhost:8081` by default. To rebuild only the frontend:

```powershell
docker compose up -d --build frontend
```

See the [frontend guide](../frontend/README.md) for development and testing. Configure models, mail, SSH source environments, and target environments through the regular administration features. There is no test-support API initialization endpoint.

## Stop services and retain data

```powershell
docker compose stop
docker compose down
```

These commands retain named volumes. `docker compose down -v` deletes the project's volumes; use it only when the database and artifact data are no longer needed. Run acceptance checks with an isolated project name, ports, and volumes, without connecting to an existing deployment.

## Troubleshooting

- Toolchain downloads fail: check container connectivity to package sources, GitHub releases, and dependency repositories. Retain complete build logs; an older image is not evidence of a successful rebuild.
- Node or npm mirrors time out or reset connections: use the official sources with `docker compose --parallel 1 build --build-arg NODEJS_MIRROR=https://nodejs.org/dist`. Keep dependency versions unchanged; changing build arguments may invalidate cached layers.
- Database schema is missing: confirm that Liquibase ran on the API node, and inspect migration logs and the target database name.
- Credentials cannot be saved or read: verify that the master key is a valid Base64-encoded 32-byte value and that an existing deployment's key has not been changed accidentally.
- Workers do not execute tasks: check RabbitMQ, worker logs, database connectivity, and worker registration.
- Source or bitcode is incomplete: handle degradation and missing evidence according to the tool protocol. An empty result is not necessarily a successful analysis.
- Host environment differences: use Docker build and test entry points rather than installing host dependencies to bypass a failure.
