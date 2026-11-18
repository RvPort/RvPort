# 结果报告后端接口契约

接口实现与联调说明见[结果报告与待修复清单](../../../../docs/report-review.zh-CN.md)。以下保留设计场景及请求示例。

## 1. 通用约定

- 复用 ApiResponse/PageResponse、项目权限、认证、账号状态、CSRF 和任务/SSE。模型配置权限仍个人隔离；报告内容按项目授权。
- UUID 必须属于同项目、同报告及绑定版本。越权子资源按不存在处理，不返回私人 profile 地址、密钥、命令或内部存储路径。
- 写请求先完成权限/账号/CSRF 校验，再校验 Context、可写状态、版本和业务数据；错误无部分写入和模型调用。
- mode: STANDARD / AI_ENHANCED。reviewState: PENDING_REVIEW / TO_FIX / EXCLUDED。origin: SCANNER / MANUAL。
- AI verdict: CONFIRMED_ISSUE / SUSPECTED_FALSE_POSITIVE / INSUFFICIENT_EVIDENCE，与原始 findingKind、riskLevel 独立。无可靠人工决定时，CONFIRMED_ISSUE 初始化为 TO_FIX，另两类及普通模式初始化为 PENDING_REVIEW。hasManualDecision 表示已有人工处置依据。草稿的 toFixCount 不代表已提交清单。
- 报告 status 复用任务 PENDING/QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED 等终态；发布单独 publishState=CURRENT/HISTORICAL_CONTEXT_CHANGED/HISTORICAL_SUPERSEDED/NOT_PUBLISHED。执行成功不表示当前可用。
- confirmationState: UNCONFIRMED / NEEDS_CONFIRMATION / CONFIRMED_EMPTY / CONFIRMED_WITH_ITEMS；过期或历史由 writable=false、contextValid=false、reasonCodes 单独表达，不抹除历史确认事实。
- 用户可见业务说明使用 message/messageEn、reason/reasonEn、description/descriptionEn 等双语字段；技术枚举不翻译。
- 个人阶段绑定沿用 GET/PATCH /api/me/model-stage-assignments。报告 AI 复核的 stage 为 REPORT_AI_REVIEW，绑定 modelProfileId 和 agentRuntimeId；TEST_LOG_COMPARE 独立保留。KB_AI_EXTENSION 为非法值，干净初始化后需要重新配置。生成请求不能指定绑定或覆盖执行身份。
- 所有个人 readiness 和报告草稿响应 no-store。Idempotency-Key 必填于生成、新增人工项、批量修改、确认；作用域 actor+project+操作。复用 key 必须同规范化 body，同 key 异 body 409；先授权再返回重放。
- 相同草稿确认唯一性也由 reportId+draftVersion 保障；所有 expectedVersion 是服务端草稿版本，非前端时间戳。

## 2. 路由清单

| 方法与路径 | 行为 |
|---|---|
| GET /api/projects/{projectId}/reports/evaluation/readiness | 四类可用来源、当前 Context、模式可用性、预览计数、当前报告/最新生成摘要、当前清单 |
| GET /api/projects/{projectId}/reports/evaluation/input-preview | 同一策略的生成前候选预览，包含可继承排除与 AI 纳入原因 |
| POST /api/projects/{projectId}/reports/evaluation | 普通/增强/重新生成同一入口；返回可恢复任务引用 |
| GET /api/projects/{projectId}/reports/evaluation | 历史分页；不读取当前项目数据拼历史正文 |
| GET /api/projects/{projectId}/reports/evaluation/latest | 复用领域外壳，分别返回 currentReport、latestGeneration、currentFixList |
| GET /api/reports/{reportId} | 固定报告摘要、输入 manifest 摘要、AI 完整性、草稿和确认摘要、可写性 |
| GET /api/reports/{reportId}/inputs | 保留固定原始来源输入查询，非草稿状态列表 |
| GET /api/reports/{reportId}/review-items | 统一全局问题列表，含人工条目、依赖、无定位问题 |
| GET /api/reports/{reportId}/review-items/{itemId} | 原始来源、AI 依据、人工状态、位置、继承与操作审计分页入口 |
| POST /api/reports/{reportId}/review-items | 从固定源码框选内容新增一个人工待修复项 |
| PATCH /api/reports/{reportId}/review-items | items 一条为单次，多条为批量，INCLUDE/EXCLUDE/RESTORE 原子执行 |
| GET /api/reports/{reportId}/source-files | 当前工作台已知源码目录/文件分页，支持零发现文件和路径搜索 |
| GET /api/reports/{reportId}/source-files/{fileKey}/context | 固定版本区间内容，hash、行数、不可读原因 |
| POST /api/reports/{reportId}/fix-list-confirmations | 确认指定草稿版本，允许空清单 |
| GET /api/reports/{reportId}/fix-list-confirmations | 历史确认分页及其摘要 |
| GET /api/reports/{reportId}/fix-list-confirmations/{confirmationId} | 确认元数据和分页快照条目，可用 query 读取全部处置或仅待修复 |
| GET /api/reports/{reportId}/download | 原报告 HTML/JSON；指定 confirmationId 时下载该确认的固定清单制品 |
| 既有任务详情、事件/SSE、日志接口 | 查询生成/复核进度、重连和失败信息，不新增重复任务轮询 API |
| 既有 code-modules/code-files/code-finding-types/code-findings/context | 保留历史模块阅读，内容固定，不用它的首屏代替完整清单 |
| 既有 /api/reports/{reportId}/comparisons | 增加 basis=REPORT_FACTS/CONFIRMED_FIX_LIST，后者要求明确两侧 confirmationId |

项目级 currentFixList 聚合复用 readiness/latest/overview，内部使用统一 resolver，不额外增加仅重复这些字段的写接口。详情审计可通过 review-items/{id}?include=history&historyPage=1 取分页，不能无限返回全部事件。

## 3. 生成与读取示例

生成请求：

```json
{
  "sourceRevisionId": "11111111-1111-4111-8111-111111111111",
  "mode": "AI_ENHANCED",
  "selectionToken": "opaque-server-token",
  "baseReportId": "22222222-2222-4222-8222-222222222222",
  "expectedDraftVersion": 12
}
```

重新生成使用 readiness 的 regenerationBaseReportId、regenerationBaseDraftVersion。首份报告无 baseReportId/expectedDraftVersion；重新生成必须带当前报告和草稿版本。服务器不接受客户端 owner、模型密钥、任意 sourceRefId 越过选定有效集合或自行指定父执行身份。新契约默认纳入策略规定的全部有效候选，不另增生成前人工勾选门槛。mode 必填，缺失或未知值返回参数错误；不保留旧请求兜底。返回沿用 TaskResponse 并增加 reportId；轮询或 SSE 后通过 reportId 读取详情。

readiness 示例（以下为 data 片段，UUID 为占位示例）：

```json
{
  "sourceRevisionId": "11111111-1111-4111-8111-111111111111",
  "selectionToken": "opaque-server-token",
  "canGenerate": true,
  "canGenerateStandard": true,
  "canGenerateAi": false,
  "recommendedMode": "STANDARD",
  "aiBlockers": [{"code":"REPORT_AI_MODEL_NOT_CONFIGURED","message":"请配置个人报告复核模型","messageEn":"Configure your personal report review model","recoveryAction":"OPEN_PERSONAL_MODEL_CONFIGURATION"}],
  "sources": [
    {"type":"KB_FINDING","available":true,"runId":"33333333-3333-4333-8333-333333333333","status":"SUCCEEDED","rawFindingCount":0,"candidateCount":0,"inheritedExcludedCount":0,"aiInputCount":0},
    {"type":"DEPENDENCY_RESULT","available":false,"reasonCode":"ANALYSIS_NOT_READY"},
    {"type":"FLOATING_POINT_FINDING","available":false,"reasonCode":"ANALYSIS_NOT_READY"},
    {"type":"MEMORY_ORDER_FINDING","available":false,"reasonCode":"ANALYSIS_PARTIAL"}
  ],
  "currentReport": null,
  "latestGeneration": null,
  "currentFixList": {"available":false,"confirmationId":null,"reasonCode":"REPORT_NOT_GENERATED"}
}
```

sources 每类均有安全 Context 摘要、resultVersion、完成时间；未运行与零发现不能都用 count=0 表示。canGenerate 为普通模式资格，不表示 AI 模式可提交。阶段/overview 增加可用成功分析前置与确认清单事实，不沿用 INCLUDED_FINDINGS 为新生成资格。

详情中三类计数分开：

```json
{
  "reportSummary":{"rawFindingCount":8,"decisionItemCount":8,"formalRiskCount":6,"unverifiedCandidateCount":2},
  "aiSummary":{"expected":7,"valid":7,"skippedInheritedExcluded":1,"execution":"COMPLETED"},
  "draftSummary":{"version":12,"total":9,"pendingReviewCount":2,"toFixCount":6,"excludedCount":1,"manualCount":1},
  "confirmationState":"UNCONFIRMED",
  "currentConfirmationId":null,
  "previousConfirmationId":null,
  "writable":true
}
```

draft total 包含人工新增与保留的已排除项；报告原始统计不随人工变化。formalRiskCount 与决策状态不是同一分区。aiSummary 只对应本次实际 AI 集合，不能声称复核了跳过项。

## 4. 处置与人工新增

PATCH body：

```json
{
  "expectedVersion":12,
  "items":[
    {"id":"44444444-4444-4444-8444-444444444444","action":"INCLUDE"},
    {"id":"55555555-5555-4555-8555-555555555555","action":"EXCLUDE","reason":"目标平台不编译该路径","reasonEn":"This path is not compiled on the target platform"}
  ]
}
```

1..500 项，同批重复 ID/未知动作/缺排除原因 400；跨报告或越权 404；冲突 409；全部通过后更新一次版本，返回 changedItems、draftSummary、confirmationState 和当前可用确认引用。RESTORE 恢复前次排除前状态，连续 EXCLUDE 不覆盖原恢复目标。人工项也使用此接口移除/恢复，无 DELETE 硬删除。

POST 人工项 body：

```json
{
  "expectedVersion":13,
  "fileKey":"opaque-file-key",
  "sourceContentHash":"sha256-of-source-bytes",
  "range":{"startLine":22,"startColumn":1,"endLine":25,"endColumn":6},
  "title":"缺少目标架构兼容处理",
  "description":"选中的调用只在源架构提供",
  "repairReason":"需要替换为目标平台实现"
}
```

行列为 1-based、UTF-16 列、终点不含；多行支持末行长度+1 列，拒绝空选区、反向/越界范围和拆分 surrogate pair。sourceRevisionId 从报告确定，不允许客户端换版本。后端保存自身提取的 snippet、snippetHash、原始字节 hash、操作者；初始 TO_FIX。返回完整新增项和递增后的草稿摘要。说明长度上限 4000 字符，title 200，单选片段最多 1000 行/128 KiB；超限 400 SOURCE_SELECTION_TOO_LARGE，不截断后假装完整。

## 5. 确认与重新确认

```json
{"expectedVersion":14}
```

成功返回 confirmationId、draftVersion、confirmedAt/By、itemCount、confirmationState、可用性和制品引用，不直接嵌入无限条目。确认包含所有处置快照，但待修复子集可以为空。确认会写审计且参与继承基线并发检测：确认事件通过独立 reviewRevision 或等价行版本保护，不要求改变语义草稿内容；生成发布比较的是完整 reviewRevision。

草稿后续发生实际修改后，previousConfirmationId 保留，currentConfirmationId 清空；下游返回 NEEDS_CONFIRMATION。恢复到相同内容也需重新确认，不能按内容相同自动复活旧快照。

## 6. 分页与源码浏览

- page 从 1 开始，默认 pageSize=50，范围 1..200；稳定排序以 id/path 作末级 tie-breaker，非法排序 400。
- review-items 过滤：reviewState、origin、sourceType、findingKind、riskLevel、fileKey、keyword（AND）；支持全部问题，不要求 moduleKey/fileKey。sortBy 白名单 createdAt/path/line/riskLevel/reviewState/title。
- 每页返回 total（过滤总数）、page/pageSize/hasMore、draftVersion 、全局 draftSummary 和过滤后的 filteredSummary；翻页可携带 draftVersion，若变化返回 REPORT_DRAFT_CONFLICT，避免同一次选择跨版本。
- source-files 使用 parentKey 获取直接子目录/文件，keyword 搜索返回规范化相对路径；树目录来自 manifest，问题数与过滤数分开。依赖与未知路径不创造树节点。
- context 支持 startLine/endLine，单次最多 500 行；返回 totalLines/contentHash/sourceRevisionId/lines；不可读时 contextAvailable=false、reasonCode、双语说明，不自动读取当前工作副本。
- 全选当前页传显式 IDs；跨页全选须在固定草稿版本下枚举完整集合（每次写最多 500，分批后刷新版本），不把第一页当全集。不新增无界“当前筛选全部更新”接口。
- 无定位问题不因 fileKey 为空而从“全部”消失；全局提交检查查询数据库完整集合。

## 7. 错误与恢复

| HTTP | code 示例 | 语义/恢复 |
|---|---|---|
| 401/403 | 现有认证、账号和 CSRF 原因码 | 保持现有规则，无写入 |
| 404 | REPORT_NOT_FOUND / REPORT_REVIEW_ITEM_NOT_FOUND / REPORT_SOURCE_FILE_NOT_FOUND | 缺失或越权，不泄漏存在性 |
| 400 | REPORT_REQUEST_INVALID / SOURCE_SELECTION_INVALID / REPORT_REVIEW_ACTION_INVALID | 字段、范围、枚举或重复 ID 非法 |
| 409 | REPORT_SOURCE_REQUIRED / REPORT_ANALYSIS_NOT_READY | 无当前源码或无有效成功分析，返回恢复动作 |
| 409 | REPORT_CONTEXT_CHANGED / REPORT_SELECTION_CHANGED | 重新读取 readiness，不混用版本 |
| 409 | REPORT_DRAFT_CONFLICT / REPORT_REVIEW_BASE_CHANGED | 返回安全当前版本，刷新后重试 |
| 409 | REPORT_READ_ONLY | 历史/过期报告，当前指针及原因仅在项目授权后返回 |
| 409 | REPORT_PENDING_REVIEW | 全局待复核数量非零，无确认写入 |
| 409 | REPORT_GENERATION_IN_PROGRESS / IDEMPOTENCY_KEY_REUSED | 活动任务冲突或 key 异请求 |
| 409 | REPORT_AI_MODEL_NOT_CONFIGURED / MODEL_CONFIGURATION_USER_UNAVAILABLE | 当前触发者不可用，不回退别人的配置 |
| 409 | SOURCE_CONTENT_CHANGED / SOURCE_CONTEXT_UNAVAILABLE | 源码 hash 变化/不可用，不使用客户端片段代替 |
| 任务失败 | REPORT_AI_OUTPUT_INVALID / REPORT_AI_INCOMPLETE / REPORT_AI_TIMEOUT | 无增强成功、无自动普通降级 |

既有 ApiResponse error 外壳不改变；新增 errors.details 包含可审计且脱敏的计数、版本和 recoveryAction，不能包含模型密钥、完整 prompt 或服务器路径。

## 8. 权限、历史和交接

- 读取历史只要求项目权限，不因查看者缺个人模型而失效；新增 AI 执行必须用该操作者个人配置。
- 当前草稿为项目报告资源，不是每人各一份；项目既有授权决定读写，admin 可按项目权限修改但留下实际 actor。
- 历史报告所有 POST/PATCH 硬性拒绝，前端禁用按钮不是权限措施。
- 新个人阶段目录仅 TEST_LOG_COMPARE/REPORT_AI_REVIEW；旧 KB_AI_EXTENSION 写入拒绝，不保留旧阶段专项结果解码层。
- 旧 KB AI 全部读写/下载路由及旧节点 migration-input 专用读写接口删除，不保留历史 GET 或 409 过渡层；通过认证和适用 CSRF 后旧路由不匹配，返回 404。
- 前端后续需接入真实查询、任务恢复、版本冲突、所有待复核拦截和个人配置提示；不属于本次实现。
- 本地夹具覆盖 requirements-mapping.md 中 B01..B18 和追加边界，用模拟上游与假密钥，不提交 .migration-reports 内容。

## 9. 详情、确认与比较的补充示例

以下均为 ApiResponse 的 data 内容。名称为拟定的新业务字段，实施时复用现有外壳，不能同时维护含义不同的同名计数。

单项详情示例：

```json
{
  "id":"44444444-4444-4444-8444-444444444444",
  "reportId":"22222222-2222-4222-8222-222222222222",
  "origin":"SCANNER",
  "sourceType":"MEMORY_ORDER_FINDING",
  "sourceRunId":"33333333-3333-4333-8333-333333333333",
  "sourceRefId":"66666666-6666-4666-8666-666666666666",
  "findingKind":"SOURCE_CANDIDATE",
  "reviewState":"PENDING_REVIEW",
  "title":"内存序候选需要核对",
  "titleEn":"Memory-order candidate requires review",
  "locations":[{"fileKey":"opaque-file-key","path":"src/queue.c","startLine":22,"endLine":25}],
  "originalEvidence":{"schemaVersion":1,"summary":"存在尚待验证的共享访问路径","summaryEn":"A shared-access path requires validation"},
  "aiReview":{"verdict":"INSUFFICIENT_EVIDENCE","reason":"缺少调用方同步约束","reasonEn":"Caller synchronization constraints are unavailable","evidenceRefs":[]},
  "inheritance":{"inherited":false,"reasonCode":"NEW_ITEM","fromReportId":null,"fromItemId":null},
  "draftVersion":12,
  "writable":true
}
```

originalEvidence 实际保留版本化领域证据，不将示例摘要当成全部原 payload。riskLevel 为原始级别或 null，unknown 单独表示，候选不得为了填字段而被赋予确定风险。未执行 AI 时 aiReview=null，并由报告 aiSummary 解释 STANDARD、跳过或 NOT_REQUIRED；继承决定不伪造新的 AI 结论。人工条目返回 manualDetails（description/repairReason/原始文本语言/translationStatus/snippetHash/range/创建人和时间），sourceRefId 为空，不伪装扫描引用。

源码区间示例：

```json
{
  "fileKey":"opaque-file-key",
  "path":"src/queue.c",
  "sourceRevisionId":"11111111-1111-4111-8111-111111111111",
  "contextAvailable":true,
  "contentHash":"sha256-of-source-bytes",
  "totalLines":80,
  "startLine":22,
  "endLine":23,
  "lines":[{"lineNumber":22,"text":"int ready = flag;"},{"lineNumber":23,"text":"return ready;"}]
}
```

context 的 startLine/endLine 是包含两端的行区间，与框选 range 的终点不含语义不同。context 不可用仍返回文件元数据、contextAvailable=false 和原因，不能返回另一版本内容补位。source-files 的每个节点返回 fileKey/parentKey/path/name/kind、contextAvailable、各状态问题计数；目录没有可框选内容。

确认空清单示例：

```json
{
  "confirmationId":"77777777-7777-4777-8777-777777777777",
  "reportId":"22222222-2222-4222-8222-222222222222",
  "draftVersion":14,
  "confirmationState":"CONFIRMED_EMPTY",
  "itemCount":0,
  "reviewedItemCount":8,
  "excludedCount":8,
  "pendingReviewCount":0,
  "availableForCurrentWorkflow":true,
  "confirmedBy":"88888888-8888-4888-8888-888888888888",
  "confirmedAt":"2026-11-17T01:00:00Z"
}
```

itemCount 指确认时 TO_FIX 子集；reviewedItemCount 包含已排除项，原本零问题时两者均为零。确认详情默认列出全部处置快照；scope=TO_FIX 列出待修复子集，均分页且不可变。availableForCurrentWorkflow 是实时资格，不修改快照事实；下载固定制品不将该实时资格重新写入历史正文。错误 details 中 pendingReviewCount 是数据库全局数量，冲突时 currentVersion 仅对有权读取者返回。

比较请求示例：

```json
{
  "baseReportId":"99999999-9999-4999-8999-999999999999",
  "basis":"CONFIRMED_FIX_LIST",
  "baseConfirmationId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "targetConfirmationId":"77777777-7777-4777-8777-777777777777"
}
```

两侧确认必须分别属于 baseReportId 和路由 reportId，且同项目；缺少或错配拒绝，不猜测最新确认。REPORT_FACTS 模式拒绝同时提交 confirmationId 以免产生歧义，可继续省略 baseReportId 自动选择上一份兼容报告。新比较差异允许 NOT_OBSERVED/EXCLUDED/NO_LONGER_SELECTED/NEW/UNCHANGED/CHANGED；不支持旧 RESOLVED 数据兼容，不由新的待修复集合消失推断已修复。


## 10. 开发数据重置边界

本契约面向干净初始化的新版本。变更前的报告、绑定、任务、制品及旧节点确认数据均不要求读取、转换或续跑；REPORT_AI_REVIEW 由用户重新配置。删除旧 migrationInputRefId/migrationInputStatus/confirmedForNextReport 字段，不返回假值兜底。新流程自己产生的历史报告/确认继续只读可查，不能因允许抛弃开发数据而删除正常业务的版本追溯能力。实际清理本轮独立验收资源以外的数据不属于本次提案操作。
