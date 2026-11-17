# Design

## 上下文

riscv-scan 的 solver_python 是其最完整 AST solver（427 行）：四类 import 状态机、ctypes/cffi 五种加载形态（参数非空门）、FFI 实例收集、cdef/set_source 内嵌 C 字符串转投 4 个 C solver。solver_cython 是 56 行行正则（.pyx）。PyPI 实查无 tree-sitter-cython 包。

## 决策

### D1: import 状态机 + 实例收集照规格移植，消误报纪律保留

限定名匹配不做 as 别名追踪（`import ctypes as ct` 的 `ct.CDLL` 不命中——riscv-scan 同款局限，文档化）；FFI 实例 `.dlopen` 只在 `ffi = FFI()` 收集后产证（未收集的 `other.dlopen` 零证据）；加载调用参数非空才产证（空参不猜）。

### D2: 内嵌 C 行级复用（等价于"喂 4 个 C solver"）

cdef/set_source 的字符串实参：#include 行 → include、代码行 → inline_asm（asm 词法器复用）→ 既有 C 检测链。`_mm_add_epi32` 直接命中词表。与 riscv-scan 把字符串注入伪造 SourceFile 调 4 个 solver 相比：走统一证据链、有稳定 ID、不引入 solver 直接互调。

### D3: .pyx 行正则降级（与 riscv-scan 同款路线）

无 grammar 包 → 行正则（# 注释跳过、ctypes 调用形态 + from ctypes import *dll* 两检测点），kind 路由与 AST 线一致。这是刻意选择而非妥协：grammar 无官方 wheel，自维护 grammar 的成本超过行正则的检测面损失。

### D4: goreleaser 走 build 通道（G 阶段遗留归位）

`.goreleaser.yml` 是构建配置不是源码——BuildInputKind 增 GORELEASER、缩进感知解析（锚点容忍 `- goarch:` 列表项形态，riscv-scan 锚点正则实测会漏该形态）、产 build_arch_constraint（targetAllowed=False）复用 RPM arch 约束语义与 BuildEvidenceNormalizer 既有链。零新证据 kind。

### D5: 库名路由沿用 Java 的修正结论

preprocessor_condition → ARCH_CONDITION_CANDIDATE 启发式（Java change 实测词表内容后的既定路线）。

## 风险与缓解

- cdef 多行字符串行号偏移：锚定字符串实参首字节（节点文本首个引号 +1）逐行累计，单测钉行列。
- goreleaser 缩进歧义：锚点要求独占行 + 列表项缩进严格更深；三态（缺失/含 riscv/无块）单测钉死。
