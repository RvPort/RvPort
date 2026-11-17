# kb-scanner-cpp-frontends 变更（Python/Cython 第七输入域）

## ADDED Requirements

### Requirement: 前端 SHALL 支持 Python/Cython 源输入域（opt-in）且动态加载保持消误报纪律

`language_candidates_for_path` SHALL 对 .py/.pyx 返回 ("python",)（仅当 language 开关含 python）。Python 前端 SHALL：按四类 import 语句维护状态机（import ctypes / from ctypes import cdll|CDLL / import cffi / from cffi import FFI，含 as 别名归类）；动态库加载调用（参数非空才产证）→ call_expression + 库名实参单发 preprocessor_condition（启发式词元识别）；cdef/set_source 的字符串实参按内嵌 C 行级扫描（#include → include、代码行 → inline_asm）；verify → selection_condition。**消误报**：未收集 FFI 实例的 `.dlopen` 调用 MUST NOT 产证；`import ctypes as ct` 的 `ct.CDLL` 限定名不追踪（已知边界）。

`.pyx` 文件 SHALL 走行正则降级（无 grammar 包）：# 注释跳过，ctypes 调用与 from ctypes import *dll* 两检测点，kind 路由与 AST 路线一致。

#### Scenario: ctypes + cffi 检出
- **WHEN** `--language python` 扫描含 ctypes.CDLL("libsimd_x86.so") 与 set_source 内嵌 `_mm_add_epi32` 的文件
- **THEN** 库名经启发式词元、内嵌 C 经词表各产观察（producer=python-frontend）

#### Scenario: 消误报
- **WHEN** 出现未收集实例的 other.dlopen("x.so") 或空参 ctypes.CDLL()
- **THEN** 不产任何加载证据

### Requirement: goreleaser 配置 SHALL 经 build 通道检出 goarch 缺失

`build_input_kind` SHALL 识别 .goreleaser.yml/.yaml 为 GORELEASER 输入；缩进感知解析器 SHALL 提取 goarch 块的 `- arch` 列表（锚点容忍 `- goarch:` 列表项形态），列表非空且不含 riscv64/riscv 时产 build_arch_constraint（targetAllowed=False）走既有 build 归一化链。

#### Scenario: goarch 缺失
- **WHEN** 扫描 goarch: [amd64, arm64] 的 .goreleaser.yml
- **THEN** SHALL 产 build_arch_constraint 观察（无语言开关要求——build 通道恒开）

#### Scenario: 含 riscv
- **WHEN** goarch 列表含 riscv64
- **THEN** 不产观察
