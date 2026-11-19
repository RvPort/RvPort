# Tasks

## 1. 实施边界与基线

- [ ] 1.1 确认 backend 独立 OpenSpec 根、基线分支 dev；只修改 backend，不操作前端、tools、根 compose 或远程环境。
- [ ] 1.2 核对设计 D1–D7 与现有代码事实（findings 表、evidence JSONB 字段、`ReportAiReviewRuntime` 模式、`OpenCodeAgentRuntimeAdapter` 合同）；差异先改设计再实施。
- [ ] 1.3 记录受影响生产包与覆盖率分母、生成/第三方排除理由；建立本轮独立测试报告目录。

## 2. 数据库与 jOOQ

- [x] 2.1 新增 Liquibase 0138：`kb_migration_runs` 表（id、project_id、scan_run_id、task_id、status、summary、item_count、done_count、false_positive_count、pending_count、model_profile_id、agent_runtime_id、created/finished 时间戳；外键与按项目索引）。
- [x] 2.2 同 changeset 扩展三处 CHECK：`tasks_type_chk` 加 `KB_MIGRATION`、`stored_artifacts_owner_type_chk` 加 `KB_MIGRATION`、`model_stage_assignments_stage_chk` 加 `KB_MIGRATION`（照 0118 的 DROP IF EXISTS + ADD 模式）。
- [x] 2.3 Docker 内生成 jOOQ 表类；空库初始化、重复执行（幂等）、约束拒绝与回滚验证。

## 3. 任务类型与阶段接线

- [x] 3.1 `RvPortTaskTypes.KB_MIGRATION` 常量；`TaskMetadataResolver` 三处 switch（stageId 复用 `kb-scan` 分组、resourceType=`kb_migration_run`、resourceId=input.migrationRunId；stageId 为自由分组字符串，无需改 `StageIds` 常量）。
- [x] 3.2 `TaskDomainFinalizationRepository.DOMAINS` 加 `new Domain("KB_MIGRATION", "kb_migration_runs", "finished_at", "summary", null, null)`。
- [x] 3.3 `ModelProfileStage.KB_MIGRATION` 枚举、`ModelProfileService` 阶段白名单与 stageLabel（"KB 迁移阶段"）；`StoredArtifactOwnerType.KB_MIGRATION`。
- [x] 3.4 单测：枚举/CHECK/白名单一致（含非法阶段拒绝、孤儿收敛覆盖新域表）。

## 4. 投影器 KbMigrationProjector

- [x] 4.1 实现 findings → items[] 投影：id=`evidence.scannerFindingId`、sourceFindingIds=DB UUID、D2 过滤（actionable/inactive/hint/去重）与 solver_type 映射（含 `.S` 后缀 asm_file、构建文件后缀分流、未知 kind 兜底 inline_asm）。
- [x] 4.2 单测（AVX fixture 与构造 fixture）：投影条目数与 actionable findings 一致；inactive/info 过滤；scannerFindingId 去重；一对多展开共享 sourceFindingIds；id 双轨（scanner id ≠ DB UUID）；roundtrip 无 ID 丢失；未知 kind 兜底；不取 category 冒充 factKind。

## 5. KbMigrationAgentRuntime 与 workspace

- [ ] 5.1 实现 workspace 组装（D3 布局）：投影输出、manifest（expectedCount/entriesFile/sourceRoot/outputFile/missingFiles）、按投影涉及文件物化 source/（ServerPathGuard 校验、符号链接不跟随）、释放 classpath `kb-migration/agents/` 到 `.opencode/agent/`。
- [ ] 5.2 prompt 合同与调用：中文任务说明（读 input、只写 output、最终单行 DONE/FAIL）、`AgentRuntimeAdapterRegistry.get(OPENCODE)` 执行、`RVPORT_AGENT_*` 环境注入复用。
- [ ] 5.3 执行后校验：输入文件 SHA-256 复核；`output/migration_result.json` 解析与校验（unknown/duplicate/missing 计数、状态枚举 DONE/NO_WORK_NEEDED/PENDING）；`migration-diff.patch` 生成；产物先入库再清理 workspace。
- [ ] 5.4 单测：hash 不一致检测、结果校验各分支、workspace 布局断言、技能资源释放完整性、缺失源文件计入 missingFiles 不中断。

## 6. Handler、Service 与产物

- [ ] 6.1 `KbMigrationHandler implements TaskHandler`：markRunning → 投影（重新生成，不信任旧文件）→ runtime 执行 → 结果校验 → 产物入库（StoredArtifactService TaskExecutionContext 重载）→ counts 回写 → markFinished；DB 写全走 `context.publish()`。
- [ ] 6.2 `KbMigrationService`：create（readiness 三查 + inputSnapshot 内嵌 `ModelExecutionSelection.from`）、list、detail、resolveExecution 断言 taskId/profileId/runtimeId 一致（防换绑，照报告复核模式）。
- [ ] 6.3 失败语义：`KB_MIGRATION_MODEL_NOT_CONFIGURED`/`KB_MIGRATION_NO_ENTRIES`/`AGENT_OUTPUT_INVALID` 不可重试；超时 ExecutionBudget；取消经 `throwIfCancellationRequested`。
- [ ] 6.4 `archive_candidates` 登记两类产物节点（MIGRATION_DIFF/MIGRATION_RESULT）。
- [ ] 6.5 单测：readiness 各拒绝分支、防换绑、产物入库与归档候选、取消与超时路径。

## 7. API

- [ ] 7.1 `KbMigrationController`：POST/GET 端点（D7 契约）、双语错误原因、项目归属校验。
- [ ] 7.2 单测：鉴权、参数校验、正常/失败响应形态；产物下载复用链路验证。

## 8. 覆盖率与验证

- [x] 8.1 Docker 内执行后端全量单测；整体与 `cn.rvport.backend.kb.migration` 模块行/分支覆盖率 ≥ 90%。
- [ ] 8.2 完整 Compose 冒烟：重建镜像 → `docker compose up -d --wait` → 以 test-projects 测试包工程发起 KB_MIGRATION → 产物可下载、任务日志可读。
- [ ] 8.3 `openspec validate add-kb-migration-task --strict` 通过；不自动归档。
