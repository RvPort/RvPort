# add-kb-migration-task

## Why

平台当前具备完整的静态扫描链路（`KB_SCAN` 产出 findings），但代码修改本身仍由人工完成（方案文档 `docs/AI迁移能力整合方案.md` §1）。本期按其"一期最小路径"落地：把当前有效扫描的 findings 投影为迁移技能可消费的 `scan_result.json`，由已接入的受管 agent runtime（OPENCODE）在隔离 workspace 内执行改编版 riscv-migrate 技能完成条目级改码，验证走容器内 QEMU。远程真机验证（rv_board.sh、板机凭据、marking 结构化回填）属二期，不在本 change 范围。

选型已实测（2026-11-19）：内网模型端点仅支持 chat completions（无 `/v1/responses`），新版 codex 仅支持 Responses 协议故不可用；opencode 以 `OpenCodeAgentRuntimeAdapter` 生产形态通过全部验收（无头执行、`.opencode/agent/*.md` 技能加载、task 工具派生 subagent、workspace 文件写入、单行 DONE）。因此本 change 复用现有 `AgentType.OPENCODE`，不新增 agent 类型。

## What Changes

- 新增任务类型 `KB_MIGRATION`：`RvPortTaskTypes` 常量、`tasks_type_chk` CHECK 扩展、`TaskMetadataResolver` 三处 switch、`TaskDomainFinalizationRepository.DOMAINS` 收敛条目、`StageIds` 阶段常量与 readiness 接线。
- 新增 `ModelProfileStage.KB_MIGRATION`：`model_stage_assignments_stage_chk` CHECK 扩展、`ModelProfileService` 阶段白名单与 stageLabel；复用 `resolveAgentForStage` 个人绑定链。
- 新增 `StoredArtifactOwnerType.KB_MIGRATION` 与 `stored_artifacts_owner_type_chk` 扩展；产物 `MIGRATION_DIFF`（统一 diff）与 `MIGRATION_RESULT`（条目终态 JSON）入库并登记归档候选。
- 新增表 `kb_migration_runs`（Liquibase 0138）：运行身份、快照引用（scan_run_id、条目计数、模型 profile/runtime 引用）、状态与摘要。
- 新增投影器 `KbMigrationProjector`：当前有效扫描的 findings → `input/scan_result.json`。条目 `id` 使用 `evidence.scannerFindingId`（跨 run 稳定），`sourceFindingIds` 携带当前 run 的 findings DB UUID；按 actionable/inactive/hint 过滤，按 scannerFindingId 去重；`fact.kind` → `solver_type` 字符串枚举映射。
- 新增 `KbMigrationAgentRuntime`（照 `ReportAiReviewRuntime` 模式）：只物化投影涉及的源码文件、写 `input/manifest.json`、注入 `.opencode/agent/*.md` 技能（classpath 资源）、执行后对输入做 hash 完整性校验、结果写 `output/migration_result.json` 与 `output/migration-diff.patch`。
- 新增 `KbMigrationHandler` 与 `KbMigrationService`：readiness（当前有效扫描 SUCCEEDED、可投影条目 > 0、`KB_MIGRATION` 阶段已绑定）、创建/执行/查询、产物入库、不可重试错误码。
- stdout 合同：agent 最终回复仅一行 `DONE` 或 `FAIL: <原因>`；全部结果落 `output/` 文件（规避 64KB stdout 截断）。

## Capabilities

### New Capabilities

- `kb-migration-runtime`：投影身份与过滤、任务创建资格、执行隔离与输入完整性、结果产物、失败语义与边界。

### Modified Capabilities

- `model-profile-management`：阶段绑定白名单扩展 `KB_MIGRATION`。
- `backend-database-design`：`kb_migration_runs` 表与三处 CHECK 扩展。
- `object-storage-artifacts`：`KB_MIGRATION` ownerType 及两类产物。
- `backend-api-design`：发起/列表/详情 API 契约。

## Impact

- 唯一 OpenSpec 根与实施范围为 `backend/`；本 change 路径为 `backend/openspec/changes/add-kb-migration-task/`。不修改前端、tools 与根 compose；技能完整改编、镜像内 QEMU/交叉工具链与 Dockerfile 变更属配套 change `add-kb-migration-runtime-assets`（后立），本 change 在 classpath 内置最小可运行技能资源。
- 新增包 `cn.rvport.backend.kb.migration`（现有 `cn.rvport.backend.migration` 已被 MigrationInput 域占用，不可混用）。
- 复用现有受管 agent runtime（OPENCODE）、个人模型绑定、任务/SSE、对象存储、路径脱敏（ServerPathGuard）与 worker 基础设施；不新增 AgentType、不更新 CLI 版本、不新增 compose 服务。
- 二期明确不做：远程真机验证、板机凭据通道、marking 结构化回填表（不改 findings.status）、多验证目标产物展开、dry-run。
- 单元测试在 Docker 内执行，整体及受影响模块行覆盖率至少 90%，分支覆盖率至少 90%；验收含完整本地 Compose 冒烟（发起 KB_MIGRATION → 产物可下载）。
