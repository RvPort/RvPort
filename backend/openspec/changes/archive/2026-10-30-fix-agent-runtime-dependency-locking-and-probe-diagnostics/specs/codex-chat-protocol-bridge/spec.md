## ADDED Requirements

### Requirement: CHAT 桥接必须是受控的 Responses 协议转换
后端 SHALL 为 Codex + CHAT profile 提供实际的 Responses → Chat Completions 转换，而非仅替换 URL 或使用不转换请求体的代理；同一 Adapter 路径 SHALL 服务 probe、KB_AI_EXTENSION 和 TEST_LOG_COMPARE。

#### Scenario: 创建桥接执行
- **WHEN** 已通过配置和权限检查的 Codex 调用使用 CHAT profile
- **THEN** SHALL 在当前 backend 或 worker 容器内建立本次执行的 loopback 监听，使用不可变 profile 快照和临时认证 token
- **AND** 上游 SHALL 固定为已解析 profile 的 CHAT endpoint
- **AND** MUST NOT 新增公网入口、主机暴露端口或要求部署额外常驻代理服务

#### Scenario: Codex 配置桥接
- **WHEN** Adapter 生成 Codex 命令与环境
- **THEN** CLI SHALL 使用 Responses 协议及本地 bridge URL/token
- **AND** CHAT 路径真实上游 API key SHALL 仅由后端持有，不传入子进程命令、环境或配置文件
- **AND** MUST NOT 将官方仅转发 Responses 的代理认定为已经实现 CHAT 转换

### Requirement: 桥接必须保留文本与工具的多轮语义
桥接 SHALL 正确映射当前受控工作流所需的文本、消息角色顺序、工具定义、调用及结果，保留跨轮的调用关联；MUST NOT 通过丢字段或拼接单条文本伪造兼容。

#### Scenario: 普通多轮文本
- **WHEN** Responses 请求包含 instructions、角色消息和多轮文本 input
- **THEN** SHALL 转换为等价的 CHAT messages，保持上下文和角色优先关系
- **AND** SHALL 将上游响应重建为合法 Responses 消息与完成状态

#### Scenario: 函数工具闭环
- **WHEN** Codex 提供函数 schema，上游返回一个或多个工具调用，随后 Codex 发送工具结果
- **THEN** SHALL 保留工具名称、参数、调用 ID、角色及各自结果对应关系
- **AND** SHALL 支持后续轮次请求并得到最终回答，不能串用并行工具的参数或输出

#### Scenario: Codex 自定义工具
- **WHEN** 固定 Codex 版本在当前工作流发出 custom/freeform 工具
- **THEN** SHALL 按已验证的显式映射转换定义、调用和结果，并恢复 Codex 期望的类型
- **AND** 无法等价支持的工具格式或约束 SHALL 返回明确不支持错误，不能静默当作普通文本
- **AND** 当前 probe 和两个受控阶段不能完成时 SHALL 保持整体兼容验收未完成

#### Scenario: 缺失或错误工具关联
- **WHEN** 输入工具结果缺少调用引用、关联不存在、重复冲突或参数格式错误
- **THEN** SHALL 返回受控协议错误
- **AND** MUST NOT 猜测对应调用或把该结果合并到其他任务上下文

### Requirement: 桥接必须实现可验证的流式事件和终态
桥接 SHALL 将 CHAT 流式响应转换为目标 Codex 支持的 Responses SSE 事件，并正确表达文本、工具参数、用量及成功/失败/不完整终态；MUST NOT 以连接结束代替成功完成。

#### Scenario: 文本与多工具流式增量
- **WHEN** 上游返回跨字节 UTF-8、跨 JSON 分块文本和交错工具参数增量
- **THEN** SHALL 保持事件顺序、item/content index、ID 与 delta 的一致关系
- **AND** SHALL 输出合法的创建、增量、完成事件，使 Codex 重建完整文本及每个工具参数
- **AND** 缓冲 SHALL 有界，不因长流耗尽内存

#### Scenario: 正常完成与截断
- **WHEN** 上游报告正常结束、长度截断或其他非成功 finish reason
- **THEN** SHALL 映射到对应 Responses 终态
- **AND** SHALL 仅在上游提供真实用量时返回对应 usage，不能伪造统计或将截断判为成功

#### Scenario: 流在完成前断开
- **WHEN** 上游 SSE 畸形、缺少终止事件、超时或中断
- **THEN** SHALL 返回受控失败；已发送流式头时 SHALL 发出可识别失败事件并关闭流
- **AND** MUST NOT 补造 completed 或让 probe 将部分 JSON 判定成功

#### Scenario: 非流式响应
- **WHEN** 支持范围内的请求选择非流式
- **THEN** SHALL 完整映射消息、工具调用、终态与上游错误，和流式路径保持同等业务语义

### Requirement: 桥接必须明确能力边界与上游错误
桥接 SHALL 维护与固定 Codex 版本一致的受支持请求矩阵，使用完整输入的无状态文本/工具路径；无法等价转换的能力 SHALL 明确拒绝。

#### Scenario: 非支持功能
- **WHEN** 请求依赖未实现的 previous_response_id、加密 reasoning、远端 compaction、后台任务、WebSocket、多模态或托管工具能力
- **THEN** SHALL 通过受支持 CLI 配置避免不必要请求，仍到达的请求 SHALL 返回明确能力不支持错误
- **AND** MUST NOT 静默忽略影响语义的字段
- **AND** 当前阶段工作流若实际依赖该能力 SHALL 更新设计并完成实现后才能通过验收

#### Scenario: 上游错误分类
- **WHEN** CHAT 上游返回 401/403、429、5xx、无效 JSON 或不可连接
- **THEN** SHALL 保留可识别的鉴权、限流、上游、协议或连接错误类别及安全摘要
- **AND** MUST NOT 将所有故障转换为普通空文本或通用超时

#### Scenario: 不自动降级协议
- **WHEN** 桥接上游出现错误
- **THEN** SHALL 遵守当前 profile 与剩余预算
- **AND** MUST NOT 自动切换到其他上游、模型、密钥或 Responses 原生协议

#### Scenario: 固定 Codex 的可选密文输出投影
- **WHEN** Codex 0.165.0 在无 reasoning 模式请求 include reasoning.encrypted_content
- **THEN** 桥接 SHALL 允许该可选输出投影，且不生成 reasoning item 或伪造密文
- **AND** 实际包含 encrypted_content 的历史输入仍 SHALL 明确拒绝

### Requirement: 桥接必须隔离请求并服从执行生命周期
桥接 SHALL 对每次执行隔离 token、profile、上下文和取消句柄，并在成功、失败、超时或取消后释放全部资源；安全边界 SHALL 不弱于已有 profile 与 Agent Adapter。

#### Scenario: 认证与任意目标防护
- **WHEN** 请求缺少/误用临时 token，或试图提交任意目标 URL、覆盖模型/授权头、访问不支持路径
- **THEN** SHALL 在发起上游请求前拒绝
- **AND** MUST NOT 携带上游凭据跟随跨源重定向

#### Scenario: 并发不同 profile
- **WHEN** 两个 Agent 执行同时使用不同模型 profile
- **THEN** 请求、密钥、工具结果和上下文 SHALL 严格隔离
- **AND** 一次执行的取消 MUST NOT 终止另一执行或复用其 token

#### Scenario: 执行结束
- **WHEN** Codex 正常结束、启动失败、超时、被取消或客户端断开
- **THEN** SHALL 停止接受新请求、取消上游请求、关闭监听与流并清理临时资料
- **AND** 结束后的 token SHALL 失效，不能留下后台模型调用

#### Scenario: 日志和公共响应
- **WHEN** 产生转换诊断或返回 API 错误
- **THEN** SHALL 只暴露有界脱敏摘要、稳定原因码及必要安全元数据
- **AND** MUST NOT 记录完整 prompt、源码正文、工具输出、真实 API key、代理 token 或内部敏感路径

### Requirement: 桥接必须由本地单元测试和真实 CLI 验证共同验收
转换模块 SHALL 同时具备独立单元测试与固定版本真实 Codex 的本地 Docker 协议验证；单纯 HTTP 成功或静态 fixture 自洽 MUST NOT 作为 Agent 兼容充分证据。

#### Scenario: 模块单元测试
- **WHEN** 验证转换模块
- **THEN** SHALL 覆盖正常文本、多轮工具、SSE 分块、错误关联、非支持能力、取消、并发隔离与脱敏
- **AND** 未加载源码 SHALL 纳入整体及完整 bridge 模块行/分支至少 90% 门槛

#### Scenario: 真实 CLI 与本地模拟上游
- **WHEN** 固定最新稳定 Codex 连接本地模拟 Responses 与 CHAT 上游
- **THEN** 原生和转换两条路径 SHALL 分别完成三项 probe、工具回传及多轮交互
- **AND** SHALL 验证协议故障、流式断开与取消，不连接或修改远程 dev/test
- **AND** SHALL 分别记录单元测试、CLI 模拟集成和完整 Compose 验收，不宣称未执行的真实模型测试
