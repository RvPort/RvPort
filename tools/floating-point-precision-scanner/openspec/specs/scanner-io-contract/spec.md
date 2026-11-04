# scanner-io-contract Specification

## Purpose

定义浮点精度扫描器的版本化请求、双语结果、稳定 finding、状态退出码和确定性原子输出契约，确保独立 CLI、RvPort 集成层和后续报告消费者能够一致解释扫描结果、失败状态与兼容边界。

## Requirements

### Requirement: 聚合 Finding 必须保留可用源码位置
生成器 SHALL 从直接证据中保留预算内的全部去重位置，优先选择可用主位置，再输出其余关联 occurrences。schema v2 的对外位置仍为点位置；不能伪造表达式结束范围。超过位置预算 SHALL 在 conclusion.semantic 中标记 locationBudgetExceeded 和 totalLocations，并将该结论标为 partial。

#### Scenario: 聚合包含空位置和多处表达式
- **WHEN** 同类结果同时包含空位置、重复位置和多个不同位置
- **THEN** 主位置 SHALL 为可用位置，关联位置 SHALL 去重且不重复主位置，不漏掉预算内其他位置

### Requirement: 版本化扫描请求
扫描器 SHALL 接受版本化 JSON 请求，必填字段为 `schema_version`、`request_id`、`project_root`、`source_profile`、`target_profile` 和源码输入选择器；源码输入选择器 SHALL 指向编译数据库或显式翻译单元列表。请求 MAY 增加 `bitcode_artifact_set`，其中包含显式 artifact root、manifest 相对路径、预期 manifest SHA-256、required profiles 和 source-mapping 要求。schema 1.3 请求 MAY 为 source/target 分别增加版本化 build context，并 MAY 增加带稳定 finding code、仓库相对路径 pattern、可选行范围和非空理由的 suppressions。请求 MAY 增加 `rules`，以稳定 code 列表选择已注册规则的全部或子集，并可使用显式空列表请求 facts-only；省略 `rules` 时 SHALL 使用该 schema/analysis version 锁定的默认规则集合。未知规则、重复冲突配置、不受支持的规则版本、无理由或越界 suppression、build context/profile 冲突、未知必填语义、不受支持的 schema 主版本、源码输入与 artifact-set 身份冲突 MUST 在分析前被拒绝。

#### Scenario: 有效 AST-only 表达式规则请求
- **WHEN** schema 1.3 请求字段、profiles 和源码路径均有效，未提供共享 BC且选择只依赖可用 AST/profile 证据的规则
- **THEN** 扫描器创建具有同一 `request_id` 的扫描，回显规范化规则、build-context 和 suppression 配置并按依赖生成 assessments

#### Scenario: 有效 AST 加共享 BC 请求
- **WHEN** 源码输入有效且 `bitcode_artifact_set` 的 manifest、摘要、required profiles 和源码身份一致
- **THEN** 扫描器规划 source facts、LLVM facts、evidence 和 enabled rule assessments 四类明确分母

#### Scenario: 显式 facts-only 请求
- **WHEN** 请求设置 `rules.enabled=[]`
- **THEN** 扫描器保留 AST-only 或 AST+IR facts 行为，并在规范化配置中明确未请求风险规则

#### Scenario: 旧请求保持旧默认集合
- **WHEN** 受支持的 schema 1.0-1.2 请求省略规则选择
- **THEN** 扫描器使用该版本锁定的四条核心规则，不静默启用 schema 1.3 新规则

#### Scenario: suppression 缺少理由
- **WHEN** 请求 suppression 使用未知 code、项目根外路径、全规则通配或空理由
- **THEN** 扫描器拒绝请求，返回 `invalid_request` 和可机器识别的 suppression 诊断

#### Scenario: 不支持的主版本或规则
- **WHEN** scan request、其引用 manifest/build context 的 schema 主版本或请求规则版本不受支持
- **THEN** 扫描器拒绝请求，返回 `invalid_request`，并输出可机器识别的版本或 `rules_not_available` 诊断

### Requirement: 稳定且双语的结果协议
扫描结果 SHALL 只发布 `schemaVersion: "2.0"` 的 JSON 文档，顶层 MUST 包含 `scan`、`project`、`summary`、`findings` 和 `diagnostics`，并 MAY 包含 `extensions`。公共字段 SHALL 使用 camelCase；`scan` MUST 公开稳定 scan ID、工具/分析版本、请求协议版本、分析范围和 `complete|partial|invalid_request|failed|internal_error` 执行状态；`project` MUST 公开可得的源码版本、文件/翻译单元覆盖状态和 reason codes；summary MUST 公开 assessment 的 planned/completed/matched/notMatched/unknown/skipped/failed 分母、finding/suppression/diagnostic 数量，以及 artifact、source facts、IR facts、evidence binding 和 rules 各层分母。facts-only 请求 MUST 明确规则未请求。所有用户可见的 finding 标题、说明、影响、修复建议和测试建议，以及需要动作的 suppression/diagnostic 信息，MUST 同时提供非空中文与英文字段。

扫描器 MUST NOT 生产旧 `kind/schema_version/analysis_version/request_id/status/metadata` 顶层形状，不得提供旧 serializer、双写、转换器或结果版本选择参数。所有受支持的 scan request 版本均 SHALL 产生同一种 v2 结果；invalid request、failed 和 internal error 也必须通过同一 v2 Schema。

#### Scenario: 完整十规则结果
- **WHEN** 所有计划 facts/evidence 和启用规则的必需 assessments 均完成，且不存在阻断判断的 unresolved unknown
- **THEN** `scan.status` 为 `complete`，各层分母与 findings/suppressions/diagnostics/unknown 计数一致，extensions 公开使用的 artifacts、profiles、build contexts 和规则版本摘要

#### Scenario: 部分规则证据不可用
- **WHEN** 至少一个启用规则完成 assessment，但另一个规则因必需类型、控制流、循环、IR、binding、profile 或 build-context 字段缺失而 unknown/failed
- **THEN** `scan.status` 为 `partial`，保留已完成 findings，并明确未评估 rule/scope，不能把空缺计为 notMatched 或 suppressed

#### Scenario: 无效请求也符合 v2
- **WHEN** 请求 JSON、Schema、规则、profile、build context 或 suppression 在分析前无效
- **THEN** 扫描器输出 `scan.status=invalid_request` 的最小 v2 文档、稳定 reason code 和双语 diagnostic，不输出旧形状或未校验的临时结构

#### Scenario: 双语字段完整
- **WHEN** 任意 finding、可审计 suppression 或需要用户动作的 diagnostic 被发布
- **THEN** 其用户可见内容同时包含非空中英文字段，且两种语言描述同一证据与结论

### Requirement: Finding 结构可定位且可去重
每条 finding SHALL 包含稳定 `id`、`fact`、`conclusion`、非空 `occurrences`、可解析的 `primaryOccurrenceId`、`evidenceChain` 和可选 namespaced `extensions`。`fact` MUST 表达稳定主事实、matched text、主位置和 typed provenance；`conclusion` MUST 表达 finding/rule code、rule version、category、severity、定性 confidence、evidence origin、requiresHumanConfirmation、状态、固定 rationale、完整双语展示字段和结构化 validation plan；occurrences MUST 保存主位置及有界相关位置、configuration、状态、reason codes 和 provenance。validation plan MUST 表达适用 input classes、absolute/relative/ULP/classification/errno/exceptions error metrics、`standard|profile|project-policy|user-provided|unknown` threshold source 及平台/选项/舍入/顺序/repeat strategy；扫描器 MUST NOT 为 unknown 或业务阈值臆造数值。

finding ID MUST 在 suppression 前由规则 code/version、规范化 scope/位置、排序后的语义 evidence refs、evidence origin 和适用 profile/build-context fingerprints 稳定生成，且不得依赖扫描顺序、双语展示文本、suppression 理由、绝对工作区路径或 artifact materialization 根。跨 TU 配置风险 SHALL 依据规则定义的 semantic aggregation key 合并，并以 occurrences 和受影响数量保存全部已确认范围。

#### Scenario: 相同输入重复扫描
- **WHEN** 相同源码、编译上下文、profiles/build contexts、facts/evidence 与规则版本被重复扫描
- **THEN** assessments、finding candidates、finding/occurrence/evidence-chain IDs 和规范化排序保持一致，不受并发调度或 suppression 是否展示该候选影响

#### Scenario: 启发式 finding
- **WHEN** 规则只能根据比较、归约或输入域线索推断风险
- **THEN** conclusion 包含 `evidenceOrigin=heuristic`、`confidence=medium|low`、固定 rationale、`requiresHumanConfirmation=true`，且 validation plan 的业务阈值来源为 `project-policy`、`user-provided` 或 `unknown` 而非扫描器生成值

#### Scenario: 特殊值验证计划
- **WHEN** finding 涉及 libm、fenv 或 NaN/Inf 语义
- **THEN** validation plan 包含适用特殊值/边界 input classes、classification/errno/exceptions metrics 和明确的平台、rounding 或重复策略

#### Scenario: 同一配置跨多个翻译单元
- **WHEN** 多个翻译单元具有相同的有效危险语义和相同规则 aggregation key
- **THEN** 结果发布一个主 finding，使用 occurrences 和受影响数量保存全部已确认范围，避免按原始命令文本重复报告

#### Scenario: 语义证据变化
- **WHEN** profile/build-context fingerprint、rule major version、effective semantics 或关联 IR evidence 发生变化
- **THEN** assessment/finding identity 或 semantic fingerprint 相应变化，不与旧结论错误去重

### Requirement: 明确状态与退出码映射
CLI SHALL 使用固定退出码：`0` 表示 `scan.status=complete`，`2` 表示 `scan.status=invalid_request`，`3` 表示无可用结果的 `scan.status=failed`，`4` 表示 `scan.status=partial`，`5` 表示 `scan.status=internal_error`。发现任意数量的浮点风险本身 MUST NOT 改变退出码；CI 是否因风险失败由独立策略层决定。`project.status`、assessment status 和 evidence-chain status 只表达覆盖完整性，不得覆盖 scan 执行状态。

#### Scenario: 扫描发现高风险项
- **WHEN** 扫描完整结束且存在 `critical` 或 `high` finding
- **THEN** `scan.status` 仍为 `complete` 且退出码为 `0`，风险数量通过 summary 和 findings 表达

#### Scenario: 部分扫描
- **WHEN** 至少一个翻译单元或必需 assessment 完成，且至少一个计划翻译单元或启用规则的必需 assessment 未完成
- **THEN** `scan.status` 为 `partial`、退出码为 `4`，并公开 planned/completed/failed/unknown 分母和 evidence-chain breaks

### Requirement: 输出应确定、原子且可审计
扫描器 SHALL 规范化路径分隔符、按稳定键排序集合、避免时间戳参与语义摘要，并通过临时文件加原子替换发布文件结果。artifact-set 本地根、对象存储位置和临时 materialization 路径 MUST NOT 参与事实/evidence 身份或默认输出；默认输出 MUST NOT 暴露环境变量值、凭据或项目根/artifact root 外的绝对路径。结果 SHALL 通过稳定 artifact/bundle digest 引用共享输入，而不是引用可变文件路径。

#### Scenario: 写出过程被中断
- **WHEN** 进程在结果序列化或文件替换前异常终止
- **THEN** 既有有效结果不被截断，新结果不会以完整文件名呈现为可用产物

#### Scenario: 相同 artifact-set 位于不同根目录
- **WHEN** 相同 manifest 和 BC 字节从不同 artifact root materialize 后扫描
- **THEN** 语义结果、facts/evidence ID、摘要和排序相同，默认结果不包含两个绝对根路径

### Requirement: Suppression 必须有理由且可审计
扫描器 SHALL 在 finding candidate 和稳定 finding ID 生成后应用 request 或源码 suppression。每条有效 suppression MUST 在 `org.rvport.floating-point-scanner.suppressions` 扩展中精确记录 rule code、规范化路径/范围、双语理由、来源、candidate finding ID 和匹配结果；被抑制候选不得出现在 `findings`，但 MUST 出现在有界 suppression audit records 并计入 `summary.suppressed`。无理由、未知 code、范围歧义、越界路径或全规则通配 MUST NOT 隐藏结果。

#### Scenario: 请求抑制匹配单个 finding
- **WHEN** 有效 request suppression 的 code、路径和行范围唯一匹配 finding candidate
- **THEN** candidate ID 保持稳定、finding 不发布、suppression audit record 和 suppressed 计数增加

#### Scenario: 带理由的源码抑制
- **WHEN** 严格格式的 `fp-precision-scanner: ignore <code> -- <reason>` 注释可唯一绑定相邻声明或表达式
- **THEN** 只抑制该范围对应 code，并记录 spelling/expansion provenance 和双语理由

#### Scenario: 源码抑制范围歧义
- **WHEN** 宏、模板或同位置多个表达式导致注释无法唯一绑定
- **THEN** suppression 不生效，输出双语 diagnostic 且候选 finding 仍发布

### Requirement: Schema 1.4 必须显式表达策略日期和缓存配置
scan request schema 1.4 SHALL 增加可选 ISO 8601 完整日期 `policy_as_of`、版本化 cache 配置以及新增结果/逻辑预算。cache 配置 MUST 包含 `off|read-only|write-only|read-write` 模式和经过允许根校验的 cache root。旧 schema 1.0–1.3 MUST 保持原解析、默认规则和无过期 suppression 行为；未知字段或不支持版本继续在分析前拒绝。

#### Scenario: 旧请求不读取当前日期
- **WHEN** schema 1.3 请求包含 suppression 且没有 policy 日期
- **THEN** suppression 按旧协议处理，系统当前日期不影响结果

#### Scenario: 有效 schema 1.4 缓存请求
- **WHEN** cache mode、root、预算和 policy 日期均合法
- **THEN** 规范化请求固定全部语义配置且不在结果中暴露绝对 cache root

### Requirement: Suppression 过期必须由显式 policy 日期决定
schema 1.4 suppression MAY 包含 `expires_on`。包含 `expires_on` 时请求 MUST 提供 `policy_as_of`；当 `policy_as_of` 晚于 `expires_on` 时 suppression MUST 过期、candidate finding 保持可见，并产生包含中英文理由的审计记录和诊断；日期相等时 suppression 仍有效。核心扫描器 MUST NOT 读取系统当前时间判断有效期。

#### Scenario: Suppression 已过期
- **WHEN** `policy_as_of=2026-10-01` 且匹配 suppression 的 `expires_on=2026-09-30`
- **THEN** finding 保持可见，suppression audit 标记 expired 且 suppressed 计数不增加

#### Scenario: 缺少 policy 日期
- **WHEN** schema 1.4 suppression 提供 expires_on 但请求未提供 policy_as_of
- **THEN** 请求在分析前以 invalid_request 和稳定诊断拒绝

### Requirement: 结果必须公开稳定预算、缓存和 suppression 审计
结果 v2 SHALL 在 `org.rvport.floating-point-scanner.runtime` 扩展中公开预算 limits、稳定逻辑用量、frontiers、cache schema/version 与 hit/miss/corrupt/write 计数，并在 suppressions 扩展中公开 applied、expired、unmatched 或 budgetUnrecorded 状态及双语理由。实际 wall/CPU/RSS telemetry、绝对 cache root 和系统时间 MUST NOT 进入 scan result semantic digest。命名空间扩展键 MUST 匹配 `org.rvport.floating-point-scanner.*`，其值 MUST 通过已注册 Schema 校验；不得使用未命名的任意对象绕过公共协议。

#### Scenario: 热缓存命中
- **WHEN** source facts、IR facts 或 evidence 从有效缓存读取
- **THEN** runtime 扩展增加对应 hit 计数，但 findings、assessments、evidence chains 和 semantic digest 与冷缓存运行相同

#### Scenario: 非法扩展命名空间
- **WHEN** 结果包含未注册、非浮点扫描器命名空间或不符合对应扩展 Schema 的字段
- **THEN** 结果校验失败且不得作为有效产物原子发布

### Requirement: Evidence chain 引用必须完整闭合
每条 finding 的 evidence chain SHALL 包含稳定 `rootStepId`、非空 `primaryPath`、steps、edges、breaks、impactStepIds 和预算。typed reference kinds MUST 使用浮点专属词汇并至少覆盖 source fact、IR fact、AST↔IR binding、profile、build context、compile option 和 assessment。所有 primary occurrence、step、edge、path、impact、diagnostic finding 和 extension references MUST 在文档内唯一解析，或显式引用具有 producer、schema 和 digest 的外部审计对象。complete chain MUST 不含 breaks；partial/ambiguous/unknown chain MUST 包含稳定 reason codes 和至少一个 break。扫描器不得借用 KB Scanner 的 knowledge、fact、reason 或 step 枚举表达浮点语义。

#### Scenario: 完整 AST 到 IR 证据链
- **WHEN** finding 同时具有 canonical AST fact、LLVM IR fact、可靠 binding、profiles 和 matched assessment
- **THEN** primary path 按稳定顺序连接 source fact、binding、IR observation、profile/build context、assessment 和 conclusion，所有 edge 端点及 provenance references 均可解析

#### Scenario: IR 证据缺失
- **WHEN** 规则需要 IR 证据但对应 artifact、fact 或 binding 不可用
- **THEN** evidence chain 为 partial 或 unknown，包含明确 break/frontier，且不得生成声称 IR 支持完整的 deterministic path

#### Scenario: 悬空引用
- **WHEN** primaryOccurrenceId、edge 端点、primaryPath、impactStepId 或 diagnostic finding ID 无法解析
- **THEN** v2 contract 校验失败且结果不得发布

### Requirement: 证据步骤必须包含可解释的双语文案
每个 evidenceChain.steps 条目 SHALL 输出非空 `title/titleEn/reason/reasonEn`，标题最多 512 字符、说明最多 4096 字符。说明 MUST 来自该步骤直接引用的实际源码事实、IR 事实、绑定、profile、构建上下文、编译选项或规则评估，不得仅根据类型假造结论。结论步骤 SHALL 复用对应 finding 的双语结论。文案 MUST 保留安全相对路径，不泄露绝对路径或凭据。新增文案 MUST NOT 改变 finding、step、引用或图边身份，也不得改变规则风险等级。

#### Scenario: AST 归约但无 IR 重排证明
- **WHEN** 规则因源码归约产生 finding，reasonCode 为 order_sensitive_reduction
- **THEN** 步骤展示实际函数、运算和相对位置，并说明可能受累加顺序影响，不声称已有 IR 重排证明

#### Scenario: 证据缺失或含歧义
- **WHEN** 步骤引用无法解析为实际事实，或源码事实声明语义歧义/未知影响
- **THEN** 输出明确的双语限制文案、unknown 步骤状态和稳定原因码，链级状态为 partial 并包含 break

#### Scenario: 历史结果与新结果
- **WHEN** 发布带该扩展的工具版本
- **THEN** 保持外层 schemaVersion=2.0，随工具 0.5.1 发布新 Schema；历史已存结果不回填，需重扫获得描述；生成时不得命中旧最终结果缓存

### Requirement: 浮点专属扩展必须保留审计分母
结果 v2 SHALL 使用 `org.rvport.floating-point-scanner.analysis`、`profiles`、`artifacts`、`suppressions` 和 `runtime` 等受约束扩展保存不属于公共骨架的完整审计语义。analysis 扩展 MUST 保存全部 assessments、规则 registry digest、启用规则/版本/依赖、analysis scope 和各层摘要，使没有 finding 的 notMatched、unknown、skipped 和 failed assessments 仍可审计。profiles/artifacts 扩展 MUST 保存 source/target fingerprints、可选 build-context fingerprints、共享 BC 身份、bundle digests、IR capabilities 和 source mapping 状态。完整 raw facts/evidence MAY 通过 producer、schema 和 digest 引用独立 bundle；不得以无界、无 Schema 的旧顶层数组重新引入。

#### Scenario: 规则未产生 finding
- **WHEN** assessment 为 notMatched、unknown、skipped 或 failed
- **THEN** assessment 仍存在于 analysis 扩展并计入 summary，unknown/failed reason 和 evidence frontier 可审计，不因 findings 为空而丢失

#### Scenario: Facts-only 扫描
- **WHEN** 请求显式不启用风险规则
- **THEN** findings 为空、analysis scope 明确为 factsOnly、assessment 分母为零，并通过 artifact/bundle references 公开已完成 facts/evidence，而不是描述为未发现风险

### Requirement: Validation result 必须使用独立协议
质量与性能验证 SHALL 输出独立 `FloatingPointScannerValidationResult@1.0`，不得将 TP/FP/FN、reviewer、benchmark telemetry 或宿主资源采样混入生产 scan result。validation result MUST 通过独立 schema 校验并引用被验证 scan result、corpus 和 oracle digest。

#### Scenario: Validation 报告序列化
- **WHEN** corpus 验证和 benchmark 完成
- **THEN** 独立报告包含质量和性能分母，生产 scan result schema 与语义摘要保持不变

### Requirement: 独立归约结果必须分别投影到 v2 Finding
每个 matched 归约候选 SHALL 输出独立 v2 Finding，并具有自己的 assessment、稳定 ID、fact、conclusion、occurrences、primaryOccurrenceId 和 evidenceChain。位置、来源与引用 SHALL 限于本候选及必要共享配置，主位置 SHALL 属于自身位置集合；不同候选 MUST NOT 仅因 finding code 或 TU 相同再次聚合。普通独立归约 Finding 的 `affectedCount` SHALL 为 1，不按证据条数膨胀。summary 和引用完整性 SHALL 反映拆分后的真实数量。

#### Scenario: 两个归约命中输出
- **WHEN** 同一 TU 有两个独立归约候选被判为 matched
- **THEN** v2 findings 包含两个不同 ID，各自关联自己的 assessment、主位置和证据链，计数为两个 Finding，所有引用可解析

#### Scenario: 多证据位置仍属于一条风险
- **WHEN** 一个归约候选包含多个可信来源位置和证据
- **THEN** 输出一个 Finding，保留本候选去重后的有界 occurrences 和必要证据，`affectedCount` 为 1

#### Scenario: 其他配置聚合保持不变
- **WHEN** 既有配置级规则按显式 semantic aggregation key 聚合同一危险配置
- **THEN** 仍按该规则的现有契约聚合，不受归约候选拆分影响

### Requirement: 归约候选的抑制与历史结果必须独立处理
扫描器 SHALL 在生成稳定候选身份后逐候选应用合法 suppression 并记录审计。仅匹配一个候选范围的抑制 MUST NOT 隐藏另一个候选；显式匹配多个候选的范围 SHALL 按现有匹配规则逐候选计数。公开结果 SHALL 保持 schema v2 与中英文字段完整，不要求前端、后端接口或历史数据迁移。

#### Scenario: 单点抑制
- **WHEN** 两个循环命中相同规则，但合法 suppression 的范围仅匹配其中一个更新
- **THEN** 只抑制该候选，另一个 Finding 保留；抑制审计指向被抑制候选，summary 与剩余结果一致

#### Scenario: 版本升级与历史记录
- **WHEN** 使用归约规则 1.1 对同一项目重新扫描
- **THEN** 新结果按独立候选生成且明确规则版本，旧扫描记录保持原样；新旧 ID 和数量可以不同，公共结果仍符合 schema v2

#### Scenario: 展示与诊断双语
- **WHEN** 发布候选相关的结论、说明、建议、诊断或 suppression 审计信息
- **THEN** 需要用户理解的内容具有语义一致的非空中文和英文字段，不将内部候选 ID 作为解释文本

### Requirement: 全规则独立问题必须分别投影为 Finding
每个 matched 独立候选 SHALL 输出一个有自身稳定 ID、assessment、直接事实、conclusion、主位置及闭合 evidenceChain 的 v2 Finding。普通候选的 affectedCount SHALL 为 1；多个证据不得重复计数。不同独立问题 MUST NOT 在输出、存储消费契约或排序阶段再次按规则、文件、TU 或位置合并。同一问题多证据位置可以保留，但每个 occurrence MUST 具有可证明的归属，辅助依赖不能自动变为额外命中。

#### Scenario: API 跨函数结果
- **WHEN** 同一文件两个函数中的独立 API 调用均命中
- **THEN** 返回两条 Finding，各自的主位置、occurrences、证据链和 assessment 仅对应自己的问题，前端按两个 ID 展示即可

#### Scenario: 一问题多证据与位置排序
- **WHEN** 同一语义问题存在多个可信直接证据位置
- **THEN** 仅发布一条结果并保留必要位置，主位置优先，其余按路径、行、列及稳定 tie-breaker 排序，不按随机 hash 跳跃排列

#### Scenario: 历史结果和双语兼容
- **WHEN** 新规则版本生成结果且历史多位置记录仍存在
- **THEN** 新旧结果均遵循 schema v2，历史记录不回填，新增用户可见解释保持中英文字段完整

### Requirement: 配置聚合与抑制必须保全独立成员审计
合法配置聚合 SHALL 保存所有成员身份、assessment refs、必要证据、位置及真实成员数量，MUST NOT 仅保留首个成员依据。稳定候选身份 SHALL 先于 suppression 生成；抑制 SHALL 对独立成员应用，再对可见且语义等价的成员形成最终聚合。公开 summary、诊断、suppression records 和 evidence refs MUST 一致且可解析，不能隐藏无关成员或把聚合 ID 当作所有候选的身份。

#### Scenario: 单点抑制不隐藏其他问题
- **WHEN** 合法 suppression 只匹配一个普通候选或配置影响成员
- **THEN** 只隐藏匹配成员，其他候选仍可见；最终聚合重新计算可见成员证据、位置和数量，抑制审计保留原独立候选身份

#### Scenario: 非主成员范围抑制
- **WHEN** suppression 匹配聚合前的非主成员位置
- **THEN** 仍按该成员生效，不因最终主位置不同误判 unmatched

#### Scenario: 多成员证据闭合
- **WHEN** 多个合法配置成员合成一条 Finding
- **THEN** 每个成员的必要证据与 assessment 均可审计，去重后的图引用闭合，不使用第一成员证据代替其他成员
