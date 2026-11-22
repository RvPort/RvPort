# kb-scanner-knowledge-and-local-detection 变更（源架构参数化）

## ADDED Requirements

### Requirement: 扫描 SHALL 声明单一源架构族并按族选择检测词表

扫描请求 SHALL 接受 `source_arch`（`x86` | `arm64`，默认 `x86`），归一化后的上下文 SHALL 把族传递到检测阶段；本地检测器 SHALL 按族选择检测器定义注册表（`C_CPP.X86.*` / `C_CPP.ARM64.*`），知识匹配（exact/token/flag/枚举）SHALL 只命中声明族的条目。x86 族的既有行为 MUST 保持不变。

#### Scenario: arm64 扫描
- **WHEN** 以 `--source-arch arm64` 扫描含 NEON/SVE/ACLE 头文件、ARM 平台宏、AArch64 内联汇编与 `__builtin_aarch64_*` 的代码
- **THEN** 检测 SHALL 产出 `arm64_header_reference`/`arm64_platform_macro_reference`/`arm64_assembly_instruction`/`arm64_builtin_reference` 观察与 findings
- **AND** 不得产出任何 `x86_*` 观察

#### Scenario: x86 扫描族隔离
- **WHEN** 以默认 `--source-arch x86` 扫描同一份 ARM 代码
- **THEN** 不得产出任何 `arm64_*` 观察
- **AND** x86 词表的既有匹配行为不变

#### Scenario: 非法源架构
- **WHEN** 请求携带未知 `source_arch`
- **THEN** 上下文构建 SHALL 拒绝该请求

### Requirement: 分析器与投影的架构过滤 SHALL 读取扫描声明的源架构

分支角色判定（知识匹配 + 族启发式词元）、替代关系建立、构建/测试选择的族过滤、投影层的内在函数符号集等架构敏感逻辑 SHALL 以 `context.source_arch` 为准，MUST NOT 硬编码 `"x86"` 字面量做过滤。

#### Scenario: arm64 分支角色
- **WHEN** arm64 扫描遇到 `#ifdef __ARM_NEON` 守卫
- **THEN** 分支角色 SHALL 判定为源架构（arm64）而非 foreign
- **AND** 同组存在 riscv/generic 分支时 SHALL 建立 alternative_to 关系

### Requirement: arm64 观察种类 SHALL 贯穿分类与投影

分类器依赖集合、策略特例（平台宏未证明）、投影建议/标题词表 SHALL 覆盖 `arm64_*` 观察种类；arm64 finding 的建议文案 SHALL 保持双语。
