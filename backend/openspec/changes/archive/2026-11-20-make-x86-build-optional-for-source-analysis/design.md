## Context

当前基线为 backend 已归档的 add-ai-scan-review-and-manual-fix-list，加上 fc125e1（PR #22）已合并代码。所有下列工程路径以 backend 根为准。

- `kb/KbScanContextService` 将成功 SOURCE_ONLY 构建作为 ready 和当前结果的必需条件；`KbExecutionContext.complete()` 要求 buildRunId 非空。KB CLI 本身已有不传编译数据库的源码模式。
- `analysis/SpecializedAnalysisService` 查询成功构建及 compile database，`SpecializedAnalysisRuntimeService` 无条件下载该制品，`src/main/resources/analysis/prepare_specialized_analysis.py` 的 build-run/compile-database 均必填。仅取消 Java 校验无法实现源码独立运行。
- 两个专项 CLI 已提供 AST/源码能力；浮点可输入显式 translation_units，内存序由统一 analyze-project 接收 source requests 和开放 Scope。无构建不等于无编译语义，适配器必须公开内部解析假设，不能称之为用户真实编译配置。
- `report/review/ReportInputSelectionPolicy` 和 `ReportSourceRepository` 用成功构建选择结果并拒绝 PARTIAL；`SpecializedAnalysisStatus.publishedResult()` 却已认可 PARTIAL。
- `ProjectWorkflowStatusService.recommended` 先选 BLOCKED、再选 PENDING，可能一直推荐可选构建。`StageReadinessService.test` 先读取测试状态作为构建 prerequisite，且单侧成功也可能被判满足；应在本次统一依赖时纠正。
- 未完成的分析、结果存在与证据充分是不同事实。报告 AI 是条目复核，不能替代构建、测试或对未扫描源码的全面审计。

## Goals / Non-Goals

**Goals:**
- 三个源码分析不因未构建、构建失败、未配置远程环境或缺少 compile database/bitcode 被整体拒绝；具备固定源码及必要本地工具和受支持分析 profile 即可创建任务。
- 可信产物增强分析；无产物时给出可审计的内部源码计划，优先保留真实源码线索和不确定候选，所有覆盖缺口进入报告。
- 保持依赖分析和后续验证链的真实门槛，统一全部查询与执行入口的依赖判定。
- 复用个人 Agent、不可变报告、草稿、人工继承及确认协议，支持高候选数量而不静默截断。

**Non-Goals:**
- 不新增跳过动作、用户已跳过状态或自动构建，不将未执行写成成功。
- 不修改 frontend、tools、根级规范或任何远程环境；不新增工具规则、替换 CLI、升级依赖、建立额外 Compose 服务。
- 不自动生成 bitcode、不运行用户工程 configure/build、包管理器或任意编译数据库命令。分析器内部已允许的锁定解析组件继续按现有契约使用。
- 不扩大为全源码 AI 主动扫描、不自动排除疑似误报、不宣称源码候选已获运行时验证。

## Decisions

### 1. 依赖图与状态职责

| 阶段 | 必需输入 | 可选输入/说明 |
|---|---|---|
| KB | 固定源码、受管扫描器 | 可信 compile database 与构建事实 |
| 浮点 | 固定源码、受管工具、受支持源/目标分析 profile | compile database、共享 BC |
| 内存序 | 固定源码、受管工具、明确分析 profile 与开放 Scope | compile database、共享 BC |
| 前置 x86 构建 | 源码、启用源环境、有效脚本 | SOURCE_ONLY 仅要求源环境；无目标环境也可执行 |
| 依赖分析 | 当前有效成功 SOURCE_ONLY 构建、必要源/目标环境 | 维持现有构建事实提取 |
| 报告 | 至少一类当前可用且合法发布的完整/部分分析产出 | 其他来源缺失不阻塞；AI 模式另查个人配置 |
| 人工修复 | 当前有效已确认清单 | 空清单表示无需本次清单修复 |
| 后续构建验证 | 源码、启用源/目标环境、有效脚本 | BOTH 不要求前置 SOURCE_ONLY、报告或人工修复完成 |
| 自动化测试 | 当前对应的成功 BOTH 验证、测试配置 | 不以单侧构建或旧测试成功代替 |
| 优化闭环 | 当前有效双端构建、匹配测试批次及比较 | 原验证链语义保持 |

未执行/进行中/成功/失败/取消/超时保持真实事实；不增加 SKIPPED。optional 是节点属性，不能作为执行状态。源码分析已完成不意味着所有候选证据完整，保留 analysisComplete、coverage 和 limitations。

推荐阶段从已完成结果的合法后续步骤与当前可执行节点中选择；可选构建未完成不抢占可运行源码分析。存在活动任务可继续展示其进度，但不能据此禁用独立可运行节点。相同资格采用稳定既有顺序；可运行源码分析优先于可选构建，报告就绪时允许进入报告，无候选不等于所有阶段完成。

### 2. 固定 AnalysisInputPlan，构建是可空增强引用

新增统一输入解析组件，为 KB 与专项创建不可变计划，至少包含 planVersion、sourceRevisionId、源码内容指纹、工具/规则/profile 版本、inputOrigin、effectiveInputDigest、可空 buildRunId/compileDatabaseArtifactId/artifactSetId、安全制品哈希、内部解析假设及 plannedFiles/TUs。入队只传受控引用，不传任意用户命令。

inputOrigin 使用 SOURCE_DISCOVERY 或 BUILD_ARTIFACTS；analysisMode 保留现有 AST_ONLY/AST_BC_MIXED/AST_BC_COMPLETE，KB 用自身源码/编译上下文模式。SOURCE_DISCOVERY 不自动保证 AST 可信，实际解析失败仍单独记录。

可选制品只从当前同源码、同环境版本、真实成功且完整登记的 SOURCE_ONLY 构建选择，权限、owner、hash、源码身份、编译上下文和 profile 全部核对。当前构建采用活动优先、否则最近创建规则；新构建运行/失败时不能回退其他旧构建来满足依赖分析或新分析的增强输入。已启动分析始终使用固定计划，不中途升级到新制品。

只有明确缺失的可选产物可在创建时选择源码模式。worker 发现已绑定可选产物丢失/损坏时，安全弃用并记录实际降级计划，不能换用其他构建；无权限资源永不读取。保存 requestedInputDigest 与 effectiveInputDigest、原因和实际模式，只有真实执行及完整发布的产出才可选入报告。源码损坏、必需工具缺失、协议输出损坏不属于安全降级，按失败处理。

选择理由：单独的 skip flag 不能描述实际输入；将假构建插入数据库会污染依赖与验证链；查询时临时选择 latest 会破坏重试和历史可追溯性。

### 3. 无编译数据库的内部源码计划与语言范围

仅在 backend 输入适配层增加路径，复用锁定工具已支持接口；不改工具算法或独立规范。

KB Scanner 已能直接扫描源码，本 change 不为其实现新的源码扫描器、不增加编译数据库依赖，也不把专项的内部 TU 投影传给 KB。KB 的改动限于后端任务前置条件、可选参数传递、实际输入身份、结果适用性和报告接入；已有 `--compile-commands` 仅在可信产物可用时沿用。下文的 C/C++ 内部解析适配主要服务于浮点与内存序分析。

1. KB 按所选且工具支持的语言在固定 manifest 中扫描，不套用专项 C/C++ 过滤器；默认仍为 c,cpp，不自动启用其他语言。浮点与内存序从固定源码 manifest 枚举允许的 C/C++ 实现文件，稳定排序、排除受控目录，保留排除规则版本、未支持语言和超限范围；不将任意头文件伪装为完整翻译单元。
2. 对专项计划中的每个 C/C++ 文件建立显式解析配置；以下 TU/语言标准规则不强制用于 KB 的其他语言。优先使用平台已有受支持的分析 profile；未显式声明语言标准时使用工具已支持的固定 C17/C++17 分析默认值，并记录 DEFAULT_ASSUMPTION，不声称与真实工程等价。真实编译数据库存在时保留原标准/宏/参数，不用默认值覆盖。
3. 只使用固定源码内可验证的引用目录与工具允许的系统资源。未知 include/macro/生成头、条件编译和目标成员关系记入缺口；不得执行仓库脚本、任意 response file 命令或网络安装来补齐。
4. 浮点使用显式 translation_units；内存序使用 source requests 与开放 Scope。若既有 backend 适配函数只能消费 compile database，可生成仅任务内部的解析输入投影，但必须标记 SOURCE_DISCOVERY，不能登记为真实构建制品、产生 buildRunId 或供依赖分析消费。Scope 无真实链接证据时保持开放，禁止从目录猜测完整程序。
5. 各 TU 独立调用工具内部分析；已得到的合法候选保留，失败/不可信 AST 不提升为语义事实。全部无法判定但工具合法发布了覆盖/诊断结果时仍可形成 PARTIAL；技术崩溃、无合法输出仍 FAILED。
6. 不新增自研词法扫描器或改变已明确排除误报的工具规则。高召回体现在保留工具真实提供的低置信/unknown/source candidate，而非编造新风险。若现有 CLI 无法承载最小源码计划，先报告接口缺口，不能越界修改 tools 或用空输出伪装完成。

有限解析假设带来误报是可接受取舍；错误位置、伪造调用关系和假装解析成功不可接受。文件/时间/内存预算保持有界，超限项进入未分析分母及原因，不静默截断。

### 4. Context、当前结果与历史

分离 executionContext（执行事实）、resultApplicability（结果是否仍适用于当前源码及实际依赖配置）、enhancementAvailable（现在是否有更强输入）。

- SOURCE_DISCOVERY 的适用性只依赖源码、实际选用的规范化语言集合、源/目标架构、scope/exclude、profile/解析策略/规则版本，不依赖未消费的远程环境或构建 ID。全局 runtimeEnvironmentVersion 可作显示事实，但不能无条件进入该模式的有效性摘要。
- BUILD_ARTIFACTS 依赖固定源码、实际环境版本、构建/制品身份和解析配置。最新构建替换或相关环境改变时旧增强结果作为历史，不用它满足新 Context。
- 同源码后来出现构建产物，不抹去仍适用的源码结果；提示 enhancementAvailable，用户可重新分析。新的合法结果成功发布后才能替换来源选择；失败重跑不掩盖旧有效产出。
- latestExecution（沿用 API 的 execution 字段表达，不新增同义字段）与 currentResult 分离。运行列表/任务页保留最近失败事实，报告可以继续消费仍有效的上次结果，但返回清楚的两份引用。最新合法 PARTIAL 必须被选择，不能回退更早 COMPLETE 隐藏新缺口。
- 源码更新使旧分析/报告/确认仅历史可读。无关环境改动不使源码报告失效；新有效分析发布、实际 profile/策略变化或已消费制品变更才重算相应 selectionToken。
- 任务恢复沿用固定输入和 configurationUserId；开始时检查权限与必需资源，发布遵守现有租约、generationSequence、reviewRevision。旧 worker 不能覆盖新结果或人工决定。

### 5. 报告准入与证据完整性

来源按各自实际 Context 查询。KB/依赖仍要求真实 SUCCEEDED；专项允许 COMPLETE、COMPLETE_WITH_RISK 以及协议一致、必要制品完整发布的 PARTIAL。失败/取消/超时仅作为来源诊断，不能拿其半截输出当合法候选。

至少一个合法发布产出即可生成报告，允许 PARTIAL 且零 Finding。全部未执行或仅技术失败时拒绝生成，同时 readiness 返回逐来源原因。报告固定每个来源的 run、taskStatus、analysisStatus、resultAvailable、inputOrigin、实际模式、coverage、assumptions、missingEvidence、limitations 和关联来源。无问题条目时仍保存分析范围及无法判定事实。

coverage 计数范围按 api-contract.md 定义：计划内 TU 分类互斥并穷尽，计划外未支持文件单独说明。analysisComplete 表示已声明计划的执行完整性，不表示构建或运行验证充分。coverage 包含 knownSourceFileCount、plannedTuCount、analyzedTuCount、failedTuCount、unsupportedTuCount、omittedTuCount；不同计数单位分别命名，不混算百分比。无法知道完整构建 TU 分母时返回 denominatorKnown=false，不以源码枚举数声称完整构建覆盖。

报告把来源缺口与待修复问题分开：未执行、解析失败、缺头文件或缺 bitcode 不自动生成一条“源码缺陷”，除非存在工具产出的真实候选。formalRiskCount 不计 SOURCE_CANDIDATE/INCONCLUSIVE；AI confirmed 不重写该字段。空确认仍可创建，但须保存证据缺口，不能表达“项目安全”。JSON/HTML、当前报告与确认下载、历史详情均保留当时限制。

### 6. AI 复核、高召回及人工收敛

AI 输入包含原始结论、可信源码片段、inputOrigin、解析假设、候选相关覆盖/证据缺口。只能复核被选来源的条目，不自动浏览未入选源码开展全项目审计。原始事实、AI 判断、人工状态保持三层独立。

候选仅因低置信、无 BC、未验证编译路径或数量较多不得被 backend 丢弃。保持逐来源身份和全部 occurrence；第一版不新增模糊/跨来源去重。已有同源稳定重复归并可保留，但不能丢来源和证据。依赖问题仍沿用中以上或 unknown 且 unresolved 的准入规则，不扩展低风险依赖。

按稳定 ID、条数与输入字节预算将实际 AI 输入分批，固定批次清单和哈希，保存每批完成记录。每批必须通过现有唯一 ID、枚举、双语、证据归属校验；完整报告成功必须覆盖全部应复核 ID。重试复用已校验同计划结果，重复/迟到批次不得覆盖已确认批次；跨 profile/runtime 配置快照变化不得混用，需废弃该尝试的未发布批次并以新 attempt 一致重跑。无论总预算不足还是单项超限，都返回可解释失败/进度，不截断后发布增强成功。

未复核项不进入已排除。CONFIRMED_ISSUE 默认 TO_FIX；SUSPECTED_FALSE_POSITIVE/INSUFFICIENT_EVIDENCE 默认 PENDING_REVIEW。AI 可确认源码问题但不能证明其进入真实构建或发生运行时错误；已有候选 evidenceLevel、原风险、缺口保持。有效人工决定优先；输入语义/位置或证据实质变化不继承。最终清单仍手动提交，普通模式仍无模型依赖。

### 7. API、存储及对接

沿用现有扫描创建、readiness、latest、报告、草稿和确认路径，不增加 skip API。具体字段与例子见 [api-contract.md](api-contract.md)。项目 readiness 保留稳定 prerequisite 状态及 UI 无关合同，增加 required 区分硬条件与可选增强；源分析的可选 X86_BUILD 即使 MISSING/FAILED 也不能被客户端解释为硬阻塞。

运行、列表与 latest 增加安全输入摘要和 resultApplicability；overview 保留状态枚举并补 optional、canStart、analysisComplete 和来源解释。BOTH 的两个构建均成功才满足测试阶段，不能用测试历史或任选一侧成功代替。读取配置就绪不执行远程探测，配置完整不等于连接/运行成功。

数据库采用新迁移为 KB/专项构建关联解除非空约束，增加模式受约束的 plan/context/coverage 存储、必要索引以及 AI 批次幂等唯一键；FK 和同项目同源码约束保持。BUILD_ARTIFACTS 必须具备有效增强来源；SOURCE_DISCOVERY 可无 build ID。完整计划/审计与对外安全摘要分离。DDL/jOOQ 在 Docker 生成验证。

### 8. 验收矩阵

| 编号 | 场景 | 主要断言 |
|---|---|---|
| V01 | 仅源码，无远程环境、构建、数据库、BC | 三个分析可以入队；依赖分析受阻；真实 CLI 产出或合法无法判定结果 |
| V02 | 同源码前置构建失败/运行中 | 不阻塞源码模式；不回退旧构建满足依赖分析 |
| V03 | 有编译数据库无 BC、部分 BC、完整 BC | 实际模式、计数、原标准/宏和身份准确 |
| V04 | 制品丢失/损坏/跨项目/旧源码 | 拒绝读取非法制品、安全降级及固定审计，不切换其他构建 |
| V05 | 缺头文件、未知宏、旧标准、unsupported、空源码、预算不足 | 真实候选及覆盖缺口保留，不生成假位置/风险/成功 |
| V06 | 只有合法 PARTIAL，零 Finding 或全 unknown | 可生成限制明确的普通/AI 报告，零 AI 输入 NOT_REQUIRED |
| V07 | 全部未运行/仅失败，失败后旧有效结果 | 无合法产出拒绝；旧有效结果与最近失败分别返回 |
| V08 | 构建补齐、环境变化、profile变化、源码切换 | 实际依赖驱动失效，源码结果可提示增强，历史字节不变 |
| V09 | 高候选、多批、错误 ID、漏项、重试/重启/取消 | 逐项完整校验、无静默截断、无重复发布或自动排除 |
| V10 | 三种 AI 结论及人工继承/确认 | 默认分流正确，证据限制不变，待复核阻止提交 |
| V11 | 无前置构建直接 BOTH；仅一侧成功；旧测试成功 | BOTH 可启动，测试仅满足真实当前双端验证才可启动 |
| V12 | overview/readiness/latest/创建/worker/报告并发 | 同一事实同一解释，optional 不阻塞，迟到不覆盖 |
| V13 | A/B/admin、伪造 plan/artifact、路径与符号链接 | 项目权限、个人模型归属、源码及资源隔离 |
| V14 | 完整本地 Compose、worker 恢复及下载 | 全部默认服务一次启动，任务与固定历史可恢复 |
| V15 | KB 多语言和专项语言边界 | 默认 c,cpp；混合及纯非C工程 KB 可运行，专项如实表示不支持范围 |
| V16 | 语言顺序/别名/范围/架构与增强身份 | 等价参数摘要一致，真实参数变化隔离；x86制品不得用于arm64，未知架构无增强 |
| V17 | 进程内存超限、并发预算与 worker 丢失 | 诊断保留、失败半截不发布、无盲目放宽预算，租约恢复且旧worker不能发布 |
| V18 | 失败部署重试、错误关联与并发 | 正确身份复用；错项目/源码/环境/版本在副作用前拒绝；双端不串用 |
| V19 | JaCoCo 正常采集及缺失/空/旧/跳过反例 | 当前exec/XML/HTML齐全、模块清单完整、90%门槛与负向门禁均验证 |

### 9. PR #22 的多语言与输入身份

KB HTTP 请求继续使用 language 逗号分隔字符串（c,cpp,rust,go,java,python,asm 的非空子集），前端已有数组转换不在本轮改动。默认 c,cpp/x86；空白、大小写、重复及顺序规范化后按固定语言顺序计算摘要，未知语言拒绝。专项仍仅使用当前工具支持的 C/C++ 输入；未选择、范围排除、工具不支持和解析失败分别计数，不将非 C 文件统一写为已分析或零问题。纯非 C 工程允许 KB；专项若不能产生合法分析产出则返回不适用事实，不制造成功空结果。

计划和报告来源快照固定 requested/effective language、sourceArch、targetArch、scanScope、excludePaths、规则/profile及规范化策略版本，恢复不重新读进程默认配置。保留 PR #22 既有源架构别名与未知值回退 x86 的行为，但保存回退假设；未知值回退不能证明真实架构。未提供架构的既有默认与显式未知值要可区分。不顺带新增前端协议或扩大专项语言范围。

真实编译/BC 增强还须证明 producer/target/ABI、源架构、TU语言与本次分析适配，架构未知或不匹配则 SOURCE_DISCOVERY 并记录原因；同项目同源码和 SOURCE_ONLY 标签不够。arm64 源码请求不得消费 x86 制品；本轮不扩展依赖分析支持的新架构，也不重命名 x86-build。混合工程仅增强能匹配的语言/TU，其他语言继续工具原生源码扫描并分项记录覆盖。

等价顺序或别名不能制造不同输入摘要；真实语言、架构、范围变化不得作为同一分析计划恢复或回退旧结果。只读预览不同选项不修改项目当前结果。新选项运行失败时，旧结果可保留在自身适用范围，但不得冒充新选项结果；新的合法结果发布后按现有来源选择规则重算报告 Context。人工继承同时校验影响该条目的架构、规则及关键证据，不能仅凭位置相同继承。

### 10. 内存预算、结构化失败和恢复

复用 KB_SCANNER_MEMORY_LIMIT_MIB 与 worker 既有容器限额，不重复实现工具 RLIMIT。记录本次实际预算和并发策略；默认2048MiB、0显式不设子进程上限的语义保留，0不解除容器边界。未来配置变更不得无审计改写已执行尝试，预算导致重试必须记录新尝试实际值，不自动增大或关闭限额。

非零退出仍不能导入 findings，但可在受控路径、输出字节上限及脱敏校验后提取失败诊断，保存稳定 reasonCode、toolDiagnosticCode、exitCode、phase、配置预算及双语说明；pipeline.memory-budget-exceeded 映射内存预算失败。输出缺失/截断则只保存可证实的失败事实，不从137/SIGKILL独断为OOM。FATAL/崩溃/超时输出不能作为合法PARTIAL；其他来源或旧的仍有效结果按既有规则可用，失败尝试独立可见。

进程地址空间限制不等于容器总内存隔离。实施先盘点既有worker并发、JVM和子进程预算，复用限流配置，在独立Compose override中限制并发及预留内存，不修改根Compose。验证正常子进程限额触发时worker仍服务；另用本地故障注入验证worker硬丢失后租约过期、终态/受控重试、临时输出隔离及迟到提交拒绝，不保证任何OOM都不会终止worker。禁止无界重试和自动加预算，恢复沿用输入身份和既有重试上限。

### 11. 构建内部署重试的身份边界

承接已合并 findByTaskId/失败部署复用；本次补所有复用分支的身份校验，尤其新增按任务分支。在 markRunning、建工具记录、打包、SSH前校验 task/project/sourceRevision/environment/environmentConfigVersion 及服务端受控remotePath和显式deploymentId一致性。按任务恢复要求task相同；跨任务复用成功部署不要求原task相同，但必须项目/源码/环境/版本完全一致。不得把request的新源码部署到旧run的目录。

相同任务失败重试复用同一行和路径；已有成功部署直接复用，双端各任务仅使用自己的环境。错误关联稳定失败且不插入新行规避约束，不修改被误引用记录；重复投递用租约/事务/唯一约束确保单次有效执行。force保持独立既有行为，不顺带调整其协议或放松校验；相关回归使用mock或本地隔离执行目标，绝不连接真实SSH。

### 12. JaCoCo 采集与报告存在性门禁

PR #22 在 Surefire 设置固定 argLine=-Xmx512m，而现有 JaCoCo prepare-agent 默认写 argLine；这存在覆盖代理参数的静态配置风险，尚未以本轮Docker运行证实。实施在保留堆限制下组合代理参数（例如默认属性初始化并使用晚绑定 @{argLine}，或专用JaCoCo属性），不得用降低覆盖率或关闭fork规避。

先做当前源码的Docker小范围采集验证，再进行全量门禁。每次独立清理输出，检查测试数大于零、当前轮jacoco.exec非空、XML可解析且行分母大于零、HTML存在，并核对全部自有类/包清单（含未加载源码）与完整受影响模块。报告必须来自本轮镜像、源码和执行，不能仅用时间戳或Maven exit=0证明。缺失数据、空XML、旧报告、跳过测试、遗漏模块均须明确失败，再检查整体及各模块LINE/BRANCH至少90%。确无分支的模块注明分母为零，不伪报100%。Python JSON门禁保持同等报告存在性与完整源码范围要求。

负向验证使用独立导出报告副本和隔离容器，分别移除exec/XML、置空数据、替换旧来源、跳过测试并断言门禁非零退出，不污染正式验收产物。该门禁仅落backend测试配置/脚本，不新增CI基础设施。

### 13. 与已合并多语言 change 的规范边界

| 内容 | PR #22 基线负责 | 本 change 增量负责 |
|---|---|---|
| 多语言/源架构 | 请求透传、默认值、别名、CLI参数 | 按分析器范围、固定身份、可信增强匹配、报告覆盖 |
| 内存预算 | 属性、子进程环境、工具限额及现有Compose限额 | 结构化失败、并发预算验收、租约恢复、报告不得误收 |
| 部署重试 | 同task失败记录复用 | 完整身份/路径/租约校验与直接BOTH回归 |
| 测试内存 | Surefire堆限制 | 保留JaCoCo代理、数据/报告存在性及90%门禁 |

`2026-11-18-multilang-scan-request-and-memory-budget` 文档仍在活动目录且无任务清单；代码已合并不代表已归档，更不代表本次验收完成。本 change 不编辑/同步/归档该目录，不复制其同名 ADDED Requirements；使用独立增量 requirement 名称，使原change先同步或后同步均不覆盖本次增量。其默认透传行为继续保留，新增输入安全约束在本次明示。未来sync前检查主规范实际状态、同名冲突和交叉场景，不把旧change的87项测试记录当本次验证。规划阶段仅写本目录；实施阶段后端源码范围按本设计授权，不修改前端、工具或根配置。

## Risks / Trade-offs

- [默认解析上下文偏离真实工程] → 明确 DEFAULT_ASSUMPTION、逐 TU 失败及候选证据限制；保留当前工具语义，不把未知升级为正式风险。
- [大量候选扩大 AI 成本] → 分批、固定分母、预算与进度；失败可恢复，不通过删条目“优化”。
- [历史成功与最近失败混淆] → currentResult/latestExecution 分开，跨接口统一 resolver 并覆盖失败重跑回归。
- [工具协议限制] → 首先在本地 Docker 用现有锁定工具验证无数据库路径；需要工具实现变动时明确报告，未经授权不扩大根。
- [范围较广导致回归] → 分组单元测试、真实 CLI 边界及完整 Compose；全部后端自有执行代码纳入整体和完整受影响包/脚本的 90% 行/分支门禁，不用本次变更行覆盖率代替。

## Migration Plan

1. 新增 schema/planVersion，旧记录不回填推测的源码模式或缺口。能证实旧构建引用的记录按原上下文读取；不能证明新计划完整性的历史记录只读，不作新当前输入。
2. 新旧协议不同的排队任务按版本受控拒绝并提示重新发起，不从 latest 补绑定；不修改已归档报告、人工审计或确认。无需要求用户清空已有实例，本轮只在独立本地数据资源验证迁移。
3. 本地 Docker 验证空库和有历史记录的升级、可空引用约束、jOOQ、并发发布、单测/覆盖率及 V01–V19；根目录构建全部默认 Compose 服务，一次 up -d --wait 启动并核验，清理仅本轮资源。
4. 不部署远程。若未来回退，旧应用不认识新无构建任务，不承诺直接降级；须停止相关消费者并使用部署前同一时点备份恢复 schema/任务/制品，具体上线另行安排。

## Open Questions

没有待用户确认的产品决策。实施前置验证需证明现有固定 CLI 能消费无构建数据库的源码计划；此项是验收任务而非已验证结论。工具接口若不满足，先输出具体限制及所需独立工具变更范围，不能越界实现或将该任务标完成。

## 实施补充：执行恢复与覆盖清单

worker 的 Rabbit listener 不自动启动。首次心跳成功注册节点后才启动消费，避免重启时排队任务早于节点注册被反复拒领。失联沿用既有 WORKER_LOST 终态和租约拒绝旧提交规则；手动重新生成创建新任务，不宣称失联任务会无限自动恢复。

可信编译数据库增强同样生成固定源码文件清单，明确未进入编译数据库的已知源码及未归档生成源码。coverage 保留真实 TU 完成/失败/未知统计，未提供的值不补零；工具完成状态与构建证据完整性分开。部分 BC 中失败记录只能用匹配可信编译上下文和固定源码重新计算源哈希进行源码辅助，已产出 BC 仍严格校验原始源哈希、编译数据库、生产者、目标和 ABI。
