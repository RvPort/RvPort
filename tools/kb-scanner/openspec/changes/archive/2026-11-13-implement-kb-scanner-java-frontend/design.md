# Design

## 上下文

riscv-scan 的 solver_java 是行正则实现（91 行）：4 个检测点 + 变量收集消误报。我们用 tree-sitter-java AST 重写（method_invocation 的 object/name/arguments 字段天然结构化，注释剥离由 AST 处理）。

## 决策

### D1: JNI 四形态按接收者结构判定（而非正则）

System/Native（standard/native_jna）、`Runtime.getRuntime().load*` 链式（object 字段 endswith getRuntime()）、已收集 Runtime 变量的 `rt.load*`（runtime_var）。**消误报纪律照搬**：裸变量接收者只在被此前的 `Runtime v = ...getRuntime()` 赋值收集时产证，未收集不猜（`mystery.loadLibrary` 零证据）；变量收集全文件不区分作用域（riscv-scan 同款局限，文档化）。

### D2: 库名实参路由走启发式而非词表（实现期修正）

初版设计塞 declaration_attribute（architectureAliases 词表 token），实测该词表是 **CPU 型号名**（alderlake/athlon…），不含 x86/amd64 架构词——库名全部落空。修正：kind=preprocessor_condition → 归一为 preprocessor → **ARCH_CONDITION_CANDIDATE 启发式**（`_ARCHITECTURE_TOKENS` 词元正则免词表识别 x86/amd64/sse）。教训：路由决策要先验证词表内容。

### D3: os.arch 比较照 GOARCH 模式

`"amd64".equals(System.getProperty("os.arch"))` → selection_condition（supportsRiscv 二值，闭集同 GOARCH）。

### D4: ABI 门禁顺手修补（既有缺口）

inspect_runtime 此前只列 c/cpp——rust/go grammar 引入时没登记（门禁盲区）。本 change 把 go/java/rust 全部列入 + grammar 集断言同步，后续新 grammar 按 spec 要求同步登记。

## 风险与缓解

- 库名误报（普通库名恰含 x86 词元）：token 词边界 + e2e 正反例钉死；候选不等于结论（structural candidate 走分类）。
