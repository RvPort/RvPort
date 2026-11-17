# ARM64 语料接入与双架构迁移扫描方案

> 状态：已实施（2026-10-29 完成主体，见 openspec change `2026-10-29-arm64-source-architecture-knowledge`）
> 范围：tools/kb-scanner + backend 透传链
> 读者：需要理解/评审/扩展该能力的开发与评审人员

---

## 1. 背景与目标

RvPort 原生只支持 **x86→RISC-V** 迁移扫描：知识契约层 `Literal["x86"]` 硬门禁、装载器无条件把所有事实归为 x86、检测器定义与观察种类全部 `C_CPP.X86.*` 命名、输出 domain 固定 `c-cpp-x86-to-riscv`。

现有一份从 **Arm Architecture Reference Manual** 人工提取的 AArch64 语料（四类纯文本清单），目标是把它接入知识库，使扫描器支持 **arm64→RISC-V** 扫描，且 **x86 既有行为完全不变**。

### 语料盘点

| 语料文件 | 内容 | 转换后事实种类 | 条目数 |
|---|---|---|---|
| `aarch64.builtin` | GCC `__builtin_aarch64_*`（FPCR/FPSR 访问） | builtins | 8 |
| `aarch64.intrinsics` | ARM 头文件名（arm_neon.h、arm_sve.h、armpl.h…） | headers | 16 |
| `aarch64.macro` | ARM 平台/feature 宏 | platformMacros | 121 |
| `aarch64.opcode` | AArch64 指令助记符（含 3 行 `#` 注释头） | assemblyInstructions | 2161 |
| **合计** | | | **2306** |

---

## 2. 关键设计决策（6 条，含理由）

### D1 扫描模型：每次扫描单一源架构，而非混装检测

CLI 新增 `--source-arch x86|arm64`（默认 x86）。**理由**：
- 迁移场景本质是"从某架构迁出"，单族视角语义清晰；
- 混装会让共有指令（ADD/AND/BIC）与跨平台 fallback 代码产生歧义，需额外消歧层；
- 单族模型下 x86 路径零改动，回归面最小。

注意：知识快照仍装载**全部两族**条目（保持知识完整、来源可审计），过滤发生在匹配层（见 D3）。

### D2 溯源：新增 `collected-v1` 来源类型，而非放宽 `generated-v2`

现有 v2 契约强制 40 位 git commit（防篡改核心），手册语料无法满足。**不放宽 v2**（避免弱化 x86 数据的溯源保证），新增独立契约：

```
CollectedSourceDescriptorModel = sourceId + officialUrl(手册) + license
                               + collectedAt + inputFiles[] + inputSha256[]  （一一对应）
```

以**语料原件字节的 SHA-256 + 官方手册 URL + 采集日期**作为溯源链。语料原件冻结于规则包 `collected/arm64/`，转换产物为确定性 JSON。

### D3 族过滤位置：匹配层（KnowledgeIndex），而非装载层

`KnowledgeIndex.by_kind_and_family(fact_kind, family)`：混合快照上 exact/token/flag 匹配只命中指定族条目。同一规范值（如指令 `add`）在两族下是**不同 entry_id**（`knowledge_entry_id` 本就含族参数），天然隔离，无需去重逻辑。

### D4 arm64 检测器：独立注册表命名，而非参数化现有 ID

`ARM64_DEFINITIONS` 与 x86 的 24 条定义同构，但 detector_id/observation_kind 独立命名（`C_CPP.ARM64.*` / `arm64_header_reference` 等）。**理由**：observation kind 贯穿 classifier → projection → 最终 findingType 字符串直到前端展示，复用 x86 名称会让 arm64 报告无法区分，扩 x86 枚举则破坏兼容。空词表类别（intrinsics 函数名/vectorTypes/registers/compilerFlags）保留定义——空集合不产生误报，语料扩充后自动生效。

### D5 输出枚举：扩展而非改名

`readiness` 保留 `x86_only` 原义、新增 `arm64_only`；`domain` 新增 `c-cpp-arm64-to-riscv`。前端按字符串映射展示，无需改动。

### D6 后端别名归一化：放 Service 层，而非 record 紧凑构造器

`KbScanRequest.sourceArch` 接受项目侧任意写法（x86_64/amd64/aarch64/arm…），`KbScanService.normalizeSourceArch` 归一为 x86/arm64（未知值保守落 x86）。record 紧凑构造器只做 blank→默认（与 targetArch 处理一致，不掺语义）。

---

## 3. 分层改造明细

### 3.1 知识契约层（kb_scanner/knowledge/schema.py）

| 改动 | 内容 |
|---|---|
| 架构族放宽 | `architectureFamily: Literal["x86"] → Literal["x86","arm64"]`（v2/curated 事实） |
| 语义范围 | 新增 `arm64-only` / `arm64-toolchain`（`<族>-only` 命名模式） |
| factKind 放宽 | 新增 `FACT_KIND_LITERALS`（12 类 = 新版 6 + legacy 6），collected 语料映射到 legacy 种类（headers/platformMacros/builtins/assemblyInstructions） |
| 新模型 | `CollectedSourceDescriptorModel`（无 revision，inputFiles/Sha256 平行元组）、`CollectedFactsModel`（**单源单族** + 事实三元组唯一不变量）、`CollectedManifestSourceModel`（kind=collected-v1，collectedFiles 唯一） |

### 3.2 装载层（provider.py）

- `_load_source` 由三路分支改四路：collected-v1 走 `CollectedFactsModel` 校验 + 经 `_collected_as_v2` 适配复用 `lint_generated`（排序/规范化 lint）+ 族取事实载荷首条；
- `_merge_entries` 增加 `architecture_family` 参数（v1 legacy 固定 x86；collected 按载荷），entry_id 与 `KnowledgeEntry.architecture_family` 均按来源族落值。

### 3.3 source-arch 贯通

```
cli.py --source-arch (choices=x86|arm64, default=x86)
  → ScanRequest.source_arch（校验合法值）
    → StagedContextFactory → ScanContext.source_arch（frozen，供各阶段只读）
```

### 3.4 检测层（detectors/）

| 文件 | 改动 |
|---|---|
| definitions.py | +`ARM64_DEFINITIONS`（24 条）+ `DEFINITIONS_BY_FAMILY` 注册表映射 |
| matchers.py | `KnowledgeIndex` 增族索引；`match_fact(..., architecture_family)`；`_ARCHITECTURE_TOKENS`/枚举强词元按族选择（arm64 词元：aarch64/arm64/armv*/neon/sve*/sme*） |
| local.py | 按族选定义注册表；`architectureDisposition` 输出实际族；compilerFlagValue 元数据覆盖两族 |
| candidate_coverage.py | 覆盖判定词元按族 |

### 3.5 分析器/投影参数化（8 处 `== "x86"` 过滤散点）

evidence_graph（分支角色判定的族知识过滤 + `_FAMILY_HINT_PATTERNS` 启发式词元兜底 + alternative_to 建边 + `_family_knowledge_ids` 族匹配与缓存键）、build_selection / cmake_selection / test_selection / detectors/build_selection 过滤、projection 的 `intrinsic_symbols`/chain/mapper 构造器——全部改为读 `context.source_arch`。classifier 的 DEPENDENCIES/policy 特例、projection 建议词表补齐 `arm64_*` 种类。

### 3.6 架构家族路径分析（analyzers/architecture_family/）

角色折叠按源架构定向：**x86 扫描** arm→foreign（原行为）；**arm64 扫描** arm/aarch64→源角色 `arm64`、x86 系→foreign。建族条件从"桶内出现 x86 角色"泛化为"出现源架构角色"。

### 3.7 输出契约

| 位置 | 改动 |
|---|---|
| projection/v2/models.py | `domain` 枚举 +`c-cpp-arm64-to-riscv`；`readiness` +`arm64_only`；KnowledgeSourceView kind +`collected` |
| summary.py | `V2SummaryReducer(architecture_family)` 注入，source_only 态按族命名 |
| schemas/scan-result-v2.schema.json | 上述三处枚举同步 |

### 3.8 后端透传链

```
KbScanRequest.sourceArch（REST 可选字段）
  → KbScanService.create：normalizeSourceArch（别名归一）→ inputSnapshot + KbScanInput
    → KbScanRuntimeService.command()：--source-arch <族>（缺省走配置）
      → RvPortProperties.KbScan.sourceArch（application.yml: RVPORT_KB_SCAN_SOURCE_ARCH，默认 x86）
```

### 3.9 端到端数据流（arm64 扫描）

```
collected/arm64/*.builtin/intrinsics/macro/opcode   （语料原件，冻结）
   │  tools/collect_aarch64_entries.py（清洗→映射→排序→确定性渲染）
   ▼
generated/arm64-collected-entries.json   （2306 条 arm64 族事实）
   │  knowledge-manifest.json（collected-v1 条目 + sha256）
   │  scanner-manifest.json（清单整体哈希，手工同步）
   ▼
Provider 装载（族透传）→ KnowledgeSnapshot（x86 13291 + arm64 2306 并存）
   │  --source-arch arm64
   ▼
ARM64_DEFINITIONS + by_kind_and_family 匹配（只命中 arm64 条目）
   ▼
classifier/projection → scan-result.json
   （domain=c-cpp-arm64-to-riscv，findings: arm64_header_reference 等）
```

---

## 4. 语料清洗规则（公开、可审计）

| 规则 | 处理对象 | 例子 |
|---|---|---|
| 跳过 `#` 注释与空行 | 全部清单 | opcode 文件头 3 行注释 |
| 完整标识符校验 | macro | 丢弃 `__arm_`、`__AARCH64_CMODEL_` 截断前缀 |
| 纯大写字母数字 | opcode | 丢弃 48 条手册表格字段名（EXC_*/DSNP_*/SPSR_*/CTI_* 等）与标点异常值 `M.A.A` |
| canonicalize | 全部 | 指令转小写、headers 去尖括号（复用 identity.py 既有规范） |

**误报防护**：指令匹配仅作用于 inline_asm 证据 + 词元边界 `(?<![\w])token(?![\w])`，C 源码标识符 `add` 不会命中；ARM 宏出现在 x86 代码属真实跨平台证据，不算误报（x86 扫描根本不匹配 arm64 词表）。

---

## 5. 验证结果（如实记录）

| 验证项 | 命令 | 结果 |
|---|---|---|
| kb-scanner 全量单测 | pytest tests/ | **1117 通过**；33 FAILED + 16 ERROR **全部为预存环境问题**（与改动前基线逐名 diff 确认零新增回归：AVX fixture 需容器挂载、clang python 模块缺失、上游哈希遗留） |
| 变更模块覆盖率 | pytest --cov | matchers 99% / provider 100% / schema 98% / architecture_family 100%（门槛 90%）✓ |
| 规则包完整性 | validate-rules | 通过（唯一 WARNING 为预存 clang 哈希问题） |
| arm64 端到端 | scan --source-arch arm64 samples/arm64 | 7 findings：header×3 / platform_macro×2 / asm_instruction / builtin ✓ |
| 族隔离双向 | 同输入 x86 扫描 | 0 条 arm64_* 观察；反向 0 条 x86_* ✓ |
| 扫描确定性 | 同输入扫两次 | scan-result.json 字节一致 ✓ |
| Docker 容器内 | kb-scanner-test 镜像 + 挂载当前源码 | 29 fails，全部落在镜像基线（更多失败）的预存集合内 ✓ |
| 后端 | mvn test -Dtest='KbScan*Tests' | 65+34 全绿 ✓ |

---

## 6. 遗留问题与后续规划

### 遗留（上游/环境，与本方案无关但需知悉）

1. **clang-x86-entries.json 哈希不一致**：冻结哈希 `adf18741…` 与仓库实际字节 `947e2ad0…` 自 eb73f68 起不一致 → 6 个测试预存失败、该源装载降级 PARTIAL。修复需单独重审冻结基线（fact-asset-baseline 等）。
2. **哈希差分测试待重审**：新增知识源改变所有扫描的快照 ID（设计使然），`test_bilingual_suggestions` 等 4 组期望哈希需按新基线重审（本机本就未通过）。
3. **compose 全栈冒烟未做**：工作树含未提交的前端/compose 代理改动，待落定后按标准流程重建三镜像验证（建 arm64 项目→扫描→看报告）。

### 后续规划（按优先级）

| 项 | 说明 | 工作量预估 |
|---|---|---|
| 语料扩充 | intrinsics 函数名（arm_neon.h 的 vaddq_* 等）、vectorTypes（int32x4_t…）、registers（x0-x30/v0-v31…）、compilerFlags（-march=armv8-a+sve…）四类当前空集，定义已预留，扩词表即生效 | 中（可从 LLVM aarch64 头文件/ARM ACLE 抽取） |
| 项目级 sourceArch 联动 | projects 表已有 source_arch 列（默认 x86_64）但扫描链路未消费；打通"建项时选源架构→扫描自动带出" | 小（KbScanService 注入 ProjectRepository，替换配置默认值） |
| 前端适配 | sourceArch 选择器、arm64_only/readiness 标签映射（当前字符串透传已兼容） | 小 |
| 上游哈希遗留修复 | 重审 clang 冻结基线或恢复正确字节 | 需评审 |

---

## 7. 使用指南

```bash
# 扫描器直接用
python -m kb_scanner scan --input <arm64项目> --rules rulesets/riscv-porting \
    --output /tmp/out --format json,html --source-arch arm64

# 后端 REST（KbScanRequest 新增可选 sourceArch，别名自动归一）
POST /api/projects/{id}/kb/scans
{ "sourceArch": "aarch64" }        # → 归一为 arm64
# 环境级默认：RVPORT_KB_SCAN_SOURCE_ARCH（application.yml，缺省 x86）

# 语料更新后重新生成（产物需与仓库字节一致，契约测试强制）
python tools/collect_aarch64_entries.py
# 然后手工同步两个 manifest 的 sha256（见 docs/knowledge.md Refresh workflow）
```
