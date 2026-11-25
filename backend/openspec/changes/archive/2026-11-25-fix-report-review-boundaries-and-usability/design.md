## Context

本设计依据 2026-11-25 前端交接清单和 dev 85cba71 的只读核查；清单中的运行结果是交接方证据，本轮提案未重新执行复现。所有路径相对 backend 根；涉及完整 Compose 的命令从仓库根运行。

当前 WorkerHeartbeatService 已先提交注册，再通过 WorkerConcurrencyController 启动 autoStartup=false 的 TaskWorkerListener；不能把这项修复重复描述为尚未实现。TaskWorkerService.process 的 claim 位于 handler 异常收尾之外，监听器耗尽重试只 nack 到死信；TaskDispatchRepairer 只修复 PENDING，WorkerCleanupItemService 主要收敛已领取任务，因此领取前失败仍缺闭环。ReportGenerateRequest 拒绝未知字段，但 GlobalExceptionHandler 没有相应 JSON 解析异常映射。

ReportService.response 无条件调用 metadata，runSummary 缺失会抛异常。ReviewItemQuery 只搜索 title/titleEn/relativePath。报告 readiness 只检查个人配置，context 每请求读取整个固定制品后校验并截取。ReportResponse 缺少源码版本展示字段，证据原因缺少统一说明投影。

## Goals / Non-Goals

**Goals:** 在一个 change 内覆盖交接八项；先复验、修复已知异常，再完成查询、提示和展示增强；给出前端可直接联调的契约及固定夹具；保持已有报告、草稿、确认清单和个人配置语义。

**Non-Goals:** 不修改前端、工具源码和远程环境；不新增 Compose 基础设施；不恢复旧请求字段、旧报告协议或旧任务；不建设旧数据迁移与 backfill；不扩展全源码 AI 审计，不绕过沙箱，不把配置成功等同运行成功；不提交或推送代码作为本提案的隐含动作。

## Decisions

### 1. 公共任务领取失败与死信收敛

沿用先注册后消费，增加领取阶段可分类的暂时错误与稳定诊断。正常有限重试继续使用既有队列策略，预算耗尽后以 TASK_DELIVERY_EXHAUSTED 明确终止仍未领取的任务，而非无限重投。收尾复用 TaskDomainFinalizationService，使报告运行、活动生成占位、任务事件及敏感临时数据一起收敛，已有有效报告不被覆盖。

数据库事务内锁 task，再核查 active handle，锁顺序与现有领取/清理一致。只有仍为 PENDING/QUEUED 且不存在有效领取者时才能失败；RUNNING 或已终态的重复消息不得终止有效执行或追加重复终态事件。提交后才 ack 或确认最终处理，提交成功但 ack 丢失必须幂等。

不能仅在异常 catch 中 best-effort 更新数据库：数据库当时也可能不可用。为现有持久 DLQ 增加有界的恢复消费者/轮询收敛服务，按固定批次、退避和单轮上限处理可识别 taskId；成功提交收尾才确认死信。数据库不可用或进程中断时保留消息，恢复后继续，避免热循环。未知/损坏消息保留诊断并隔离处理，不能据不可信载荷修改任意任务；任务事实仍取数据库。部署状态下不主动消费历史真实死信，本轮仅验收专用队列。

不按 QUEUED 等待时长直接失败：Worker 暂停或繁忙时排队是合法行为。新增恢复设施只处理有明确失败投递证据的消息。清单故障场景须包含 API 重启，不以已有“停 Worker 再启动”记录替代。

### 2. 在请求绑定边界统一报告错误

针对报告生成处理 HttpMessageNotReadableException，包括 unknown setter、损坏 JSON、非法 UUID/枚举/类型、空请求；对缺少必要业务字段继续复用语义校验。返回 400 REPORT_REQUEST_INVALID、可读双语说明及 traceId，不回显请求体、堆栈或内部类名。识别报告入口使用框架 handler 信息或限定的异常处理范围，不用全局一刀切的报告错误码影响其他接口。

未知字段继续拒绝；不引入 useAiEnhancement 兼容。绑定失败不得调用任务创建、占用 Idempotency-Key 或投递；合法同 key 同内容返回原任务，同 key 异内容仍 409。用 HTTP/MVC 绑定测试覆盖，不能只测试 rejectUnknown 方法。

### 3. 缺少复核数据的有限读取容错

先在独立库用当前 schema 构造 reports 有记录而 report_review_runs 缺失的夹具，与新流程完整报告混排复验；不运行旧版迁移脚本修改现有库。预期复验 orElseThrow 失败后，将摘要读取改为显式 Optional/缺失处理。

范围仅限当前 ReportView 能正常解码的基础报告：列表/详情仍可读，review=null，reviewAvailable=false，reviewUnavailableReasonCode=REPORT_REVIEW_UNAVAILABLE，提供双语原因。既有原始制品存在且通过权限检查时仍可下载。缺少复核数据的报告不进入当前有效报告/确认选择，不可修改、确认或作为人工继承基线；依赖 manifest 的工作区接口返回 409 REPORT_REVIEW_UNAVAILABLE，不伪造空列表、零待复核或空确认。若作为当前指针被发现，暴露明确诊断，不偷偷回退历史确认。

正常新报告仍在同一事务创建 reports/run；缺失属于完整性异常，记录脱敏结构化诊断。普通读取不得写草稿、补 run、迁移状态或触发生成。更新 report-review-transition 中“无旧数据读取层”要求，仅允许这一个缺失摘要的安全读取分支，不扩大到旧 JSON 解析适配或历史任务恢复。

### 4. 展示正文搜索

建立共享的 ReviewDisplayTextExtractor，按已知来源协议提取 title/titleEn/relativePath、originalSnapshot 的描述/证据说明/建议、aiReview 的判断依据、manualDetails 的说明/修复原因，以及当前人工排除说明的中英文正文。实现时在 api-contract.md 补全各来源实际键路径，未知结构不遍历所有字符串，不把 id、hash、actor、内部路径、隐藏元数据当正文。

使用 Locale.ROOT 的大小写无关字面包含，trim 关键词，长度最多 256 UTF-16 单元，空白等于不筛选。先过滤再稳定排序、计数、分页；filteredSummary 对完整匹配集合统计，globalSummary 不受关键词影响。ReportInputPreview 若复用同一查询器，投影其实际存在的扫描正文；它没有 AI/人工内容时不伪造这些字段。搜索不会触发 AI，不改变处置状态。

### 5. 配置就绪与 Worker 执行能力分离

个人配置判定保持现状：当前触发者 REPORT_AI_REVIEW 绑定、启用状态及必填配置，不要求历史 probe 成功。新增 aiConfigurationReady 与独立 aiExecutionCapability，aiBlockers 扩展为带 category 的原因数组，保留既有 code/message/messageEn/recoveryAction 字段。

Worker 在实际执行 UID/容器/安全策略下以无凭据子进程执行有超时的本地沙箱能力自检，复用 backend 所有的沙箱 helper；不调用模型、不启动真实业务 Agent，不修改 KB 等工具源码。结果随现有心跳上报，绑定 runtime/版本标识、policyVersion、observedAt 和有效期。API 仅读缓存，不在 readiness GET 发起探测；不返回 Worker 主机名或内部路径。

仅聚合同一任务队列、启用且有并发容量资格的在线 Worker 对当前 runtime 的有效观测（瞬时忙碌不等于无能力）：全部支持为 AVAILABLE，全部明确缺失为 UNAVAILABLE；无在线 Worker、观测过期/缺失、版本不符或支持情况混合为 UNKNOWN。混合不能因某台可用就承诺共享队列一定分配成功，也不因为某台不可用而一概阻断；返回双语 warning。暂不引入按能力分队列的调度。

有实际 AI 输入时，已知 UNAVAILABLE 进入 aiBlockers 并使 canGenerateAi=false；UNKNOWN 不阻止排队，但说明尚不能确认运行能力；AVAILABLE 也不保证上游 API 可达。无实际 AI 输入时 executionRequired=false，能力标为 NOT_REQUIRED，仍校验个人配置，保持零调用。推荐模式与 canGenerateAi 一致。创建时用相同 resolver 重新检查，已知不可用返回 409 REPORT_AI_SANDBOX_UNAVAILABLE；Worker 执行时仍做真实隔离校验，能力变化按原运行错误失败，不能自动降级 STANDARD。

通用模型配置接口继续仅表达配置；报告 stage readiness/overview/latest 不以 AI 沙箱限制阻断 STANDARD 或已有报告。配置失败的恢复动作指向个人配置，执行失败指向 Worker 环境，不能统称“请配置模型”。

### 6. 报告固定文件全文搜索

新增 GET /api/reports/{reportId}/source-files/{fileKey}/search，仅在报告 manifest 指定文件和固定制品内搜索。参数、响应及错误语义见 api-contract.md。一次请求读入并验证完整内容摘要后扫描，复用 context 的权限、编码/大小/路径/符号链接和内容一致性约束；不使用项目当前工作目录。

首版单行字面搜索，不支持正则或跨行模式；支持大小写开关，返回 1-based 绝对行号、UTF-16 列及有限上下文，结果有上限和 truncated。定位长度按原文计算，大小写比较不能改变原文列坐标。读取并校验完整文件后可只扫描到 limit+1 个匹配以判定截断；不承诺总匹配数。限制查询、结果数和片段长度，不绕过已有文件大小上限，不引入跨用户共享缓存。

有效文件无匹配为 available=true + matches=[]；内容变更、制品缺失等为 available=false + 双语原因，不能伪装无匹配。非法/越权 fileKey 延续既有 404 隐藏策略。现有 context 500 行上限不变。用同一约 4.8 MB/5 万行合成文件比较实际扫描整个文件的分段请求与单次搜索，同时记录清单的九段局部读取对照；报告实际请求数/耗时，不复用交接方的 65/68ms 作为当前结论。

### 7. 固定源码展示元数据

新报告创建时保存对应 sourceRevisionId 的 versionLabel/gitCommit 展示快照，列表与详情共用投影为 sourceVersionLabel/sourceGitCommit；不纳入分析证据身份或导致 Context 失效。历史未保存快照时，允许批量关联同一个 sourceRevisionId 的持久版本元数据，缺失返回 null，不读取 active 版本或猜测 Git HEAD。明确返回 sourceMetadataOrigin=SNAPSHOT/REVISION_RECORD/UNAVAILABLE。确认与既有原始下载不重写；后续新报告/新确认按其固定版本渲染。

### 8. 双语原因投影与不可变事实

建立后台共享 ReasonDescriptionResolver，按来源命名空间及 reasonCode 返回 code/message/messageEn/catalogVersion。至少覆盖交接示例 input-or-relation-unknown 以及当前报告会展示的缺失构建证据、源码不可读、部分覆盖、继承未生效原因；具体枚举从当前生产协议提取，文档列出，不凭名称捏造工具语义。未知码保留 raw code，使用“未提供该原因码的解释 / No description is available for this reason code”。

不修改 originalSnapshot 原始结构或 evidenceHash，不让翻译更新造成重新生成时人工决定失效。新报告在单独 displayEvidence 快照中保存标准双语投影与字典版本，工作区、HTML/JSON 及新确认消费同一快照。已有不可变下载保持原字节；缺少 displayEvidence 的历史接口可附带明确标记为 READ_TIME 的展示解释，不能声称这是旧生成时的结论，不能重新渲染覆盖旧制品。搜索使用同一展示正文提取规则。

## Risks / Trade-offs

- 数据库与 RabbitMQ 无跨系统原子事务 → DB 状态为准、提交后 ack、持久死信恢复及重复收尾幂等；模拟每个崩溃窗口。
- 共享队列的 Worker 能力可能异构或过期 → UNKNOWN 提示、创建/执行复查，不假装具备能力路由。
- 缺失复核数据被误判为正常旧报告 → 明确 unavailable 状态与完整性诊断，禁止写和下游消费，不自动修复数据。
- 全文件搜索仍有单次 O(file size) 成本 → 保留文件大小预算，限制输出、禁止正则，压测请求次数与内存而非承诺任意大文件常数时间。
- 新双语说明影响不可变历史 → 事实与展示投影分开版本化，旧下载字节不变。
- 质量缺口超出受影响范围 → 如实记录覆盖率差距，不能缩小统计范围或擅自重构无关模块。

## Migration Plan

实施复核后无需 DDL：复用 worker_instances.metadata 的 reportAi/taskQueue、reports.summary 的 sourceMetadata 和 report_review_runs.manifest 的独立展示快照。缺字段安全默认 UNKNOWN/READ_TIME/UNAVAILABLE，不强制初始化数据库，不回填历史报告事实。先升级可读新字段的 API，再升级 worker 并等待能力心跳；旧 worker 缺能力记录按 UNKNOWN。切换只在独立 Compose 演练，不操作真实 dev/test。

恢复轮询固定每 30 秒执行，最多 50 条、2 秒轮次预算（单条 DB/确认等待不被强行中断）；DB/传输故障下一轮重试。无法核对业务身份或损坏的载荷转入既有 RabbitMQ 内 `<deadLetterQueue>.invalid` 持久隔离队列，publisher confirm 成功后才 ack 原消息；确认最多等待 5 秒，失败保留原消息。该隔离队列不是新增 Compose 服务。领取恢复与正常领取同样锁定 task，确认既有活动 handle，事务提交后才确认死信；已运行或终态任务只确认重复消息。

Worker 观测缓存 30 秒，真实探测时间到期 90 秒，外部子进程 10 秒上限。runtime 身份包含 id/type/executablePath/defaultArgs/config 的摘要和 `report-sandbox.v1` 策略版本；这是沙箱执行前提观测，不是 CLI 启动、模型网络或兼容性测试成功证明。已禁用 runtime 不发布，忙碌 Worker 仍属于有资格集合；无容量、离线及其他队列被排除。

回退保留新增可空字段和既有业务记录，检查旧程序可读性，不通过删除数据库回滚。任务状态收敛和新的不可变报告事实不逆向重写；坏版本停用新增恢复调度后诊断，消息仍可靠保留。

## Open Questions

无阻塞性产品决策。实施首项复验需确认历史缺失记录的具体异常、各来源正文键路径、当前队列确认配置和本地沙箱可用性，并记录实际发现；如与契约冲突，先更新本 change 文档再实现，不扩大前端或远程范围。
