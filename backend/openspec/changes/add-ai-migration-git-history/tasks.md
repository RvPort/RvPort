# Tasks

## 1. 仓库与提交基座

- [x] 1.1 `MigrationRepoProperties`（runs 布局、保留策略默认全留）+ application.yml + compose 环境变量透传。
- [x] 1.2 `MigrationRepoService`：init、基线物化提交（first-init）、四元组白名单与正则校验、marker 工厂（--allow-empty）、`agent-start/agent-finish/intermediate-*/stage-end-*` 提交挂点方法。
- [x] 1.3 单测：PATH 假 git 探针 + 真实 git 分支（init/白名单拒绝/空 marker/reflog 顺序/基线改写检测/GitPython 顺序坑位用 CLI 语义规避）。

## 2. 运行时接入

- [x] 2.1 `AiMigrationAgentRuntime`：workspace 切换到 `runs/{runId}/`（不存在则 init+物化+first-init；重试复用并打 intermediate marker）；`.prompt.txt`/输入物化逻辑不变。
- [x] 2.2 收尾挂点：agent 结束（成败）→ worker 提交 agent-finish 或 intermediate-*；终态前 stage-end marker（不阻塞终态）。
- [x] 2.3 diff 来源切换：`git diff <base> HEAD --binary` + 等价排除集；基线完整性校验改为基线提交改写检测（treeDigest 语义平移）。
- [x] 2.4 清理收敛：removeTree → 白名单式临时物清理（`.migrate_verify/`、`.prompt.txt`），断言 `.git`/`source/`/`baseline/`/`input/` 存活。
- [x] 2.5 既有 166 条迁移模块测试回归 + 更新受影响断言。

## 3. 产物扩展

- [x] 3.1 `MIGRATION_PATCH_SERIES`（format-patch --binary，多 patch 打单 tar 制品）与 `MIGRATION_SOURCE`（HEAD tar + sha256）入库。
- [ ] 3.2 归档候选 diffStat 口径切换 `git diff --stat`；前端补丁视图消费的 MIGRATION_DIFF 兼容性断言（固定样例树对比）。
- [x] 3.3 单测：format-patch `git am` 往返一致、SOURCE 解包等价 HEAD、DIFF 旧口径等价。

## 4. 历史/文件/回滚 API

- [x] 4.1 `GET /ai-migrations/{id}/history`（reflog 解析、四元组标签、isCurrent、未分类兜底）+ 单测。
- [x] 4.2 `GET /ai-migrations/{id}/repo-file`（ref 缺省 HEAD、路径安全校验复用、越界/符号链接拒绝）+ 单测。
- [x] 4.3 `POST /ai-migrations/{id}/rollback`（三原语、marker 提交 migration-rollback、派生产物重建入新版本、run 摘要计数更新、status 不变、权限+审计）+ 单测。
- [x] 4.4 `AiMigrationHistoryController/Service` 装配与路由测试。

## 5. 验收

- [x] 5.1 Docker 全量单测；新增模块行/分支覆盖率 ≥90/90。
- [x] 5.2 compose 冒烟：真实迁移一次 → history ≥4 提交点、DIFF/PATCH_SERIES/SOURCE 三产物可下载、回滚一次（hard）→ 产物新版本、status 不变、历史含回滚 marker。
- [ ] 5.3 `openspec validate add-ai-migration-git-history --strict` 通过；不自动归档。
