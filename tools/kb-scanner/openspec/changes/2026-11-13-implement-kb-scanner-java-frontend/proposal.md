# Why

Java 工程的原生库加载（JNI）与 os.arch 分派对 kb-scanner 是盲区——迁移 Java 项目时 x86 专属 .so 的加载点无法检出。riscv-scan 的 solver_java 提供 4 个检测点规格（含变量收集消误报），本 change 按合同重写为第六输入域。

## What Changes

- `frontends/java/` 三文件（tree-sitter-java 0.23.5，ABI 14 实测兼容）：四形态 JNI 加载检测——`System.load[Library]`（standard）、`Native.loadLibrary`（native_jna）、`Runtime.getRuntime().load[Library]` 链式（runtime_chain）、已收集 Runtime 变量的 `rt.load[Library]`（runtime_var，**消误报**：全文件收集 `Runtime v = ...getRuntime()` 赋值变量名，未收集的裸变量调用不产证——与 riscv-scan 一致不猜）；`os.arch` 比较 → selection_condition（supportsRiscv 二值）。
- 库名实参单发 `preprocessor_condition` 证据 → 归一为 preprocessor → **ARCH_CONDITION_CANDIDATE 启发式**（_ARCHITECTURE_TOKENS 词元正则识别 x86/amd64/sse，免词表——architectureAliases 词表是 CPU 型号名不适用）。
- 接线：Composite 第六成员、`language_candidates_for_path` 加 `.java`、domain 枚举 `java-x86/arm64-to-riscv`、runtime.lock tree-sitter-java==0.23.5、`--language java` opt-in（默认输出字节不变）。
- **ABI 门禁修补**：`tests/tree_sitter_native_support.py` 的 inspect_runtime 此前只列 c/cpp——rust/go 是既有缺口，本 change 把 go/java/rust 全部列入 ABI 门禁覆盖。

## Capabilities

### Modified Capabilities

- `kb-scanner-cpp-frontends`: java 第六输入域。

## Impact

- 代码：frontends/java/（新三文件）、frontends/cpp/workspace.py、pipeline/composite.py、projection（前缀映射 + Literal + schema）、tests/tree_sitter_native_support.py
- 测试：unit 12（四形态矩阵/变量消误报/os.arch 双向/无关 load 排除/确定性/预算）+ e2e 4（库名启发式命中/domain/默认兼容/sample）+ samples/java
- 已知边界（文档化）：Runtime 变量收集全文件不区分作用域（riscv-scan 同款局限）；AST 解析失败降级 PARTIAL 不产证
