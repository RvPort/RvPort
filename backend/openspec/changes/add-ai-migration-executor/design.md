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
- 技能以 git volume 装载，管理 API 支持版本查看与更新。
- `AiMigrationAgentRuntime` 执行通道可配置切换（container/process），外部行为不变。
- 容器模式 compose 冒烟跑通一次迁移。

**Non-Goals:**

- 不改数据合同（投影、条目身份、产物、API）。
- 不做前端技能管理页（后端 API 先行）。
- 不做真机验证/板机（既有二期边界不变）。
- 不做多技能并存调度（先只挂 riscv-migrate；架构上 skills 目录天然支持扩展）。

## Decisions

### E1 执行器镜像分层

```dockerfile
# backend/executor/Dockerfile（独立构建，产物 rvport-ai-executor:local）
FROM ubuntu:noble
# ① apt：git curl xz-utils qemu-user-static + riscv64 交叉工具链（gcc-14-riscv64-linux-gnu 系）
#    apt 源模板化（APT_URI 构建参数，受限网络可换内网镜像）
# ② node（NODEJS_MIRROR tar 直装，同 backend/Dockerfile 模式）
# ③ pi：npm install -g（NPM_REGISTRY 可覆盖）+ pi-subagents + pi-mcp-adapter
# ④ wxai provider extension（~20 行 .mjs，env 引用 WXAI_API_KEY）
# ⑤ 构建期自检：pi --version && qemu-riscv64 --version && riscv64-unknown-linux-gnu-gcc --version
# ⑥ 入口适配脚本 /opt/rvport/entrypoint.sh（见 E3）
```

不 vendoring riscv_doc_mcp（一期 MCP 走降级速查表，与基线一致；镜像预留 pip 层位置）。

### E2 容器调用契约

```
docker run --rm --network host \
  --name rvport-executor-<taskId> \
  -v <workspace>:/app \
  -v rvport-ai-skills:/root/.pi/agent/skills:ro \
  -e WXAI_API_KEY=<解密值> -e WXAI_MODEL=<model> -e WXAI_BASE_URL=<url> \
  -e http_proxy= -e https_proxy= \
  --user <hostUid>:<hostGid> \      # 免 chown：直接以宿主 uid 跑
  rvport-ai-executor:local
```

要点（源自 rvport-web 预踩坑 + 我们的环境事实）：
- 凭据 `-e` 显式传值（docker exec 不继承 run 的 env，且避免依赖父进程 environ）。
- **代理置空**：wxai 直连可达（冒烟已证），QEMU/编译不涉外网。
- **`--user` 替代 rvport-web 的 chown 后手法**：worker 以 root 跑、workspace 属 root，直接 root 即可；若未来非 root 化再引入 uid/gid。
- 取消：`ExecutorContainerService` 注册 `CancellableResource`，cancel() = `docker kill`；容器 `--rm` 自清理。
- 孤儿兜底：启动前 `docker rm -f` 同名容器（重试场景）；不实现全局扫描（依赖 `--rm` + 显式命名）。
- 超时：worker 侧 `waitFor` 带超时（沿用 ExecutionBudget），超时即 kill 容器。

### E3 执行器入口适配

镜像内 `/opt/rvport/entrypoint.sh`（固定逻辑，非技能内容）：
1. `cd /app`；
2. `pi --print --skill /root/.pi/agent/skills/riscv-migrate --provider wxai --model "$WXAI_MODEL" --no-session --no-context-files "<平台迁移指令>"`；
3. 透传退出码。

平台迁移指令（worker 拼装，进 prompt 而非技能）：读 `input/scan_result.json`、改 `source/`、结果写 `output/migration_result.json`（沿用一期 prompt 合同与 schema 样例）、最终单行 DONE/FAIL。**技能内容零改动**——riscv-migrate 原生目录整体挂载。

### E4 技能 volume 与管理面

- named volume `rvport-ai-skills`，首次初始化：worker 启动时若空则 `git clone --depth 1 <skillRepoUrl>` 到 volume（`RvPortProperties.AiMigration.Executor.skillRepoUrl`，默认 zte-riscv/rvport-skills）。
- `SkillRepository`（宿主侧，git 命令封装）：`currentRevision()`（HEAD sha + 简述）、`update()`（fetch + `rev-list HEAD..origin/<branch>` 计数 + `reset --hard origin/<branch>`，返回前后 sha 与变更文件数 `git diff --stat`）。
- 管理 API（管理员权限，照现有 admin controller 模式）：GET 查询（当前版本/远端是否有更新/差异摘要）、POST 更新（幂等，返回结果）。
- 容器内只读挂载（`:ro`）——更新只发生在宿主侧 git 操作，容器重启即见新版。

### E5 执行通道切换与回退

`AiMigrationAgentRuntime` 增加配置分派：
- `execution-mode: container`（默认）：走 `ExecutorContainerService`；workspace 组装、hash 校验、结果校验、diff 全部不变。
- `execution-mode: process`：保留一期子进程路径（opencode），作为回退与对照。
两条路径产出同一套产物合同；切换不改 DB/不迁移数据。

### E6 配置与 compose

`rvport.ai-migration.executor.*`：`image`（默认 rvport-ai-executor:local）、`mode`（container/process）、`docker-timeout`、`max-concurrent`（类型级信号量，默认 1）、`skill-repo-url`、`skill-branch`。
compose：backend-worker 挂 `/var/run/docker.sock:/var/run/docker.sock` 与 `rvport-ai-skills` volume；环境变量透传 `RVPORT_EXECUTOR_*`。

### E7 测试策略

- `ExecutorContainerService`：docker CLI 拼装（命令行快照断言）、取消 kill、超时、孤儿 rm——不真跑 docker（命令构造层单测 + 冒烟层验证真实调用）。
- `SkillRepository`：git 输出解析（fetch/rev-list/diff --stat 的固定输出 fixture）。
- `AiMigrationAgentRuntime`：模式分派、容器路径的命令契约（mock ExecutorContainerService）。
- 冒烟（8.2 延续）：构建执行器镜像 → compose 起 worker（docker.sock）→ 发起迁移 → 容器模式产出双产物。
