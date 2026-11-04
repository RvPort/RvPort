# C/C++ 浮点精度扫描基础框架

[English](README.md) | 简体中文

本模块为 RvPort 提供仅面向 C/C++ 的浮点事实扫描基础设施。当前版本支持 C11、C17、
C++17 和 C++20，读取 `compile_commands.json` 或显式翻译单元，并通过锁定的 Clang
21.1.8 生成版本化 canonical floating-point facts。

可选的 `bitcode_artifact_set` 输入允许扫描器只读消费 x86 构建流程已经发布的不可变
LLVM 21.1.8 BC。扫描器不会生成 BC，也不会在 artifact 正在生成时轮询等待；编排层应在
artifact 已进入终态后启动扫描。BC 会由隔离的 `fp-ir-worker` 转换为 canonical IR facts，
再与 AST facts 形成单独的 evidence 层。

scan schema 1.3–1.4 默认运行十条迁移规则：既有四条核心架构规则，加上
`floating-environment`、`mixed-precision-conversion`、`fragile-fp-comparison`、
`reduction-reassociation`、`math-library-variance` 和 `architecture-specific-fp-api`。
旧 schema 1.0–1.2 省略 `rules` 时仍锁定为原四条规则，避免工具升级后默认语义漂移。
规则只接收规范化 AST facts、共享 LLVM BC facts、AST↔IR evidence、版本化 profile 和可选
build context，不直接依赖 Clang/LLVM 内部对象。

请求没有 `rules` 字段时启用对应 schema 版本的默认集合；`rules.enabled` 可选择稳定 code 子集，显式
`{"enabled": []}` 则保留 `factsOnly` 模式。unknown、skipped 与 failed assessment 会保留在
分母中，不能把空 findings 解释为工程没有浮点风险。

工具 0.6.0 将十条规则统一为候选独立评估：API/数学调用、比较/转换表达式、ABI 边界、
循环累加器、完整 FMA、x87 求值链及 fenv 状态区间分别生成 Finding，证据、置信度和
unknown/failed 仅作用于当前候选。普通 Finding 的 affectedCount 为 1；同问题的多处证据
仍可保留。仅语义完全等价的危险编译配置允许跨 TU 聚合，且逐成员抑制先于聚合。
旧结果不回填，升级后重新扫描生成新粒度结果。协议继续为结果 v2，展示字段保持中英文。
完整矩阵、真实语料和覆盖率命令见 [验证说明](docs/validation.zh-CN.md)。

## 安装与环境

在仓库根目录的 PowerShell 中构建独立开发镜像。依赖安装、编译和验证均在 Docker 内执行，不创建宿主机虚拟环境：

```powershell
docker build -f tools/floating-point-precision-scanner/Dockerfile.dev -t rvport-fp-dev:local tools/floating-point-precision-scanner
docker run --rm --memory=8g --cpus=2 rvport-fp-dev:local fp-precision-scanner version
```

真实前端验证使用 `Dockerfile.dev` 中锁定的 Linux/amd64、Clang/LLVM 21.1.8 环境；
扫描器不会在锁定编译器缺失时回退到 PATH 中的其他版本。

## CLI

下列是开发容器内、工作目录 `/workspace` 的命令；外部源码和请求应只读挂载，输出使用单独可写目录。

```text
fp-precision-scanner version
fp-precision-scanner profiles list
fp-precision-scanner profiles show x86_64-linux-gnu-clang21@1.0
fp-precision-scanner validate-request --request request.json
fp-precision-scanner scan --request request.json --output result.json --clang /usr/lib/llvm-21/bin/clang-21 --ir-worker /workspace/build/fp-ir-worker
```

请求与结果示例见 `examples/`。扫描请求必须显式选择 source/target profile，并在
`input.compile_commands` 与 `input.translation_units` 中二选一。混合工程只规划 C/C++；
其他语言以 `unsupported_language` 双语诊断排除，不进入扫描分母。

共享 BC 模式仍要求提供相同源码版本的 `compile_commands.json` 或显式翻译单元。请求必须
固定 manifest SHA-256；consumer 随后校验源码修订、编译输入摘要、成员身份、profile、文件
大小和 SHA-256。默认只要求 `source-preserving`，`build-faithful` 必须显式请求。详细约定见
[`docs/shared-bitcode-consumer.md`](docs/shared-bitcode-consumer.zh-CN.md)。

## 核心规则

| 规则 | 主要输入 | 结论重点 |
|---|---|---|
| `long-double-abi` | AST 边界 facts、source/target profile | 公开 ABI、变参、记录与持久化布局差异 |
| `x87-excess-precision` | AST 运算链、有效选项、IR/evidence | x87 扩展中间精度和存储舍入边界 |
| `fma-contraction` | AST 乘加候选、build-faithful IR/evidence | 显式 FMA 或可 contraction 的乘加一次/两次舍入差异 |
| `unsafe-fp-optimization` | 最终生效选项、IR fast-math flags | NaN、Inf、±0、重结合、近似、errno 与异常语义放宽 |

## 表达式风险规则

`reduction-reassociation@1.1` 按独立循环和累加器生成独立 Finding。同一函数的两个循环即使
复用 `result` 也分别评估；同一循环的 `sum` 和 `product` 也分别评估。单候选可以包含多个
证据位置，但 `affectedCount` 为 1。证据、置信度、unknown/failed 和 suppression 均按候选
隔离。复杂递推身份不明确时局部 unknown，不再把整个编译单元的归约合成一条结果。

AST-only 保持 heuristic。置信度提升要求本候选无歧义的 build-faithful IR 关联、重结合标志，
以及明确的源/目标重结合策略差异。当前内置 profiles 没有声明该策略差异，因此默认仍要求
人工确认。输出继续采用结果 schema v2；历史结果保持原样，重新扫描后才能得到新粒度。

| 规则 | 主要输入 | 结论边界 |
|---|---|---|
| `floating-environment` | fenv pragma/API、函数摘要、effective options、profile/IR | 间接调用、外部调用或摘要预算边界返回 `unknown` |
| `mixed-precision-conversion` | canonical source/target type、常量、整数位宽与 storage/use | 显式 cast 默认视为意图证据；类型或值域不可恢复时返回 `unknown` |
| `fragile-fp-comparison` | operand provenance、NaN/零/epsilon 形态与控制流用途 | NaN 错误模式可确定；精确比较与 epsilon 风险为 heuristic，不生成业务容差 |
| `reduction-reassociation` | 循环递推、补偿算法、IR phi/vector reduction 与 flags | 仅 AST 候选为 heuristic；可靠 IR lowering 可提供确定证据 |
| `math-library-variance` | 解析后数学声明、参数域、source/target build context | 只有已验证的 errno/exception/special-value 等语义差异才命中；libm 未固定返回 `unknown` |
| `architecture-specific-fp-api` | resolved declaration/builtin/header、target features 与 active guard | 名称匹配不能单独命中；可移植目标分支明确时不报告 |

schema 1.3 可分别绑定 `FloatingPointBuildContext@1.0`。context 指纹不包含本地 sysroot 或
materialization 绝对路径；未可靠固定 libm、运行环境或 target feature 时必须显式写
`unknown`。请求 suppression 必须给出稳定 rule code、仓库相对 POSIX 路径、可选完整行范围
和非空理由；源码注释使用
`fp-precision-scanner: ignore <code> -- <reason>`。suppression 在 finding ID 生成后应用，审计
记录进入 `org.rvport.floating-point-scanner.suppressions` 扩展，并计入
`summary.suppressed`。

schema 1.4 增加统一运行预算、canonical 中间产物缓存和 suppression 生命周期。带
`expires_on` 的 suppression 必须同时提供显式 `policy_as_of`；日期相等时仍有效，过期后
finding 保持可见。缓存仅保存 source facts、LLVM IR facts 和 AST↔IR evidence，最终 finding、
assessment、suppression 与完整结果始终重新计算。详见
[`docs/analysis-cache.md`](docs/analysis-cache.zh-CN.md) 和
[验证语料](docs/validation.zh-CN.md#doc-validation-corpus)。

LLVM worker 会把同一模块内可解析的直接调用环境摘要有界传播到调用者，并记录 callee、
call-site、传播深度和稳定 frontier。请求可用 `max_function_summary_depth`、
`max_ir_summary_call_edges` 和 `max_ir_use_chain` 分别限制摘要深度、摘要调用边和 IR use-chain；
递归、间接调用、外部调用或预算截断均保守标记 `unknown_effect`。

需要 build-faithful IR 的规则在制品缺失、绑定歧义或 profile 字段 unknown 时输出 unknown
assessment 和双语诊断，不猜测结论。high finding 要求人工确认；finding 本身不改变成功退出码。

## 状态与退出码

扫描结果只发布 `schemaVersion: "2.0"`。顶层固定为 `scan/project/summary/findings/diagnostics/extensions`，
公开字段使用 camelCase；不提供旧结果 serializer、双写、转换器或结果版本选择。每条 finding
使用 `fact/conclusion/occurrences/primaryOccurrenceId/evidenceChain` 表达事实、结论、位置与
可闭合证据链。AST/IR/binding/profile/build-context/assessment 审计对象通过带 producer、schema
和 digest 的 typed reference 解析到 analysis 扩展，原始 facts/IR/evidence 不再作为无约束顶层数组。

| 状态 | 退出码 | 说明 |
|---|---:|---|
| `complete` | 0 | `scan.status=complete`；所有计划 facts、evidence 和规则 assessment 均完成，可以包含 finding |
| `invalid_request` | 2 | schema、路径、profile 或输入选择无效 |
| `failed` | 3 | 没有翻译单元产生可用结果 |
| `partial` | 4 | 有可用结果，但同时存在失败或阻断性 unknown |
| `internal_error` | 5 | 未分类内部错误或结果无法安全发布 |

## 确定性与安全

- 稳定 ID 和语义摘要只使用版本、仓库相对 POSIX 路径、语义位置与载荷；
- 时间戳、PID、临时目录、绝对工作区和并发完成顺序不进入摘要；
- JSON 集合使用固定键排序，文件结果通过同目录临时文件、fsync 和原子替换发布；
- 默认诊断同时提供中英文，不输出凭据、环境变量值或项目根外绝对路径。
- 共享 BC 结果分别公开 artifact/source/IR/evidence 四层分母及不可变摘要；全部必需 IR
  成员失败时，即使 AST facts 可用，整体仍为 `failed`。

## 验证

以下命令从仓库根目录执行，复用上面构建的镜像。使用独立容器保存覆盖率报告，先导出再删除该容器：

```powershell
docker run --name rvport-fp-unit --memory=8g --cpus=2 --network=none -e CMAKE_BUILD_PARALLEL_LEVEL=2 rvport-fp-dev:local make coverage cpp-coverage
docker cp rvport-fp-unit:/workspace/artifacts/coverage ./fp-coverage
docker rm rvport-fp-unit
```

完整发布检查还包括 lint、类型检查、语料与性能基线，可在同一镜像内执行 `make verify-release`；其结果与普通单元测试分别记录。

覆盖率统计显式包含 `src/floating_point_precision_scanner` 下全部项目自有源码，包括未被
测试导入的文件。项目整体及受影响模块的行覆盖率、分支覆盖率均必须至少为 90%。唯一
配置排除项是 `if TYPE_CHECKING:` 静态导入分支；项目自有业务逻辑不排除。
LLVM worker 使用 source-based coverage，同样对 `src/cpp` 全部项目源码执行 line/branch
双门禁；第三方 LLVM、GTest 和生成的构建文件不计入项目覆盖率。
reviewed corpus 与固定 S/M/L benchmark 的维护和验收方式见
[验证语料与性能基线](docs/validation.zh-CN.md#doc-benchmark)。

## 文档入口

[文档索引](docs/README.zh-CN.md) 集中提供架构、输入协议、缓存、验证和依赖说明；历史阶段状态以 [OpenSpec](openspec) 为准。
