# Why

RvPort 目前的知识库与检测链路只支持 x86 源架构（契约层 `Literal["x86"]`、装载器无条件覆写架构族、检测器定义/观察种类全部 `X86`/`x86_*` 命名、输出 domain 固定 `c-cpp-x86-to-riscv`）。业务上需要把 ARM64（aarch64）代码库也纳入 RISC-V 迁移扫描：用户已从 Arm Architecture Reference Manual 人工收集了 AArch64 语料（builtin/头文件/平台宏/指令助记符四类清单），需要一个受控通道把这类"手册语料"接入与 x86 并列的知识体系。

## What Changes

- 知识契约新增 `collected-v1` 来源类型：以语料原件 SHA-256 + 官方手册 URL + 采集日期登记溯源（手册语料无精确 git commit，不复用 `generated-v2` 的 revision 合同）；语料原件冻结于规则包 `collected/arm64/`。
- 架构族成为一等维度：事实模型 `architectureFamily` 从 `Literal["x86"]` 放宽为 `Literal["x86","arm64"]`，语义范围新增 `arm64-only`/`arm64-toolchain`；装载器按来源族透传（不再无条件写 x86），v2/collected 事实条目允许 legacy 六类 factKind（detector 本就消费两类词表）。
- 扫描请求新增 `--source-arch x86|arm64`（默认 x86，现行为不变）：`ScanRequest`/`ScanContext` 增加字段，检测器注册表（`ARM64_DEFINITIONS`）与知识索引按族过滤，8 处 `architecture_family == "x86"` 的分析器/投影过滤参数化，arm64 词元正则（注释线索/架构形候选/枚举强词元）按族选择。
- 输出契约扩展：v2 `domain` 枚举增加 `c-cpp-arm64-to-riscv`，`readiness` 增加 `arm64_only`，知识来源视图新增 `collected` kind；JSON schema 同步。
- 架构家族路径分析按源架构参数化：arm64 扫描时 arm/aarch64 折叠为源角色、x86 系折叠为 foreign，建族条件改为"桶内出现源架构角色"。
- 后端透传：`KbScanRequest`/`KbScanInput`/`RvPortProperties.KbScan` 新增 `sourceArch`（配置默认 `RVPORT_KB_SCAN_SOURCE_ARCH=x86`），`KbScanService` 把项目侧别名（x86_64/amd64/aarch64/arm）归一化为扫描器族，运行时命令拼装 `--source-arch`。
- x86 现有行为保持不变：legacy YAML/旧词表字节不动；`--source-arch x86` 输出与改动前一致（除全局快照 ID 因新增知识源而变化）。

## Capabilities

### Modified Capabilities

- `kb-scanner-knowledge-data-production`: 新增 collected-v1 语料生产合同（手册语料的登记、清洗、可再生成与数据门禁）。
- `kb-scanner-knowledge-and-local-detection`: 架构族维度贯通知识装载、索引过滤与检测器定义选择。
- `kb-scanner-report-schema-v2`: domain/readiness/knowledgeSource kind 枚举扩展。
- `kb-scanner-architecture-family-analysis`: 路径角色折叠与建族条件按源架构参数化。
