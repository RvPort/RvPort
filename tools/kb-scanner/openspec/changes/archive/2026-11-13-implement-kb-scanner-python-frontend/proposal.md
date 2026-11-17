# Why

Python/Cython 工程的原生库加载（ctypes/cffi）与 cffi 内嵌 C 代码对 kb-scanner 是盲区；GoReleaser 配置的 goarch 架构清单缺失检测是 G 阶段遗留。riscv-scan 的 solver_python（tree-sitter AST + 四类 import 状态机 + 内嵌 C 复扫）与 solver_cython（行正则）/solver_go_releaser（缩进状态机）提供规格，本 change 按合同重写为第七输入域 + build 通道微步。

## What Changes

- `frontends/python/` 三文件（tree-sitter-python 0.25.0，ABI 15）：
  - **四类 import 状态机**（含 as 别名识别；限定名匹配不做别名追踪——riscv-scan 同款局限，文档化）；
  - **动态库加载五形态**（参数非空才产证）：ctypes.CDLL / ctypes.cdll.LoadLibrary（qualified）、cdll.LoadLibrary / CDLL()（from-import）、FFI().dlopen（inline）、已收集 ffi 实例 .dlopen（instance，**消误报**：未收集实例不产证）→ call_expression + 库名单发 preprocessor_condition（ARCH_CONDITION_CANDIDATE 启发式）；
  - **cdef/set_source 内嵌 C**：#include 行 → include、代码行 → inline_asm（复用 asm 词法器进 C 检测链——riscv-scan 喂 4 个 C solver 的等价重实现）；
  - **.pyx 行正则降级**（PyPI 无 tree-sitter-cython）：ctypes 调用 + from ctypes import *dll* 两检测点，# 注释跳过；
  - verify() → selection_condition 兜底。
- **goreleaser 微步**（build 通道）：BuildInputKind 增 GORELEASER；`frontends/build/goreleaser.py` 缩进感知解析（锚点容忍列表项 `- goarch:` 形态）→ build_arch_constraint（targetAllowed=False，复用 RPM arch 约束语义与既有 BuildEvidenceNormalizer 链）。
- 接线：Composite 第七成员、`.py`/`.pyx` 后缀、domain `python-x86/arm64-to-riscv`、runtime.lock tree-sitter-python==0.25.0、ABI 门禁列入 python；`--language python` opt-in（默认输出字节不变）。

## Capabilities

### Modified Capabilities

- `kb-scanner-cpp-frontends`: python 第七输入域（.py AST + .pyx 行正则）。
- `kb-scanner-build-support`: goreleaser goarch 配置输入（build 通道第 10 类）。

## Impact

- 代码：frontends/python/（新三文件）、frontends/build/{vocabulary,workspace,frontend,goreleaser}.py、frontends/cpp/workspace.py、pipeline/composite.py、projection（前缀+Literal+schema）、tests/tree_sitter_native_support.py
- 测试：unit 17（import 状态机五形态/FFI 实例消误报/内嵌 C/pyx 行正则/goreleaser 三态）+ e2e 6 + samples/python（app.py + fastmath.pyx）
- 已知边界（文档化）：import as 别名不追踪限定名（ct.CDLL 不命中）；py 行正则形态有限
