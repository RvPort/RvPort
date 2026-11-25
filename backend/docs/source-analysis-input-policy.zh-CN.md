# 后端对接契约：源码分析的可选构建

本文记录本 change 的实施对接协议；最终验收状态以 tasks.md 和 verification.md 为准。路径来自当前 controller；只扩展后端，不修改前端。以下 JSON 是响应 data 内的关键字段片段，非完整外壳。所有历史记录保留原版本，新增展示说明提供中文与英文。

## 1. 既有接口与职责

| 既有接口 | 本次调整 |
|---|---|
| GET /api/projects/{projectId}/readiness?stageId=… | 明确硬前置与可选增强、计划输入摘要；stageId 沿用 kb、floating-point-analysis、memory-order-analysis、x86-build、build、test 等实际注册项 |
| GET /api/projects/{projectId}/overview | 节点 optional、canStart、analysisComplete 及依赖驱动推荐 |
| POST /api/projects/{projectId}/kb-scans | 无构建也可创建，服务端固定计划 |
| POST /api/projects/{projectId}/floating-point-analyses | 无编译数据库也可创建，服务端生成内部源码计划 |
| POST /api/projects/{projectId}/memory-order-analyses | 同上，开放 Scope，保留无法判定事实 |
| GET 上述各运行集合的 /latest | 保留 execution/result 与 freshness 外壳；新增 currentResult，区分最近执行和可消费结果 |
| 各分析列表、详情、findings、制品下载 | 返回真实模式、证据缺口及安全摘要，不暴露服务器路径 |
| POST /api/projects/{projectId}/builds | SOURCE_ONLY 可选；BOTH 不依赖前置成功，仅校验源码、所需环境、有效脚本 |
| POST /api/projects/{projectId}/reports/evaluation | 固定合法发布来源，支持专项 PARTIAL，保留现有幂等请求协议 |
| GET /api/projects/{projectId}/reports/evaluation/readiness、/input-preview、/latest | 统一可生成资格、当前来源、最近生成与当前报告，个人 AI 配置资格独立 |
| GET /api/reports/{reportId} 及其输入、工作区、下载接口 | 固定覆盖与证据限制，人工编辑不改原始报告 |
| POST /api/reports/{reportId}/fix-list-confirmations | 保留手动提交及待复核阻止提交规则，空确认仍保存限制 |

不增加 skip 路径、参数或 SKIPPED 状态。客户端不能提交任意工具命令或伪造计划/构建引用。依赖分析、测试和优化保留各自接口及真实门槛。

## 1.1 PR #22 请求与语言覆盖

KB请求继续用 `language: "c,rust"` 和 `sourceArch: "arm64"`，不是HTTP数组；现有前端会将选择数组转换成字符串，本轮不改前端。默认 `c,cpp` / `x86`，支持语言非空子集为c、cpp、rust、go、java、python、asm。大小写、空白、重复和顺序规范化，未知语言拒绝。架构保留现有别名及未知值回退x86的兼容行为，同时说明回退假设；不得以回退值证明制品架构匹配。

运行inputPlan和报告来源快照增加 `requestedLanguage`、`effectiveLanguages`、`requestedSourceArch`、`effectiveSourceArch`、`targetArch`、`scanScope`、`excludePaths`、`normalizationVersion`。缺省请求值可为null，effective值入队前固定。请求别名/顺序归一后的等价参数不改变语义摘要；真实选择变化使用不同摘要，恢复不重新读取全局默认值。原始请求不直接反射不可信超长内容，长度校验与安全编码沿用现有输入守卫。

```json
{
  "language": "rust,c,c",
  "sourceArch": "aarch64",
  "excludePaths": ["vendor"]
}
```

该请求的effectiveLanguages为 `["c", "rust"]`，effectiveSourceArch为 `arm64`；需要同架构、同源码和相符编译语义才能增强。x86制品不匹配时记录 `ANALYSIS_ARTIFACT_IDENTITY_MISMATCH` 并执行源码模式；显式未知架构回退返回 `ANALYSIS_ARCHITECTURE_ASSUMED`，增强前仍须有可信匹配依据，不能仅比较回退值。

`summary.coverage.byLanguage[]` 返回语言、`knownFileCount`、`manifestFileCounts` 和 `executionKnown`。文件清单中的 `PLANNED`、`NOT_SELECTED`、`UNSUPPORTED`、`EXCLUDED`、`FAILED`、`OMITTED` 是范围事实；工具没有逐语言实际完成计数时 `executionKnown=false`，不将 PLANNED 改写为 ANALYZED，也不补零伪造完成。单语言内同一路径只计一次；共享头文件可属于 c 和 cpp 两个语言范围，因此跨语言计数不能直接相加作为文件总量。`coverage.knownSourceFileCount` 是整个 manifest 的文件总量，不是真实构建分母。KB 按所选语言扫描，专项不为非 C/C++ 文件创建假 TU。没有合法产出时不伪造成功零发现。

## 2. 资格与可选性

prerequisites 每项增加 required:boolean。只有 required=true 且未 SATISFIED 的项目阻止创建；status 仍为 SATISFIED/MISSING/RUNNING/FAILED/STALE/UNAVAILABLE。项目 readiness 不添加 ready/actions/buttons 等 UI 指令；overview 的 canStart 使用同一 resolver 计算。

```json
{
  "stageId": "memory-order-analysis",
  "prerequisites": [
    {"key": "ACTIVE_SOURCE", "status": "SATISFIED", "required": true},
    {"key": "X86_BUILD", "status": "MISSING", "required": false},
    {"key": "SPECIALIZED_ANALYSIS_EVIDENCE", "status": "MISSING", "required": false,
     "reasonCode": "SOURCE_DISCOVERY",
     "details": {"inputOrigin": "SOURCE_DISCOVERY", "analysisMode": "AST_ONLY", "bcStatus": "MISSING",
                 "planFixed": false, "assumptions": ["DEFAULT_ASSUMPTION", "C17_CPP17", "OPEN_SCOPE"],
                 "missingEvidence": ["COMPILE_DATABASE_UNAVAILABLE", "BITCODE_UNAVAILABLE"]}}
  ]
}
```

示例省略已有 referenceId、context、SPECIALIZED_ANALYZER 工具/profile 必需项；实现返回全部必要事实。编译/BC 可选证据沿用已有 `SPECIALIZED_ANALYSIS_EVIDENCE`，不引入同义 prerequisite。readiness 是只读建议，不物化源码或持久化输入计划；创建时固定请求计划，执行时记录实际计划。context 的全局环境版本可显示，不能无条件用于 SOURCE_DISCOVERY 失效判断。读取配置不发起远程探测；配置完整不等于运行成功。

x86 节点 optional=true；依赖分析的 X86_BUILD 始终 required=true。后续 build 只要求 ACTIVE_SOURCE、必要启用环境和有效脚本，不能要求报告确认、前置构建成功或已有部署成功。任务内部准备源码。test 要求当前对应 BOTH 完整成功及测试配置，单侧或旧测试成功不能满足。

## 3. 执行事实、可消费结果与证据

execution 保持 latestExecution 的含义（活动优先，否则最近创建，ID 稳定排序），result 属于该执行，不能混入另一任务。currentResult 是独立的最新合法发布且实际 Context 仍有效的结果引用与摘要；没有时 null。文档使用 latestExecution 描述语义，不要求新增重复的同义 JSON 字段。

运行 DTO 顶层提供 inputOrigin、resultAvailable、analysisComplete、resultApplicability、enhancementAvailable；专项的 analysisMode 保持原 AST 枚举。固定证据 coverage、assumptions、missingEvidence、limitations 及 inputPlan 位于 `summary` 中。CURRENT/STALE/EMPTY 表达选中执行的 freshness；currentResult 的适用性独立返回。enhancementAvailable 只是当前存在匹配增强证据的提示，不改变历史输入或完成度。

```json
{
  "inputOrigin": "SOURCE_DISCOVERY",
  "buildRunId": null,
  "analysisMode": "AST_ONLY",
  "analysisStatus": "PARTIAL",
  "resultAvailable": true,
  "analysisComplete": false,
  "enhancementAvailable": false,
  "coverage": {
    "knownSourceFileCount": 4,
    "plannedTuCount": 3,
    "analyzedTuCount": 2,
    "failedTuCount": 1,
    "unsupportedTuCount": 0,
    "omittedTuCount": 0,
    "denominatorKnown": false
  },
  "missingEvidence": ["COMPILE_DATABASE_UNAVAILABLE"],
  "limitations": [{
    "reasonCode": "COMPILE_DATABASE_UNAVAILABLE",
    "message": "未提供可信编译参数，分析采用默认解析假设。",
    "message_en": "Trusted compilation arguments are unavailable; analysis uses default parsing assumptions."
  }]
}
```

计数必须注明范围：knownSourceFileCount 是已知 manifest 文件，TU 数是计划范围，互不充作分母。分类互斥，计划内 analyzed/failed/unsupported/omitted 合计等于 plannedTuCount；未进入计划的语言或文件另外在 limitations 给出安全范围和计数，不强塞 TU 分母。无法知道真实构建 TU 总量时 denominatorKnown=false。analysisComplete 指已声明计划是否完整执行且没有未处理范围，不是“已验证安全”；即使为 true，缺少 BC/真实编译参数仍可保留 limitations。KB 不伪造专项 AST 枚举。

浮点工具实际提供的 `unknown` 使用 `unknownTuCount` 保存，不改称失败或不支持；因此有此字段时它也计入计划内分类合计。工具没有提供的计数保持未知（省略），不得补零伪造完成。`bcStatus=MISSING` 与 `analysisStatus=COMPLETE` 可以同时成立：前者表示缺失增强证据，后者表示已声明源码计划完整执行。只有工具合法发布的实际部分完成才能形成 `PARTIAL`，不能按是否构建推导。

新增下载版本为 `report-review.v2` 和 `confirmed-fix-list.v2`，两者的 `sources` 取自该报告固定 manifest 的同一证据投影；空清单也保存覆盖和限制。已有下载制品不重写。

resultApplicability 包含 applicable:boolean 和稳定 reasons[]；inputPlan 对外仅返回受控 ID、摘要哈希、版本与说明，完整命令/路径/密钥不出接口。requested/effective digest 区分执行中安全降级，不回写原计划。

## 4. 报告与复核

每个来源快照保存 taskStatus、analysisStatus、resultAvailable、inputOrigin、coverage 和缺失证据，区分 NOT_RUN、技术失败、过期、合法 PARTIAL、完整零发现。来源可用状态不能用 findingCount 推导。至少一项有效合法发布结果才可生成；合法 PARTIAL 零发现也允许。技术失败半截输出不可纳入。

AI 请求只覆盖 manifest 中固定应复核 ID 和对应源码/证据。progress 返回 totalItems、validatedItems、totalBatches、validatedBatches；分母入队固定，仅已通过协议校验的输出计入 validated。SSE 复用现有任务事件并持久化，断线后查询能恢复同一进度。所有批次完整后才发布，AI 的 INSUFFICIENT_EVIDENCE 是合法结论，网络/进程/协议失败不能冒充该结论。

| AI 判断 | 无人工覆盖时的草稿状态 | 原始事实 |
|---|---|---|
| CONFIRMED_ISSUE | TO_FIX | 风险级别、候选类别、coverage、缺口均不重写 |
| SUSPECTED_FALSE_POSITIVE | PENDING_REVIEW | 不自动排除 |
| INSUFFICIENT_EVIDENCE | PENDING_REVIEW | 不伪造已验证 |

可靠继承人工决定优先；最终仍手动确认。SOURCE_CANDIDATE/INCONCLUSIVE 不因 AI 确认进入 formalRiskCount。JSON/HTML/确认清单使用固定版本证据；历史下载不补写当前构建信息。普通报告无 AI 执行依赖，AI 模式使用触发者 REPORT_AI_REVIEW 个人 Agent+模型。

## 5. 错误与并发

沿用权限及项目不存在、PREREQUISITE_NOT_SATISFIED、Idempotency-Key/reviewRevision/Context 冲突协议；当前实现使用以下安全原因码（任务外层 errorCode 与分析 summary.reasonCode 分开读取）：ANALYSIS_INPUT_PLAN_REQUIRED、ANALYSIS_INPUT_PLAN_MISMATCH、ANALYSIS_PROFILE_UNSUPPORTED、OPTIONAL_ARTIFACT_UNAVAILABLE、REPORT_AI_REVIEW_INPUT_TOO_LARGE、REPORT_AI_REVIEW_INCOMPLETE。可选制品不可用正常产生降级说明而非整体前置失败；缺少必需源码/工具则拒绝或任务失败。

创建与 worker 都重新校验权限、固定输入和实际依赖，不能由 readiness 返回值授权执行。跨用户/项目引用不得泄露存在性；人工与 AI 错误说明均脱敏且双语。最新失败与上次有效结果分别展示；新合法 PARTIAL 替代旧 COMPLETE；并发发布用租约、Context 和 reviewRevision 原子校验。

## 5.1 内存失败与部署重试诊断

在既有任务/运行错误外壳扩展安全诊断，KB明确内存预算超限时示例为：

```json
{
  "reasonCode": "ANALYSIS_MEMORY_BUDGET_EXCEEDED",
  "toolDiagnosticCode": "pipeline.memory-budget-exceeded",
  "phase": "SCAN",
  "memoryLimitMib": 2048,
  "resultAvailable": false,
  "message": "扫描进程超出配置的内存预算，本次结果未发布。",
  "message_en": "The scanner exceeded its configured memory budget; this attempt did not publish a result."
}
```

exitCode有已证实值才返回；进程被杀但原因不明用 `ANALYSIS_PROCESS_TERMINATED`，worker丢失沿用任务外层 `WORKER_LOST`，不从137猜测OOM。超时/取消保留既有状态和原因。memoryLimitMib只表示配置，不是实测峰值；0关闭子进程预算，不表示容器无限资源。所有诊断持久化并进入任务恢复、SSE及报告来源说明，失败残留findings永不发布。latest的旧有效currentResult与本次失败execution分开。

部署重试身份不符沿用 `PROJECT_MISMATCH`，受控路径不符使用 `SOURCE_DEPLOY_PATH_MISMATCH`；对调用者只给出安全原因及双语说明，不返回被误引用资源的真实路径。错误关联在写入/SSH前失败；正确同task重试保持同一部署ID，跨任务成功复用保持项目/源码/环境/版本一致即可。并发冲突仍沿用租约与现有错误协议，不新增部署入口或重试按钮。

AI 漏项/非法 ID 等逐批校验失败保留既有 `REPORT_AI_OUTPUT_INVALID` 等具体诊断；总批次完整性失败使用 `REPORT_AI_REVIEW_INCOMPLETE`，不得将失败任务的外层通用执行码当作复核成功。

## 6. 联调夹具

实施时在 backend 测试资源提供无凭据、无真实路径的请求/响应和固定源码；覆盖 design V01–V19，并至少提供：纯源码可运行、x86失败但可分析、依赖分析受阻、PARTIAL零发现、全部来源不可用、AI三结论、批次进度/失败、同源码增强可用、失效历史只读、直接BOTH验证、单侧不满足测试、越权与并发冲突。夹具必须由当前实现的契约测试校验，不能用手写静态样例冒充验收。

补充夹具：多语言/别名等价输入、纯非C工程、显式未知架构回退、x86制品与arm64请求错配、范围变更及报告继承、2048/0预算、内存诊断完整/截断、worker租约恢复、同task错误部署关联与双端隔离。JaCoCo报告存在性属于后端开发验收，不增加产品API。PR #22的基础透传与限额约定作为基线，仅本change新增字段和规则需要在此对接；不复制或修改原change。

## 7. 实际覆盖范围与完成度

`summary.coverage.compileContextCoverage` 保存 rawCommandCount、localizedCommandCount、unavailableSourceCount、excludedCommandCount 和 unavailableSources。未归档的生成源码标记 SOURCE_NOT_IN_FIXED_SNAPSHOT；固定源码中的 C/C++ 文件未被可信编译数据库选择时，coverage.files 标记 OMITTED / NOT_IN_COMPILE_DATABASE。文件、编译条目和 TU 分母分别统计，不相互替代。

PLANNED 仅是计划状态，不能据此显示已分析。工具没有提供的完成数保持未知。COMPLETE/PARTIAL 来自合法工具输出中的实际分析结果；缺少构建、数据库或 BC 仅记录输入模式和 missingEvidence，不直接推导 PARTIAL。声明范围之外的遗漏继续保留在报告和确认下载，COMPLETE 也不表示全工程所有语言或所有构建路径均已验证。
