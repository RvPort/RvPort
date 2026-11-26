# add-ai-migration-git-history 设计

## 概述

以任务级 git 仓库替代一次性 workspace：agent 仍只改 `source/`（安全边界不变），但改动落入持久仓库的历史流；产物、历史、回滚全部从提交历史派生。设计对标上游 rvport-web `common_git.py` 的已验证形态，按 RvPort 的两容器执行模型（worker 提交、执行器改码）收敛。

## 决策

### D1 仓库归属与布局（决策：运行级，非项目级）

上游为「用户×目录」级仓库（一个用户一个 changed 目录，跨任务共用历史）。RvPort 采用**运行级**（`runs/{runId}/`）：① 与现有 `ai_migration_runs` 身份、产物、审计、重试语义一一对应，无需引入目录↔运行的映射表；② 失败清理/保留策略可以运行粒度执行；③ 任务事件（开始/结束/取消）天然是运行边界，marker 语义不混淆。代价是同一项目多次运行历史分散在多个仓库——以「运行详情页内看本次历史」为交互边界，可接受（跨运行对比走已有的报告比较域，不在本 change）。

### D2 提交执行点（决策：worker 侧统一提交）

上游单进程内 agent 改码后直接 commit。RvPort 的 agent 跑在执行器容器（无平台 git 身份、故意不装 git 提交能力）。因此提交统一在 worker 的 `AiMigrationAgentRuntime` 收尾阶段：agent 进程结束（无论成败）→ worker 执行 `agent-finish`（或 `stage-end-*`）提交。收益：git 身份/白名单单点管控、执行器镜像不变。代价：agent 运行中途被强杀（容器被 kill）时该次改动无 agent-finish 提交——但改动仍以未提交状态留在工作区，下次重试的 `intermediate-*` marker 前会先 `git add -A` 打包（上游同款语义：半成品先入库再记中断）。

### D3 白名单子集（决策：删去单文件 AI 路径与用户编辑保存）

上游白名单含 `codemig-file-change-view`（前端单文件编辑保存）与 9 个用户点击边界。RvPort 无单文件 AI 建议与前端编辑入口，收敛为：type `{manual, ai, marker}`、src `{rvport-agent, rvport-init, rvport-boundary}`、reason 8 值（first-init / agent-start / agent-finish / intermediate-user-stop / intermediate-error / stage-end-success / stage-end-error / stage-end-cancel）。`user-rollback` reason 预留在正则外（回滚 marker 用 `marker/marker/rvport-boundary/migration-rollback`，走白名单扩展点），避免一次引入过多语义。

### D4 历史读取走 reflog（决策：沿用上游，含其坑位修复）

reset --hard 后裸 log 丢失被弃提交；reflog 保留 HEAD 全部移动轨迹（gc 窗口内）。上游踩过的实现坑直接规避：GitPython `head.log()` 返回顺序与 CLI `git reflog` 相反（最旧在前），必须反转后才是「时间倒序」。RvPort 用 ProcessGitCli 直调 `git reflog` CLI（无 GitPython 依赖），输出本身即最新在前。列表项 `isCurrent` 判定以 HEAD hexsha 相等唯一。

### D5 diff/补丁来源切换（决策：git 派生为权威，裸 diff 为兼容视图）

`MIGRATION_DIFF` 从 `diff -ruN baseline source` 切到 `git diff <base-commit> HEAD --binary`，排除集用 `--` 显式路径排除（.migrate_verify、*.o 等）保持等价；新增 `MIGRATION_PATCH_SERIES`（format-patch，含提交信息与二进制，`git am` 重放）与 `MIGRATION_SOURCE`（HEAD tar 快照）。三产物同源，口径以 git 为权威。风险：format-patch 的 rename 呈现与裸 diff 行数不同——归档候选的 diffStat 统计改用 `git diff --stat` 口径，前端补丁视图继续消费 MIGRATION_DIFF（内容兼容）。

### D6 回滚语义（决策：整仓三原语，单文件软回滚不引入）

上游有单文件 `revert_to_last_commit`，依赖「前端单文件编辑」交互。RvPort 补丁以整仓为应用单元，收敛为三原语：working-tree（丢弃未提交）、soft-reset（保留差异到工作区）、hard-reset。回滚产生 marker 提交 + 重建派生产物 + 更新 run 摘要计数，`status` 不变——「运行成功了」与「用户对结果不满意回退了」是两个正交事实，后者是产物版本演化。产物版本化沿用 stored_artifacts 既有机制（同 ownerType/artifactType 多版本，列表天然按时间序）。

### D7 workspace 生命周期（决策：从删除改为保留）

`removeTree(workspace)` 收敛为清理白名单式删除（仅 `.migrate_verify/`、`.prompt.txt`）。input/ 的 manifest/scan_result 保留（重试与审计需要 hash 校验基线）。磁盘治理：仓库目录计入 workspaceRoot；新增 `RVPORT_AI_MIGRATION_REPO_RETENTION`（默认 0=全留；N>0 时按运行最旧整仓清理，清理前置检查该运行产物已全部入库）。清理任务复用 worker 的既有调度器框架（另立清理 change 接管时只需替换策略实现）。

## 架构

```
创建运行 → workspace = runs/{runId}/（若不存在：git init + 物化 + first-init 提交）
运行     → worker 写 .prompt.txt → agent-start 提交 → 执行器容器改 source/
           → worker 收尾：agent-finish 提交（含改动）或 intermediate-* marker
           → 结果校验（基线提交不可改写 + 条目合同，均不变）
           → 派生 DIFF/PATCH_SERIES/SOURCE 入库 → stage-end marker → 终态
重试     → 复用仓库 → intermediate marker → agent-start …
回滚     → rollback API → 三原语之一 → migration-rollback marker → 重建派生产物
查询     → history(reflog) / repo-file(ref+path)
```

新增类（建议落点，均在 `ai.migration` 包）：

| 类 | 职责 |
|---|---|
| `MigrationRepoService` | init/提交/白名单校验/marker 工厂（薄封装 ProcessGitCli，无 GitPython） |
| `MigrationRepoProperties` | workspaceRoot 下 runs 布局、保留策略配置 |
| `AiMigrationHistoryController` | history / repo-file / rollback 三端点 |
| `AiMigrationHistoryService` | reflog 解析、标签映射、文件读取路径校验、回滚编排与派生重建 |

改动类：`AiMigrationAgentRuntime`（workspace 布局与清理、提交挂点、diff 来源）、`AiMigrationRuntimeService`（产物扩展、重试复用仓库、摘要重建）、`AiMigrationService`（产物 kind 枚举扩展）、`application.yml` + compose 环境变量（保留策略）。

## 数据与 API

- 表结构零新增：`stored_artifacts` 复用（两个新 artifactType 值无需 CHECK 扩展，artifact_type 为自由字符串列，既有 owner AI_MIGRATION 不变）。
- API：`GET /ai-migrations/{id}/history`、`GET /ai-migrations/{id}/repo-file`、`POST /ai-migrations/{id}/rollback`（body：`{target: "working-tree"|"commit"|"hard", commitHash?: string}`）；权限与既有迁移接口一致（项目归属）+ 审计日志。
- 配置：`rvport.ai-migration.repo.retention-runs`（默认 0=保留全部）。

## 测试策略

- `MigrationRepoService`：真实 git（PATH 探针模式，同 `SkillRepositoryTests` 既有手法）覆盖 init/白名单拒绝/marker 空提交/reflog 顺序/基线改写检测。
- 派生产物：临时仓库上断言 format-patch `git am` 往返一致、SOURCE tar sha256、DIFF 与旧口径等价（固定样例树对比）。
- 回滚：三原语各断言派生重建与摘要更新、status 不变、marker 存在。
- `AiMigrationAgentRuntime` 既有 166 条迁移模块测试回归；workspace 清理断言 `.git` 存活。
- 冒烟：compose 内一次真实迁移 → history ≥4 条提交、三种产物可下载、回滚一次后产物更新。

## 开放问题（实施前需确认）

1. marker 提交的 `user.name` 取任务框架标识（如 `rvport-worker`）还是运行归属人？建议前者（提交身份≠操作者，操作者走审计日志），与上游 user_name 直灌不同。
2. `MIGRATION_PATCH_SERIES` 多提交时 format-patch 为多文件（编号前缀），对象存储按单制品存（tar 内含多 patch 文件）还是逐文件多制品？建议单制品 tar（下载语义一次一包）。
3. 保留策略清理是否本期实现（建议：配置项本期落地，清理调度器另立小 change，默认全留无风险）。
