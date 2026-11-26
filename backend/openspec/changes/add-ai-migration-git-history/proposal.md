# add-ai-migration-git-history

## Why

`add-ai-migration-task` + `add-ai-migration-executor` 交付的迁移运行以「一次性 workspace + 派生 diff 补丁」为产物形态：workspace 在产物入库后整体删除，修改后的源码与运行过程不留任何历史。用户无法回答三个基本问题——迁移后的完整源码长什么样（补丁只是差分）、一次运行里 agent 分几步改了什么（无中间态）、迁移不满意如何回退（无任何回滚原语）。

上游参考实现 rvport-web（`server/plugin/codemig/codemig_common/common_git.py`）已验证完整形态：每个迁移任务持有一个任务级 git 仓库，agent 直接在仓库内改码，以**白名单化的结构化 commit message**（`Save type:{type} stage:{stage} src:{src} reason:{reason}`）把代码改动与运行事件（用户点击、agent 启动/结束、阶段成功/失败/取消）混排在同一条历史上；关键节点以 `--allow-empty` 的 **marker commit** 强制留痕（零改动也有事件锚点）；历史查询走 **HEAD reflog**（reset 之后仍可回跳）；补丁与「修改后的源码」都是提交历史的派生视图而非独立存储。本 change 把该形态引入 RvPort，替代一次性 workspace 模型。

## What Changes

- **任务级 git 仓库（新增）**：每个迁移运行在 workspace 持久目录下持有一个 git 仓库（`git init` + 基线提交），仓库存放 `source/`（agent 可改）与 `baseline/`（原始副本，物化逻辑不变）。仓库在运行结束后**保留**（不再 removeTree 整目录），生命周期由保留策略管理（默认保留全部，上限可配）。
- **结构化提交与边界 marker（新增）**：沿用上游四元组白名单语义（type/stage/src/reason，正则严格校验），在固定节点打提交：`first-init`（基线物化后）、`agent-start`（agent 启动前）、`agent-finish`（agent 正常结束后，含改动时为实体提交）、`stage-end-success|error|cancel`（终态 marker，`--allow-empty` 强制留痕，零改动失败也可审计）。白名单类型/来源/理由集合为上游子集（去单文件 AI 建议路径，RvPort 无该形态）。
- **产物扩展（修改）**：在既有 `MIGRATION_DIFF` / `MIGRATION_RESULT` 之外新增 `MIGRATION_SOURCE`（运行结束 HEAD 的源码快照 tar，含 sha256）与 `MIGRATION_PATCH_SERIES`（`git format-patch` 基线→HEAD，含二进制文件，`git am` 可重放——替代手工 `diff -ruN` 成为补丁权威来源；裸 diff 产物保留作为兼容视图）。产物定义与 `StoredArtifactOwnerType.AI_MIGRATION` 复用，不新增 owner 类型。
- **历史 API（新增）**：`GET /api/ai-migrations/{runId}/history`（基于 reflog 的提交点列表：hash/short/message/时间/类型化标签/is_current）；`GET /api/ai-migrations/{runId}/repo-file?path=...&ref=...`（读取指定提交版本的文件内容，服务端路径校验沿用既有 source path 安全规则）。条目、计数与校验合同零改动。
- **回滚原语（新增，后端先行）**：`POST /api/ai-migrations/{runId}/rollback`，body 指定 `target: working-tree | last-commit | commit-hash`，分别对应 `restore_workspace`（checkout+clean，丢弃未提交）、`revert-to-commit`（单文件软回滚语义按上游收敛为整仓 `reset --soft` 到目标提交并重新生成派生产物）与 `reset --hard`（整仓硬回退）。回滚后重新派生 `MIGRATION_DIFF`/`MIGRATION_PATCH_SERIES` 并更新 run 摘要，不改 `ai_migration_runs` 终态语义（SUCCEEDED 运行回滚后状态不变，产物反映新 HEAD）。前端交互不在本 change 范围。
- **workspace 生命周期（修改）**：`AiMigrationAgentRuntime` 的 `removeTree(workspace)` 收敛为「清理临时物」（`.migrate_verify/`、`.prompt.txt`、`input/` 中可再生文件），`source/`+`baseline/`+`.git` 保留；workspace 根从运行临时目录移至 `AiMigrationExecutorProperties.workspaceRoot` 下的 `runs/{runId}/`（容器 bind mount 路径规则不变）。重试语义相应调整：同 runId 重试**复用既有仓库**（agent-start 前打 `intermediate-user-stop`/`intermediate-error` marker 记录上次中断），不再全新物化。
- **diff 生成（修改）**：`diffPatch` 从 `diff -ruN baseline source` 切换为 `git diff <base-commit> HEAD --binary`（`.migrate_verify` 与编译中间产物以 `.gitattributes`/`-- .` 路径排除规则保持等价过滤）；基线完整性校验（treeDigest）改为校验 `baseline` 提交对象未被改写。

## Capabilities

### New Capabilities

- `ai-migration-git-history`：任务级仓库模型（init/基线/提交白名单/边界 marker/保留策略）、历史查询、文件读取、回滚原语与派生产物重建。

### Modified Capabilities

- `ai-migration-runtime`：workspace 生命周期从一次性目录改为持久任务仓库；产物集合扩展两类；diff 生成切换为 git 派生。
- `object-storage-artifacts`：`AI_MIGRATION` owner 下新增 `MIGRATION_SOURCE` 与 `MIGRATION_PATCH_SERIES` 产物类型。
- `backend-api-design`：迁移运行新增 history / repo-file / rollback 三个端点契约。

## Impact

- 实施范围为 `backend/`（`cn.rvport.backend.ai.migration` 包）；根 compose 不变（workspace 卷已挂载，仅目录布局调整）。前端展示另行立项。
- 仓库布局与宿主路径：worker 容器内 git 操作与执行器容器共享同一 bind mount（agent 在容器内改码，worker 在容器外提交——上游为单进程内闭环，本平台为两容器协作；提交动作统一由 worker 在 agent 进程结束后执行，执行器容器内不执行 git 提交，避免两处身份配置）。
- 历史方案采用 reflog（上游实测坑：GitPython `head.log()` 顺序与 CLI 相反，需反转；gc 窗口内可回跳）而非裸 log。
- 不改：投影器、结果校验器（条目身份/计数合同已冻结）、执行器镜像与技能、模型绑定、取消/超时链路。
- 单元测试在 Docker 内执行，新增类行/分支覆盖率 ≥90/90；`SkillRepository`/`ProcessGitCli` 家族的真实 git 分支以 PATH 假 git 探针模式补测；验收含 compose 冒烟（一次迁移 → 历史可见 ≥4 个提交点、快照与补丁序列可下载、回滚后派生产物正确更新）。
- 风险：两容器 git 身份（统一 worker 侧提交规避）；持久仓库磁盘占用（保留策略默认全留，上限可配，后续按需接清理任务）；`git format-patch` 对 rename 的呈现需与现裸 diff 的行数口径在归档候选统计中对齐。
