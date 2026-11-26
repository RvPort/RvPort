# Tasks

## 1. 输入侧：报告条目投影

- [x] 1.1 `AiMigrationProjector` 新增报告条目入口：`ReviewItemSnapshot` → 投影条目（id 取 scannerFindingId、定位取报告字段、TO_FIX 不过滤、MANUAL 跳过记 missingFiles）。
- [x] 1.2 `AiMigrationService.create`：资格改为 current 报告 + 可投影 > 0；inputSnapshot 以 `reportId` 为键；新错误码 `AI_MIGRATION_REPORT_REQUIRED` / `AI_MIGRATION_REPORT_STALE`（不可重试集合）。
- [x] 1.3 `AiMigrationRuntimeService.migrate`：条目源切换为报告条目；执行时复检报告 contextHash（复用 readiness 语义）。
- [x] 1.4 单测：全量投影/身份对齐/状态不过滤/MANUAL 跳过/无报告拒绝/STALE 拒绝/context 复检；既有 111 条迁移模块回归。

## 2. 回写侧：条目状态

- [x] 2.1 `MigrationStatusWriter`（新）：条目状态映射（DONE→MIGRATED、PENDING→MIGRATION_PENDING、NO_WORK_NEEDED→MIGRATION_NO_WORK（独立状态，与 MIGRATED 一同退出风险计数但展示区分）、FAILED/CANCELLED 运行全置 MIGRATION_FAILED）与工作台回写（`review_items.input_snapshot` 加字段，走既有条目更新路径）。
- [x] 2.2 终态事务内回写接入（`AiMigrationRuntimeService.migrate` publish 事务）；失败不阻塞终态（记日志）。
- [x] 2.3 回滚联动：rollback API 后重算（HEAD 树==基线 → NOT_MIGRATED，migrationRunId 保留）。
- [x] 2.4 单测：状态映射/回滚重算/失败不阻塞/幂等覆盖。

## 3. 回写侧：报告聚合

- [x] 3.1 `ReportSourceRepository` + `ReportInputSelectionPolicy`：`AI_MIGRATION_RESULT` 来源（最新 SUCCEEDED 运行、无则 unavailable）。
- [x] 3.2 `ReportReviewRenderer`：document 迁移结果段（运行摘要 + 制品引用 + 逐条目 changeSummary）；summary/overallRisk/formalRiskCount 排除 MIGRATED。
- [x] 3.3 `ReviewSnapshotFactory`：迁移状态继承（evidenceHash 相同 + 同 context 继承；不触发 EVIDENCE_CHANGED）。
- [x] 3.4 readiness：报告 sources 新增 AI_MIGRATION_RESULT 项；迁移阶段前置改 REPORT（含 STALE 指引）。
- [ ] 3.5 单测：迁移后计数下降/无迁移报告不变/历史报告不重算/状态继承与 EVIDENCE_CHANGED 不冲突；报告 review 套件回归。

## 4. 前端文案与验收

- [x] 4.1 迁移抽屉无报告时的引导文案（"请先生成评估报告"，复用既有空态模式）。
- [x] 4.2 Docker 全量单测；新增/修改模块覆盖率 ≥90/90。
- [x] 4.3 compose 冒烟：生成报告 → 迁移 17/17 → 重生成报告（计数降 17、条目带 migrationState、含迁移结果段）→ 回滚 → 重生成对齐。
- [ ] 4.4 `openspec validate add-migration-report-loop --strict` 通过；既有两份被修订 spec（ai-migration-runtime / migration-report-runtime）delta 一并核对；不自动归档。
