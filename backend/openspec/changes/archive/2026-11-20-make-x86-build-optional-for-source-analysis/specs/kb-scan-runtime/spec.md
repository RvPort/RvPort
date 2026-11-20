## MODIFIED Requirements

### Requirement: KB 扫描必须自动复用构建上下文 compile database
KB 扫描运行时 SHALL 优先使用固定且可信的编译上下文；无构建或编译数据库时 SHALL 直接进行源码扫描，MUST NOT 在执行时重新选择其他构建。

#### Scenario: 自动使用绑定的成功构建上下文
- **WHEN** worker 执行具有完整上下文且前置校验通过的 `KB_SCAN` 任务
- **AND** 绑定构建的 `build_facts.compileDatabase.available=true`
- **THEN** worker SHALL 从对象存储下载对应 compile database 到受控临时文件
- **AND** worker SHALL 调用 KB Scanner 时传入 `--compile-commands <temp-file>`
- **AND** `kb_scan_runs.summary.compileCommands.mode` SHALL 为 `USED`
- **AND** summary SHALL 记录绑定 build run ID、stored artifact ID、sha256 和 command count

#### Scenario: 成功构建没有编译数据库时降级扫描
- **WHEN** worker 已确认固定源码和必需工具可用
- **AND** 不存在可用的绑定构建或 compile database
- **THEN** worker SHALL 不传 `--compile-commands` 并继续源码上下文扫描
- **AND** `kb_scan_runs.summary.compileCommands.mode` SHALL 为 `NOT_AVAILABLE`
- **AND** summary SHALL 保存可空绑定构建 ID、实际 inputOrigin 与安全原因码

#### Scenario: 没有成功构建允许源码扫描
- **WHEN** 未构建或构建未成功，但固定源码和工具可用
- **THEN** SHALL 使用 SOURCE_DISCOVERY 计划扫描，不要求远程环境、不创建假构建，不把缺失编译上下文称已验证。

#### Scenario: artifact 下载失败时降级扫描
- **WHEN** 成功绑定构建的 compile database artifact 缺失、下载失败、所有权不符或内容不可用
- **THEN** worker SHALL 记录 warning 并继续现有路径脱敏和所有权保护
- **AND** worker SHALL 不传 `--compile-commands`，按源码上下文扫描继续执行
- **AND** `kb_scan_runs.summary.compileCommands.mode` SHALL 为 `FALLBACK`
- **AND** summary SHALL 保存可空绑定构建 ID、实际 inputOrigin 与安全原因码
- **AND** worker MUST NOT 改用其他构建的 artifact

#### Scenario: 源码版本隔离
- **WHEN** 项目上传或同步新源码并产生新的 `source_revision_id`
- **THEN** KB 扫描 MUST NOT 复用其他源码版本的 compile database
- **AND** 后端 SHALL 验证绑定构建与本次 scan run 的 `source_revision_id` 一致

#### Scenario: 扫描输出继续脱敏
- **WHEN** KB Scanner 使用 compile database 产生 findings、diagnostics、stdout、stderr 或 artifacts
- **THEN** 后端 SHALL 继续执行现有路径脱敏和输出安全处理
- **AND** API、SSE 和 artifact summary MUST NOT 暴露 compile database 临时路径、远端路径或对象存储 key

### Requirement: 知识库扫描 readiness 必须校验 active source 和 x86 构建
项目级 readiness 在 `stageId=kb` 时 SHALL 返回源码及必需工具条件和可选 x86 增强事实，并 SHALL 与创建接口和结果选择使用同一输入计划规则。

#### Scenario: active source 已存在
- **WHEN** 项目存在 active source revision
- **THEN** `ACTIVE_SOURCE` prerequisite SHALL 返回 `SATISFIED`
- **AND** `referenceId` SHALL 指向 active source revision

#### Scenario: 当前上下文 x86 构建成功
- **WHEN** 当前源码、源环境和运行环境版本下按活动优先、否则最近创建规则选中的 `SOURCE_ONLY` 构建为成功
- **THEN** `X86_BUILD` prerequisite SHALL 返回 `SATISFIED`
- **AND** `referenceId` SHALL 指向该构建

#### Scenario: 当前上下文 x86 构建尚未成功
- **WHEN** 当前上下文没有 x86 source-only 构建，或选中的构建处于排队、运行、失败、取消或超时状态
- **THEN** `X86_BUILD` prerequisite SHALL 返回对应的 `MISSING`、`RUNNING` 或 `FAILED`
- **AND** 源码与工具满足时知识库分析 SHALL 被表示为可开始；X86_BUILD SHALL 标记 required=false
- **AND** 后端 MUST NOT 借用其他版本或不合格构建增强源码分析；缺少增强不阻塞源码模式

#### Scenario: active source 缺失
- **WHEN** 项目不存在 active source revision
- **THEN** `ACTIVE_SOURCE` prerequisite SHALL 返回 `MISSING`
- **AND** `reasonCode` SHALL 为 `ACTIVE_SOURCE_MISSING`

### Requirement: KB 创建与 worker 必须固定并校验执行上下文
后端 SHALL 在创建事务内固定源码输入计划和可空构建关联；worker SHALL 核对权限、源码与工具，按记录执行并保存实际模式，不自动补绑。

#### Scenario: 创建时前置条件不满足
- **WHEN** 源码不可用、无权访问或必需工具不可用
- **THEN** SHALL 拒绝且无任务/消息副作用；没有构建、环境或 compile database 不是拒绝源码模式的理由。

#### Scenario: 显式历史源码执行
- **WHEN** 有权限用户明确选择本项目历史源码且必需输入可用
- **THEN** SHALL 按固定历史计划执行，不能占用其他 active 源码的当前状态；跨项目请求拒绝且不泄露资源细节。

#### Scenario: 创建事务内上下文变化
- **WHEN** 校验后实际使用的源码/profile/计划或增强输入改变
- **THEN** SHALL 以锁或版本重验拒绝不一致写入并整体回滚。

#### Scenario: 排队期间执行前置变化或旧任务缺失上下文
- **WHEN** 必需固定输入不可用或不能验证旧任务输入协议
- **THEN** SHALL 受控失败，不猜测历史输入；仅可选制品丢失可安全降级并保存 effectiveInputDigest，不影响源码事实。

#### Scenario: 排队期间仅切换 active source
- **WHEN** 固定历史源码仍可读且其他必需输入有效
- **THEN** SHALL 允许按原计划执行，结果仅为历史，不占用新源码当前状态。

#### Scenario: 执行期间上下文变化
- **WHEN** 源码、相关配置或可选构建发生变化
- **THEN** SHALL 保留固定输入和真实执行终态，按实际依赖判断适用性；未消费的环境/构建变化不能单独使源码模式失效。


### Requirement: 知识库扫描必须提供 latest
知识库扫描领域 SHALL 提供当前 Context 下的 latest 接口，使用完整执行上下文判断当前性，并分别表达执行终态与结果是否仍适用。

#### Scenario: 查询知识库扫描 latest
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/kb-scans/latest`
- **THEN** 后端 SHALL 返回统一 latest 外壳
- **AND** `context.sourceRevisionId` SHALL 为项目当前 active source revision
- **AND** `CURRENT` 记录 SHALL 匹配当前项目、active source 及实际消费的输入计划；SOURCE_DISCOVERY 不要求构建或远程环境，BUILD_ARTIFACTS 校验实际绑定上下文
- **AND** `execution` SHALL 指向该有效上下文内最新活动扫描任务，否则指向最近创建的终态扫描任务
- **AND** `result` SHALL 包含对应扫描摘要、finding 数量、artifact 摘要及安全执行上下文
- **AND** 相同创建时间 SHALL 使用 ID 作为稳定排序条件

#### Scenario: 知识库扫描 latest 识别旧源码
- **WHEN** 只有旧源码版本的知识库扫描记录存在
- **THEN** latest SHALL 返回最近创建的历史记录且 `freshness=STALE`
- **AND** 后端 MUST NOT 将该记录标记为 `CURRENT`

#### Scenario: 环境或构建变更使旧扫描失效
- **WHEN** 扫描实际依赖的源环境、运行环境版本或增强构建 ID 与当前适用上下文不同；SOURCE_DISCOVERY 未消费的字段除外
- **AND** 没有其他当前有效扫描记录
- **THEN** latest SHALL 返回历史记录并标记 `STALE`，提供环境变化、构建变化或构建未就绪的安全原因
- **AND** 后端 MUST NOT 修改历史执行的真实状态

#### Scenario: 缺少上下文的旧扫描
- **WHEN** 历史扫描缺少可验证的计划版本及实际依赖身份；合法 SOURCE_DISCOVERY 的 buildRunId=null 不属于此情况
- **THEN** 后端 SHALL 将其视为 `STALE`，原因包含 `KB_SCAN_CONTEXT_UNKNOWN`
- **AND** 后端 MUST NOT 仅根据源码相同、summary 中孤立的构建 ID 或时间接近猜测为当前有效

#### Scenario: 当前失败或活动扫描优先于旧成功
- **WHEN** 同一有效上下文存在活动扫描，或最新终态扫描为失败、取消、超时
- **THEN** latest SHALL 返回活动扫描优先、否则最近终态的记录及真实状态
- **AND** 后端 MUST NOT 用旧成功覆盖最新执行事实；独立 currentResult SHALL 可引用仍适用的旧成功并明确结果 ID/有效性，不混淆两者

#### Scenario: 无 active source 但存在历史
- **WHEN** 项目没有 active source 但存在扫描历史
- **THEN** latest SHALL 返回历史记录且 `freshness=STALE`

#### Scenario: 无扫描记录
- **WHEN** 项目不存在任何扫描记录
- **THEN** latest SHALL 返回 `EMPTY`，`execution` 和 `result` 为 null
- **AND** 后端 MUST NOT 以 404 表达无记录

### Requirement: KB finding 必须返回报告纳入状态
KB 列表和详情 SHALL 按报告固定来源引用返回真实 includedInLatestReport；人工处置和 AI 判断 SHALL 从具体报告读取，不恢复旧节点确认或动态 aiExtension。

#### Scenario: 已纳入当前有效报告
- **WHEN** 当前报告快照引用该扫描及 finding
- **THEN** SHALL 返回真实纳入状态，不表示最终清单已确认。

#### Scenario: 未纳入或历史报告
- **WHEN** 当前报告未引用该 finding 或仅历史报告引用
- **THEN** SHALL 返回准确当前纳入事实，历史关系可追溯，不返回旧 migrationInputRefId、migrationInputStatus、confirmedForNextReport。


## ADDED Requirements

### Requirement: KB 源码独立运行必须保留多语言范围与输入身份
后端 SHALL 承接已合并多语言字符串及sourceArch透传，默认c,cpp/x86不变，不以专项C/C++过滤器裁剪KB；固定语言、架构和范围供执行、latest、报告与审计使用。

#### Scenario: 混合工程无构建
- **WHEN** 请求language=c,rust,go,java,python,asm且无构建数据库
- **THEN** SHALL 按固定工具支持能力扫描所选语言，逐语言保留候选和覆盖；不能要求其他语言生成C/C++ TU或编译数据库。

#### Scenario: 纯非C工程
- **WHEN** 固定源码只有所选受支持的非C语言
- **THEN** KB SHALL 可执行并保留合法结果，专项只返回自身未支持范围或合法无法判定结果，不伪造完整零风险。

#### Scenario: 请求规范化与恢复
- **WHEN** KB请求为空缺省、已支持别名或合法多语言组合，任务随后恢复
- **THEN** SHALL 保留既有默认与别名语义，记录规范化策略及显式未知架构回退假设；恢复使用固定输入，不读取新的全局语言或架构配置替换原输入。

### Requirement: KB 资源失败必须可诊断且不得发布半截结果
后端 SHALL 复用既有内存限额，受控提取FATAL诊断并保存reasonCode、toolDiagnosticCode、exitCode、phase、实际预算与双语说明；MUST NOT 将失败进程残留输出作为成功或合法PARTIAL。

#### Scenario: 内存预算明确超限
- **WHEN** 子进程非零退出且有合法pipeline.memory-budget-exceeded诊断
- **THEN** SHALL 记录稳定内存预算失败原因并保持FAILED，不导入残留findings；有其他或旧的有效产出时可按其独立身份供报告使用。

#### Scenario: 无完整诊断
- **WHEN** 进程被杀、超时或输出截断且不能证明内存超限
- **THEN** SHALL 只保存已证实退出/超时/worker丢失事实，不从退出码137猜测OOM，不以伪造证据不足代替技术失败。

#### Scenario: 配置与并发预算
- **WHEN** 使用默认2048MiB、显式0或配置的扫描预算启动任务
- **THEN** SHALL 记录实际值并保留0仅关闭子进程限额的含义，结合worker现有并发、JVM与容器预算限流验证，不自动增大或关闭预算重试。

#### Scenario: worker丢失后的恢复
- **WHEN** 独立本地故障注入终止worker后租约过期
- **THEN** SHALL 按既有有界重试/终态策略恢复固定计划，隔离未发布输出并拒绝旧租约提交；正常子进程超限另验worker继续服务，不承诺所有容器OOM均可避免。

## REMOVED Requirements

### Requirement: KB findings 必须可加入迁移输入
**Reason**: 与已归档报告复核及本次源码独立输入流程冲突的退役要求。
**Migration**: 原始扫描保持不可变，AI 与人工处置统一由报告复核及确认清单承担，不恢复旧入口。

### Requirement: KB 扫描节点必须支持本节点确认进入报告
**Reason**: 与已归档报告复核及本次源码独立输入流程冲突的退役要求。
**Migration**: 原始扫描保持不可变，AI 与人工处置统一由报告复核及确认清单承担，不恢复旧入口。

### Requirement: KB finding 响应必须附带最新 AI 扩展建议
**Reason**: 与已归档报告复核及本次源码独立输入流程冲突的退役要求。
**Migration**: 原始扫描保持不可变，AI 与人工处置统一由报告复核及确认清单承担，不恢复旧入口。

### Requirement: KB Scanner 主输出不得被 AI 扩展修改
**Reason**: 与已归档报告复核及本次源码独立输入流程冲突的退役要求。
**Migration**: 原始扫描保持不可变，AI 与人工处置统一由报告复核及确认清单承担，不恢复旧入口。

### Requirement: KB 扫描报告生成可以引用 AI 扩展建议
**Reason**: 与已归档报告复核及本次源码独立输入流程冲突的退役要求。
**Migration**: 原始扫描保持不可变，AI 与人工处置统一由报告复核及确认清单承担，不恢复旧入口。
