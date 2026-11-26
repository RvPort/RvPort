# 后端接口与联调契约

本文为本 change 已实现的后端契约，验证证据见 verification.md。沿用 ApiResponse 外层、认证/CSRF、项目权限、traceId 和现有分页格式；下文只列业务 payload。只读 GET 不触发模型、任务或业务数据补写。新增公开说明提供中文及英文；不泄漏凭据、绝对路径、Worker 主机信息。

## 1. 报告生成及异步任务

POST /api/projects/{projectId}/reports/evaluation 保留 sourceRevisionId、mode、selectionToken、baseReportId、expectedDraftVersion 和 Idempotency-Key 既有契约。

| 输入/状态 | 响应/行为 |
|---|---|
| useAiEnhancement 或其他未知字段 | 400 REPORT_REQUEST_INVALID，不接受旧字段 |
| 损坏 JSON、错误类型/枚举/UUID、缺失请求体或必填业务值 | 400 REPORT_REQUEST_INVALID，安全双语说明 |
| 同幂等键同合法内容 | 原任务，零额外任务/投递 |
| 同幂等键异内容 | 409，保持既有幂等错误码 |
| AI 有实际输入，配置满足但 Worker 能力明确不可用 | 409 REPORT_AI_SANDBOX_UNAVAILABLE，无创建副作用 |
| 排队后实际沙箱能力丢失 | 任务 FAILED，errorCode=REPORT_AI_SANDBOX_UNAVAILABLE，安全双语说明，不降级生成普通报告 |
| 非法请求后同键提交合法请求 | 非法请求未占键，正常创建 |

GET /api/tasks/{taskId} 保持现有结构。重试耗尽且未被合法领取时终态 FAILED，errorCode=TASK_DELIVERY_EXHAUSTED，附安全说明；事件/SSE 与领域记录一致。队列暂未消费不等于失败。

## 2. 历史报告读取

GET /api/projects/{projectId}/reports/evaluation 与 GET /api/reports/{reportId} 新增以下字段：

| 字段 | 含义 |
|---|---|
| reviewAvailable | 是否存在可读取的复核运行摘要；不代表草稿可写或报告当前有效 |
| reviewUnavailableReasonCode / reviewUnavailableMessage / reviewUnavailableMessageEn | 不可用原因；可用时 null |
| sourceVersionLabel / sourceGitCommit | 所绑定源码版本的展示标签、完整提交号，可为 null |
| sourceMetadataOrigin | SNAPSHOT、REVISION_RECORD 或 UNAVAILABLE |

缺少 run 时 review=null 且 reviewAvailable=false，原因 REPORT_REVIEW_UNAVAILABLE；完整报告 review 保持原统计结构。缺少草稿但仍生成中的合法 run 不等价于缺 run，继续返回真实生成状态。依赖缺失 manifest/run 的工作区读取、修改、确认、继承请求返回 409 REPORT_REVIEW_UNAVAILABLE；无权限仍按既有权限隐藏规则处理，不先泄漏缺记录情况。

## 3. 问题关键词

GET /api/reports/{reportId}/review-items?keyword=... 保留路径；keyword 为 trim 后最多 256 UTF-16 单元的字面串，大小写无关，不解释 SQL、通配符或正则。覆盖中英文标题、公开相对路径、原始证据展示正文、AI 依据、人工说明/修复原因及当前人工排除说明；内部 ID、哈希、凭据和隐藏字段永不参与。

输出分页与计数结构不变；total/filteredSummary 是完整匹配集合，globalSummary 是完整报告草稿。input-preview 只搜索它实际具备的扫描展示正文。

| 来源 | 展示正文白名单 |
|---|---|
| 全部条目 | title/titleEn/relativePath、exclusionReason/exclusionReasonEn |
| KB_FINDING 的 originalSnapshot | description/descriptionEn、suggestion/suggestionEn、evidence 中的公开正文 |
| DEPENDENCY_RESULT 的 originalSnapshot | recommendation/recommendationEn、evidence 中的公开正文 |
| MEMORY_ORDER_FINDING、FLOATING_POINT_FINDING 的 originalSnapshot | message/messageEn、suggestion/suggestionEn、testSuggestion/testSuggestionEn、detail 中的公开正文 |
| 各来源分析缺口 | analysisEvidence 内 assumptions、limitations、missingEvidence、analysisReasonCodes 的正文 |
| aiReview | reason/reasonEn、suggestion/suggestionEn；inputId、evidenceRefs 不参与匹配 |
| manualDetails | description/descriptionEn、repairReason/repairReasonEn；snippetHash、range 不参与匹配 |
| displayEvidence | message/messageEn；code 不独立当作展示正文搜索 |

嵌套只沿 evidence/evidences/detail/details/limitations/missingEvidence/assumptions/analysisReasonCodes/analysisEvidence/conclusion/evidenceChain/rawFinding/sourceDetail/breaks，最多 8 层。可匹配字符串正文或公开正文数组；对象只提取 title、description、reason、suggestion、message、explanation、repairReason、text、summary、recommendation、testSuggestion 及已声明英文键。未知键、内部 ID/hash/人员信息不递归搜索。

## 4. AI readiness

GET /api/projects/{projectId}/reports/evaluation/readiness 保留 canGenerate、canGenerateStandard、canGenerateAi、recommendedMode、aiBlockers，新增：

```json
{
  "aiConfigurationReady": true,
  "aiExecutionCapability": {
    "status": "UNAVAILABLE",
    "executionRequired": true,
    "reasonCode": "REPORT_AI_SANDBOX_UNAVAILABLE",
    "message": "当前可接收任务的 Worker 不支持报告 AI 所需的沙箱能力",
    "messageEn": "The eligible workers do not support the sandbox required for report AI review",
    "observedAt": "2026-11-25T02:00:00Z",
    "expiresAt": "2026-11-25T02:01:30Z"
  },
  "canGenerateStandard": true,
  "canGenerateAi": false,
  "recommendedMode": "STANDARD",
  "aiBlockers": [{
    "category": "EXECUTION",
    "code": "REPORT_AI_SANDBOX_UNAVAILABLE",
    "message": "报告 AI 执行沙箱不可用",
    "messageEn": "The report AI execution sandbox is unavailable",
    "recoveryAction": "CHECK_WORKER_ENVIRONMENT"
  }],
  "aiWarnings": []
}
```

以上时间仅为契约示例；实际自检缓存 30 秒、观测有效期 90 秒，子进程最多等待 10 秒，过期结果不续期冒充新探测。配置阻塞 category=CONFIGURATION，沿用真实配置错误码与 OPEN_PERSONAL_MODEL_CONFIGURATION。UNKNOWN 返回 aiWarnings，reasonCode 可区分 WORKER_CAPABILITY_UNKNOWN、WORKER_CAPABILITY_STALE、WORKER_CAPABILITY_MIXED，observedAt/expiresAt 可空，不阻止排队。所有 eligible Worker 已知不可用才作为硬阻塞；没有在线 Worker 按 UNKNOWN。

无实际 AI 输入为 NOT_REQUIRED/executionRequired=false，不因沙箱缺失阻塞，但个人配置与来源资格仍必须满足。通用个人配置接口 GET /api/me/model-stage-assignments 不增加沙箱或 probe 成功门槛。支持沙箱不代表模型实际调用成功。

## 5. 固定源码全文搜索

新增 GET /api/reports/{reportId}/source-files/{fileKey}/search。

| 参数 | 规则 |
|---|---|
| keyword | 必填，1—256 UTF-16 单元；拒绝空白及换行，按原串匹配，不 trim 有意义的前后空格 |
| caseSensitive | 布尔，默认 false |
| limit | 整数 1—200，默认 100；匹配位置数，不是匹配行数 |

使用字面非重叠匹配，按行/列排序，列为 1-based UTF-16、endColumn 不含终点；与人工框选坐标体系一致。同一行多个位置分别返回。示例 payload：

```json
{
  "fileKey": "fixture-file-key",
  "path": "src/example.c",
  "sourceRevisionId": "11111111-1111-4111-8111-111111111111",
  "contentHash": "fixture-content-hash",
  "available": true,
  "matches": [{
    "lineNumber": 501,
    "startColumn": 5,
    "endColumn": 11,
    "preview": "    needle();",
    "previewStartColumn": 1,
    "previewTruncated": false
  }],
  "returnedCount": 1,
  "truncated": false,
  "reasonCode": null,
  "message": null,
  "messageEn": null
}
```

preview 最多 512 UTF-16 单元且不截断代理对，优先覆盖匹配内容；previewStartColumn 标注片段在原行的起点，匹配列始终是原文件绝对列。不返回未经完整校验的部分匹配。

有效无匹配返回 available=true/matches=[]/truncated=false。源码不可读或 hash 不符返回 200、available=false、空 matches 和与 context 一致的原因码/双语说明，不伪装无匹配。非法搜索参数为 400 REPORT_REQUEST_INVALID；报告/文件越权沿用既有 404；缺 run/manifest 为 409 REPORT_REVIEW_UNAVAILABLE。不接受客户端路径或源码内容覆盖 fileKey，不支持 regex。

## 6. 证据双语解释

保留原始 reasonCode，在单独 displayEvidence 中增加标准说明项 `{code,message,messageEn,catalogVersion}`；同一来源同一 code 的含义必须一致，使用来源命名空间避免同名歧义。displayEvidenceOrigin=SNAPSHOT 或 READ_TIME 明确生成时固定与历史读取时附加的区别。

input-or-relation-unknown 的中文表达为“输入信息或关联关系尚无法确定”，英文为“Input information or relationships could not be determined”；已对照工具 policy 中 kind/relation unknown 条件确认语义，不将其解释为已确认问题或安全无风险。未知码保留 code，使用通用未知解释。新页面响应、新报告和新确认导出共用同一固定投影，既有下载不重写。

## 7. 八项追踪与联调夹具

字典版本为 `report-reasons.v1`。命名空间 KB_FINDING 专属码为 `input-or-relation-unknown`；通用码包括 COMPILE_DATABASE_UNAVAILABLE、BITCODE_UNAVAILABLE、OPTIONAL_ARTIFACT_UNAVAILABLE、SOURCE_CONTEXT_UNAVAILABLE、SOURCE_CONTENT_CHANGED、FILE_TOO_LARGE、BINARY_FILE、INVALID_UTF8、SOURCE_NOT_IN_FIXED_SNAPSHOT、NOT_IN_COMPILE_DATABASE、ANALYSIS_NOT_READY、NEW_ITEM、SOURCE_VERSION_CHANGED、EVIDENCE_CHANGED、MATCH_AMBIGUOUS、LOCATION_UNVERIFIABLE、NO_MANUAL_DECISION、SAME_SOURCE_EVIDENCE。未知原因码始终保留并返回双语回退，不更改风险或证据完整性。

新报告 displayEvidence 的结构为 `{catalogVersion,items:{itemId:[说明项]},sources:{sourceType:[说明项]}}`。工作区条目的 displayEvidence 为对应说明数组，带 displayEvidenceOrigin。新确认的 snapshot 保留原有 snake_case 原始字段，仅附加 displayEvidence/displayEvidenceOrigin；历史确认快照及下载不更新。

| 交接项 | 设计位置 | 必备夹具/断言 |
|---|---|---|
| 1 | design 决策 1 | API/worker 重启、领取失败、重试耗尽、DB 故障后恢复、重复投递和有效租约竞争 |
| 2 | 决策 2 | 旧字段/未知字段/非法 JSON/类型及合法幂等，无创建副作用 |
| 3 | 决策 3 | 缺 run 与完整新报告混排、详情、下载、只读/写入拒绝，缺草稿生成中不误判 |
| 4 | 决策 4 | originalSnapshot/aiReview/manualDetails 各自独有关键词，隐藏 ID 反例，跨页统计 |
| 5 | 决策 5 | 配置缺失、支持/不支持/未知/过期/混合 Worker、NOT_REQUIRED、提交后能力变化 |
| 6 | 决策 6 | 第 500/501 行之外命中、多匹配/截断/Unicode/超长行、损坏制品、跨用户读取 |
| 7 | 决策 7 | 上传标签、Git 提交、缺元数据、源码切换后读取旧报告、列表批量查询 |
| 8 | 决策 8 | 已知/未知原因、双语页面与新导出一致、旧下载及 evidenceHash 不变 |

夹具只在独立本地 Docker 数据库/对象存储/队列中生成，使用临时账号和本地模拟上游。可复用 src/test/fixtures/report_handoff_acceptance.py；实际 ID、结果及摘要见 verification.md，原始脱敏响应保存在专用 .migration-reports/report-handoff/。不得提交真实凭据或把原文占位 ID 当实际验收结果。
