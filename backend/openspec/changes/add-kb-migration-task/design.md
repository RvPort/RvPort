# Design

## Context

本设计的项目路径均相对于仓库根目录，唯一 OpenSpec 根是 `backend`，基线分支 dev。上游依据：`docs/AI迁移能力整合方案.md`（2026-10-29 v0.1）的一期最小路径，及其后三次实测修订（2026-11-19/20）：

1. runtime 选型实测：内网端点（wxai 系）仅支持 chat completions、`/v1/responses` 404；新版 codex 仅支持 Responses 协议。opencode 按 `OpenCodeAgentRuntimeAdapter` 生产形态实测通过：无头执行、`.opencode/agent/*.md` 自定义 agent 加载、task 工具派生 subagent、workspace 写文件、单行 `DONE`。
2. 摸底勘误：实际表名为 `findings`（非方案文档的 kb_findings）；`kb/ai` 包与 `KB_AI_EXTENSION` 已被 2026-11-18 change 退役删除；当前 agent workspace 范本是 `report/review/ReportAiReviewRuntime`。
3. **pi 探针实测（2026-11-20，第 0 步）**：`@earendil-works/pi-coding-agent`（rvport-web 镜像同款；npm 另有 `@mariozechner/pi-coding-agent` 0.73.0，bin 同名 `pi`，探针用后者验证）原生支持 `--print` 无头与 `--skill <path>`；实现 **Agent Skills 标准（agentskills.io）**，文档明确可直接加载 Claude Code 技能目录——riscv-migrate 的 SKILL.md 即此标准。20 行 provider extension 注册 wxai（`api: "openai-completions"`）即接通内网模型；探针 workspace 中"规则只存在于 SKILL.md"的判定 100% 正确，输出单行 DONE。
4. **rvport-web 调研（2026-11-20，第 1 步）**：上游 `zte-riscv/rvport-web` 的 Agent mode 即本 change 的目标形态——执行器镜像 `codemig-agent-base`（Dockerfile.template：qemu-user + 双架构交叉工具链 + node + pi + `pi install npm:pi-subagents pi-mcp-adapter` + riscv_doc_mcp）、宿主 `/app` bind mount、`docker run --rm --network host` 一次性容器、技能"总是贴皮"（assets 的 SKILL.md 运行时 cp 到 `/root/.pi/agent/skills/` 覆盖镜像内版本——**改 SKILL.md 不用重建镜像**，与我们的热更新需求同构）、uid/gid chown 处理宿主文件权限、`.skills/` 与 `.prompt.txt` 从迁移产物中排除。其 migrate/uttest/asmrepair 三技能带 `tools:` frontmatter（含 riscv-doc-mcp）与中语言守则模板。

**架构定案（用户决策 2026-11-20）**：一期实现的 worker 内子进程形态保留为已验证的基线；本 change 后续演进为**独立执行器容器**形态——技能+pi+QEMU+工具链独立镜像、技能 volume 热更新（git 管理）、补丁接受工作流。演进遵循下方 D8。

## Goals / Non-Goals

**Goals:**

- findings → `input/scan_result.json` 投影：跨 run 稳定条目身份、结构化过滤与 solver_type 映射。
- `KB_MIGRATION` 任务全链路：创建资格、worker 执行、产物入库与归档候选、失败语义。
- agent 执行隔离：workspace 只含投影涉及文件、输入 hash 校验、技能注入、结果全落 `output/`。
- Docker 单测 90/90 覆盖与本地 Compose 冒烟。
- 演进目标（D8）：独立执行器容器 + pi 原生技能搬运 + 技能热更新 + 补丁接受工作流。

**Non-Goals:**

- 本 change 不实现 D8 的执行器容器与热更新（独立演进 change 承接）；本 change 交付其数据合同与可复用骨架。
- 不修改 findings 状态或建立 marking 回填表（二期）；不做远程真机验证与板机凭据（二期）。
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

### D4 复用 OPENCODE，不新增 AgentType

**部署形态决策记录（2026-11-20 评估定案）**：迁移 agent 与 QEMU/交叉工具链全部进现有 backend 镜像，由 backend-worker 以本地子进程方式执行，不新增 compose 服务与队列。评估过独立迁移 worker 镜像（"第二个 worker"形态）后否决，依据三个事实：

1. `RVPORT_WORKER_CONCURRENCY_LIMIT` 默认 1——单 worker 串行下不存在迁移与其他任务的资源挤占；拆双 worker 反而使总并发翻倍，双 JVM + 交叉编译 + QEMU 的内存峰值叠加（12GB 级机器）比现状更危险；
2. "第二个 worker"形态无安全增益——迁移容器仍运行完整后端代码并持有全部凭据，与方案风险 #4 的根因（容器 root + 全信 agent）无关；真隔离需精简执行器镜像 + 容器编排，超出本期范围；
3. 工具链层以独立构建阶段 + final `COPY --from`（照 bitcode-toolchain 现成模式）即可保证 Java 迭代不触发工具链重下载；且同镜像分发总量（单镜像 ~5GB）低于双镜像（3GB + 5GB）。

切换独立迁移 worker 的触发条件（满足其一再立项）：迁移并发常态化（多项目同时迁移、worker 并发需放开）；出现真实的 agent 安全隔离要求（届时正确形态是精简执行器镜像而非第二全量 worker）；镜像分发成为部署瓶颈。

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

### D8 独立执行器演进（用户决策 2026-11-20，后续 change 承接）

一期 worker 内子进程形态是已验证基线（冒烟打通至 agent 执行层）；产品需求（技能热更新、独立容器、对标 rvport-web Agent mode）要求演进为执行器容器架构。要点与依据：

1. **执行器镜像**：`pi（npm 钉版）+ provider extension（wxai, openai-completions）+ qemu-user-static + RISC-V 交叉工具链 + 可选 riscv-doc-mcp`。配方参照 rvport-web `server/assets/ai-agent/Dockerfile.template`（已解决 fastmcp 版本上界、pi-subagents/mcp-adapter 安装、apt 源模板化等坑）。
2. **技能原生搬运**：pi 实现 Agent Skills 标准（探针验证 `--skill <path>` 无头加载 Claude Code 形态 SKILL.md），`rvport-skills/riscv-migrate` 整目录挂载即用；不需要改写为 opencode agent md。入口适配（input/output 约定 + 单行 DONE）作为技能仓库内的薄平台适配文件维护，不动原文件。
3. **技能热更新**：技能以 git 管理的 volume 挂载（非 classpath、非镜像层）。更新按钮 = `git fetch` + rev-list/diff 对比 + 展示差异 + `git pull`。参照 rvport-web 的"总是贴皮"模式（运行时 cp 覆盖，改技能不重建镜像）。
4. **容器调用**：worker 侧 `docker run --rm` 一次性容器（宿主 workspace bind mount 到 /app、`--network host`、uid/gid chown、产物排除 `.skills/` 与 prompt 文件），参照 rvport-web `_assemble_docker_run_cmd`。取消 = kill 容器；孤儿容器按容器名前缀清理。
5. **补丁接受工作流**（新能力，独立立项）：`.diff` 产物展示 → 用户接受 → apply 到 working copy → 生成新 source_revision → 供后续构建验证。数据合同复用本 change 的 scannerFindingId 双轨与产物体系。
6. **并发模型**：任务-容器一一对应；专属队列 + 迁移专属 worker；实际并发上限由宿主容量决定。本 change 的 TaskRetryPolicy 不可重试码、publish 纪律、产物入库链全部复用。

探针与调研产物：`/tmp/pi-probe/`（wxai provider extension + 探针技能）、`/tmp/rvport-web-ref/`（上游参考实现 clone）。
