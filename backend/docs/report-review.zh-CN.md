# 结果报告与待修复清单

一个项目可以保留多份结果报告。报告固定生成时的扫描发现、输入版本、源码和 AI 结果。人工操作写入该报告的复核草稿。手动确认时再保存一个独立的清单版本，后续比较、复检及下载可引用其 ID。

## 接口

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


## 生成与重新生成

先读取 readiness。四类来源分别为 KB_FINDING、DEPENDENCY_RESULT、FLOATING_POINT_FINDING、MEMORY_ORDER_FINDING。至少一类有当前有效的成功运行即可生成，包括成功且零发现。查看每类的 available、status、reasonCode、runId、resultVersion、executionContext、finishedAt 与三个计数 candidateCount、inheritedExcludedCount、aiInputCount。缺少匹配运行返回 ANALYSIS_NOT_READY；仅有 PARTIAL 等运行时返回实际状态与对应原因。相同上下文中较新的失败不能抹去上次成功。更换了当前源码、构建、环境或相关的 bitcode 制品集后，只能选用满足该来源新上下文的结果。

知识库保留低风险项。依赖只收录未解决且中风险以上，或结论未知的项目；已安装且满足要求的依赖不会因名称匹配而生成问题。浮点项目保留原始风险，内存序的 SOURCE_CANDIDATE 和 INCONCLUSIVE 单列；DISCHARGED 仅计数。每条扫描项保留自己的 sourceType、sourceRunId、sourceRefId。groupKey 可用于展示关联项，逐项处置使用条目 id。

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


每次请求从 readiness 复制 selectionToken、regenerationBaseReportId（传作 baseReportId）与 regenerationBaseDraftVersion（传作 expectedDraftVersion）。首次生成和切换源码后的首份报告，后两者为 null。同源码新增一项分析时，当前报告可能已经失效，此时仍使用服务端给出的重新生成基线。写请求发送 Idempotency-Key，同一操作者用同一个 key 重放相同请求可取回原响应。草稿增删、确认与重新生成应各用独立 key。

成功入队返回 id（任务）和 reportId。查询 GET /api/tasks/{id}，项目事件流 GET /api/projects/{projectId}/task-events 可用 Last-Event-ID 续读。GET latest 中 currentReport 和 latestGeneration 分别表示可用报告及最近一次执行，失败任务可与上一份成功报告同时存在。报告的草稿可写性读取 writable、contextValid、readOnlyReason。

STANDARD 可直接生成。AI_ENHANCED 使用当前触发者在个人阶段 REPORT_AI_REVIEW 显式绑定的 Agent 和模型。未配置时通过 aiBlockers 引导个人配置。自动重试沿用原身份与引用；可靠继承的人工排除跳过模型，待复核集合为空时 aiSummary.execution=NOT_REQUIRED。AI 的输入 ID 必须全部且恰好返回一次，并有合法的结论、双语依据和属于该输入的证据引用。失败和超时保留上一份成功报告，失败详情可通过任务查询。

| 原始 AI 结论 | 无显式人工处置时的草稿状态 |
|---|---|
| CONFIRMED_ISSUE | TO_FIX |
| SUSPECTED_FALSE_POSITIVE、INSUFFICIENT_EVIDENCE | PENDING_REVIEW |
| 普通报告，没有 AI 结论 | PENDING_REVIEW |

同源码版本按稳定身份、证据和位置继承人工处置；人工框选另校验整文件与片段 hash。跨源码版本重新核对。每份新报告都需要用户手动提交清单。

## 列表、源码和人工操作

列表使用 page、pageSize（1 至 200，默认 50）、reviewState、origin、sourceType、findingKind、riskLevel、fileKey、keyword，支持按 createdAt、path、line、riskLevel、reviewState、title 排序。draftSummary 是全报告统计，filteredSummary 是过滤后全部页统计，items 仅当前页。翻页可携带 draftVersion。扫描详情保留 originalEvidence、aiReview、inheritance 和 locations。每个位置的 fileKey 必须能在固定源码树中找到；无定位条目仍出现在全局列表。人工条目的 manualDetails 保留用户原文、片段及范围。

source-files 以 parentKey 浏览，keyword 搜索已知源码，包括零发现文件。context 的 startLine/endLine 包含两端，单次至多 500 行。不可读时返回 contextAvailable=false 与原因。人工框选采用下列独立的范围规则。

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


## 确认、冲突与下载

向 fix-list-confirmations 提交 {"expectedVersion":14}。数据库对完整草稿检查 pendingReviewCount；即便当前页都是待修复项，只要其他页尚有待复核项，就返回 409 REPORT_PENDING_REVIEW。无待复核时保存全部处置快照，itemCount 只计算 TO_FIX。原本零发现和全部排除均可提交空清单。相同版本并发确认返回同一 confirmationId。草稿在确认后有改动，必须重新确认。

currentFixList.available 及 reasonCode 表示实时可用性（包括 UNCONFIRMED、NEEDS_CONFIRMATION、CONFIRMED_EMPTY、CONFIRMED_WITH_ITEMS、REPORT_CONTEXT_CHANGED）。历史详情按 scope=ALL 或 TO_FIX 分页，availableForCurrentWorkflow 表示当前是否还能用于后续流程。按 reportId 下载固定报告 HTML/JSON；追加 confirmationId 下载该次确认制品。修改草稿、重新生成、切换源码都不会改写已生成的制品。

报告比较的 CONFIRMED_FIX_LIST 需要明确 baseReportId、baseConfirmationId、targetConfirmationId。源码复检的请求需要两侧 sourceRevisionId 和 confirmationId（base、target 前缀），仅相同版本对复用；基线可选历史确认，目标须是当前确认。差异的 NOT_OBSERVED、EXCLUDED、NO_LONGER_SELECTED 均保留原语义，不据此声称问题已修好。

读取历史仍须具备该项目权限。生成、修改草稿和确认需有效登录及 CSRF。资源无权访问按不存在处理。版本冲突、REPORT_CONTEXT_CHANGED、REPORT_SELECTION_CHANGED、REPORT_REVIEW_BASE_CHANGED 时刷新 readiness 或草稿；REPORT_READ_ONLY 时切换到当前版本。REPORT_GENERATION_IN_PROGRESS 时查看最新任务。遇到 IDEMPOTENCY_KEY_REUSED 应检查请求体。框选非法返回 400，源码 hash 已改变返回 409。诊断通过既有 ApiResponse/任务接口读取。

## 开发实例切换

先停止同一旧实例的 API 和 worker。以新的 Compose 项目、网络、数据库、Redis、RabbitMQ、MinIO、源码及运行工作区卷启动匹配版本。旧队列和任务重试消息、数据库、对象制品、源码与缓存是同一组资源，不能仅清库后连接旧队列。本次演练全部使用新资源。重新初始化后需创建账号和项目、上传源码、准备有效扫描，并在个人模型配置中绑定 REPORT_AI_REVIEW。原 KB_AI_EXTENSION 与 migration-input 节点确认专用读写接口已移除。清理旧开发数据会丢失旧账号、密钥、绑定、任务与历史报告。
