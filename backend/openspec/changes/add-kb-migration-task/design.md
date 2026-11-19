# Design

## Context

本设计的项目路径均相对于仓库根目录，唯一 OpenSpec 根是 `backend`，基线分支 dev。上游依据：`docs/AI迁移能力整合方案.md`（2026-10-29 v0.1）的一期最小路径，及其后两次实测修订（2026-11-19）：

1. runtime 选型实测：内网端点（wxai 系）仅支持 chat completions、`/v1/responses` 404；新版 codex 仅支持 Responses 协议。opencode 按 `OpenCodeAgentRuntimeAdapter` 生产形态（workspace 内 `opencode.json` + `run --auto --dir <ws> --model rvport-openai-compatible/<model>` + `OPENCODE_CONFIG` env）实测通过：无头执行、`.opencode/agent/*.md` 自定义 agent 加载（规则仅存在于 md 且判定正确）、task 工具派生 subagent、workspace 写文件、最终回复单行 `DONE`。
2. 摸底勘误：实际表名为 `findings`（非方案文档的 kb_findings）；`kb/ai` 包与 `KB_AI_EXTENSION` 已被 2026-11-18 change 退役删除；当前 agent workspace 范本是 `report/review/ReportAiReviewRuntime`（只物化清单文件、hash 完整性校验、`AiReviewValidator` 结果校验），不是已删除的 KbAiExtensionAgentRuntime。

## Goals / Non-Goals

**Goals:**

- findings → `input/scan_result.json` 投影：跨 run 稳定条目身份、结构化过滤与 solver_type 映射。
- `KB_MIGRATION` 任务全链路：创建资格、worker 执行、产物入库与归档候选、失败语义。
- agent 执行隔离：workspace 只含投影涉及文件、输入 hash 校验、技能以 classpath 资源注入、结果全落 `output/`。
- Docker 单测 90/90 覆盖与本地 Compose 冒烟。

**Non-Goals:**

- 不新增 AgentType（复用 OPENCODE）；不更新任何 CLI 版本。
- 不修改 findings 状态或建立 marking 回填表（二期）；不做远程真机验证与板机凭据（二期）。
- 不修改前端与扫描器；根 compose 仅新增 `backend-migration-worker` 服务（见 D4）。
- 不做 dry-run、多验证目标产物展开、missing_class 条目投影。

## Decisions

### D1 条目身份双轨（投影合同核心）

`findings.id` 是每 run 全删重插的 DB UUID，`fingerprint` 公式含 runId，两者跨 run 均不稳定；跨 run 稳定键是 `evidence.scannerFindingId`（`finding:<sha256>`，扫描器 stable_view_id）。因此：

```json
{
  "schemaVersion": "kb-migration.v1",
  "scanRunId": "<uuid>",
  "items": [
    {
      "id": "<evidence.scannerFindingId>",
      "sourceFindingIds": ["<当前 run findings.id>"],
      "status": "TODO",
      "solver_type": "intrinsics | macros | inline_asm | asm_file | build_makefile | build_cmake | headers",
      "file": "<findings.file_path 相对路径>",
      "line": <line_start>,
      "symbol": "<evidence.symbol>",
      "matched_text": "<evidence.matchedText>",
      "severity": "<findings.severity>",
      "blocking": <evidence.blocking>,
      "description": "<title：description>",
      "suggestion": "<suggestion>",
      "evidence": [{"file": "...", "line": 1, "symbol": "..."}]
    }
  ]
}
```

- 一个 finding 可展开多条目（一对多），子条目共享 `sourceFindingIds`。
- 回填（本 change 仅产物展示，不写库）与二期 marking 表均以 `id` 为主键、`sourceFindingIds` 为 DB 句柄。

### D2 过滤与映射规则

过滤（不投影）：`evidence.actionable == false`；`evidence.conclusionStatus == "inactive"`；`severity == "info"` 且非 actionable；`evidence.scannerFindingId` 相同的重复条目（保第一条）。

`fact.kind`（evidence JSONB `factKind`，9 值）→ `solver_type`：

| fact.kind | solver_type |
| --- | --- |
| intrinsic_call / vector_type | intrinsics |
| macro_invocation / macro_replacement | macros |
| architecture_probe（内联 asm 上下文） | inline_asm；`.S`/`.s`/`.asm` 文件归 asm_file |
| compiler_flag / build_argument | build_makefile（Makefile 类）/ build_cmake（CMake 类，按 file 后缀分流） |
| header_include | headers |
| test_asset | 跳过 |
| 未知 kind | 兜底 inline_asm 走完整闭环（与扫描器对 architecture_probe 的回落语义一致，疑义从重） |

注意取 `evidence.factKind` 而非 `findings.category`（后者是 conclusion.findingType，几十种 observation.kind，语义不同）。

### D3 workspace 布局与技能注入

照 `ReportAiReviewRuntime` 模式（`Files.createTempDirectory("rvport-kb-migration-")`，finally 删除；产物先入库再删目录）：

```text
<workspace>/
  input/scan_result.json      # 投影输出
  input/manifest.json         # schemaVersion/expectedCount/entriesFile/sourceRoot/outputFile
  source/<涉及文件>            # 仅投影条目引用的文件（file_path 相对源码根），不整树拷贝
  .opencode/agent/*.md        # 技能（classpath:kb-migration/agents/ 释放），mode: subagent
  opencode.json               # OpenCodeAgentRuntimeAdapter 生成（现有机制）
  output/migration_result.json
  output/migration-diff.patch
```

- 源码物化从 `source_revision.workingCopyPath` 读取，经 ServerPathGuard 路径校验；文件缺失计入 manifest 的 `missingFiles`，不中断（行号漂移是既有语义）。
- 执行前后对 `input/scan_result.json` 与 `input/manifest.json` 做 SHA-256 校验，防 agent 篡改输入。
- 技能资源（`backend/src/main/resources/kb-migration/agents/`）本 change 内置最小可运行集：主迁移 agent + reviewer subagent；完整改编（四角色、QEMU 验证细则、指令映射表）随配套 change 替换内容，布局不变。

### D4 独立迁移镜像与队列分流（用户决策 2026-11-19）

采用"第二个 worker 镜像"形态，而非全新容器编排：

- **镜像**：backend/Dockerfile 新增 `migration` 构建目标——在现有运行时阶段之上叠加 `qemu-user-static`（apt）与 RISC-V 交叉工具链（照 bitcode-toolchain 的 aria2c + 双 sha256 + 确定性解压模式，`RISCV_TOOLCHAIN_URL` 构建参数钉版本，保留本地预置逃生出口）。产出 `rvport-backend-migration:local`。技能 md 仍走 classpath（随 jar 版本化，不进镜像层）。
- **队列分流**：`rvport.queue.task` 现为单 quorum 队列 `rvport.tasks.default`（routingKey=队列名，direct exchange）。新增 `rvport.tasks.kb-migration` quorum 队列 + DLQ 与绑定；`TaskQueueClient` 按任务类型选择 routingKey（`KB_MIGRATION` → 专属队列，其余不变）。监听侧队列名已配置化（`RVPORT_TASK_QUEUE_NAME`），migration worker 容器设该 env 即只消费迁移队列。
- **compose 服务**：新增 `backend-migration-worker`（image `${BACKEND_MIGRATION_IMAGE:-rvport-backend-migration:local}`、`RVPORT_WORKER_ENABLED=true`、`RVPORT_TASK_QUEUE_NAME=rvport.tasks.kb-migration`（及对应 DLQ env）、独立 `mem_limit` 护栏、模型凭据 env 与既有 worker 同步维护——三份环境块的漂移风险在验收清单中显式检查）。
- **取消与超时**：按 workerInstanceId 前缀的取消队列自动覆盖新 worker；进程治理（supervisor、`registerLocalProcess`、stdout 64KB 纪律）全部复用本地子进程模式。
- **不做的**：不做跨容器 workspace 共享（迁移 worker 本地临时目录即可）；不做容器间 API 编排；迁移 worker 不需要 DB/Redis 之外的新依赖。

### D4b 复用 OPENCODE，不新增 AgentType

agent_runtimes 表每 agent_type 唯一行且 CHECK 收紧，新增类型需种子与 CHECK 迁移；而 OPENCODE 已满足全部需求（chat completions 兼容、自定义 agent md、subagent）。`KbMigrationAgentRuntime` 通过 `AgentRuntimeAdapterRegistry.get(AgentType.OPENCODE)` 调用，模型凭据经现有 env 注入链（`RVPORT_AGENT_*`），不接触明文 key。

### D5 stdout 合同与结果校验

- prompt 合同：最终回复仅一行 `DONE` 或 `FAIL: <原因>`（实测已验证可遵守）；stdout 其余内容仅进任务日志。
- `output/migration_result.json` 校验（照 `AiReviewValidator` 模式）：条目 `id` 必须属于投影集合（unknown 计数）、不得重复（duplicate 计数）、缺失计 missing；状态枚举 `DONE | NO_WORK_NEEDED | PENDING`（PENDING 为合法终态，对应未处理或需人工条目）。全部缺失/校验失败时任务 FAILED，部分成功允许 SUCCEEDED 带 counts 摘要。
- `migration-diff.patch` 由 workspace 内 `source/` 与输出侧统一 diff 汇总生成（agent 被禁止改 `input/` 与 `source/` 之外路径，diff 只反映改码结果）。

### D6 任务接线与失败语义

- `KbMigrationService.create`：readiness 三查（当前有效扫描 SUCCEEDED、投影条目 > 0、`KB_MIGRATION` 阶段个人绑定可解析）→ 建 `kb_migration_runs` 行 → `TaskService.create`（inputSnapshot 内嵌 `ModelExecutionSelection.from(actorUserId, config)`，照报告复核防换绑模式）。
- handler 内 DB 写全部走 `context.publish()`；外部执行（agent、diff 生成）在 publish 外。
- 不可重试错误码（直接 FAILED，不走 `TaskRetryPolicy` 默认重试）：`KB_MIGRATION_MODEL_NOT_CONFIGURED`、`KB_MIGRATION_NO_ENTRIES`、`AGENT_OUTPUT_INVALID`；超时由 ExecutionBudget 强制（沿用 `model_profile.max_runtime_seconds`，建议用户对该阶段配置 ≥ 7200，不在代码里放宽上限）。
- 产物入库走 `StoredArtifactService` 的 TaskExecutionContext 重载（S3 上传在 attempt 事务外）；`archive_candidates` 登记两类产物。

### D7 API 契约

- `POST /api/projects/{projectId}/kb-migrations`：创建（返回运行与任务 ID；失败返回双语原因）。
- `GET /api/projects/{projectId}/kb-migrations`：分页列表。
- `GET /api/kb-migrations/{runId}`：详情（状态、条目计数、产物摘要、任务引用）。
- 产物下载复用现有 stored artifacts 下载链；项目归属校验照 `KbScanController` 模式。
