## MODIFIED Requirements

### Requirement: Profile 上游协议必须与 Codex 传输协议分离
模型 profile 的 `RESPONSES` / `CHAT` SHALL 保持管理员配置的上游协议语义；Codex Adapter SHALL 选择原生 Responses 或 worker 内受管 OpenCodex 路径，不把 CHAT 直接传给不支持该值的 Codex CLI。

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

## ADDED Requirements

### Requirement: Codex CHAT 探针必须在现有 worker 执行且保持客户端契约
Codex CHAT 的异步 probe run 和旧测试兼容入口 SHALL 通过现有基础设施分派到 worker；backend API 角色 SHALL 仅负责授权、分派、查询和结果归并，MUST NOT 在本进程启动 OpenCodex 或回退到自研桥接。

#### Scenario: 创建或读取多 runtime 探针
- **WHEN** run 同时包含 Codex CHAT 和其他 runtime
- **THEN** Codex CHAT SHALL 在 worker 执行，其他 Agent 保持既有路径与版本
- **AND** SHALL 保留现有 runId、状态查询、三项真实分项及聚合规则，全部所选分项达到终态后汇总
- **AND** 单个 runtime 失败 MUST NOT 覆盖其他 runtime 的真实结果或导致提前标记全部成功

#### Scenario: 旧兼容入口
- **WHEN** 旧 `POST /api/admin/model-profiles/{profileId}:test` 选择 Codex CHAT
- **THEN** SHALL 复用 worker 路径并在原有有界请求语义内返回兼容格式的结果或明确失败
- **AND** MUST NOT 要求前端增加轮询、代理配置或更换 API 才能工作
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
