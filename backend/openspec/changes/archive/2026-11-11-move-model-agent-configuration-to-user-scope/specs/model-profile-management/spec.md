## MODIFIED Requirements

### Requirement: 模型 Profile 管理必须支持多个模型配置
后端 SHALL 提供已认证配置所有者模型 profile 管理能力，用于维护多个可供阶段绑定使用的 OpenAI-compatible 模型配置。

#### Scenario: 创建模型 profile
- **WHEN** 已认证配置所有者调用模型 profile 创建 API 并提交名称、provider 类型、base URL、模型名、wire API、超时配置和 API key
- **THEN** 后端 SHALL 创建模型 profile
- **AND** 后端 SHALL 校验 profile 名称在同一所有者内大小写不敏感唯一
- **AND** 后端 SHALL 校验 provider 类型只允许 `OPENAI_COMPATIBLE`
- **AND** 后端 SHALL 校验 wire API 只允许 `RESPONSES` 或 `CHAT`
- **AND** 后端 SHALL 加密保存 API key
- **AND** API 响应 MUST NOT 返回 API key 明文或密文

#### Scenario: 查询模型 profile 列表
- **WHEN** 已认证配置所有者调用模型 profile 列表 API
- **THEN** 后端 SHALL 返回分页列表
- **AND** 列表项 SHALL 包含 profile ID、名称、启用状态、provider 类型、base URL、模型名、wire API、API key 配置状态、最近测试状态、最近测试时间、创建时间和更新时间
- **AND** 列表接口 SHALL 支持按启用状态、provider 类型、测试状态和关键字过滤
- **AND** 列表接口 SHALL 支持按名称、创建时间、更新时间和最近测试时间排序

#### Scenario: 更新模型 profile
- **WHEN** 已认证配置所有者更新模型 profile
- **THEN** 后端 SHALL 只允许更新名称、启用状态、连接参数、模型名、wire API、超时配置和 API key
- **AND** 如果请求未提交 API key 字段，后端 SHALL 保留已有 API key
- **AND** 如果请求提交空 API key，后端 SHALL 清空已有 API key
- **AND** API 响应 MUST NOT 返回 API key 明文或密文

#### Scenario: 删除模型 profile
- **WHEN** 已认证配置所有者删除模型 profile
- **THEN** 后端 SHALL 在该 profile 未被任何阶段绑定时允许删除或软删除
- **AND** 如果 profile 已被阶段绑定，后端 SHALL 拒绝删除并返回明确业务错误

#### Scenario: 无权访问他人的模型 profile
- **WHEN** 已认证用户使用他人 profile ID 调用详情、更新、删除或测试 API
- **THEN** 后端 SHALL 返回与不存在资源一致的 404
- **AND** 响应 MUST NOT 泄漏 API key、密文或内部执行细节

#### Scenario: 普通用户管理自己的配置
- **WHEN** 普通用户或 admin 调用个人模型 API
- **THEN** 后端 SHALL 按认证用户限定列表、计数及全部操作；新记录所有者由服务端确定，不接受客户端身份覆盖

### Requirement: 模型 Profile 必须支持可用性测试
后端 SHALL 允许已认证配置所有者对已保存或临时覆盖的模型 profile 执行最小可用性测试。

#### Scenario: 测试已保存模型 profile
- **WHEN** 已认证配置所有者调用模型 profile 测试 API
- **THEN** 后端 SHALL 使用该 profile 的已保存配置运行 Codex provider 探测
- **AND** 后端 SHALL 保存最近一次测试状态、脱敏消息和测试时间
- **AND** API 响应 SHALL 返回成功或失败状态、脱敏消息和测试时间

#### Scenario: 使用临时覆盖测试模型 profile
- **WHEN** 已认证配置所有者调用模型 profile 测试 API 并提交临时覆盖配置
- **THEN** 后端 SHALL 使用覆盖后的配置执行测试
- **AND** 临时覆盖中的 API key MUST NOT 写入已保存 profile；必要跨 worker 暂存 SHALL 加密、限定本次个人执行、有有效期并在终态清理
- **AND** 测试结果中的错误信息 MUST 脱敏

#### Scenario: 测试配置不完整
- **WHEN** 模型 profile 未启用、缺少 API key、缺少 base URL 或缺少模型名
- **THEN** 后端 SHALL 返回明确测试失败或参数错误
- **AND** 响应 MUST NOT 泄漏 API key、授权头或服务器真实路径

### Requirement: 阶段模型绑定必须从已配置 Profile 中选择
后端 SHALL 提供阶段模型绑定能力，使 `TEST_LOG_COMPARE` 测试结果比较阶段和 `KB_AI_EXTENSION` 知识库 AI 扩展阶段可以选择一个已启用模型 profile 和一个已启用 agent runtime。

#### Scenario: 查询阶段模型绑定
- **WHEN** 已认证配置所有者调用阶段模型绑定查询 API
- **THEN** 后端 SHALL 返回 `TEST_LOG_COMPARE` 和 `KB_AI_EXTENSION` 阶段及其当前绑定的模型 profile 摘要和 agent runtime 摘要
- **AND** 未绑定时 SHALL 明确返回空绑定状态

#### Scenario: 保存阶段模型绑定
- **WHEN** 已认证配置所有者保存阶段模型绑定
- **THEN** 后端 SHALL 校验阶段名称属于后端白名单，当前允许 `TEST_LOG_COMPARE` 和 `KB_AI_EXTENSION`
- **AND** 后端 SHALL 校验绑定的模型 profile 属于当前用户、存在且已启用
- **AND** 后端 SHALL 校验绑定的 agent runtime 存在且已启用
- **AND** 后端 SHALL 保存当前所有者与阶段到模型 profile 和 agent runtime 的唯一绑定

#### Scenario: 阶段绑定非法
- **WHEN** 请求包含未知阶段、已禁用 profile、不存在 profile、已禁用 agent runtime、不存在 agent runtime 或重复阶段绑定
- **THEN** 后端 SHALL 返回统一参数或业务错误
- **AND** 后端 MUST NOT 保存部分非法绑定

#### Scenario: 运行时解析阶段模型
- **WHEN** 后续测试结果比较 agent runtime 需要执行模型任务
- **THEN** 后端 SHALL 创建时通过配置主体的个人阶段绑定解析模型 profile 和 agent runtime，执行时使用已持久化引用并复核归属
- **AND** 如果阶段未绑定、绑定 profile 已禁用或绑定 agent runtime 已禁用，后端 SHALL 返回明确业务错误
- **AND** 业务任务请求 MUST NOT 直接提交 base URL、API key、agent 类型、可执行路径或任意模型参数

### Requirement: 测试结果比较必须使用 TEST_LOG_COMPARE 阶段绑定
模型 profile 管理 SHALL 将 `TEST_LOG_COMPARE` 阶段绑定作为测试结果比较 agent runtime 的唯一模型和 agent 来源。

#### Scenario: 比较任务解析模型
- **WHEN** 后端准备自动创建或执行测试结果比较任务
- **THEN** 后端 SHALL 创建时通过触发者的 `TEST_LOG_COMPARE` 个人绑定解析并保存引用，执行时校验持久化主体和引用
- **AND** 后端 MUST NOT 从业务请求体读取 base URL、模型名、API key、provider 参数、agent 类型或 agent 可执行路径

#### Scenario: 比较任务模型不可用
- **WHEN** 阶段未绑定、绑定 profile 被禁用、绑定 agent runtime 被禁用或 profile 缺少必要连接信息
- **THEN** 后端 SHALL 将自动比较摘要标记为不可用或返回测试结果比较功能不可用错误
- **AND** 后端 MUST NOT 泄漏 provider secret、agent 命令或内部执行细节

### Requirement: 模型 Profile 测试必须验证指定 agent 兼容性
模型 profile 管理 SHALL 在可用性测试中验证模型是否支持指定 agent runtime 的工作模式，并 SHALL 返回 agent 类型、agent runtime ID、分项 probe 结果和聚合兼容性状态。

#### Scenario: 测试基础文本能力
- **WHEN** 已认证配置所有者测试模型 profile
- **THEN** 后端 SHALL 验证模型能够完成基础文本 JSON 输出
- **AND** 结果 SHALL 包含 `BASIC_TEXT` 或等效分项状态

#### Scenario: 测试 agent 纯文本能力
- **WHEN** 已认证配置所有者测试模型 profile 并指定 agent runtime
- **THEN** 后端 SHALL 使用该 profile 配置和指定 agent runtime 运行纯文本最小任务
- **AND** 结果 SHALL 包含 `AGENT_TEXT` 或等效分项状态
- **AND** 结果 SHALL 包含当前测试的 `agentType` 和 `agentRuntimeId`

#### Scenario: 测试 agent workspace 工具闭环
- **WHEN** 已认证配置所有者测试模型 profile 并指定 agent runtime
- **THEN** 后端 SHALL 创建临时 workspace 和测试输入文件
- **AND** 后端 SHALL 使用指定 agent runtime 要求读取该文件并返回指定 JSON
- **AND** 测试 SHALL 覆盖模型发起工具调用、agent 执行工具、工具结果回传和最终 JSON 输出的完整闭环
- **AND** 结果 SHALL 包含 `AGENT_WORKSPACE_TOOL` 或等效分项状态
- **AND** 结果 SHALL 包含当前测试的 `agentType` 和 `agentRuntimeId`

#### Scenario: 历史测试比较输出 schema 结果
- **WHEN** 配置所有者读取历史 TEST_LOG_COMPARE 专项测试结果
- **THEN** 后端 SHALL 保留历史阶段专项结果，不在新通用 probe 中执行该检查
- **AND** 原有结果包含 TEST_COMPARE_SCHEMA 时 SHALL 保持该历史事实

#### Scenario: 历史 KB AI 扩展输出 schema 结果
- **WHEN** 配置所有者读取历史 KB_AI_EXTENSION 专项测试结果
- **THEN** 后端 SHALL 保留历史阶段专项结果，不在新通用 probe 中执行该检查
- **AND** 原有结果包含 KB_AI_EXTENSION_SCHEMA 时 SHALL 保持该历史事实

#### Scenario: 返回聚合兼容性状态
- **WHEN** 模型 profile 测试完成
- **THEN** API 响应 SHALL 返回 agent 类型、agent runtime ID、基础可用状态、agent 兼容状态及通用 workspace 读取和写入状态
- **AND** 历史阶段兼容字段 SHALL 仅用于读取历史事实，不参与新通用 probe 的结果或聚合判断
- **AND** 后端 SHALL 保存最近一次测试的脱敏摘要、分项结果和测试时间
- **AND** 响应 MUST NOT 返回 API key、授权头、完整 agent 命令或服务器真实路径

#### Scenario: 基础可用但 agent 不兼容
- **WHEN** 模型基础文本测试通过但 workspace 工具闭环测试失败
- **THEN** 后端 SHALL 将基础可用状态标记为可用
- **AND** 后端 SHALL 将当前 agent 兼容状态标记为不兼容
- **AND** 脱敏消息 SHALL 明确说明该模型不能用于当前 agent runtime 工作模式

#### Scenario: 后续新增 agent 类型
- **WHEN** 后端新增非 `CODEX`、`DEEPSEEK_HARNESS` 或 `OPENCODE` 的 agent 执行器
- **THEN** 模型 profile 测试结果 SHALL 能记录该 agent 类型对应的分项 probe 结果
- **AND** 阶段绑定 SHALL 能依据该 agent 类型判断模型是否适用于对应阶段
- **AND** 后端 SHALL NOT 要求重建模型 profile 的基础数据结构

### Requirement: 模型 Profile 连通性测试必须独立于 Agent Probe
模型 profile 管理 SHALL 提供独立连通性测试能力，用于验证模型 provider 配置是否可用，且 SHALL NOT 在连通性测试中启动 agent runtime。

#### Scenario: 测试模型连通性
- **WHEN** 已认证配置所有者对模型 profile 发起连通性测试
- **THEN** 后端 SHALL 使用 profile 的 provider 类型、base URL、模型名、wire API、timeout 和 API key 执行最小模型调用
- **AND** 后端 SHALL 返回连通性状态、脱敏消息、测试时间和耗时
- **AND** 后端 SHALL 保存最近一次连通性测试摘要
- **AND** 响应和数据库 MUST NOT 保存 API key、授权头、完整 prompt 或服务器真实敏感路径

#### Scenario: 临时覆盖配置测试连通性
- **WHEN** 已认证配置所有者提交临时覆盖配置执行连通性测试
- **THEN** 后端 SHALL 使用覆盖后的配置执行测试
- **AND** 临时覆盖中的 API key MUST NOT 写入已保存 profile；必要跨 worker 暂存 SHALL 加密、限定本次个人执行、有有效期并在终态清理
- **AND** 测试结果中的错误信息 MUST 脱敏

#### Scenario: 连通性测试不启动 Agent
- **WHEN** 后端执行模型连通性测试
- **THEN** 后端 SHALL NOT 创建 agent workspace
- **AND** 后端 SHALL NOT 调用 Codex、DeepSeek Harness、OpenCode 或其他 agent runtime adapter

### Requirement: 模型 Profile Agent Probe 必须支持多 Runtime 选择
模型 profile 管理 SHALL 支持已认证配置所有者选择一个或多个 agent runtime 对模型 profile 执行阶段无关的通用兼容性 probe，并 SHALL 独立保存每个模型 Profile 与 agent runtime 组合的分项结果。

#### Scenario: 创建多 Agent Probe Run
- **WHEN** 已认证配置所有者提交 `agentRuntimeIds` 列表测试模型 profile
- **THEN** 后端 SHALL 校验列表非空、runtime 存在且启用
- **AND** 后端 SHALL 创建一次 agent probe run
- **AND** 响应 SHALL 返回 `runId`、聚合状态、所选 runtime 摘要和创建时间
- **AND** 后端 SHALL 在后台执行所选 agent runtime 的通用兼容性 probe
- **AND** 后端 MUST NOT 查询或依赖该组合是否绑定到任何业务阶段

#### Scenario: 查询 Agent Probe Run
- **WHEN** 已认证配置所有者查询 agent probe run
- **THEN** 后端 SHALL 返回 run 的聚合状态、profile 摘要、所选 runtime 摘要、每个 runtime 的通用分项 probe 结果、脱敏消息、开始时间和结束时间
- **AND** 单个 runtime 失败 SHALL 只影响该 runtime 的结果和 run 聚合状态
- **AND** 响应 MUST NOT 返回 API key、授权头、完整 agent 命令、完整 prompt 或服务器真实敏感路径

#### Scenario: 查询 Profile Agent 兼容性矩阵
- **WHEN** 已认证配置所有者查询模型 profile 详情或 agent probe 结果列表
- **THEN** 后端 SHALL 返回该 profile 对各 agent runtime 的最近通用兼容性状态
- **AND** 每个结果 SHALL 包含 agent runtime ID、agent 类型、Agent 响应状态、workspace 读取状态、workspace 写入状态、聚合兼容状态、最近测试时间和脱敏消息
- **AND** 每个结果 MUST NOT 包含测试比较、KB AI 扩展或其他业务阶段的兼容性字段

#### Scenario: 后续新增 Agent Runtime 或业务阶段
- **WHEN** 后端新增新的 agent runtime 类型或新的 Agent 业务阶段
- **THEN** 模型 profile agent probe SHALL 能为新 runtime 记录独立通用结果
- **AND** 新业务阶段 MUST NOT 要求改变模型 Agent 兼容性结果结构
- **AND** 后端 SHALL NOT 要求改变 `model_profiles` 的基础配置结构

### Requirement: 阶段模型绑定必须支持 KB_AI_EXTENSION
模型 profile 管理 SHALL 支持 `KB_AI_EXTENSION` 阶段绑定，使 KB AI 扩展扫描只能使用配置所有者配置的受控模型 profile 和 agent runtime。

#### Scenario: 查询 KB AI 扩展阶段绑定
- **WHEN** 已认证配置所有者调用阶段模型绑定查询 API
- **THEN** 响应 SHALL 包含 `KB_AI_EXTENSION` 阶段及其当前绑定的模型 profile 摘要和 agent runtime 摘要
- **AND** 未绑定时 SHALL 明确返回空绑定状态

#### Scenario: 保存 KB AI 扩展阶段绑定
- **WHEN** 已认证配置所有者保存 `KB_AI_EXTENSION` 阶段绑定
- **THEN** 后端 SHALL 校验绑定的模型 profile 属于当前用户、存在且已启用
- **AND** 后端 SHALL 校验绑定的 agent runtime 存在且已启用
- **AND** 后端 SHALL 保存当前所有者与该阶段到模型 profile 和 agent runtime 的唯一绑定

#### Scenario: KB AI 扩展解析阶段模型
- **WHEN** 后端准备执行 KB AI 扩展扫描
- **THEN** 后端 SHALL 创建时通过触发者的 `KB_AI_EXTENSION` 个人绑定解析并保存引用，执行时校验持久化主体和引用
- **AND** 业务任务请求 MUST NOT 直接提交 base URL、API key、agent 类型、可执行路径或任意模型参数

#### Scenario: KB AI 扩展阶段配置不可用
- **WHEN** 阶段未绑定、绑定 profile 已禁用、绑定 agent runtime 已禁用或 profile 缺少必要连接信息
- **THEN** 后端 SHALL 返回 KB AI 扩展功能不可用错误
- **AND** 响应 MUST NOT 泄漏 provider secret、agent 命令或内部执行细节

### Requirement: Profile 上游协议必须与 Codex 传输协议分离
模型 profile 的 `RESPONSES` / `CHAT` SHALL 保持配置所有者配置的上游协议语义；Codex Adapter SHALL 选择原生 Responses 或 worker 内受管 OpenCodex 路径，不把 CHAT 直接传给不支持该值的 Codex CLI。

#### Scenario: 原生 Responses profile
- **WHEN** Codex 执行绑定到 RESPONSES profile
- **THEN** SHALL 直接使用该 profile 的上游，以 `wire_api=responses` 调用
- **AND** MUST NOT 启动 OpenCodex 或更改已有持久化配置

#### Scenario: 仅支持 CHAT 的 profile
- **WHEN** Codex 执行绑定到 CHAT profile
- **THEN** SHALL 使用 worker 内受管 OpenCodex 的 Responses → Chat Completions 转换路径
- **AND** Codex CLI SHALL 接收 `wire_api=responses`，上游 SHALL 接收 CHAT 请求
- **AND** 持久化 wireApi SHALL 保持 CHAT，无需前端新增代理配置

#### Scenario: 连通性与兼容性分别判定
- **WHEN** 执行模型连通性测试或 Agent 兼容性测试
- **THEN** 连通性 SHALL 测试 profile 声明的原始上游协议，不启动 Agent 或 OpenCodex
- **AND** Agent 兼容性 SHALL 验证实际 Adapter 路径的文本与工具闭环
- **AND** 上游 HTTP 200 MUST NOT 单独构成 Agent 兼容通过

#### Scenario: 协议请求失败
- **WHEN** 当前协议请求出现超时、401、404、429 或 5xx
- **THEN** SHALL 返回该路径的明确失败原因
- **AND** MUST NOT 自动切换协议、上游、模型或密钥以隐藏失败

### Requirement: Profile 协议适配必须保持权限与客户端兼容
协议适配 SHALL 保留 RESPONSES/CHAT 枚举、原生与桥接路由、probe 主要状态字段和双语安全诊断；管理权限与 API 路径 SHALL 按个人化契约迁移，不能保留全局权限作为兼容后门。

#### Scenario: 未授权资源或非法绑定
- **WHEN** 用户探测他人 profile，或业务请求覆盖绑定的凭据和上游
- **THEN** 后端 SHALL 拒绝且不调用模型
- **AND** MUST NOT 泄漏密钥、完整命令、桥接 token 或内部端口配置

#### Scenario: 非 Codex Agent 回归
- **WHEN** 使用 DSH 或 OpenCode 执行既有受支持路径
- **THEN** SHALL 保留对应 Adapter 的原生调用边界与真实探测行为
- **AND** MUST NOT 强制通过 Codex 转换层

#### Scenario: 客户端迁移说明
- **WHEN** 发布个人 API 契约
- **THEN** SHALL 提供旧路径到个人路径映射、主要响应字段和权限变更说明
- **AND** 本 change MUST NOT 修改前端，MUST NOT 声称旧前端无需适配即可继续管理模型

### Requirement: Codex CHAT 探针必须在现有 worker 执行且保持客户端契约
Codex CHAT 的异步 probe run 和个人单 Agent 兼容入口 SHALL 通过现有基础设施分派到 worker；backend API 角色 SHALL 仅负责授权、分派、查询和结果归并，MUST NOT 在本进程启动 OpenCodex 或回退到自研桥接。

#### Scenario: 创建或读取多 runtime 探针
- **WHEN** run 同时包含 Codex CHAT 和其他 runtime
- **THEN** Codex CHAT SHALL 在 worker 执行，其他 Agent 保持既有路径与版本
- **AND** SHALL 保留现有 runId、状态查询、三项真实分项及聚合规则，全部所选分项达到终态后汇总
- **AND** 单个 runtime 失败 MUST NOT 覆盖其他 runtime 的真实结果或导致提前标记全部成功

#### Scenario: 个人兼容入口
- **WHEN** 个人 `POST /api/me/model-profiles/{profileId}:test` 选择 Codex CHAT
- **THEN** SHALL 复用 worker 路径并在原有有界请求语义内返回兼容格式的结果或明确失败
- **AND** 单 Agent 个人接口 SHALL 保持有界同步等待语义，不要求客户端配置代理；旧全局路径的路由和专用权限规则 SHALL 删除，不保留兼容或迁移响应接口
- **AND** 等待超时 SHALL 过期或取消对应执行，不能留下无主后台调用

#### Scenario: 重复投递和迟到结果
- **WHEN** 内部任务重复投递、worker 失联恢复或旧所有者迟到回报
- **THEN** SHALL 通过持久执行标识、原子领取、租约/截止时间和幂等终态写入防止重复执行及覆盖正确结果
- **AND** 重试 SHALL 有界并遵守剩余预算，不得无限重新调用模型
- **AND** 消息 SHALL 仅包含执行引用与非敏感元数据，不含明文 API key、完整 prompt 或模型凭据

#### Scenario: worker 不可用或任务过期
- **WHEN** worker 不可用、消息发布失败、领取/执行租约过期或代理启动失败
- **THEN** SHALL 在规定预算内得到明确失败或可查询的待执行状态，并最终收敛为终态
- **AND** MUST NOT 永久保持 RUNNING、伪造探针通过或在 backend 启动备用代理
- **AND** 公共诊断 SHALL 有稳定原因码，新增展示说明同时包含中文和英文字段

#### Scenario: Probe 身份恢复
- **WHEN** worker 领取或恢复个人 probe job
- **THEN** SHALL 校验持久化配置主体、profile 所有者和账号状态；结果归并 SHALL 保持相同归属，不能回写全局 runtime 个人测试摘要

### Requirement: 动态 profile 必须按执行快照确定生效时机
Codex CHAT 调用 SHALL 在 worker 领取时重新检查 profile/runtime 有效性并冻结配置；执行后配置变更 MUST NOT 导致当前执行串用模型、URL 或凭据。

#### Scenario: 排队期间更新或禁用
- **WHEN** job 领取前 profile 被更新、禁用或删除
- **THEN** 更新后的有效配置 SHALL 用于本次领取；禁用/删除 SHALL 明确失败且不发起上游请求
- **AND** 已绑定 profile 的既有删除限制 SHALL 保持

#### Scenario: 执行期间更新或删除
- **WHEN** worker 已冻结快照并开始执行后 profile 更新、禁用或删除
- **THEN** 本次执行 SHALL 使用原快照直到完成或由取消机制终止，新执行 SHALL 使用新配置或拒绝失效 profile
- **AND** 不承诺更新/删除自动终止全部在途执行，MUST NOT 在同一执行中混用新旧密钥

#### Scenario: 临时覆盖与历史结果
- **WHEN** 兼容测试使用临时覆盖配置或重新验证新版代理
- **THEN** 覆盖 SHALL 仅属于本次执行，必要的跨 worker 暂存 SHALL 加密、有有效期并清理，不修改已保存 profile
- **AND** SHALL 创建新的真实探针结果，不修改历史失败结果或回填未证实原因

#### Scenario: 个人归属与固定引用
- **WHEN** worker 领取或重试任务
- **THEN** SHALL 复核已存配置主体与 profile 归属并保持已选 profile/runtime 引用；阶段改绑 MUST NOT 改变已排队任务的选择
- **AND** 账号禁用、删除或项目执行资格丧失 SHALL 在调用上游前明确失败

## REMOVED Requirements

### Requirement: 模型 Profile 测试旧接口必须作为兼容入口
**Reason**: 个人化后必须删除原 admin 模型相关 API，不能继续保留旧全局测试入口、别名或迁移响应处理器。
**Migration**: 客户端另行适配个人单 Agent、连通性和多 runtime 测试 API；旧路径从 Controller、专用安全规则及当前 OpenAPI 定义中删除。历史规范与审计保留，前端修改不属于本 change。

## ADDED Requirements

### Requirement: 个人模型测试必须提供受归属约束的测试入口
后端 SHALL 通过 `POST /api/me/model-profiles/{profileId}:test` 提供个人单 Agent 测试，并通过个人连通性、多 runtime probe run API 提供独立能力测试；MUST NOT 保留 `/api/admin/model-profiles/{profileId}:test`。

#### Scenario: 个人单 Agent 调用
- **WHEN** 配置所有者调用个人单 Agent 测试入口
- **THEN** 后端 SHALL 使用指定 runtime、本人的阶段 runtime 或平台默认受管 runtime 返回既有主要格式结果
- **AND** MUST NOT 回退他人模型凭据，响应 MUST NOT 泄漏密钥、完整命令或敏感路径

#### Scenario: 独立测试接口
- **WHEN** 客户端分别测试连接和通用 Agent 能力
- **THEN** 后端 SHALL 提供个人连通性与多 runtime probe run API，保持原有主要结果字段

#### Scenario: 旧 admin 测试路由不存在
- **WHEN** 通过认证与 CSRF 校验的 admin 调用旧测试路径
- **THEN** 后端 SHALL 按未注册 API 返回 404，不创建任务、不调用模型、不返回 profile 或结果
- **AND** 路由注册表与当前 OpenAPI 中 MUST NOT 存在旧入口
