# Design

## Context

执行面演进的前置事实（全部实测/调研，详见 `add-ai-migration-task` design D8 与 Context 3/4）：

1. pi（`@mariozechner/pi-coding-agent` 探针版；rvport-web 用同 bin 的 `@earendil-works` 版）原生 `--print` 无头 + `--skill <path>`，实现 Agent Skills 标准，直接加载 Claude Code 形态 SKILL.md；20 行 provider extension 注册 wxai（`api: "openai-completions"`，env 引用 key 不落盘）。
2. rvport-web 执行器配方：`server/assets/ai-agent/Dockerfile.template`（qemu-user tar 注入 + 交叉工具链 apt + node + pi + `pi-subagents`/`pi-mcp-adapter` + riscv_doc_mcp，fastmcp 钉 `<4`）；容器调用 `_assemble_docker_run_cmd`（`docker run --rm --network host` + `/app` bind mount + `-e` 显式传值 + uid/gid chown + 产物排除 `.skills/`）；技能"总是贴皮"（运行时 cp 覆盖镜像内版本）。
3. 一期基线（`AiMigrationAgentRuntime` 子进程形态）已冒烟验证至 agent 执行层；其 workspace 组装（投影输入、source/baseline 双份物化、hash 校验）、结果校验（信容纳错）、产物入库全部保留。

## Goals / Non-Goals

**Goals:**

- 执行器镜像可独立构建、自检、版本化（`rvport-ai-executor:<tag>`）。
- 一次性容器生命周期完整：启动、取消（kill）、超时、退出码映射、孤儿清理。
- 迁移技能随镜像内置，与工具链版本绑定；镜像离线可用，不依赖技能远端仓库。
- `AiMigrationAgentRuntime` 执行通道可配置切换（container/process），外部行为不变。
- 容器模式 compose 冒烟跑通一次迁移。

**Non-Goals:**

- 不改数据合同（投影、条目身份、产物、API）。
- 不做技能的运行期更新与版本管理（技能改动经重建镜像发布）。
- 不做真机验证/板机（既有二期边界不变）。
- 不做多技能并存调度（先只加载 riscv-migrate；镜像内 skills 目录天然支持扩展）。

## Decisions

### E1 执行器镜像分层

```dockerfile
# backend/executor/Dockerfile（独立构建，产物 rvport-ai-executor:local）
FROM ubuntu:noble
# ① apt：git curl xz-utils qemu-user-static + riscv64 交叉工具链（gcc-13-riscv64-linux-gnu）
#    + gcc g++（x86 参考侧）+ make cmake + python3（pip、venv）；apt 源模板化（APT_URI 构建参数）
# ② node（NODEJS_MIRROR tar 直装，同 backend/Dockerfile 模式）
# ③ pi：npm install -g（NPM_REGISTRY 可覆盖）
# ④ wxai provider extension（~20 行 .mjs，env 引用 WXAI_API_KEY）
# ⑤ 迁移技能：COPY backend/executor/skills/ → /root/.pi/agent/skills/（随源码入库、随镜像发布）
# ⑥ 构建期自检：pi / qemu-riscv64 / 交叉编译器 / make / cmake / python3 版本 + 技能文件存在性
# ⑦ 入口适配脚本 /opt/rvport/entrypoint.sh（见 E3）
```

不 vendoring riscv_doc_mcp（一期 MCP 走降级速查表，与基线一致）。

2026-11-24 修订：不装 `pi-subagents` / `pi-mcp-adapter`。二者要求新版 pi
（`@earendil-works/pi-coding-agent` ≥ 1.2.0），与这里钉的 `@mariozechner/pi-coding-agent@0.73.0` 不兼容，
且其安装器需联网克隆最新代码，构建不可复现。子代理能力待代理升级时另行接入（pi 新版自带 subagent 扩展示例）。

### E2 容器调用契约

```
docker run --rm --network host \
  --name rvport-executor-<taskId> \
  -v <workspace>:/app \
  -e WXAI_API_KEY=<解密值> -e WXAI_MODEL=<model> -e WXAI_BASE_URL=<url> \
  -e http_proxy= -e https_proxy= \
  --user <hostUid>:<hostGid> \      # 免 chown：直接以宿主 uid 跑
  rvport-ai-executor:local
```

要点（源自 rvport-web 预踩坑 + 我们的环境事实）：
- 凭据 `-e` 显式传值（docker exec 不继承 run 的 env，且避免依赖父进程 environ）。
- **代理置空**：wxai 直连可达（冒烟已证），QEMU/编译不涉外网。
- **不挂载技能卷**（2026-11-24 修订）：迁移技能随镜像内置。唯一的 `-v` 是 workspace；挂载技能卷会用卷里的技能盖住镜像内置版本。
- **`--user` 替代 rvport-web 的 chown 后手法**：worker 以 root 跑、workspace 属 root，直接 root 即可；若未来非 root 化再引入 uid/gid。
- 取消：`ExecutorContainerService` 注册 `CancellableResource`，cancel() = `docker kill`；容器 `--rm` 自清理。
- 孤儿兜底：启动前 `docker rm -f` 同名容器（重试场景）；不实现全局扫描（依赖 `--rm` + 显式命名）。
- 超时：worker 侧 `waitFor` 带超时（沿用 ExecutionBudget），超时即 kill 容器。

### E3 执行器入口适配

镜像内 `/opt/rvport/entrypoint.sh`（固定逻辑，非技能内容）：
1. `cd /app`；
2. `pi --print --skill /root/.pi/agent/skills/riscv-migrate --provider wxai --model "$WXAI_MODEL" --no-session --no-context-files "<平台迁移指令>"`；
3. 透传退出码。

平台迁移指令（worker 拼装，进 prompt 而非技能）：读 `input/scan_result.json`、改 `source/`、结果写 `output/migration_result.json`（沿用一期 prompt 合同与 schema 样例）、最终单行 DONE/FAIL。技能内容随镜像内置（见 E4）。

### E4 技能随镜像内置（2026-11-24 修订，取代原「技能 volume 与管理面」）

- 技能文件随源码入库：`backend/executor/skills/`，保持技能仓库的目录布局（`riscv-migrate/`、`riscv-scan/`、`riscv-verify/` 及 LICENSE、NOTICE）。
  来源 zte-riscv/rvport-skills@d0422e96；入库时不带 `.git` 与运行期产物。此后技能在本仓库内维护，不再与上游同步。
- 镜像构建时 `COPY` 到 `/root/.pi/agent/skills/`。技能版本即镜像版本：改技能 → 重建镜像。
- 容器内技能目录**可写**：技能自带脚本要在自身目录下写运行期文件（如 `env.d/`），只读挂载会使其失败。
- 不做运行期更新：后端无技能仓库访问、无技能管理接口；compose 无技能卷与相关环境变量。
- 取舍：换来离线可用、技能与工具链版本绑定、部署简单；代价是改技能必须重建镜像，且技能更新不再有独立的审计记录。

### E5 执行通道切换与回退

`AiMigrationAgentRuntime` 增加配置分派：
- `execution-mode: container`（默认）：走 `ExecutorContainerService`；workspace 组装、hash 校验、结果校验、diff 全部不变。
- `execution-mode: process`：保留一期子进程路径（opencode），作为回退与对照。
两条路径产出同一套产物合同；切换不改 DB/不迁移数据。

### E6 配置与 compose

`rvport.ai-migration.executor.*`：`image`（默认 rvport-ai-executor:local）、`mode`（container/process）、`docker-timeout`、`max-concurrent`（类型级信号量，默认 1）、`workspace-root`、`workspace-host-root`。
（2026-11-24 修订：`skills-volume`、`skill-repo-url`、`skill-branch`、`skill-repo-proxy`、`skills-root` 已移除。）
compose：backend-worker 挂 `/var/run/docker.sock:/var/run/docker.sock` 与 workspace 宿主目录；不再声明技能 named volume。

### E7 测试策略

- `ExecutorContainerService`：docker CLI 拼装（命令行快照断言，含「不挂载技能卷」）、取消 kill、超时、孤儿 rm——不真跑 docker（命令构造层单测 + 冒烟层验证真实调用）。
- `AiMigrationAgentRuntime`：模式分派、容器路径的命令契约（mock ExecutorContainerService）。
- 冒烟（8.2 延续）：构建执行器镜像 → compose 起 worker（docker.sock）→ 发起迁移 → 容器模式产出双产物。
