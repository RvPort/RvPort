# Java 调用 kb-scanner 示例 / Java kb-scanner invocation demo

`KbScannerDemo.java` 演示以子进程方式从 Java 调起 kb-scanner 并解析 V2 结果——
命令合同与生产后端 `KbScanRuntimeService.command()` 一致。

## 一键运行（推荐）

```bash
./docs/examples/run-demo.sh [项目目录]   # 单次扫描（缺省仓库 fixture）
./docs/examples/run-demo.sh matrix      # 多语言×多架构矩阵：12 个合成用例自检
```

矩阵 demo（`KbScannerMatrixDemo.java`）自带每种组合的合成测试工程（C/Rust/Go/
Java/Python/Cython/汇编/混合语言 × x86/arm64），现场生成源码→扫描→断言期望的
findingType——既是各语言域检测点的速查样例，也可当集成自检用。

## 手动编译运行

### 运行前提

- JDK 25（其他版本未验证）；classpath 上需要 jackson-databind 2.x；
- Python 3.12 venv 装有 kb-scanner 运行依赖（`tools/kb-scanner/requirements/runtime.lock`，
  `tree-sitter-cmake` 等含原生扩展的包需要 `build-essential` + `python3-dev` + `CC=gcc`）；
- `scanner-root` 指向仓库的 `tools/kb-scanner`（规则包与模块源码）。

## 编译与运行

```bash
# 编译（CP 为 jackson jar 集合）
CP=$(find ~/.m2/repository/com/fasterxml/jackson -name '*.jar' | grep -vE 'sources|tests' | tr '\n' ':')
javac -cp "$CP" KbScannerDemo.java

# 扫描 C/C++ 工程（默认 x86 → riscv64）
java -cp ".:$CP" KbScannerDemo <project-dir> <repo>/tools/kb-scanner
```

示例输出（仓库 fixture `tests/fixtures/architecture-candidate-noise`）：

```
扫描完成：exit=0，耗时 PT17.7s
=== 扫描摘要 ===
文件 1 | findings 3 | issues 3 | 阻断 0 | inactiveOrGuarded 1
[high]   x86_target_feature            zlib-shape.c:25
[medium] inline_asm_candidate          zlib-shape.c:26
[low]    x86_platform_macro_reference  zlib-shape.c:5
产物目录: /tmp/kb-scan-demo-.../（scan-result.json / scan-report.html / scan.log）
```

## 与生产后端的对应关系

| demo 做法 | 生产后端（`KbScanRuntimeService`）|
|---|---|
| `python -m kb_scanner scan --input ... --rules ... --schema-version 2.0` | 相同命令合同（`command()` 方法）|
| `KB_SCANNER_MEMORY_LIMIT_MIB=2048` 环境变量 | 相同（`memoryLimitMib()` 注入，扫描器内 setrlimit）|
| 超时 `destroyForcibly()` 返回 124 | 相同语义 |
| `schemaVersion` 精确校验 `"2.0"` | 相同（不兼容即拒绝）|
| stdout/stderr 读空防管道阻塞 | 相同（异步 read）|

demo 省略了生产代码的：进度上报、产物入库、路径脱敏、任务取消注册——按需参考
`backend/src/main/java/cn/rvport/backend/kb/runtime/KbScanRuntimeService.java`。

## 扫其他语言域 / 源架构

demo 命令行写死 `--language c,cpp --source-arch x86`；按需改为：

```java
"--language", "rust",          // 或 go / java / python / asm / c,rust 混合
"--source-arch", "arm64",      // ARM 源工程（NEON/SVE 检测）
```
