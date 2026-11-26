# add-migration-report-loop 设计

## 概述

双向打通评估报告与 AI 迁移：输入侧把迁移条目源从 KB 扫描切换为报告全部条目；回写侧让迁移结果成为报告第五类来源并驱动风险计数。条目身份因 `scannerFindingId` 双轨同源而零迁移成本——这是整个 change 能做小的根基。

## 决策

### D1 输入源：报告条目全量直读（决策：不引入"确认清单过滤"）

`CurrentFixListResolver` 提供确认清单资格门，但用户明确决策"读取全部的条目"——投影不做 TO_FIX 过滤。理由：① 迁移的价值覆盖"复核尚未确认但技术可迁移"的条目（agent 做 necessity 分析本就是技能职责）；② 确认清单语义属人工工作台流程，把迁移资格绑死在确认动作上会让迁移被人工节奏卡住；③ 全量直读后报告条目状态（TO_FIX 与否）仍经 `originalSnapshot` 完整透传给 agent，不损失信息。`CurrentFixListResolver` 的资格门留给未来"只迁确认项"的显式模式（不在本 change）。

### D2 条目身份与投影适配（决策：双入口单语义）

投影器新增报告条目入口（`ReviewItemSnapshot` → 投影条目）：`id` 取 `originalSnapshot.scannerFindingId`、定位取报告条目字段。原 findings 入口保留（既有测试与对照），两入口产出同构条目——身份同源保证 `AiMigrationResultValidator`、git 历史仓库、既有 17/17 运行的合同零改动。MANUAL 条目（无 scannerFindingId）跳过并记 missingFiles——迁移域只处理扫描发现的事实，人工添加项留给人工修复阶段。

### D3 无报告回退（决策：拒绝而非回退）

无 current 报告时拒绝（`AI_MIGRATION_REPORT_REQUIRED`），不回退读扫描。理由：单一权威输入源。若保留回退，"迁移迁的是报告确认的集合"与"迁移迁的是扫描原始集合"两种语义并存，回写匹配（按报告条目）与资格判断都会分叉；拒绝的成本是一次清晰的用户引导（先生成报告——这本来就是流水线编排的前置顺序，前端 topology 里 ai-migration 的前置就是 report）。

### D4 回写路径（决策：走工作台条目更新，快照重建兜底）

回写不新增表：`review_items` 的 `input_snapshot` JSON 加 `migrationState`/`migrationRunId` 字段，经工作台既有条目更新路径写入。幂等性关键——报告快照重建（`ReviewSnapshotFactory`）时迁移状态作为输入之一参与继承匹配：`evidenceHash` 相同且报告同 context 的条目继承迁移状态（迁移状态不属于"证据变化"，不触发 EVIDENCE_CHANGED 丢弃继承）。回写失败不阻塞终态：工作台草稿可能被并发锁定，失败记日志，下次重建对齐——快照重建是幂等的最终一致器。

### D5 风险计数语义（决策：formalRiskCount 排除 MIGRATED）

报告的核心问题是"还有什么要处理"。已 MIGRATED 条目仍列 findings 段（追溯）但退出风险计数。实现点：`ReportReviewRenderer.summary` 与 `overallRisk` 的 scanner 流过滤加 `migrationState != MIGRATED`。前端统计卡消费既有字段（上一轮修复对齐的 codeFindingCount 等）——数字下降与新语义一致，无前端改动需求；`migrationState` 字段前端可后续自主消费。历史报告不重算：迁移状态进的是**新**报告的快照。

### D6 回写触发点与幂等（决策：终态落库同步写，非事件驱动）

不做事件总线：迁移终态在 `AiMigrationRuntimeService.migrate` 的 publish 事务内同步回写（同事务保证"终态可见则状态可见"），回滚在 rollback API 内同步重算。多次迁移同一报告：后写覆盖（migrationRunId 指向最新运行），条目状态按最新结果——与"最新运行是当前事实"语义一致。

## 架构

```
生成报告 → 复核工作台条目（含 scannerFindingId 定位）
                │
发起迁移（create：资格 = current 报告 + 可投影 > 0；inputSnapshot.reportId）
                │
执行（migrate：投影源 = 报告条目全量；执行/git 历史零变化）
                │ 终态 publish 事务内
                ├─→ 运行表终态
                └─→ 工作台条目 migrationState/migrationRunId 回写
回滚 API → 条目状态重算（HEAD==基线 → NOT_MIGRATED）
                │
重新生成报告 → InputSelectionPolicy 新增 AI_MIGRATION_RESULT 来源
                │        + ReviewSnapshotFactory 继承迁移状态
                ▼
        新报告：迁移结果段 + 条目带状态 + formalRiskCount 扣除 MIGRATED
```

改动类（建议落点）：

| 类 | 改动 |
|---|---|
| `AiMigrationService` | create 资格（current 报告）、inputSnapshot 键（reportId） |
| `AiMigrationRuntimeService` | migrate 条目源、终态事务内回写调用 |
| `AiMigrationProjector` | 新增报告条目入口 `projectFromReport(reportId, items)` |
| `MigrationStatusWriter`（新） | 条目状态映射与工作台回写/重算 |
| `ReportInputSelectionPolicy` | `AI_MIGRATION_RESULT` 来源（选最新 SUCCEEDED 运行） |
| `ReportSourceRepository` | 迁移运行来源查询 |
| `ReportReviewRenderer` | document 迁移段、summary/overallRisk 计数过滤 |
| `ReviewSnapshotFactory` | 迁移状态继承（不触发 EVIDENCE_CHANGED） |
| `StageReadinessService` / `ProjectWorkflowStatusService` | 迁移前置改 REPORT |
| `AiMigrationController`（错误码） | `AI_MIGRATION_REPORT_REQUIRED`/`AI_MIGRATION_REPORT_STALE` |

## 数据与 API

- 表结构零新增：`review_items.input_snapshot`（JSONB）加字段；`ai_migration_runs` 的 `scan_run_id` 保留（历史运行追溯），inputSnapshot 新增 `reportId` 键。
- 新错误码两个（不可重试集合）。
- readiness：报告 sources +1 项；迁移 readiness 前置键改 REPORT。
- 前端仅文案级：无报告时迁移抽屉的引导提示（"请先生成评估报告"）。

## 测试策略

- 投影报告入口：全量投影/身份对齐/TO_FIX 不过滤/MANUAL 跳过。
- 资格：无报告拒绝、STALE 拒绝、执行中 context 复检。
- 回写：SUCCEEDED 映射（DONE/PENDING/NO_WORK_NEEDED）、FAILED/CANCELLED 全置 FAILED、回滚重算、回写失败不阻塞终态、幂等（重复运行覆盖）。
- 报告聚合：迁移后计数下降 17、无迁移报告不变、历史报告不重算、迁移状态继承不破坏 EVIDENCE_CHANGED 语义。
- 既有套件回归：迁移模块 111 条、报告 review 套件、全量套件。
- compose 冒烟：生成报告 → 迁移 17/17 → 重生成报告 → 计数降 17 且条目带状态 → 回滚 → 重生成对齐。

## 已决事项（2026-11-26 用户确认，按建议执行）

1. `NO_WORK_NEEDED` → 独立状态 `MIGRATION_NO_WORK`：不并入 MIGRATED。"无需迁移"≠"已迁移"；MIGRATION_NO_WORK 与 MIGRATED 一同退出 formalRiskCount（均已处置），但报告展示区分两种处置方式。
2. 回写不进任务事件流：本期回写属报告域变更，前端经报告查询可见；事件流留给前端立项。
3. STALE 判定 = 报告 contextHash 变化即拒（与报告自身 READINESS 语义一致，避免迁移旧报告条目）。
