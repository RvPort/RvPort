# kb-scanner-cpp-frontends 变更（Java 第六输入域）

## ADDED Requirements

### Requirement: 前端 SHALL 支持 Java 源输入域（opt-in）且 JNI 检测保持消误报纪律

`language_candidates_for_path` SHALL 对 .java 返回 ("java",)（仅当 language 开关含 java）。Java 前端 SHALL 产三类证据：四形态 JNI 加载 → call_expression（metadata.jniForm ∈ standard/native_jna/runtime_chain/runtime_var）+ 库名实参单发 preprocessor_condition（走 ARCH_CONDITION_CANDIDATE 启发式）；`os.arch` 比较 → selection_condition（supportsRiscv 二值）。

**消误报纪律**：裸变量接收者 `v.load[Library]` 只在 v 被此前的 `Runtime v = ...getRuntime()` 赋值收集时产证；未收集的变量调用 MUST NOT 产证（不猜类型）。变量收集是全文件的（不区分作用域，已知边界）。

#### Scenario: 四形态 JNI
- **WHEN** `--language java` 扫描含 System.loadLibrary("simd_x86") / Native.loadLibrary / Runtime.getRuntime().load / 已收集 rt.load 的文件
- **THEN** 四形态各产 call 证据；"simd_x86" 经启发式词元识别产 architecture_identifier_candidate 观察

#### Scenario: 消误报
- **WHEN** 出现未收集变量的 `mystery.loadLibrary("x")`
- **THEN** 不产任何 JNI 证据

#### Scenario: 默认语言向后兼容
- **WHEN** 默认 `--language c,cpp`
- **THEN** .java 文件零证据零 Finding

### Requirement: ABI 门禁 SHALL 覆盖全部锁定的 tree-sitter grammar

`tests/tree_sitter_native_support.py` 的 inspect_runtime SHALL 列出 runtime.lock 中全部 tree-sitter grammar 包（c/cpp/make/cmake/bash + go/java/rust），validate_runtime 的 ABI 区间校验对所有 grammar 生效。

#### Scenario: 新 grammar 引入
- **WHEN** runtime.lock 新增 tree-sitter grammar 包
- **THEN** 该包 SHALL 同步列入 inspect_runtime，否则视为门禁缺口
