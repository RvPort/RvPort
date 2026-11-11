## MODIFIED Requirements

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
