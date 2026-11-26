# add-migration-report-loop

## Why

当前评估报告与 AI 迁移是两条互不感知的断头链：报告聚合四类来源（KB 扫描、依赖、浮点、内存序），迁移读 KB 扫描 findings，各自独立。结果是——迁移完成后报告原样重现 57 条风险（已迁移的部分、PENDING 的部分、失败的原因全部不可见）；用户要回答"处理到哪了"只能去迁移抽屉翻运行记录；而迁移输入也没有用上报告复核工作台已备好的资格门禁（`CurrentFixListResolver` 的确认清单）与条目定位。

本 change 把两个方向接通，形成完整闭环：

- **输入侧**：迁移条目改从当前评估报告取全部条目（用户既有决策——"迁移输入应该是读取全部的条目"），报告成为迁移的权威输入源；
- **回写侧**：迁移结果成为评估报告的第五类输入来源，报告条目携带迁移状态，报告能回答"每条风险处理得怎么样"。

衔接基础已就绪（2026-11-25 实测确认）：报告条目 `original.evidence.scannerFindingId` 即迁移投影条目 id（身份天然对齐，切换输入源不改变条目身份）；`CurrentFixListResolver` 提供带完整资格门的确认清单；`ReviewItemSnapshot` 携带 `sourceType/sourceRunId/sourceRefId` 与路径/行区间（投影所需定位信息齐全）。

## What Changes

- **迁移输入源切换（输入侧核心）**：`AiMigrationRuntimeService.migrate` 的条目来源从 `kbScanRepository.listAllFindingViews(scanRunId)` 切换为当前报告复核工作台条目（全部条目：TO_FIX 与非 TO_FIX 一并投影，不做确认状态过滤——"全部的条目"语义）。投影器入口适配：报告条目（`originalSnapshot` 形态）→ 投影条目，条目 `id` 仍取 `scannerFindingId`（身份不变，`AiMigrationResultValidator` 合同零改动）。
- **创建资格重定义**：`AiMigrationService.create` 的资格从"存在有效 KB_SCAN 运行"改为"存在 current 报告且其条目可投影数 > 0"；`inputSnapshot` 的 `scanRunId` 让位于 `reportId`（新键），报告上下文变化（readiness 的 `REPORT_CONTEXT_CHANGED`）在创建时复检。无报告时的行为：拒绝（`AI_MIGRATION_REPORT_REQUIRED`），不回退到扫描直读——单一权威输入源，避免双轨。
- **迁移状态回写（回写侧核心，轻量层）**：迁移终态落库时（SUCCEEDED/FAILED/CANCELLED）向报告复核工作台写条目级迁移状态：按条目 id 匹配（`scannerFindingId` ↔ 工作台条目），写入 `migrationState`（MIGRATED / MIGRATION_PENDING / MIGRATION_FAILED / NOT_MIGRATED）与 `migrationRunId`。回滚操作（`add-ai-migration-git-history` 的 rollback API）同步重算受影响条目的状态（HEAD 内容回基线的条目回到 NOT_MIGRATED）。
- **报告聚合迁移结果（回写侧完整层）**：`ReportInputSelectionPolicy` 新增第五类来源 `AI_MIGRATION_RESULT`（取该 sourceRevision 下最新 SUCCEEDED 运行）；`ReportReviewRenderer` 的 document 新增"迁移结果"段（doneCount/pendingCount/补丁与快照制品引用/逐条目 changeSummary）；报告 JSON 制品与复核工作台均可见。
- **迁移结果对报告输入选择的影响**：已 MIGRATED 的条目在新一轮报告中不重复计入 formalRiskCount（风险已处置），但保留在 findings 段（可追溯），以 `migrationState` 字段呈现——报告的风险等级反映"当前仍需人工处理"的集合。
- **readiness 联动**：报告 readiness 的 sources 列表新增 `AI_MIGRATION_RESULT` 项（available/candidateCount）；迁移阶段 readiness 的前置从"KB_SCAN 就绪"改为"报告已生成"（`REPORT` 前置复用既有键）。
- **OpenSpec 既有条款修订**：`ai-migration-runtime` 的"迁移执行不得改变扫描域状态"修订为"不得改变**扫描域**状态（findings/扫描记录不变，本条保留）；迁移结果 SHALL 回写**报告复核工作台**（新增义务）"；`migration-report-runtime` 补充迁移结果来源与条目迁移状态。

## Capabilities

### New Capabilities

- `migration-report-loop`：输入侧（报告条目→迁移投影的映射与资格）、回写侧（条目状态回写与回滚重算、迁移结果作为第五类报告来源、风险计数语义）。

### Modified Capabilities

- `ai-migration-runtime`：输入源从 KB_SCAN findings 切换为报告条目；终态回写义务；创建资格（reportId 输入键、报告上下文复检）。
- `migration-report-runtime`：报告来源集合扩展 AI_MIGRATION_RESULT；条目携带迁移状态；formalRiskCount 排除已迁移条目。
- `backend-api-design`：迁移创建请求/响应的输入键变更；readiness 响应新增来源项。

## Impact

- 实施范围 `backend/`（report/review 与 ai/migration 两域）+ 前端迁移抽屉的输入提示文案（无报告时的引导）；根 compose 不变。
- **身份对齐零迁移成本**：条目 id 双轨同源（`scannerFindingId`），既有 17/17 运行、结果校验器、git 历史仓库全部不受影响——只有"条目从哪读"换了一处。
- 投影器新增报告条目形态入口（`ReviewItemSnapshot` → 投影条目），原 findings 入口保留给既有测试与无报告回退场景的对照（不删除，双入口单语义）。
- 迁移状态回写走报告复核工作台的既有条目更新路径（不新增表；`review_items` 快照 JSON 加字段）。回写失败不阻塞迁移终态（记任务日志，报告侧下次重建自然对齐——快照重建幂等）。
- formalRiskCount 语义变更是**报告行为变化**：既有报告消费者（前端统计卡）看到的"高风险数"下降——需在验证中确认前端展示与该语义一致（已迁移高风险项不再计入）。
- 历史报告不重算：迁移状态只影响**新生成**的报告（快照语义，与"历史报告不可变"一致）。
- 明确不做：迁移自动触发报告重生成（用户手动重新生成）；迁移状态驱动的前端交互（报告页内嵌迁移抽屉跳转另行立项）。
- 单元测试 Docker 内执行，新增/修改模块行/分支覆盖率 ≥90/90；验收含 compose 冒烟：生成报告 → 发起迁移 → 完成后重新生成报告 → 报告含迁移结果段与条目状态、formalRiskCount 下降、回滚后重生成对齐。
