# test-result-comparison-runtime Specification

## Purpose
TBD - created by archiving change add-test-result-comparison-runtime. Update Purpose after archive.
## Requirements
### Requirement: 测试结果比较运行时必须作为自动化测试节点的自动后续步骤
后端 SHALL 在自动化测试节点完成两侧测试运行后自动创建测试结果比较运行，并在原测试批次触发者的个人模型可用时自动投递 `TEST_LOG_COMPARE` 长任务。

#### Scenario: 自动化测试完成后自动创建比较
- **WHEN** 同一自动化测试节点下 source 和 target 两侧测试记录均进入可比较终态
- **THEN** 后端 SHALL 自动创建测试比较运行记录
- **AND** 后端 SHALL 使用两侧测试记录作为 `sourceTestRunId` 和 `targetTestRunId`
- **AND** 后端 SHALL 在原测试批次触发者的个人模型配置可用时自动创建 `TEST_LOG_COMPARE` 任务
- **AND** 前端 MUST NOT 需要额外点击“创建比较”才能获得比较报告

#### Scenario: 等待另一侧测试完成
- **WHEN** 自动化测试节点只有一侧测试记录进入终态
- **THEN** 后端 SHALL 保存该侧测试结果
- **AND** 后端 MUST NOT 立即创建最终比较报告
- **AND** 后端 SHALL 在另一侧测试记录完成后再尝试创建比较运行

#### Scenario: 不可比较的测试记录
- **WHEN** 自动化测试节点缺少 source 或 target 测试记录，或某侧测试记录被取消
- **THEN** 后端 SHALL 记录比较不可用或不可比较摘要
- **AND** 后端 MUST NOT 重新执行远程测试命令

#### Scenario: 比较任务不重新执行测试
- **WHEN** worker 执行测试结果比较任务
- **THEN** worker SHALL 只读取已有 `test_runs`、任务日志和测试报告 artifact
- **AND** worker MUST NOT 重新连接远程环境运行测试命令

#### Scenario: 派生和手动重触发身份分离
- **WHEN** 自动派生比较或用户手动重新触发比较
- **THEN** 自动派生 SHALL 继承批次触发者，手动重触发 SHALL 新建执行并使用当前认证用户配置；两者均持久化主体与配置引用
- **AND** 不能推导可信主体时 SHALL 明确不可用而非回退 admin

### Requirement: 测试结果比较必须在模型未配置时明确不可用
测试结果比较 SHALL 依赖原测试批次触发者的个人 `TEST_LOG_COMPARE` 阶段绑定的可用模型 profile；未配置或不可用时，自动化测试 SHALL 保持正常完成，比较摘要 SHALL 标记为不可用。

#### Scenario: 未配置模型时自动比较
- **WHEN** 自动化测试两侧运行完成，但原触发者的 `TEST_LOG_COMPARE` 阶段未绑定模型 profile
- **THEN** 后端 SHALL 创建或更新比较运行摘要为 `UNAVAILABLE`
- **AND** 比较运行 SHALL 记录 `TEST_COMPARE_MODEL_NOT_CONFIGURED` 或等效原因
- **AND** 后端 SHALL 返回测试事实及可供客户端消费的个人比较配置缺失原因和双语说明，本 change 不修改前端
- **AND** 后端 MUST NOT 将自动化测试记录标记为失败

#### Scenario: 模型配置不可用时自动比较
- **WHEN** 原触发者的个人 `TEST_LOG_COMPARE` 阶段绑定的模型 profile 已禁用、缺少 API key、缺少 base URL、缺少模型名或 executable path 不可用
- **THEN** 后端 SHALL 将比较运行标记为 `UNAVAILABLE` 或明确失败前置状态
- **AND** 后端 MUST NOT 创建 agent 执行任务
- **AND** 响应、任务事件和日志 MUST NOT 泄漏 API key、密文、授权头或服务器真实路径

#### Scenario: 自动化测试不依赖模型配置
- **WHEN** 原触发者没有配置个人 `TEST_LOG_COMPARE` 模型 profile
- **THEN** 自动化测试创建、执行、查询、取消和日志下载 SHALL 继续正常工作
- **AND** 后端 MUST NOT 因模型缺失拒绝 `AUTOMATED_TEST` 任务

### Requirement: 测试结果比较必须聚合日志和报告作为 agent 输入
测试结果比较运行时 SHALL 从两条测试记录聚合状态、退出码、日志和报告 artifact，生成受控 agent 输入。

#### Scenario: 聚合测试输出
- **WHEN** worker 准备比较输入
- **THEN** 输入 SHALL 包含两侧测试状态、退出码、输出摘要、源码版本、环境摘要和任务 ID
- **AND** 输入 SHALL 包含两侧 stdout/stderr 日志内容或受控摘要
- **AND** 输入 SHALL 包含两侧已回收报告 artifact 的内容或受控摘要
- **AND** 输入 MUST NOT 包含明文凭据、环境变量明文、服务器真实路径或对象存储内部 key

#### Scenario: 输入过大时裁剪
- **WHEN** 单个日志、单个报告或总输入超过后端定义的大小上限
- **THEN** 后端 SHALL 使用确定性策略裁剪或摘要化输入
- **AND** 后端 SHALL 在比较运行摘要中记录发生过裁剪
- **AND** 后端 SHALL 保留原始日志和 artifact 的查询或下载引用

### Requirement: 测试结果比较必须调用 Codex agent 并保存结构化结果
测试结果比较运行时 SHALL 通过 `TEST_LOG_COMPARE` 阶段解析的模型 profile 和 agent runtime 调用已绑定 agent，并保存结构化比较结果。

#### Scenario: agent 比较成功
- **WHEN** 已绑定 agent 返回合法比较结果
- **THEN** 后端 SHALL 保存比较运行状态为 `SUCCEEDED`
- **AND** 后端 SHALL 保存总体结论、风险等级、置信度、摘要和建议
- **AND** 后端 SHALL 保存结构化差异项
- **AND** 后端 SHALL 保存原始 agent 输出 artifact
- **AND** 原始输出 artifact SHALL 包含脱敏后的 `mergedOutput`

#### Scenario: agent 输出不可解析
- **WHEN** 已绑定 agent 返回非 JSON、字段缺失或结构不合法的输出
- **THEN** 后端 SHALL 保存原始 agent 输出 artifact
- **AND** 后端 SHALL 将比较运行标记为 `FAILED` 或等效受控失败状态
- **AND** 后端 SHALL 返回基于 `mergedOutput` 的脱敏错误摘要

#### Scenario: agent 执行失败
- **WHEN** 已绑定 agent 超时、进程退出码非 0 或 provider 调用失败
- **THEN** 后端 SHALL 将比较运行标记为 `FAILED` 或 `TIMEOUT`
- **AND** 后端 SHALL 保存基于 `mergedOutput` 的脱敏失败摘要
- **AND** 任务状态 SHALL 与比较运行状态一致

### Requirement: 测试结果比较必须支持查询、分页、过滤和排序
后端 SHALL 提供测试比较运行和差异项查询能力，并 SHALL 支持分页、过滤和排序。

#### Scenario: 查询项目比较列表
- **WHEN** 用户查询项目测试结果比较列表
- **THEN** 后端 SHALL 返回分页比较运行列表
- **AND** 列表接口 SHALL 支持按状态、源码版本、测试运行 ID、环境、风险等级和时间范围过滤
- **AND** 列表接口 SHALL 支持按创建时间、完成时间、状态和风险等级排序

#### Scenario: 查询比较详情
- **WHEN** 用户查询单个测试比较运行详情
- **THEN** 后端 SHALL 校验用户有权访问所属项目
- **AND** 响应 SHALL 返回比较运行摘要、两侧测试记录摘要、状态、总体结论、风险等级、置信度、建议和 artifact 摘要
- **AND** 响应 MUST NOT 返回完整日志、完整 agent 输入、API key、对象存储内部 key 或服务器真实路径

#### Scenario: 查询比较差异项
- **WHEN** 用户查询测试比较差异项列表
- **THEN** 后端 SHALL 返回分页差异项列表
- **AND** 列表接口 SHALL 支持按严重程度、差异类型、置信度和关键字过滤
- **AND** 列表接口 SHALL 支持按严重程度、置信度、创建时间和序号排序

### Requirement: 测试结果比较必须通过任务事件暴露运行状态
测试结果比较运行时 SHALL 复用项目级 SSE 和任务日志机制暴露运行状态。

#### Scenario: 比较任务状态推送
- **WHEN** 比较任务进入 `RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED`、`TIMEOUT` 或 `UNAVAILABLE`
- **THEN** 后端 SHALL 通过项目级 SSE 推送任务状态或比较摘要事件
- **AND** 前端 SHALL 能通过比较详情接口查询历史结果

#### Scenario: 取消比较任务
- **WHEN** 用户取消运行中的测试结果比较任务
- **THEN** 后端 SHALL 复用任务取消机制取消 Codex agent 进程
- **AND** 已保存的比较运行、日志和 artifact SHALL 保留为取消前的事实记录

### Requirement: 测试结果比较必须输出结构化环境测试统计
测试结果比较运行时 SHALL 要求 agent 从 merged log 和报告材料中提取 source 与 target 两侧测试统计，并 SHALL 保存结构化摘要。

#### Scenario: 输出 source 测试统计
- **WHEN** 当前配置的 agent 完成测试结果比较
- **THEN** 输出 SHALL 包含 source 环境的状态、退出码、测试总数、执行数、通过数、失败数、跳过数、失败项和失败原因
- **AND** 输出 SHALL 包含统计提取置信度和统计依据

#### Scenario: 输出 target 测试统计
- **WHEN** 当前配置的 agent 完成测试结果比较
- **THEN** 输出 SHALL 包含 target 环境的状态、退出码、测试总数、执行数、通过数、失败数、跳过数、失败项和失败原因
- **AND** 输出 SHALL 包含统计提取置信度和统计依据

#### Scenario: agent 负责提取测试统计
- **WHEN** 后端准备测试结果比较输入
- **THEN** 后端 SHALL 将 merged log 和已回收报告材料提供给 agent
- **AND** 后端 MUST NOT 为不同测试工具实现专用 parser 来提取测试数量或失败项
- **AND** 后端 SHALL 只校验 agent 输出结构、字段类型和脱敏边界

#### Scenario: 无法确定测试数量
- **WHEN** agent 无法从日志或报告中可靠判断测试数量
- **THEN** agent 输出 SHALL 使用空值或低置信度表达不确定
- **AND** agent MUST NOT 编造测试总数、通过数或失败数

#### Scenario: 保存结构化统计
- **WHEN** agent 返回合法结构化测试统计
- **THEN** 后端 SHALL 将 source 与 target 统计摘要保存到测试比较运行
- **AND** API 查询比较详情时 SHALL 返回该结构化统计
- **AND** 数据库普通摘要字段 MUST NOT 保存完整原始日志或完整 agent 输入

### Requirement: 测试结果比较必须基于合并日志判断失败上下文
测试结果比较运行时 SHALL 使用有序 merged log 判断测试步骤与错误输出之间的上下文关系。

#### Scenario: 错误输出与测试步骤相邻
- **WHEN** merged log 中 `STDERR` 错误输出出现在某个测试步骤输出之后
- **THEN** agent SHALL 基于有序上下文判断该错误可能关联的测试项
- **AND** 比较结果中的失败项 SHALL 尽量包含对应测试名称、错误证据和原因

#### Scenario: 分流日志不足以判断上下文
- **WHEN** 只有 stdout/stderr 分流日志而没有 merged log
- **THEN** agent SHALL 在统计依据或摘要中说明上下文顺序可能不完整
- **AND** 后端 SHALL 允许该比较结果使用较低置信度

### Requirement: 测试结果比较必须使用阶段绑定的 Agent Runtime
测试结果比较 SHALL 使用原触发者个人 `TEST_LOG_COMPARE` 阶段绑定所选的 agent runtime，而不是固定使用 Codex。

#### Scenario: 解析完整阶段绑定
- **WHEN** 后端执行测试结果比较任务
- **THEN** 后端 SHALL 读取创建时从配置主体个人绑定保存的 profile/runtime 引用并复核归属与有效性，不在执行时重新选择阶段绑定
- **AND** 后端 SHALL 通过 agent adapter registry 选择对应 adapter
- **AND** 业务任务输入 MUST NOT 包含 agent 类型、可执行路径、模型名、base URL 或 API key

#### Scenario: Agent runtime 未配置
- **WHEN** 原触发者的 `TEST_LOG_COMPARE` 阶段没有绑定 agent runtime 或绑定 runtime 不可用
- **THEN** 后端 SHALL 将比较标记为不可用或失败
- **AND** 自动化测试结果本身 MUST 保持可查询
- **AND** 错误摘要 MUST NOT 泄漏内部命令或密钥

#### Scenario: 保存运行时摘要
- **WHEN** 比较任务完成或失败
- **THEN** 比较详情 SHALL 能返回使用的 agent 类型和 agent runtime 摘要
- **AND** 响应 MUST NOT 返回完整 agent 命令、完整 prompt、API key 或服务器真实路径

### Requirement: 测试结果比较必须优先读取 agent 结果文件
测试结果比较运行时 SHALL 要求 agent 在专用 workspace 中写出固定结构化结果文件，并 SHALL 以该文件作为比较结果的主要解析来源。

#### Scenario: agent 写出结果文件
- **WHEN** 后端调用 `TEST_LOG_COMPARE` 阶段绑定的 agent runtime
- **THEN** 后端 SHALL 在 agent workspace 中提供比较输入文件
- **AND** prompt SHALL 要求 agent 写出 `comparison-result.json`
- **AND** `comparison-result.json` SHALL 包含总体结论、风险等级、置信度、source/target 测试统计和差异项
- **AND** 后端 SHALL 优先读取 `comparison-result.json` 作为结构化结果

#### Scenario: transcript 不作为主要解析来源
- **WHEN** agent 执行结束并产生 stdout、stderr 或 mergedOutput
- **THEN** 后端 MUST NOT 从 `mergedOutput` 中直接截取第一个 JSON 对象作为成功结果
- **AND** 后端 SHALL 将 stdout、stderr 和 mergedOutput 保存为诊断材料
- **AND** 后端 SHALL 在 artifact 摘要中记录实际解析来源

#### Scenario: 结果文件缺失
- **WHEN** agent 退出码为 0 但未写出 `comparison-result.json`
- **THEN** 后端 MAY 从 stdout 中提取 fenced JSON 或最后一个完整 JSON 作为兼容降级
- **AND** 降级结果仍 MUST 通过结构和语义校验
- **AND** 如果无法取得合法结果，比较运行 SHALL 标记为 `FAILED`

### Requirement: 测试结果比较必须拒绝占位符和组合枚举输出
测试结果比较运行时 SHALL 对 agent 输出执行结构校验和语义校验，防止 schema 示例或占位符被保存为成功结果。

#### Scenario: 拒绝组合枚举值
- **WHEN** agent 输出字段值包含 `SUCCEEDED|FAILED|CANCELLED|TIMEOUT|UNKNOWN`、`CRITICAL|HIGH|MEDIUM|LOW|INFO` 或类似组合枚举字符串
- **THEN** 后端 SHALL 认为 agent 输出无效
- **AND** 比较运行 SHALL 标记为 `FAILED`
- **AND** 错误码 SHALL 为 `TEST_COMPARE_AGENT_OUTPUT_INVALID` 或等效受控错误码

#### Scenario: 拒绝模板占位符
- **WHEN** agent 输出的 `summary`、`recommendation`、`testCountBasis`、finding 标题或描述仍为 `...`、空模板或明显示例内容
- **THEN** 后端 SHALL 认为 agent 输出无效
- **AND** 后端 MUST NOT 将该比较运行保存为 `SUCCEEDED`

#### Scenario: 校验统计字段
- **WHEN** agent 输出 source 或 target 测试统计
- **THEN** `status` SHALL 是 `SUCCEEDED`、`FAILED`、`CANCELLED`、`TIMEOUT`、`UNKNOWN` 中的单个值
- **AND** 测试数量字段 MAY 为 `null`
- **AND** 非空测试数量字段 MUST 为非负整数
- **AND** `extractionConfidence` MUST 为空或位于 0 到 1 之间

#### Scenario: 校验差异项字段
- **WHEN** agent 输出差异项列表
- **THEN** 每条差异项的 `differenceType` SHALL 是 `EXIT_CODE`、`STDERR`、`STDOUT`、`REPORT`、`MISSING_REPORT`、`OTHER` 中的单个值
- **AND** 每条差异项的 `severity` SHALL 是 `CRITICAL`、`HIGH`、`MEDIUM`、`LOW`、`INFO` 中的单个值
- **AND** `confidence` MUST 为空或位于 0 到 1 之间

### Requirement: 测试结果比较提示词必须避免可照抄的占位 JSON
测试结果比较 prompt SHALL 以字段说明和单值枚举约束描述输出格式，避免把组合枚举或 `...` 作为可直接照抄的 JSON 字段值。

#### Scenario: 输出契约描述
- **WHEN** 后端生成测试比较 agent prompt
- **THEN** prompt SHALL 明确要求最终结果写入 `comparison-result.json`
- **AND** prompt SHALL 说明每个枚举字段必须选择单个合法值
- **AND** prompt MUST NOT 使用 `"A|B|C"` 作为实际 JSON 字段值示例
- **AND** prompt MUST NOT 使用 `...` 作为可直接输出的字段值

#### Scenario: 允许补充说明文件
- **WHEN** agent 需要输出非结构化判断说明
- **THEN** agent MAY 写出 `comparison-notes.txt`
- **AND** 后端 MAY 将其保存为诊断 artifact
- **AND** 后端 MUST NOT 从说明文件解析业务字段
