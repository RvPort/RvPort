# Tasks

## 1. 实施边界与镜像

- [x] 1.1 确认基线分支、backend 独立 OpenSpec 根；执行器资产放 `backend/executor/`，根 compose 仅改 backend-worker。
- [x] 1.2 编写 `backend/executor/Dockerfile`：qemu-user-static + riscv64 交叉工具链 + node + pi（+pi-subagents/pi-mcp-adapter）+ wxai provider extension + entrypoint.sh；构建参数模板化（APT_URI/NODEJS_MIRROR/NPM_REGISTRY）；构建期三连自检。
- [x] 1.3 本地构建执行器镜像并验证自检通过；受限网络出口记录在案（ARIA2/NPM mirror）。

## 2. 执行器容器服务

- [x] 2.1 `ExecutorContainerService`：docker run 命令拼装（命名、挂载、env 显式传值、代理置空、--rm）、启动前同名孤儿 rm、退出码/超时映射。
- [x] 2.2 取消集成：`registerCancellableResource` → `docker kill`；与任务取消链路（worker cancel）联通。
- [x] 2.3 单测：命令快照断言（挂载/env/命名/代理置空）、孤儿清理、超时 kill、退出码映射（不真跑 docker）。

## 3. 技能 volume 与管理面

- [x] 3.1 `SkillRepository`：volume 路径解析、空 volume 初始化 clone、`currentRevision()`、`update()`（fetch/rev-list/diff --stat 解析）。
- [x] 3.2 管理 API：GET `/api/admin/ai-migration/skills`、POST `/api/admin/ai-migration/skills:update`（管理员权限、审计日志）。
- [x] 3.3 单测：git 输出 fixture 解析、初始化幂等、API 鉴权与响应形态。

## 4. 执行通道切换

- [ ] 4.1 `RvPortProperties.AiMigration.Executor` 配置节 + application.yml 默认值 + compose 变量透传。
- [ ] 4.2 `AiMigrationAgentRuntime` 模式分派：container 路径（prompt 合同复用、经 ExecutorContainerService）与 process 回退路径并存。
- [ ] 4.3 compose：backend-worker 挂 docker.sock 与技能 volume。
- [ ] 4.4 单测：分派逻辑、容器路径契约（mock 服务）、process 路径回归不破。

## 5. 验证

- [ ] 5.1 Docker 全量单测；整体与新增模块行/分支覆盖率 ≥90%。
- [ ] 5.2 冒烟：执行器镜像构建自检 → compose 全栈起 → 容器模式发起一次 AI_MIGRATION → 产物入库可下载；process 模式回归一次。
- [ ] 5.3 `openspec validate add-ai-migration-executor --strict` 通过；不自动归档。
