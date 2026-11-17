# 知识数据与维护

[English](knowledge.md) | 简体中文

运行时只读 scanner bundle：`scanner-manifest.json` 固定知识 manifest 摘要及 policy identity；manifest 不包含执行命令或插件。generated、accepted curated 和 reference registry 可被加载，decision ledger 不参与运行时。

<a id="doc-knowledge-production-baseline"></a>

## 数据组成

## 三类制品

- `generated/*.json` 是工具链结构定义的确定性输出。
- `curated/x86-migration-semantics.json` 只含 accepted 条目；`curated/source-references.json` 保存闭合的权威引用。
- `collected/arm64/*` 保存冻结的 ARM64 语料原件（Arm Architecture Reference Manual 人工提取）；`generated/arm64-collected-entries.json` 是其确定性转换产物，在 manifest 中以 `collected-v1` 类型登记——手册语料无精确 git revision，溯源采用语料原件 SHA-256 + 官方手册 URL + 采集日期。
- `audit/candidate-decisions.json` 保存 generated/accepted/rejected/deferred 决策和理由，但 Provider 不加载它。`audit/candidate-inventory.json` 明确是非权威候选清单。

知识 JSON 不携带 regex、operator、matcher、severity、blocking、reason、suggestion、message 或 readiness。如何匹配仍由 Python `DetectorDefinition` 管理；最终影响仍由后续分类阶段管理。

## Manifest 与兼容

生产 manifest 已显式升级为 v2，可声明 `legacy-generated`、`generated-v2`、`curated-v1`、`collected-v1` 四类来源。Provider 仍严格接受原 v1 generated-only manifest，v1 继续拒绝 `curatedSets`。回滚时可用 `tests/fixtures/knowledge-production/v1-manifest.json` 替换生产 manifest，只加载两份旧事实。

当前两份冻结文件的摘要如下。GCC 来源路径已脱敏为 `vendor/gcc/gcc/config/i386`，事实内容不变；历史报告摘要保留原版本，不回写。

| 文件 | SHA-256 |
|---|---|
| `generated/clang-x86-entries.json` | `adf187416c006b120e43f2d2ce0677ee2104a4c27de4dd9cabd1df12add352f7` |
| `generated/gcc-x86-entries.json` | `22d68c2ab3c8c40c8606451af2e5f493877661d031b6b2f379eff7886c23d191` |

历史 legacy CLI、`schemaVersion: "1.0"` 结果、Finding 语义、24 个 YAML/`generatedSet` 和三个 artifact 由完整回归继续保护；新知识不会创建第二套主结果结构。

## 检测语义

- inline asm 中有边界地命中指令/寄存器、源码调用命中 CPU API、target attribute 命中 feature 或 CPU/架构名称时产生带 Knowledge ID 的 `confirmed_local`。CPU 名称不会被用于任意宏/变量的全局扫词，避免 `generic`、`intel` 等普通词误报。
- `vector_size(16/32/64)` 只产生带知识引用的 `candidate`。
- comment 仍为 `hint`；未知汇编词、普通数字或相似 API 名称不会升级为 confirmed。
- graph、构建选择、打包和 blocking/severity/readiness 不属于知识层的职责。

<a id="doc-knowledge-refresh-guide"></a>

## 刷新流程

## 前提

刷新不是扫描运行时行为。维护者先在容器外取得上游官方仓库的指定 release/commit，并校验 checkout；collector 本身不联网、不 clone、不运行 compiler/assembler/linker。输入 checkout 以只读 mount 提供，输出写到临时目录，审核差异后再替换仓库中的 generated/curated 文件。

固定输入路径：

- LLVM：`llvm/lib/Target/X86/X86RegisterInfo.td`、`llvm/lib/Target/X86/X86.td`
- GCC：`gcc/common/config/i386/i386-common.cc`、`gcc/common/config/i386/i386-isas.h`、`gcc/config/i386/cpuid.h`、`gcc/config/i386/ia32intrin.h`、`gcc/doc/extend.texi`
- Binutils：`opcodes/i386-opc.tbl`、`opcodes/i386-reg.tbl`

## 刷新

只构建独立测试镜像：

```powershell
docker compose -f tools/kb-scanner/compose.test.yml build kb-scanner-test
```

然后以 `LLVM_SOURCE`、`GCC_SOURCE`、`BINUTILS_SOURCE` 和 `KNOWLEDGE_OUTPUT` 对应的绝对目录做只读输入/临时输出 mount，在 `rvport-kb-scanner-test:local` 中运行 `scripts/refresh_knowledge_sources.py`。脚本只接受显式 `--llvm-root`、`--gcc-root`、`--binutils-root`、`--output-root`，不会自己寻找宿主机路径。

同一输入连续运行两次，两个输出目录中的文件 SHA-256 必须完全相同。随后检查：

1. 上游 revision、release、许可证、input path/hash 是否正确。
2. 各 fact kind 数量和 diff 是否合理；不能用大量 opcode 掩盖 API/向量缺口。
3. decision ledger 中的 rejected/deferred 是否仍有理由；它不得加入 manifest。
4. curated entry 是否逐条 accepted、ID 可重算且引用闭合。
5. 更新 manifest 中新增数据文件摘要，但不得触碰两份 legacy compiler facts。

## 验证与恢复

刷新在 Docker 中执行。保持上游 revision、许可证和输入摘要可追溯，连续两次生成结果应字节一致。两份冻结的 Clang/GCC facts 不随其他知识更新。审核差异后再替换 manifest；失败时恢复整套已验证数据与摘要，不能只替换其中一个 manifest。运行完整测试入口见 [开发指南](development.zh-CN.md)。
