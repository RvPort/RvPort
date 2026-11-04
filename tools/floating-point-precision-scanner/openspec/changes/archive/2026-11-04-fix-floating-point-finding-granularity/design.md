## Context

OpenSpec 根为本工具目录。用户要求一次修复所有浮点规则中“独立问题合成一条结果”的同类缺陷，不再逐截图补丁。动机与范围见 [proposal.md](proposal.md)。

2026-11-03 对当前源码的排查：

| 规则 | 当前评估行为 | 本次规定的独立问题边界 |
|---|---|---|
| `architecture-specific-fp-api` | 一个 TU 的所有 API 一次判断；任一 unresolved/ambiguous 会使整组 unknown | 解析后的调用表达式及实际调用点；不同函数、不同调用点独立 |
| `math-library-variance` | 整 TU 的数学调用合成一条结果，输入域线索合并 | 独立数学调用与自身输入域，必要 libm context 可共享 |
| `fragile-fp-comparison` | 整 TU 判断；NaN 分支提前返回；一个 use_context 缺失可遮蔽其他命中 | 独立比较表达式；仅有明确关系时将同一比较模式的辅助事实归入本候选 |
| `floating-environment` | 全 TU 的 environment 与 function_summary 混用 | 函数内可证明的环境状态影响区间；同函数内独立区间分开；跨函数仅沿已证明调用关系依赖 |
| `fma-contraction` | 全 TU 的候选、IR 与 binding 混用 | 完整乘加/乘减候选及其关联 lowering；不能把一次表达式的 multiply/add 拆成两条风险 |
| `x87-excess-precision` | 全 TU 的运算链合并，任意 IR 转换被引用 | 同一可证明的求值链至存储/舍入边界；不同函数、独立链分别评估 |
| `long-double-abi` | 已按声明边界输出，但 eager 批量计算且批量异常捕获 | 独立已证明 ABI/持久化边界；重复声明事实去重，修复局部异常和预算执行 |
| `mixed-precision-conversion` | 已按转换输出，但 eager 批量计算且批量异常捕获 | 独立转换表达式及其存储/use 关系；修复局部异常、稳定去重和预算执行 |
| `reduction-reassociation` | 已按循环/递推候选输出 | 保留独立循环及累加器，纳入同一套全链路回归 |
| `unsafe-fp-optimization` | TU 配置风险；仅用 active flag 集合作跨 TU 聚合键，位置收集全部 facts | 有完整语义身份的有效配置风险；聚合只允许严格等价配置成员 |

当前 `_aggregate_findings()` 合并位置和数量，但其他成员的 evidence/assessment 仍可能由首条结果代表；`apply_suppressions()` 在规则聚合之后只按主位置匹配，因此合并后的单点抑制可能隐藏其他问题，非主位置抑制也可能失效。候选修复必须贯通这些共用环节。

探索已在隔离 Docker 中加载当前 `/review/src`，用两个独立函数中的 `_mm_add_ss` 与 `_mm_sub_ss` 复现 `complete`、一条 API Finding、两个 occurrences、`affectedCount=2`。这只是缺陷复现，不是修复验证。较大的 AVX 头文件样例返回 `frontend_nonzero_exit`，不纳入成功证据；正式回归须使用可解析的真实编译上下文并保留失败诊断。

前端 `presentRiskEvidence` 直接消费一个 Finding 的 occurrences，详情仅在数量大于 1 时提供位置切换；Java 专项接口保存并返回该 detail。此次不通过前端拆分或隐藏位置改变语义。

## Goals / Non-Goals

**Goals:**

- 十条规则全部有明确且已验证的候选边界；六条批量评估规则完成修复，三条已有候选规则补齐通用生命周期，配置规则聚合可证明且审计完整。
- 相互独立的问题具有独立 assessment/Finding、直接证据、状态、置信度、抑制和有界调度，不因同规则、同文件或同 TU 再次合并。
- 保留同一问题的完整多证据链；直接定位属于本候选，辅助说明保留在 evidenceChain，不将必要上下文自动当作额外命中。
- 同一变更完成十规则多候选单测、真实语料、全量双覆盖率、性能与隔离部署验收，不将同类缺口推迟到另一个 change。

**Non-Goals:**

- 不修改前端、Java 后端、其他扫描工具或历史扫描数据，不更换结果 v2 外层协议。
- 不把每一行、每个 AST 节点或每个 IR 指令机械地拆成结果，不删除合法的多位置表达能力。
- 不扩展到新的风险类别或无界全程序数值证明；事实不足时使用候选级 unknown，不能声称确定风险或确定安全。

## Decisions

### 1. 候选构造与规则判断分离，覆盖全部 descriptor

采用可测试的候选描述，包含规则、规范化编译上下文、语义身份、直接 source refs、作用范围、必要依赖及无法证明的边界。descriptor 的 assessment_scope 与实际调度一致，不能只作为展示元数据。共享有界调度器调用每条规则的候选构造器与局部 evaluator。

候选身份使用稳定声明/表达式/调用/函数/循环/递推关系和编译语义，排除临时路径、遍历序号、整个 bundle 摘要及其他候选。完全相同事实去重；宏展开、模板实例与同一行不同列的独立调用保持独立。只有被证明属于同一语义问题的事实才合并。

拒绝只在 `_finding()` 或前端按位置拆分的方案：聚合之前的 unknown、IR 和置信度污染无法通过展示拆分修复。也不把整 TU facts 切片后直接复用全部 IR，这仍会让不同候选互相影响。

### 2. 按语义关系划分复杂候选

API/libm/转换/比较按表达式或声明身份构造；FMA 将乘法与相连的加减作为一个候选；x87 沿有证据的数据依赖到实际存储/舍入边界，避免每个嵌套算术节点重复报警。fenv 按函数内状态操作及已证明的作用范围组织，区分同函数的多个独立 save/change/use/restore 区间。无法确定区间或跨函数调用效果时，局部 unknown，不能用另一个函数的恢复动作消除风险。

facts 或 IR 缺少区分能力时，在本工具内补足必要的关系字段、mapper/worker 与版本及测试；不以函数名/相同行号猜测绑定。拒绝将 fenv 永远按函数整体合并的简化，因为同函数也可能有可独立处理的环境区间。

### 3. 证据只沿可靠关联进入候选

从候选直接 source refs 沿合法 binding、operand/use-chain 和必要函数摘要构造有界闭包，带上必需 profile、build context 和有效编译选项。跨候选共享事实必须是明确的依赖，并保留其作用边界；必要全局 profile 缺失可以影响所有依赖它的候选，但不能冒充单个候选污染。

FMA 的 `contract` multiply/add 必须属于同一候选表达式和数据链；x87 的 fpext/fptrunc 必须对应当前求值链；API 宏歧义、comparison use 缺失、某个函数摘要 unknown 不传播到无关候选。置信度提升只能使用本候选证据；悬空、多义、冲突或越界 binding 明确降级，不任选一侧。

### 4. 所有规则在求值前执行候选预算并局部捕获异常

候选先按稳定键排序，再惰性求值；candidate、assessment、evidence refs 与 related locations 上限均在工作边界生效，不能 eager 评估后截断。可分类异常仅产生当前候选 failed，其他有效候选继续；整份协议输入无效仍在输入层失败。

零候选保留现有 no-candidate/notMatched 审计；零预算、恰好上限、超限、不同规则/TU 共用预算和缺失位置分别验证。frontier 标识未处理范围；候选 diagnostic 中 TU 身份与候选 scope 分别保存，不能把候选哈希伪装成 TU。

### 5. 配置级聚合是唯一显式例外，必须保全成员

`unsafe-fp-optimization` 先按规范化编译上下文产生独立配置风险成员，再依据规则版本、完整有效危险语义、相关编译器/目标/profile/build context 指纹和证据模式判定严格等价。仅危险 flag 名称相同不足以合并；缺少决定性身份时保守保持独立或记录 unknown。

聚合对象必须保存全部成员身份、assessment refs、去重后的必要证据、稳定位置和真实数量，不能复制首个成员后丢失其他依据。候选局部证据不足或失败不被另一个成员覆盖。`affectedCount` 对普通语义候选为 1，对配置聚合为独立配置影响成员数量，不按 AST facts 数量膨胀。

调整规则求值与发布的衔接，让稳定候选身份先于 suppression，成员抑制先于最终聚合；保留可见成员并重新构建聚合证据和计数。抑制审计引用独立候选，聚合身份携带确定的成员集合。公开 schema v2 不新增无约束私有形状；需要额外成员审计时放在受 schema 约束的既有 namespaced extension 中并维护 contract tests。

### 6. 输出与兼容性

每个独立候选仅一次投影为 v2 Finding，主位置属于自己的直接位置集合；occurrences 按源码路径、行、列及稳定 tie-breaker 排序，主位置优先，不能仅按 hash 给出跳跃式位置序列。证据步骤顺序依赖图关系，不能以展示排序修改因果关系。

有多处位置的 Finding 必须能审计为同一问题或严格等价的配置影响组。用户报告中的独立 API 调用及不同函数不得再出现在同一条风险的“位置 1/2”。同问题多证据与历史多位置结果继续合法。

逐规则记录行为变化和版本决策；候选身份/证据/缓存语义变化同步必要版本、schema、registry 摘要与 fixtures。保留受支持请求的默认规则集合、配置校验、结果 v2、双语字段、suppression 日期和安全路径要求。历史结果不回填，重扫生成新身份。

### 7. 十规则验收矩阵作为自动门禁

每条规则必须显式列入矩阵，记录候选定义、多个独立候选、同候选多事实、matched+unknown、matched+notMatched、可隔离 failed、无关证据、单候选抑制、预算、稳定 ID、冷暖缓存和 v2 引用验证。确实不适用的组合必须写出语义理由及等价测试，不允许缺省遗漏或以“已有测试”跳过。

真实 C/C++ 多候选输入至少覆盖同函数两个独立问题、跨函数、同一行不同列、宏/模板和多 TU/配置。FMA/x87 使用实际 Clang/LLVM 关联；fenv 包含两个函数及同函数两个独立区间；comparison 包含 NaN 与普通比较并存；配置规则覆盖同语义合并、异语义分离、逐成员 suppression 与证据完整性。

扩展现有 `corpus_runner` 和独立 oracle，多 TU/多输入文件/编译上下文的 recipe 需要有效单元测试；不得仅验证人为注入 facts。observed 必须由本次候选源码真实执行并记录源码、输入与 oracle 摘要，保留重复 Finding、多候选状态、归属、计数和 lifecycle 检查。定位数量不足以证明证据隔离，contract/unit 还必须断言引用集合、状态与置信度。

验收继续执行 Docker 内 `make lint typecheck coverage cpp-coverage integration validation` 和固定 4 CPU/4 GiB 的 `make benchmark`。工具整体及每个受影响 Python/C++ 模块行、分支覆盖率均至少 90%；统计未加载源码，不排除自有业务逻辑。保留逐规则 strict precision/recall 至少 90%、整体 exact match 至少 95%、lifecycle 100% 以及全部性能基线门槛。

镜像来自实际拟交付 Git 快照，记录 tree、输入字节、镜像和报告，不把旧镜像或历史 observed 用作本次验收。使用隔离容器与资源限额，按模块依次运行；从仓库根完成默认服务完整 Compose 构建并以一次 `up -d --wait` 启动最终验收轮次，在实际安装包核对十规则矩阵和代表性项目的结果数量、源码位置、证据归属。报告留在私有 `.migration-reports/fix-floating-point-finding-granularity/`，变更内记录脱敏命令和结果。

## Risks / Trade-offs

- 新结果数量增加、身份改变 → 增加有界调度、稳定排序和 suppression 回归；保留历史数据，文档明确重扫行为。
- FMA/x87/fenv 关系不完整 → 补足可证明事实，无法证明时局部 unknown；补充正反例并保持检测质量门槛，不能通过全部返回 unknown 达标。
- 配置聚合与抑制顺序改变可能影响 summary/refs → 用成员矩阵验证聚合前后身份、计数和图引用，逐规则公开变更。
- 扩展语料或证据闭包增加成本 → 使用原有 facts/worker/runtime 预算，固定环境重跑 S/M/L 性能，不降低阈值。
- 同一问题多位置被误认为遗留 → 在验收矩阵记录每一种保留多位置的语义理由；独立调用/表达式/循环不得借此例外合并。

## Migration Plan

1. 固化当前十规则行为、独立预期和缺陷复现，实施统一候选契约及必要事实/证据关系。
2. 完成六规则修复、三规则加固和配置聚合修复，同步生命周期及版本，保留 schema v2。
3. 同一变更完成所有单元、真实语料、双覆盖率、性能及实际安装包验收；任何规则或门禁未完成均不得标记整体完成或归档。
4. 交付后通过重新扫描使用新粒度；部署回退采用此前完整工具版本，不混用不兼容缓存。部署、同步主规范、归档和 Git 发布按对应阶段授权执行。

## Open Questions

无须用户再次选择的范围问题。实现时需验证的技术事项为 fenv 区间及 FMA/x87 依赖关系在现有事实中的完整性，已明确纳入本变更的 facts/worker 范围；不得据此自动遗漏规则。若出现超出本工具的真实依赖阻塞，应报告具体证据并保持任务未完成。
