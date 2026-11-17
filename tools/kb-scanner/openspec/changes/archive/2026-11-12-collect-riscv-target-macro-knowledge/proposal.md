# Why

RvPort 的知识包目前只覆盖**源架构侧**词汇（x86/arm64 的宏、builtin、指令），目标侧（RISC-V）完全没有词表——扫描器无法判别"代码已含 RISC-V 分支/实现"，`architecture_guards` 的双向推理与 `architecture_family` 的 riscv 角色判定只能靠 paths.py 的 3 个 token（riscv/riscv32/riscv64）路径词法兜底。上游 rvport-skills/riscv-scan 仓库已人工核出 203 项 RISC-V 目标侧平台宏（`__riscv*`，源出 RISC-V C-API/ISA 手册约定），需要一个受控通道把这批"目标侧手册语料"接入知识体系，用于宏条件分支的目标族角色判定。

## What Changes

- **"riscv" 进入知识架构族枚举，但仅作为目标族**：`ARCHITECTURE_FAMILIES` 增 `"riscv"`，`SEMANTIC_SCOPES` 增 `"riscv-only"`（`knowledge/schema.py`）。`context.source_arch` 仍为 `{"x86","arm64"}`，检测器注册表 `DEFINITIONS_BY_FAMILY` 不加 riscv 键——riscv 条目只被消费端显式 `family="riscv"` 查询命中，**出现即表示已有 RISC-V 分支**的方向性钉在 semanticScope。
- **语料与生产**：203 项宏冻结于 `rulesets/riscv-porting/collected/riscv/riscv.macro`；新 collector `tools/collect_riscv_entries.py`（模板 `collect_aarch64_entries.py`）确定性渲染 `generated/riscv-collected-entries.json`（collected-v1 形态，单源单族）；manifest 三层 sha256 链同步。
- **消费点**：`analyzers/evidence_graph.py` 的 `_branch_role()` 在源族匹配前增加目标族预检 `_target_family_role()`——条件串命中 riscv 族 platformMacros（词边界正则）即返回 `("riscv", matched_ids)`（negated 时返回 `("unknown", ...)`），使 `#ifdef __riscv_xlen` 判定为 riscv 分支，与 x86 分支建立 alternative 关系，`build_selection` 的"已有 RISC-V 路径"判定受益。
- **储备数据**：riscv-scan 的 `rust_arch_functions.json`/`rust_arch_struct.json` 冻结到 `collected/reserve/`（不进 manifest、不进运行时），契约测试只钉哈希；启用路径待 Rust frontend 立项。
- **差集记录**：riscv-scan 的 aarch64.macro/intrinsics/builtin 与已收编 `collected/arm64/` 语料逐行一致（差集为空），契约测试固化该等值断言。
- **行为不变承诺**：x86/arm64 既有样本输出字节不变（manifest 身份摘要因新源加入而变化的项，按既有惯例同步基线）；任何源族检测器不得命中 riscv 条目（负测断言 `DEFINITIONS_BY_FAMILY` 键集）。

## Capabilities

### Modified Capabilities

- `kb-scanner-knowledge-data-production`: riscv 目标族语料的登记、清洗、可再生成与数据门禁（collected-v1 通道复用）。

## Impact

- 代码：`knowledge/schema.py`（Literal 扩展）、`analyzers/evidence_graph.py`（目标族消费）、`knowledge/provider.py`（如需族透传微调）
- 数据：`collected/riscv/riscv.macro`（新）、`generated/riscv-collected-entries.json`（新）、`collected/reserve/`（新）、`knowledge-manifest.json`/`scanner-manifest.json`（哈希链）
- 测试：契约测试钉死新源（计数/哈希/再生成一致/单源单族）、端到端 riscv 分支角色用例、负测（源族隔离）
- 文档：`docs/knowledge.md`/`knowledge.zh-CN.md` riscv 目标族段落（双语）
- 后端：零改动
