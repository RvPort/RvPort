# Design

## 上下文

四层贯穿的 x86 硬编码：pydantic 契约（`Literal["x86"]` 门禁）、provider 装载（`_merge_entries` 无条件写 x86）、检测层（`DEFAULT_DEFINITIONS` 全部 `C_CPP.X86.*`、`KnowledgeIndex` 不分族）、输出层（`domain`/`readiness` 常量）。另有 8 处分析器/投影按 `architecture_family == "x86"` 过滤的散点。ARM64 语料提取自 ARM 手册 PDF，无 git commit 可引用，无法满足 `generated-v2` 的 40 位 revision 合同。

## 决策

### D1: 扫描模型——每次扫描单一源架构（而非混装检测）

`--source-arch x86|arm64` 一次只装一族视角。理由：迁移场景本身就是"从某架构迁出"；混装会让共有指令（ADD/AND）与跨平台 fallback 代码产生歧义，需要额外的消歧层；单一族模型下 x86 行为完全不变，回归面最小。快照仍装载全部两族条目（保持知识完整、来源可审计），过滤发生在匹配层。

### D2: 溯源——新增 collected-v1 而非放宽 generated-v2

`SourceDescriptorModel.revision` 强制 40 位 commit 是 generated-v2 的防篡改核心，放宽会弱化既有 x86 数据的溯源保证。手册语料用独立的 `CollectedSourceDescriptorModel`（inputFiles+inputSha256 一一对应登记语料原件字节），复用 `GeneratedFactV2Model` 作为条目模型（`FACT_KIND_LITERALS` 放宽到 12 类）与 `lint_generated` 排序/规范化校验（经 `_collected_as_v2` 适配）。

### D3: 知识索引按族过滤（在 match 层而非 load 层）

`KnowledgeIndex.by_kind_and_family(fact_kind, family)`：混合快照上 exact/token/flag 匹配只命中指定族条目。族来自 `DetectorDefinition` 所属注册表 → `context.source_arch`。同一规范值（如指令 `add`）在两族下是不同 entry_id（`knowledge_entry_id` 本就含族参数），天然隔离。

### D4: arm64 检测器定义独立注册表（而非参数化现有 ID）

`ARM64_DEFINITIONS` 与 x86 同构但 detector_id/observation_kind 独立命名（`C_CPP.ARM64.*`/`arm64_*`）。理由：observation kind 贯穿 classifier（risk DEPENDENCIES/policy 特例）、projection（建议/标题）到最终 findingType 字符串，复用 x86 名称会让 arm64 报告难以区分，扩枚举则破坏 x86 兼容。空词表类别（intrinsics 函数名/vectorTypes/registers/compilerFlags）保留定义——空集合不匹配，语料扩充后自动生效。

### D5: readiness/domain 枚举扩展（而非改名）

`x86_only` 保留原义，新增 `arm64_only`；domain 新增 `c-cpp-arm64-to-riscv`。前端按字符串映射展示，无需前端改动。

### D6: 后端别名归一化放 KbScanService（而非 record 紧凑构造器）

`KbScanRequest.sourceArch` 接受项目侧任意写法（x86_64/amd64/aarch64/arm），`normalizeSourceArch` switch 归一为 x86/arm64，未知值保守落 x86。record 紧凑构造器只做 blank→默认，不做语义归一（与 targetArch 处理一致）。

## 风险与缓解

- **快照 ID 变化**：knowledge_snapshot_id 摘要全部 entry ids，新增 arm64 源后所有扫描的 scan.id 变化。哈希差分类测试（bilingual suggestions 等）需要按新基线重审——这些测试在本机因上游 clang 哈希问题本就失败，不属于本次回归。
- **上游遗留**：`clang-x86-entries.json` 的冻结哈希（adf18741…）与仓库内实际字节（947e2ad0…）自 eb73f68 起不一致，导致该源装载降级 PARTIAL 与 3 个预存测试失败。本变更不修此问题（单独修复需重审冻结基线），如实记录。
- **arm64 指令误报**：指令匹配仅作用于 inline_asm 证据 + 词元边界（`(?<![\w])token(?![\w])`），C 标识符不命中；手册表格字段名（EXC_*/DSNP_* 等 48 条）在转换脚本中清洗掉。
