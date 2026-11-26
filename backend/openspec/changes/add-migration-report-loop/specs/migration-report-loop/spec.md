# migration-report-loop 新能力

## ADDED Requirements

### Requirement: 迁移输入必须取自当前评估报告的全部条目

后端 SHALL 将迁移条目来源从 KB_SCAN findings 切换为当前报告（`publishState=CURRENT`）复核工作台的全部条目：TO_FIX 与非 TO_FIX 状态的条目 SHALL 一并投影，MUST NOT 以复核状态过滤（"全部条目"语义）。投影 SHALL 复用条目双轨身份：条目 `id` 取报告条目 `originalSnapshot` 中的 `scannerFindingId`；`sourceFindingIds` 携带报告条目 id。投影输出的条目文件、行区间 SHALL 取自报告条目的定位字段（`relativePath`/`startLine`/`endLine`）。无 `scannerFindingId` 的报告条目（MANUAL 人工项）SHALL 跳过并计入 missingFiles 说明，MUST NOT 生成迁移条目。

#### Scenario: 报告条目全量投影
- **WHEN** 迁移运行执行且存在 current 报告
- **THEN** 投影条目数 SHALL 等于报告 SCANNER 条目中携带 scannerFindingId 的条目数
- **AND** 条目 id 与报告条目的 scannerFindingId 一一对应
- **AND** 复核状态（TO_FIX/NOT_A_FIX 等）SHALL 不影响投影资格

#### Scenario: 人工条目跳过
- **WHEN** 报告条目 origin 为 MANUAL（人工添加，无 scannerFindingId）
- **THEN** SHALL 不产生迁移条目，运行 manifest 记录跳过原因
- **AND** 该条目在报告中的迁移状态 SHALL 保持 NOT_MIGRATED

### Requirement: 无报告时迁移创建必须被拒绝

创建迁移的资格 SHALL 要求存在 current 报告且其可投影条目数大于零。无报告或报告非 current（`freshness` 为 STALE/EMPTY、或 readiness 报 `REPORT_CONTEXT_CHANGED`）时 SHALL 以确定性错误拒绝（`AI_MIGRATION_REPORT_REQUIRED` / `AI_MIGRATION_REPORT_STALE`），MUST NOT 回退到直接读 KB_SCAN findings——报告是唯一权威输入源，避免双轨语义。`inputSnapshot` SHALL 以 `reportId` 为输入键（`scanRunId` 键移除）；创建时与执行时 SHALL 复检报告上下文未变化（复用 readiness 的 contextHash 语义）。

#### Scenario: 无报告拒绝
- **WHEN** 项目尚未生成任何评估报告时发起迁移
- **THEN** SHALL 返回 `AI_MIGRATION_REPORT_REQUIRED`，任务不创建
- **AND** 错误信息 SHALL 引导用户先生成报告

#### Scenario: 报告过期拒绝
- **WHEN** 迁移执行时报告上下文已变化（contextHash 不一致）
- **THEN** SHALL 以确定性错误失败（不可重试），提示重新生成报告后再次发起

### Requirement: 迁移终态必须回写报告条目的迁移状态

迁移终态落库（SUCCEEDED/FAILED/CANCELLED）时，后端 SHALL 按条目 id（迁移条目 ↔ 报告条目经 scannerFindingId 匹配）回写报告复核工作台条目的迁移状态：`migrationState`（MIGRATED / MIGRATION_PENDING / MIGRATION_FAILED / NOT_MIGRATED）与 `migrationRunId`。SUCCEEDED 运行按结果条目状态映射（DONE→MIGRATED、PENDING/NO_WORK_NEEDED→对应状态）；FAILED/CANCELLED 运行 SHALL 将全部投影条目置 MIGRATION_FAILED。回滚 API 执行后 SHALL 重算受影响条目：HEAD 树内容与基线一致的条目回到 NOT_MIGRATED。回写 MUST NOT 修改 findings 表与扫描产物（扫描域只读语义保留）；回写失败 SHALL 不阻塞迁移终态（记任务日志），报告侧下次快照重建自然对齐。

#### Scenario: 成功运行回写
- **WHEN** 迁移 SUCCEEDED 且 17 条目 DONE
- **THEN** 报告对应条目 `migrationState=MIGRATED`、`migrationRunId` 指向该运行
- **AND** 未参与投影的其余条目保持 NOT_MIGRATED

#### Scenario: 回滚后状态重算
- **WHEN** 用户对 MIGRATED 运行执行 hard 回滚到基线
- **THEN** 内容回到基线的条目 `migrationState` 回到 NOT_MIGRATED
- **AND** `migrationRunId` 保留（追溯最后一次迁移尝试）

#### Scenario: 回写失败不阻塞终态
- **WHEN** 回写时报告草稿被并发锁定
- **THEN** 迁移终态照常落库，任务日志记录回写失败原因
- **AND** 下次报告快照重建时状态自然对齐

### Requirement: 迁移结果必须作为报告的第五类输入来源

`ReportInputSelectionPolicy` SHALL 新增来源类型 `AI_MIGRATION_RESULT`：取该 sourceRevision 下最新 SUCCEEDED 迁移运行（无则该来源 unavailable）。报告 document SHALL 新增"迁移结果"段：运行摘要（itemCount/doneCount/pendingCount/制品引用）与逐条目结论（id/changeSummary/files/status）。报告条目（findings 段）SHALL 携带迁移状态字段。`formalRiskCount` 与 `riskCounts` SHALL 只统计 `migrationState` 非 MIGRATED 的条目——风险计数反映"当前仍需处理"的集合；已迁移条目保留在 findings 段以供追溯。历史报告 MUST NOT 重算（快照语义，与既有"历史报告不可变"一致）。

#### Scenario: 迁移后报告计数下降
- **WHEN** 57 条报告中 17 条已 MIGRATED 后重新生成报告
- **THEN** 新报告 formalRiskCount SHALL 较上份减少 17
- **AND** findings 段仍含全部条目，已迁移条目带 `migrationState=MIGRATED`

#### Scenario: 无迁移时报告不变
- **WHEN** 项目从未运行迁移
- **THEN** 报告与既有行为一致（无迁移结果段，条目无迁移状态或均为 NOT_MIGRATED）
- **AND** formalRiskCount 与现状口径一致

### Requirement: readiness 必须暴露迁移结果来源与报告前置

报告 readiness 的 sources 列表 SHALL 新增 `AI_MIGRATION_RESULT` 项（available/runId/candidateCount）。迁移阶段 readiness 的前置 SHALL 从"KB_SCAN 就绪"改为"报告已生成"（复用既有 `REPORT` 前置键语义）；报告过期时迁移前置 SHALL 报未满足并给出重新生成报告的指引。

#### Scenario: 迁移前置展示报告依赖
- **WHEN** 项目无 current 报告时查询迁移阶段 readiness
- **THEN** 前置 SHALL 报报告未满足，reasonCode 引导先生成报告
- **AND** 既有 KB_SCAN 前置不再作为迁移资格项
