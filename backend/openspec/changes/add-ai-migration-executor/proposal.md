# add-ai-migration-executor

## Why

`add-ai-migration-task` 交付了一期基线：worker 内子进程执行 agent（opencode）+ classpath 最小技能。产品需求（技能热更新、独立执行容器、对标 rvport-web Agent mode）要求把执行面演进为**独立执行器容器**：技能以 git 管理的 volume 装载原生 riscv-migrate（Agent Skills 标准，免改写），pi agent + QEMU + RISC-V 工具链独立成镜像，worker 经 docker 起一次性容器执行迁移。已实测支撑（见 `add-ai-migration-task` design D8）：pi `--print --skill` 无头加载原生 SKILL.md 通过探针验证；wxai 经 20 行 provider extension 接入；rvport-web 提供镜像配方与容器调用细节（含 fastmcp<4、uid/gid chown、技能贴皮等预踩坑）。

本 change 只做执行面替换：**数据合同（投影、条目身份、产物、校验）零改动**，`AiMigrationAgentRuntime` 之下的执行通道从"子进程 + opencode"切换为"一次性容器 + pi"，并新增技能 volume 管理面。

## What Changes

- 新增执行器镜像构建资产：`backend/executor/Dockerfile`（独立于 backend/Dockerfile）——pi（npm 钉版）+ wxai provider extension + `qemu-user-static` + RISC-V 交叉工具链（`riscv64-unknown-linux-gnu`，aria2 + sha256 + 确定性解压模式）；构建期自检（`pi --version`、`qemu-riscv64 --version`、`riscv64-unknown-linux-gnu-gcc --version`）。
- 新增 `ExecutorContainerService`：封装 `docker run --rm` 一次性容器调用（workspace bind mount 到 `/app`、模型凭据经 `-e` 显式注入、`--network host`、容器名 `rvport-executor-<taskId>`）、取消即 kill 容器（`registerCancellableResource`）、退出码与超时语义映射。
- 新增技能装载：`skills/` 目录按 git 仓库布局挂载为 named volume（`rvport-ai-skills`），容器内 `/root/.pi/agent/skills/` 只读挂载引用；宿主侧 `SkillRepository` 提供 `currentRevision()` / `update()`（`git fetch` + `rev-list` 对比 + `git reset --hard`）。
- 新增技能管理 API（管理员）：`GET /api/admin/ai-migration/skills`（当前版本、可用更新）、`POST /api/admin/ai-migration/skills:update`（拉取更新，返回前后版本与变更文件数）。
- `AiMigrationAgentRuntime` 执行通道切换：workspace 组装与结果校验不变；agent 调用改为构造 pi 命令（`pi --print --skill /root/.pi/agent/skills/riscv-migrate --provider wxai ...`）经 `ExecutorContainerService` 执行；执行器内跑入口适配脚本（读 input/、写 output/、单行 DONE）。
- compose：backend-worker 挂载 `/var/run/docker.sock` 与技能 volume；新增 `rvport-ai-skills` named volume；`RVPORT_EXECUTOR_IMAGE` 环境变量（默认 `rvport-ai-executor:local`）。
- 配置：`RvPortProperties` 新增 `AiMigration.Executor` 节（镜像名、docker 超时、并发上限、技能仓库 URL/分支）。

## Capabilities

### New Capabilities

- `ai-migration-executor-runtime`：执行器镜像契约、一次性容器生命周期（启动/取消/清理/孤儿回收）、技能 volume 装载与管理面。

### Modified Capabilities

- `ai-migration-runtime`：执行通道从 worker 子进程升级为执行器容器（外部合同不变）。

## Impact

- 唯一 OpenSpec 根与实施范围为 `backend/`（执行器 Dockerfile 归 backend 目录树）；根 compose 仅改 backend-worker 服务的挂载与变量。
- 不修改投影器、校验器、产物体系、API 契约（`add-ai-migration-task` 已冻结）；前端技能管理页不在本 change（后端 API 先行，前端另行立项）。
- opencode 路径保留为回退开关（配置项 `execution-mode: container|process`），默认 container；子进程实现不删除。
- 单元测试 Docker 内执行，新增类覆盖率 90/90；验收含执行器镜像构建自检 + compose 冒烟（容器模式跑通一次迁移）。
- 风险：docker.sock 权限（worker 容器需 docker 组或 root，与现有一致）；pi 在 wxai 上的长任务稳定性（探针已过，全量以冒烟验收）。
