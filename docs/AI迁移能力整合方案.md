# RvPort AI 迁移能力整合方案

| | |
| --- | --- |
| 状态 | 草案（评审中） |
| 版本 | 2026-10-29 v0.1 |
| 范围 | AI 代码迁移能力、知识/词表整合、MCP 知识库服务的容器化接入 |
| 不在范围 | 真实 RISC-V 编译器建设、性能优化度量、ARM 源工程参考侧（一期） |

## 1. 背景与目标

RvPort 当前具备完整的静态扫描链路（kb-scanner → kb_findings → 三层建议展示），但**代码修改本身仍由人工完成**。本方案整合三个上游仓库的能力，补齐"AI 自动迁移 + 板上验证"环节：

| 上游仓库 | 贡献 | 接入通道 |
| --- | --- | --- |
| [rvport-skills](https://github.com/zte-riscv/rvport-skills) | 三技能（scan/migrate/verify）流程骨架、条目状态机协议、迁移门禁设计 | 技能改编后随镜像分发，Pi agent 加载执行 |
| [rvport-web](https://github.com/zte-riscv/rvport-web) | 多语言检测点设计（Java/Go/Rust/Python）、RISC-V 侧宏词表、builtin 词表 | 检测点按 kb-scanner 合同重写；词表走知识生产管线 |
| [rvport-mcp](https://github.com/zte-riscv/rvport-mcp) | 本地 stdio MCP 知识库（RISC-V 规范混合检索，4 库） | 知识库数据 + server 随镜像分发，agent 会话内拉起 |

三者均为 Apache-2.0（rvport-mcp 知识库数据为 CC BY 4.0），与本项目许可证兼容；搬运的数据内容经 collector 标注 source identity，满足署名要求。

**一期目标**（x86 → riscv64，C/C++ 工程）：平台用户在项目工作流中发起"AI 迁移"任务 → agent 在容器内按条目改码 → 交叉编译 → SSH 到 RISC-V 物理机验证输出一致性 → 结果与验证报告回流平台。

**明确不做**：QEMU 模拟验证（被真机替代）；riscv-scan 技能（被 kb-scanner 扫描 + 投影器替代）；用户可编辑词表（知识冻结纪律不变）。

## 2. 总体架构

### 2.1 部署形态

AI 能力部署进现有 backend 镜像（与 codex/dsh/opencode 同模式），不新增 compose 服务：

```text
┌────────────────────── 同一镜像 rvport-backend:local ──────────────────────┐
│  backend (API 模式)                    backend-worker (worker 模式)       │
│                                                                        │
│  KB_MIGRATION 任务（TaskHandler，RabbitMQ 派发）                         │
│    └─ MigrationAgentRuntime                                            │
│         ├─ ① 投影：kb_findings → workspace/input/scan_result.json      │
│         ├─ ② PiAgentRuntimeAdapter                                     │
│         │     pi --print --skill riscv-migrate --skill riscv-verify \  │
│         │        --approve --no-mcp …（MCP 经会话配置拉起）              │
│         │     ├─ /app/tools/rvport-skills/（改编版技能）                 │
│         │     ├─ /app/tools/rvport-mcp/（stdio MCP，Pi 子进程拉起）      │
│         │     └─ /opt/rvport/toolchains/riscv/（交叉工具链）            │
│         └─ ③ 回填：workspace/output/ → kb_findings 状态                 │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │ ssh / scp（凭据解密后注入子进程 env）
                                   ▼
                        ┌─────────────────────┐
                        │ RISC-V 物理机（共享）│
                        │ ~/rvport-runs/<task> │
                        │ flock 串行执行二进制 │
                        └─────────────────────┘
```

### 2.2 数据流与状态权威

**数据库是权威，scan_result.json 是每阶段重新生成的工作副本**：

```text
kb_findings (PostgreSQL，权威)
   │ ① 投影（每阶段开始时从 DB 重新生成）
   ▼
workspace/input/scan_result.json     ← agent 唯一的状态输入
   │ ② agent 消费：按状态机推进条目 TODO → START → DONE
   ▼
workspace/output/migration_result.json（条目状态 + marking）
   │ ③ 回填（按 finding_id 写回 DB）
   ▼
kb_findings 状态更新 → verify 阶段重新投影 → marking 回写 → 闭环
```

禁止让同一份 scan_result.json 跨阶段滚动累积状态（容器重启、任务重试、文件损坏都会造成状态漂移，且违背单一写入者协议）。

### 2.3 新增组件一览

| 组件 | 位置 | 类型 | 作用 |
| --- | --- | --- | --- |
| `PiAgentRuntimeAdapter` | `backend/.../agentruntime/` | Java，~80 行 | Pi 无头调用（第 4 次实例化现有模式） |
| `MigrationTaskProjector` | `backend/.../migration/` | Java | kb_findings → scan_result.json 条目投影 |
| `MigrationResultBackfill` | `backend/.../migration/` | Java | marking 回填 kb_findings |
| `KB_MIGRATION` 任务类型 | handler + service | Java | 任务生命周期、产物入库 |
| 改编版技能 | `tools/rvport-skills/` | SKILL.md + 脚本 | 单 agent 阶段化流程 + 板上验证协议 |
| `rv_board.sh` | 技能 resources | bash | 物理机唯一入口（probe/run/gcov） |
| 知识 collector | `tools/kb-scanner/scripts/` | Python | 上游词表 → 候选知识条目 |
| MCP 预置 | `tools/rvport-mcp/`（vendored） | 数据 + server | agent 会话内知识检索 |

## 3. AI 能力整合详细设计

### 3.1 投影规范（scan_result.json 合同）

**这是本方案最先冻结的合同**，约束后续所有环节。

**条目 Schema**（对齐 rvport-skills `references/project_scan.md`，保留其状态机与 solver_type 枚举）：

```json
{
  "items": [
    {
      "id": "<kb-scanner finding.id，原样使用>",
      "sourceFindingIds": ["<finding.id>"],
      "status": "TODO",
      "solver_type": "intrinsics",
      "file": "src/foo.c",
      "line": 56,
      "symbol": "_mm256_add_epi32",
      "matched_text": "_mm256_add_epi32",
      "arch": "x86_64",
      "severity": "high",
      "blocking": true,
      "description": "<conclusion.title：conclusion.reason>",
      "suggestion": "<conclusion.suggestion>",
      "evidence": [
        {"file": "src/foo.c", "line": 56, "symbol": "_mm256_add_epi32"}
      ]
    }
  ]
}
```

**映射规则**：

| kb-scanner V2 | 条目 | 规则 |
| --- | --- | --- |
| `fact.primaryLocation` / `fact.symbol` / `fact.matchedText` | `file`/`line`/`symbol`/`matched_text` | 直接映射（路径已是项目相对路径） |
| `conclusion.severity` / `conclusion.blocking` | `severity` / `blocking` | 直接映射；blocking 决定 planner 排序权重 |
| `conclusion.suggestion` | `suggestion` | 初始建议，agent 可参考 |
| `fact.kind` | `solver_type` | 见下表 |
| `evidenceChain.primaryPath` 摘要 | `evidence[]` | 压缩为 file/line/symbol 三元组列表，不搬整链 |

**fact.kind → solver_type**：

| `fact.kind` | `solver_type` | 说明 |
| --- | --- | --- |
| `intrinsic_call` | `intrinsics` | |
| `vector_type` | `intrinsics` | 附 vector width 信息 |
| `macro_invocation` / `macro_replacement` | `macros` | |
| `architecture_probe`（asm 上下文） | `inline_asm` | 按 matchedText 是否 asm 语法判别；`.S` 文件归 `asm_file` |
| `compiler_flag` / `build_argument` | `build_makefile` / `build_cmake` | 按文件后缀分流 |
| `header_include` | `headers` | |
| `test_asset` | —（跳过） | 不产生迁移条目 |

**过滤规则**（不投影的 Finding）：

- `conclusion.actionable == false` 或 `conclusion.status == "inactive"` → 跳过（已被架构宏保护，无迁移工作量）；
- hint 类（`severity == "info"` 且非 actionable）→ 跳过；
- 重复指纹（`fingerprint` 字段）→ 去重。

**一对多处理**：一个 Finding 可展开为多条目（如一条 `architecture_probe` 含多个宏命中），子条目 `sourceFindingIds` 指向同一 finding.id；回填时按 `sourceFindingIds` 聚合回写。

### 3.2 marking 回填 Schema

```json
{
  "findingId": "<uuid>",
  "status": "DONE | NO_WORK_NEEDED | REWORKED",
  "resolver": "ai | human",
  "changeSummary": "将 _mm256_add_epi32 替换为 __riscv_vle32… 循环实现",
  "files": ["src/foo_riscv.c"],
  "verification": {
    "status": "PASS | FAIL | PENDING",
    "report": "test/foo/report.md",
    "coverage": "test/foo/coverage.txt"
  },
  "suggestionUsed": true,
  "reason": "NO_WORK_NEEDED 时必填：说明判定误报的依据"
}
```

- `NO_WORK_NEEDED` 是误报通道：回填后计入 findingType 级误报率统计，反哺规则与分类器调优；
- `verify` 阶段发现缺陷时，对应条目 `DONE` 恢复 `START` 回流（沿用原技能 REFLOW 语义）。

### 3.3 SSH 板上验证协议

**物理机是共享单资源**，所有交互经 `rv_board.sh` 唯一入口：

```bash
# 凭据：不落盘、不进镜像。DB 加密存储（现有 credential 体系，
# RVPORT_CREDENTIAL_MASTER_KEY 解密），worker 注入子进程 env：
#   RV_BOARD_HOST / RV_BOARD_PORT / RV_BOARD_USER / RV_BOARD_KEY_FILE
rv_board_probe                       # 连通性 + ISA/VLEN/内存探测 → board_info.json
rv_board_run <task-id> <bin> [args]  # flock 串行 + 执行 + 输出/退出码回传 + 远端清理
rv_board_gcov <task-id>              # .gcda 打包回传，容器内 riscv64-gcov 离线分析
```

**验证口径**（沿用原技能协议）：整数/字节流/checksum 逐字节一致；浮点按容差（`rel_err=1e-9`/`abs_err=1e-12`）；对比基准为容器内 x86 参考侧输出（系统 gcc 编译运行）。

**编译留在容器，物理机只执行**：工具链版本由镜像锁定（可复现）；物理机无状态、不被编译任务污染。

**安全清单**：

- 物理机专用低权限账号，仅可写 `~/rvport-runs/`；
- 私钥经共享卷只读挂载（权限 600）或凭据库解密到临时文件（任务结束删除）；
- `known_hosts` 预注入镜像或 `accept-new`，禁止交互式首连确认；
- 所有远端命令 `ConnectTimeout=10` + `timeout <N>` 包裹；trap 兜底清理远端目录；
- 任务失败时远端残留目录由下次 `rv_board_probe` 顺带清扫（超龄删除）。

### 3.4 技能改编要点

fork rvport-skills 至 `tools/rvport-skills/`（Apache-2.0，保留版权与 NOTICE），改编清单：

| 原设计 | 改编为 | 原因 |
| --- | --- | --- |
| `agents/` 目录（Claude Code subagent 约定） | 删除；各角色职责改写为 SKILL.md 阶段化自查清单 | Pi 无 subagent；单 agent 顺序执行角色切换 |
| riscv-scan 技能整体 | 删除 | 由 kb-scanner + 投影器替代；镜像不含此技能 |
| QEMU 验证（3.x/4.x） | `rv_board.sh` SSH 验证 | 真机替代模拟 |
| `prepare_verify_env.sh` 运行时下载 | 简化为存在性检查 + 板机连通探测 | 依赖已烘进镜像 |
| VLEN 可调参数（QEMU -cpu） | 启动时探测物理机 ISA/VLEN 写入报告 | 硬件能力固定 |
| `config.env` 工具链 URL 项 | 移除；保留 `RISCV_DOC_MCP_URL` 等运行时项 | 配置面收窄 |
| 知识库查询 `query.py`（HTTP MCP） | 默认指向本地 stdio MCP（容器内路径） | 见 3.5 |

**保留不动**：条目状态机（TODO/START/DONE）、冻结令牌集（PLAN_READY/NO_WORK_NEEDED/…）、单一写入者纪律、浮点容差口径、验证产物落位规范（`<project_root>/test/<目标名>/`）。

**入口约定**（写进 SKILL.md 与 adapter prompt）：agent 读取 `input/scan_result.json`（平台预生成，禁止重扫），按 riscv-migrate 流程处理条目，最终回复仅一行 `DONE` 或 `FAIL: <原因>`，全部结果写 `output/`。

### 3.5 MCP 知识库接入

- **部署**：`tools/rvport-mcp/` vendored 进仓库（server.py + docs/ 知识库数据约几十 MB）；镜像内预装完整 venv（`RISCV_MCP_FULL=1`）并预下载嵌入模型（`HF_HOME=/opt/rvport/mcp-models`，~95MB），确保**离线容器内 hybrid 检索可用**（纯 BM25 降级虽可用，但中文语义查询恰是迁移场景最需要的）；
- **接入**：Pi 会话级 MCP 配置拉起 stdio server（`bash /app/tools/rvport-mcp/start.sh`），随 agent 生命周期；adapter 命令行不带 `--no-mcp`；
- **技能衔接**：`query.py` 的 `RISCV_DOC_MCP_URL` 默认指向本地实例，未配置时降级速查表的既有逻辑保留；
- **知识库更新**：随上游 rvport-mcp 版本 vendored 更新（spec_repos.yml 的 pin 链保证可追溯），不在此方案内自建重建流水线。

### 3.6 任务生命周期与产物

- 新增任务类型 `KB_MIGRATION`：`RvPortTaskTypes` + `MigrationHandler`；执行侧 `MigrationAgentRuntime` 复用 workspace 合同（input/manifest.json、output/、source/ 整树拷贝、SKIP_DIRS 排除）；
- 模型绑定：`ModelProfileStage.KB_MIGRATION` → PI runtime（复用 `ModelProfileService.resolveAgentForStage`）；超时取 `max_runtime_seconds`（迁移任务建议默认放宽至 7200s）；
- 产物入库（StoredArtifact，ownerType=KB_MIGRATION）：
  - `migration-diff.patch`（全部条目变更的统一 diff）；
  - `migration_result.json`（条目终态 + marking）;
  - 每验证目标 `report.md` / `coverage.txt` / `run.log`；
  - `board_info.json`（板机特性快照，可复现性的一部分）;
- 进程治理：adapter 补 `registerLocalProcess` 注册（现有 `DefaultCodexProcessRunner` 缺失，取消/worker 失联时进程树会被连根终止——本方案顺带修复该欠账）。

### 3.7 容器镜像变更（backend/Dockerfile）

```dockerfile
# final 阶段新增（均为既有模式的第四次实例化）：
ARG PI_CLI_VERSION=<锁定>
ARG RISCV_TOOLCHAIN_URL=<riscv-collab gcc 14.2 nightly 2024.09.03，与技能钉死版本一致>

# 1) agent CLI：插入现有 npm install -g 行 + pi --version 自检
# 2) apt 增加 openssh-client rsync（当前镜像缺失，板上验证必需）
# 3) 技能与 MCP：
COPY tools/rvport-skills /app/tools/rvport-skills
COPY tools/rvport-mcp  /app/tools/rvport-mcp
RUN <MCP venv 预装 + 模型预下载至 /opt/rvport/mcp-models>
# 4) RISC-V 工具链：复用 bitcode-toolchain 阶段的 aria2c + sha256 + 确定性 tar 模式
RUN <下载校验解压至 /opt/rvport/toolchains/riscv/>

ENV RISCV_SKILLS_ROOT=/app/tools/rvport-skills \
    RVPORT_RISCV_TOOLCHAIN_ROOT=/opt/rvport/toolchains/riscv/<dir>
```

compose 变更：backend 与 backend-worker 两服务 environment 各加 `RV_BOARD_HOST/PORT/USER` 与 `RV_BOARD_KEY_FILE`（指向共享卷 secrets 路径）；建议 backend-worker 增加 `mem_limit`（Pi + 交叉编译 + JVM 同容器，无护栏会互相挤占）。**不新增服务**。

## 4. 规则整合详细设计

### 4.1 认知校准（先于一切）

kb-scanner 不是"词表匹配器"：词表只回答"什么是 x86 特有的"；扫描能力的主体是 2.2 万行自建分析代码（AST 解析、证据图、宏实参传递链、构建选择证明、用途闭包、五态不确定性语义）。rvport-web 的 solver 是行文本正则、"匹配即报"，两者存在代差。**因此规则整合的策略是：拿它的检测点设计与数据，不拿它的实现与语义。**

### 4.2 词表补充（走知识生产管线，禁止直改 generated/）

```text
rvport-web 词表（riscv.macro 203 项、x86.builtin 964 项、rust_arch_functions.json 等）
  → 新 collector：scripts/collect_rvport_web_entries.py（模仿 collect_gcc_x86_entries.py）
  → 候选条目带 source identity（来源 rvport-web@<commit>，Apache-2.0 标注）
  → audit/candidate-decisions.json 逐条 accepted/rejected 决策（人工审校）
  → knowledge-manifest.json 更新（sha256 链）
  → 随版本冻结发布
```

**红线**：

- `scanner-manifest.json` 的 sha256 校验链不可破坏——直改 generated 文件会致运行时拒载；
- 已冻结的 GCC/Clang entries（analysis-capabilities.md 钉死哈希）不动，新数据走新文件、新 sourceId；
- 知识 JSON 仍不含 regex/severity/suggestion（数据与策略分离纪律不变）。

**优先级排序**：

1. **`riscv.macro`（RISC-V 侧宏，203 项）——最高价值**。现有知识包几乎纯 x86 侧；补目标侧词汇直接增强 `architecture_guards` 双向推理（判别"代码已有 RISC-V 分支"）与架构家族分析；
2. `x86.builtin`（964 项）与 GCC/Clang 现有数据做差集，仅补缺口；
3. opcode/寄存器表与 binutils 现有数据比对，预期增量有限。

### 4.3 新语言扫描扩展（Java / Go / Rust / Python）

每个新语言 = **一个独立交付单元**，按 kb-scanner 合同全新实现，不移植 solver 代码：

| 层 | 实现 | 从 rvport-web 拿什么 |
| --- | --- | --- |
| frontend | tree-sitter 对应语法包（java/python/rust/go 均有官方 grammar） | 检测点清单（见下） |
| detector | 新 `DetectorDefinition`，产出 Observation | 匹配点设计 |
| 语义 | Evidence → Fact → Observation → Classification 全链路，保持五态不确定性 | **不拿**"匹配即报"语义 |
| 词表 | 走 4.2 collector 通道 | `rust_arch_functions.json` 等 |

**各语言检测点**（源自 rvport-web solver 设计，按我们的合同重写）：

| 语言 | 检测点 |
| --- | --- |
| Java | JNI `System.load/loadLibrary`、`Runtime.getRuntime()` 链式加载、arch-specific 依赖 |
| Python | `ctypes.CDLL`/`dlopen`、cffi `FFI()/cdef/set_source/verify`、C 扩展 import |
| Go | cgo 块（`import "C"`）、`unsafe`/`syscall` 使用、go.mod 中 arch 相关依赖 |
| Rust | `cfg(arch)` 表达式（rvport-web 有手写 cfg parser 可参考）、`asm!` 宏、arch crates（`std::arch::x86_64`） |

**节奏约束**：一次一个语言，先选客户项目占比最高者；且**与迁移技能对齐**——riscv-migrate 对该语言的改码能力同步支持后才对外发布该语言端到端。每个语言走一个 OpenSpec change（`kb-scanner-<lang>-frontend`），配齐单测与 90/90 覆盖门禁。

### 4.4 与 AI 迁移的关系

规则整合与 AI 整合**解耦推进**：新语言扫描先上线（平台可展示该语言 findings），迁移技能支持随后跟进。scan_result.json 投影规范（3.1）已按语言无关设计（solver_type 枚举可扩展），新语言条目自动可投影。

## 5. 实施计划

| 阶段 | 内容 | 前置 | 验收标准（DoD） |
| --- | --- | --- | --- |
| **P0 合同冻结** | 投影规范 + marking schema + 板上验证协议文档；OpenSpec change 立项 | 无 | 三份合同评审通过并入 openspec/changes/ |
| **P1 投影器** | MigrationTaskProjector + 单测 | P0 | AVX fixture：投影条目数与 actionable findings 一致；roundtrip 无 ID 丢失；inactive 正确过滤 |
| **P2 镜像与 adapter** | Dockerfile 四项 + PiAgentRuntimeAdapter + changeset + 进程注册 | 无（可与 P1 并行） | `pi --version` 自检过；探针三项（text/workspace-read/write）过；取消任务进程树被杀 |
| **P3 技能改编** | fork + 删 QEMU/subagent + rv_board.sh + board_verify.md | P0 | 宿主机人工跑通：投影 → 手动执行 migrate（真 LLM）→ 板上验证 → 回填 |
| **P4 任务接线** | KB_MIGRATION handler + 产物入库 + marking 回填 | P1+P2+P3 | compose 全链路：前端发起任务 → diff/report 入库可下载 → kb_findings 状态更新 |
| **P5 词表整合** | collector + riscv.macro 优先 | 无（独立线） | 知识管线跑通；riscv.macro 条目 accepted 入 manifest；scanner 回归全绿 |
| **P6 新语言** | 按占比选首个语言，OpenSpec change | P5 模式验证 | 该语言 demo 工程扫描出正确 findings，覆盖门禁达标 |

依赖关系：P0 → P1/P3；P1+P2+P3 → P4；P5/P6 独立线可并行。

## 6. 风险与开放问题

| # | 风险/问题 | 影响 | 对策 |
| --- | --- | --- | --- |
| 1 | 板机并发争用：多任务同时验证互相干扰（性能失真、内存挤爆） | 验证结果不可信 | `rv_board_run` 远端 flock 串行；后端侧板机信号量（二期）；worker 并发对 KB_MIGRATION 保守为 1 |
| 2 | agent 长任务不可取消（现有 runner 未注册进程） | 孤儿进程、板机被占 | P2 顺带补 `registerLocalProcess`（已有先例 KbScanRuntimeService） |
| 3 | workspace 磁盘无清理兜底（全量源码+编译产物+gcov，单任务可达数百 MB） | 磁盘只增不减 | workspace 从 /tmp 迁至 runtime 卷按 taskId 布局；实现 KbScanRuntimeService 注释中欠账的运行时目录清理 |
| 4 | 容器 root + Pi `--approve` 全信模式 | agent 写文件/起进程均 root | 迁移 workspace 独立子目录 + prompt 合同约束只写 workspace；rv_board.sh 低权限账号 + 超时包裹；非 root 化二期评估 |
| 5 | 64KB stdout 截断（DefaultCodexProcessRunner） | 长输出丢失 | 合同约定结果全写 output/ 文件，stdout 仅一行状态（现有 prompt 模式已如此） |
| 6 | NO_WORK_NEEDED 误报滥用 | 误报率统计失真 | marking.reason 必填；回填时校验 evidence 引用；按 findingType 聚合误报率进扫描质量看板 |
| 7 | 一对多投影的回填聚合语义 | 状态不一致 | 条目带 sourceFindingIds[]；回填按聚合规则（全部 DONE 才回写 finding DONE） |
| 8 | 板机不可达时的任务行为 | 任务卡死 | probe 失败快速失败（明确错误码 BOARD_UNREACHABLE），不做本地降级验证 |
| 9 | MCP 模型下载依赖出网 | 离线部署 hybrid 失效 | 镜像预下载模型（HF_HOME 固定路径）；降级纯 BM25 作为兜底并标注 mode |
| 10 | Pi 无头批准行为未实测 | 无人值守卡死 | P2 验收项：`--print --approve` 跑含 Bash 调用的技能脚本确认无交互阻塞 |

**开放问题**（评审时决策）：

- 板机凭据存 `agent_runtimes.config` JSONB 还是独立表？（倾向前者，随 runtime 行管理）
- 迁移产物的 git 语义：diff.patch 是否足够，还是需要分支/commit 形式？（倾向 patch，平台侧已有 artifact 体系）
- `KB_MIGRATION` 是否允许用户选择"仅迁移不改码"（dry-run 出建议清单）？（倾向支持，marking 全 PENDING 即 dry-run）

## 7. 许可与署名

- rvport-skills / rvport-web / rvport-mcp 代码：Apache-2.0，fork/vendored 时保留原版权头与 NOTICE（Apache §4c）；
- rvport-mcp 知识库数据：CC BY 4.0，`docs/第三方来源说明.md` 增补对应条目；
- 词表经 collector 注入 source identity 后，来源与许可证随知识 manifest 可追溯。
