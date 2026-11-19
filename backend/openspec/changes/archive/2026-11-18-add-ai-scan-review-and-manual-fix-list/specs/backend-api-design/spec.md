## MODIFIED Requirements

### Requirement: 运行与 Finding 列表必须支持分页过滤排序
两个领域的列表 API SHALL 使用统一分页响应，并 SHALL 对允许的过滤和排序字段使用白名单。

#### Scenario: 查询运行列表
- **WHEN** 用户按 status、sourceRevisionId、toolVersion、创建时间范围过滤并按 createdAt、startedAt、finishedAt 或 status 排序
- **THEN** API SHALL 返回稳定分页结果和总数

#### Scenario: 查询 Finding 列表
- **WHEN** 用户按 severity、confidence、status、code、category、filePath、关键字过滤并按 severity、path、line、code 或 createdAt 排序
- **THEN** API SHALL 返回稳定分页结果
- **AND** 每条 SHALL 返回真实 includedInLatestReport；MUST NOT 返回旧 migrationInputRefId、migrationInputStatus、confirmedForNextReport，人工状态在报告复核接口查询

#### Scenario: 非法查询字段
- **WHEN** 用户传入未列入白名单的排序、过滤或分页值
- **THEN** API SHALL 返回统一参数错误
- **AND** MUST NOT 把原始字段拼接到 SQL

### Requirement: API 必须支持迁移评估报告生成和查询
后端 API SHALL 支持创建、查询和下载迁移评估报告，并 SHALL 对所有报告接口执行项目访问权限校验。

#### Scenario: 创建迁移评估报告
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/reports/evaluation`
- **THEN** 后端 SHALL 校验用户有该项目访问权限
- **AND** 请求体 MAY 包含 `sourceRevisionId`
- **AND** 未提交 `sourceRevisionId` 时后端 SHALL 使用项目当前 active source revision
- **AND** 后端 SHALL 校验源码版本属于该项目且为当前版本，要求至少一类当前有效成功分析，并固定 manifest；mode、selectionToken 和重新生成基线 SHALL 遵循报告复核契约
- **AND** 响应 SHALL 返回报告生成任务的 `taskId`、任务类型和当前状态

#### Scenario: 创建报告没有源码版本
- **WHEN** 项目没有 active source revision 且请求未提交 `sourceRevisionId`
- **THEN** 后端 SHALL 返回统一业务错误
- **AND** 后端 MUST NOT 创建报告任务

#### Scenario: 查询项目报告列表
- **WHEN** 用户调用 `GET /api/projects/{projectId}/reports/evaluation`
- **THEN** 后端 SHALL 返回该项目的分页报告列表
- **AND** 列表接口 SHALL 支持按 `sourceRevisionId`、`status`、`riskLevel` 和时间范围过滤
- **AND** 列表接口 SHALL 支持按 `generatedAt`、`createdAt`、`status` 和 `riskLevel` 排序
- **AND** 响应 MUST NOT 返回完整报告正文、对象存储内部 key、`htmlPath`、`jsonPath` 或其他服务器真实路径

#### Scenario: 查询最新迁移评估报告
- **WHEN** 用户调用 `GET /api/projects/{projectId}/reports/evaluation/latest`
- **THEN** 后端 SHALL 分别返回仍有效的 currentReport、latestGeneration 和 currentFixList，不能以最近失败或进行中的任务遮蔽当前报告
- **AND** 请求 MAY 支持 `sourceRevisionId` 过滤
- **AND** 没有报告时 SHALL 返回 currentReport=null 及明确原因；最新任务和当前清单分别表达

#### Scenario: 查询报告详情
- **WHEN** 用户调用 `GET /api/reports/{reportId}`
- **THEN** 后端 SHALL 通过 reportId 反查所属项目并校验访问权限
- **AND** 响应 SHALL 包含报告 ID、项目 ID、源码版本 ID、任务 ID、类型、状态、标题、摘要、总体风险、源码版本摘要、制品摘要、生成时间和创建时间
- **AND** 若存在上一份可比较历史报告，响应 SHOULD 包含 latest comparison 入口摘要或 base report 摘要
- **AND** 响应 MUST NOT 返回服务器真实文件路径、bucket 或 object key

#### Scenario: 查询报告输入快照
- **WHEN** 用户调用 `GET /api/reports/{reportId}/inputs`
- **THEN** 后端 SHALL 返回该报告使用的输入快照分页列表
- **AND** 列表接口 SHALL 支持按 `inputType` 和关键字过滤
- **AND** 列表接口 SHALL 支持按 `createdAt`、`inputType` 和快照内风险展示字段排序

#### Scenario: 下载报告
- **WHEN** 用户调用 `GET /api/reports/{reportId}/download?format=HTML` 或 `format=JSON`
- **THEN** 后端 SHALL 校验报告所属项目访问权限
- **AND** 后端 SHALL 从新流程保存的受控对象存储制品中返回对应格式的报告内容
- **AND** 后端 MUST NOT 支持未明确允许的格式
- **AND** 响应 MUST NOT 暴露对象存储 bucket、object key 或服务器真实路径

#### Scenario: 普通用户不能访问其他项目报告
- **WHEN** 普通用户通过 reportId 访问其他用户项目的报告详情、输入快照或下载接口
- **THEN** 后端 SHALL 返回 `FORBIDDEN` 或等效访问拒绝错误
- **AND** 后端 MUST NOT 泄露报告是否存在于其他项目


### Requirement: API 必须提供报告比较入口
后端 API SHALL 将评估报告历史查看和报告比较作为报告页面的主流程能力，并 SHALL 保留源码复检 API 作为兼容入口。

#### Scenario: 创建报告比较
- **WHEN** 前端在报告页面发起当前报告与历史报告比较
- **THEN** 前端 SHALL 调用 `POST /api/reports/{reportId}/comparisons`
- **AND** 请求体 MAY 包含 `baseReportId` 和 `basis`；basis 默认 REPORT_FACTS，CONFIRMED_FIX_LIST 必须提供明确两侧 confirmationId
- **AND** 未传入 `baseReportId` 时后端 SHALL 自动选择上一份可比较历史报告
- **AND** 响应 SHALL 返回比较运行 ID、任务 ID、状态、base report 摘要和 target report 摘要

#### Scenario: 查询报告比较结果
- **WHEN** 前端查看报告比较结果
- **THEN** 前端 SHALL 能调用 `GET /api/report-comparisons/{comparisonId}` 获取比较摘要
- **AND** 前端 SHALL 能调用 `GET /api/report-comparisons/{comparisonId}/diff-items` 获取分页差异项
- **AND** 前端 SHALL 能调用 `GET /api/report-comparisons/{comparisonId}/diff-items/{diffItemId}` 获取差异详情
- **AND** 差异列表接口 SHALL 支持分页、排序和过滤

#### Scenario: 源码比较 API 作为兼容能力保留
- **WHEN** 文档描述 `GET /api/projects/{projectId}/source/compare/latest`、`POST /api/projects/{projectId}/rechecks` 或 `POST /api/projects/{projectId}/rechecks/latest`
- **THEN** API 设计 SHALL 标记这些接口为兼容能力
- **AND** 新主流程 SHALL 引导前端使用报告比较 API


### Requirement: KB finding API 必须返回 schema v2 优先结构
KB finding 列表和详情 API SHALL 以 schema v2 的领域结构作为主要返回合同，不再以 v1 扁平字段作为默认合同。

#### Scenario: 分页查询 finding 列表
- **WHEN** 前端分页查询某个项目的 KB finding 列表
- **THEN** 后端 SHALL 返回可分页、可排序、可过滤的记录
- **AND** 每条记录 SHALL 包含用于列表展示的标题、风险等级、类型、来源、位置摘要和报告纳入状态
- **AND** 每条记录 SHOULD 包含 v2 `fact` 和 `conclusion` 的摘要字段
- **AND** 后端 MUST NOT 要求前端依赖 v1 的 `reason`、`suggestion`、`trace` 扁平结构

#### Scenario: 查询 finding 详情
- **WHEN** 前端查询特定 KB finding 详情
- **THEN** 后端 SHALL 返回完整 v2 finding payload
- **AND** 返回内容 SHALL 包含 `fact`、`conclusion`、`occurrences` 和 `evidenceChain`
- **AND** 返回内容 SHALL 包含真实报告纳入状态；旧节点人工确认字段 SHALL 删除；MUST NOT 动态附加旧 AI 扩展建议，人工复核状态 SHALL 从具体报告草稿读取

#### Scenario: 查询 finding 代码上下文
- **WHEN** 前端查询特定 finding 的代码上下文
- **THEN** 后端 SHALL 使用 v2 primary location 或导入投影位置定位源码
- **AND** 后端 SHALL 返回目标代码行上下各 20 行以内的上下文
- **AND** 返回内容 SHALL 包含每行行号、代码文本和是否高亮
- **AND** 当源码文件不存在或路径越界时 SHALL 返回明确错误


### Requirement: 个人模型配置 API 必须按认证所有者授权
后端 API SHALL 为已认证配置所有者提供模型 profile 管理、安全 agent runtime 目录，以及 `TEST_LOG_COMPARE`、`REPORT_AI_REVIEW` 两个阶段的个人模型/agent 绑定接口。旧 `codex-provider` 单例配置 API SHALL NOT 继续作为管理入口保留，受控业务阶段的模型和 agent 选择 SHALL 以个人模型 profile、平台 agent runtime 和个人阶段绑定为事实来源。

#### Scenario: 查询模型 profile 列表
- **WHEN** 已认证配置所有者调用 `GET /api/me/model-profiles`
- **THEN** API SHALL 返回分页模型 profile 列表
- **AND** 响应 SHALL 包含 `id`、`name`、`enabled`、`modelProviderType`、`baseUrl`、`model`、`wireApi`、`timeoutSeconds`、`maxRuntimeSeconds`、`apiKeyConfigured`、`apiKeyMasked`、`lastTestStatus`、`lastTestMessage`、`lastTestAgentType`、`lastTestDetails`、`lastTestedAt`、`createdAt` 和 `updatedAt`
- **AND** 响应 MUST NOT 返回 API key 明文或密文

#### Scenario: 创建或更新模型 profile
- **WHEN** 已认证配置所有者调用 `POST /api/me/model-profiles` 或 `PATCH /api/me/model-profiles/{profileId}`
- **THEN** API SHALL 校验并保存模型 profile
- **AND** `modelProviderType` SHALL 只允许 `OPENAI_COMPATIBLE`
- **AND** `wireApi` SHALL 只允许 `RESPONSES` 或 `CHAT`
- **AND** `baseUrl` SHALL 只允许 `http` 或 `https` URL
- **AND** `model` SHALL 为非空字符串
- **AND** `timeoutSeconds` 和 `maxRuntimeSeconds` SHALL 在后端定义的安全范围内
- **AND** API MUST NOT 接受未声明的任意配置字段
- **AND** 个人 API MUST NOT 接受 `executablePath` 或任意命令覆盖

#### Scenario: 保存模型 profile API key
- **WHEN** 已认证配置所有者保存模型 profile 且请求包含非空 `apiKey`
- **THEN** 后端 SHALL 加密保存 API key
- **AND** API 响应 MUST NOT 返回 API key 明文或密文

#### Scenario: 保留或清空模型 profile API key
- **WHEN** 已认证配置所有者更新模型 profile 且请求不包含 `apiKey`
- **THEN** 后端 SHALL 保留已有 API key
- **WHEN** 已认证配置所有者更新模型 profile 且 `apiKey` 为空字符串
- **THEN** 后端 SHALL 清空已有 API key

#### Scenario: 测试模型 profile
- **WHEN** 已认证配置所有者调用 `POST /api/me/model-profiles/{profileId}:test`
- **THEN** API SHALL 使用已保存配置或请求中的临时覆盖配置执行最小可用性测试
- **AND** 请求 SHALL 支持指定 `agentRuntimeId`
- **AND** 未指定 `agentRuntimeId` 时后端 SHALL 使用本人的 `TEST_LOG_COMPARE` 当前绑定的 agent runtime 或默认受管 Codex runtime
- **AND** 临时覆盖配置中的 API key MUST NOT 写入已保存 profile；跨 worker 暂存 SHALL 加密、限定当前执行并在终态清理
- **AND** 响应 SHALL 返回成功或失败状态、agent 类型、agent runtime ID、分项 probe、脱敏消息和测试时间
- **AND** 后端 SHALL 记录最近一次测试状态、脱敏消息、agent 类型、agent runtime ID、分项结果和测试时间

#### Scenario: 查询和保存阶段模型绑定
- **WHEN** 已认证配置所有者调用 `GET /api/me/model-stage-assignments` 或 `PATCH /api/me/model-stage-assignments`
- **THEN** API SHALL 返回或保存当前用户两个受控阶段到本人模型 profile 和平台 agent runtime 的绑定
- **AND** 阶段名称 SHALL 使用后端白名单枚举，当前允许 `TEST_LOG_COMPARE` 和 `REPORT_AI_REVIEW`
- **AND** 绑定目标 SHALL 为本人已启用模型 profile 和已启用 agent runtime
- **AND** 响应 MUST NOT 返回 API key 明文、密文、完整 agent 命令或完整 prompt

#### Scenario: 跨用户访问模型配置
- **WHEN** 任一已认证用户（包括 admin）使用他人 profile 或绑定目标访问个人接口
- **THEN** 资源访问 SHALL 返回与不存在资源一致的 404；跨用户绑定 SHALL 整体拒绝，不产生上游调用
- **AND** 后端 MUST NOT 泄漏任何 provider secret、agent 命令或内部执行细节

#### Scenario: 模型配置 API 不直接暴露模型调用能力
- **WHEN** 前端配置模型 profile、agent runtime 和阶段绑定
- **THEN** 后端 SHALL 只保存、查询、测试配置以及为测试结果比较和报告 AI 复核提供个人阶段解析
- **AND** 后端 MUST NOT 通过配置 API 提供任意提示词执行或直接 LLM 调用能力

#### Scenario: 普通用户和 admin 使用个人配置
- **WHEN** 已认证且账号有效的普通用户或 admin 管理自己的模型配置
- **THEN** API SHALL 从认证主体确定 owner，限定列表、计数和全部操作，拒绝客户端覆盖身份
- **AND** 同一所有者内名称 SHALL 大小写不敏感唯一，不同用户可以同名

## REMOVED Requirements

### Requirement: API 必须提供迁移输入列表和详情
**Reason**: 开发阶段弃用旧数据，删除旧确认池及兼容路径。
**Migration**: 使用新报告输入、草稿和确认快照；旧开发实例全新初始化，不转换旧记录。

### Requirement: API 必须支持迁移输入批量导入和自动合并
**Reason**: 开发阶段弃用旧数据，删除旧确认池及兼容路径。
**Migration**: 使用新报告输入、草稿和确认快照；旧开发实例全新初始化，不转换旧记录。

### Requirement: API 报告生成必须只消费确认后的迁移输入
**Reason**: 生成后复核取代生成前确认，四来源候选与当前草稿/确认分开。
**Migration**: 按本能力新增契约及 api-contract.md 扩展原报告路径；新流程历史读取保持原版本，变更前数据不兼容。

### Requirement: API 必须提供评估报告页 readiness
**Reason**: 生成后复核取代生成前确认，四来源候选与当前草稿/确认分开。
**Migration**: 按本能力新增契约及 api-contract.md 扩展原报告路径；新流程历史读取保持原版本，变更前数据不兼容。

### Requirement: API 必须提供评估报告输入预览
**Reason**: 生成后复核取代生成前确认，四来源候选与当前草稿/确认分开。
**Migration**: 按本能力新增契约及 api-contract.md 扩展原报告路径；新流程历史读取保持原版本，变更前数据不兼容。

### Requirement: API 报告详情必须返回当前输入摘要
**Reason**: 生成后复核取代生成前确认，四来源候选与当前草稿/确认分开。
**Migration**: 按本能力新增契约及 api-contract.md 扩展原报告路径；新流程历史读取保持原版本，变更前数据不兼容。

## ADDED Requirements

### Requirement: 报告 API 必须统一候选模式和当前状态
后端 SHALL 扩展现有 reports/evaluation 创建、readiness、input-preview、history/latest 和报告详情，返回四类有效候选、生成模式、个人 AI 资格、当前报告/最近任务/清单状态。

#### Scenario: 有效来源与模式
- **WHEN** 请求生成或预览
- **THEN** SHALL 至少一类有效成功分析，普通与 AI 资格分开；mode 必填，缺失返回参数错误，不能静默切换用户指定 AI。

#### Scenario: 当前与历史
- **WHEN** 查询 latest 或历史详情
- **THEN** SHALL currentReport/latestGeneration 分开，历史内容不重算；固定摘要与草稿/确认摘要分开，接口无真实路径/秘密。

#### Scenario: 阶段 readiness
- **WHEN** 查询项目 report 阶段
- **THEN** SHALL 返回来源资格与清单确认事实，保留原 status 枚举及无 UI 按钮约束；不能以 INCLUDED_FINDINGS 默认满足代替分析资格。

### Requirement: 报告 API 必须提供统一处置框选和确认契约
后端 SHALL 提供 review-items 的 GET/详情/POST/PATCH、已知 source-files/context、fix-list-confirmations 创建/分页/详情；复用任务/SSE 和受控下载，不按每个处置动作拆接口。

#### Scenario: 一条多条同接口
- **WHEN** 客户端提交 PATCH items
- **THEN** SHALL 按同一版本/权限/原子语义执行 INCLUDE/EXCLUDE/RESTORE，返回草稿版本、全局统计和确认可用性。

#### Scenario: 框选与确认
- **WHEN** 新增人工问题或确认草稿
- **THEN** SHALL 校验源码 hash/range 或全局待复核数量，拒绝越权/历史写入，支持空清单；读历史确认不读取当前草稿。

#### Scenario: 完整列表
- **WHEN** 列表超过一页或带文件过滤
- **THEN** SHALL 提供稳定分页筛选排序、全局与过滤统计、版本冲突；依赖无源码仍在全局问题中。

#### Scenario: 对接文档
- **WHEN** 交付后端
- **THEN** SHALL 固化请求响应、双语说明、错误码、任务恢复、Context/权限、限制和可重复联调夹具，前端本轮不修改。
