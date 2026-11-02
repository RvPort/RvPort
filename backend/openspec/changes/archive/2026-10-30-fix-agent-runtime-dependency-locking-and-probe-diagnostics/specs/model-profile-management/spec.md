## ADDED Requirements

### Requirement: Profile 上游协议必须与 Codex 传输协议分离
模型 profile 的 `RESPONSES` / `CHAT` SHALL 保持管理员配置的上游协议语义；Codex Adapter SHALL 根据此语义选择原生 Responses 或受管协议转换路径，而不是把 CHAT 直接传给不支持该值的 Codex CLI。

#### Scenario: 原生 Responses profile
- **WHEN** Codex 执行绑定到 RESPONSES profile
- **THEN** SHALL 直接使用该 profile 的上游，以 `wire_api=responses` 调用
- **AND** MUST NOT 启动不必要的 CHAT 转换或更改已有持久化配置

#### Scenario: 仅支持 CHAT 的 profile
- **WHEN** Codex 执行绑定到 CHAT profile
- **THEN** SHALL 使用受管的 Responses → Chat Completions 转换路径
- **AND** Codex CLI SHALL 接收 `wire_api=responses`，上游 SHALL 接收 CHAT 请求
- **AND** 持久化 wireApi SHALL 保持 CHAT，无需前端新增代理配置

#### Scenario: 连通性与兼容性分别判定
- **WHEN** 执行模型连通性测试或 Agent 兼容性测试
- **THEN** 连通性 SHALL 测试 profile 声明的原始上游协议
- **AND** Agent 兼容性 SHALL 验证实际 Adapter 路径的文本与工具闭环
- **AND** 上游 HTTP 200 MUST NOT 单独构成 Agent 兼容通过

#### Scenario: 协议请求失败
- **WHEN** 当前协议请求出现超时、401、404、429 或 5xx
- **THEN** SHALL 返回该路径的明确失败原因
- **AND** MUST NOT 自动切换协议、上游、模型或密钥以隐藏失败

### Requirement: Probe 必须保留独立分项和可解释的聚合结果
通用 Agent probe SHALL 保持文本、workspace 读取、workspace 写入三个分项的独立结果；聚合兼容性 SHALL 只在全部分项真实成功时为通过。

#### Scenario: 文本超时而工具检查成功
- **WHEN** 文本检查超时，后续读取和写入检查成功
- **THEN** SHALL 保留三个不同的真实分项结果，基础响应与聚合兼容性仍为未通过
- **AND** 诊断 SHALL 指向文本分项超时，MUST NOT 描述为全部执行失败或 CLI 未安装

#### Scenario: 上游没有工具调用能力
- **WHEN** 模型能返回普通文本但不能完成工具调用及结果回传
- **THEN** Agent 兼容性 SHALL 为失败并指明对应工具分项
- **AND** MUST NOT 由协议转换层伪造工具执行结果或输出文件以获得通过

#### Scenario: 重新验证与历史记录
- **WHEN** CLI 版本、依赖或 Adapter 改变后重新执行 probe
- **THEN** SHALL 保存带安全版本/路由信息的新结果
- **AND** MUST NOT 将历史失败记录改写为成功或回填未经证实的根因

### Requirement: Profile 协议适配必须保持权限与客户端兼容
本 change SHALL 保留已有管理权限、阶段绑定、API 路径、profile 协议枚举及 probe 主要响应字段；新增诊断 SHALL 采用兼容增量字段和中英双语展示说明。

#### Scenario: 普通用户或非法绑定
- **WHEN** 无权限用户管理或探测 profile，或业务请求试图覆盖绑定的模型凭据和上游
- **THEN** SHALL 按现有权限边界拒绝
- **AND** MUST NOT 泄漏密钥、完整命令、桥接 token 或内部端口配置

#### Scenario: 非 Codex Agent 回归
- **WHEN** 使用 DSH 或 OpenCode 执行既有受支持路径
- **THEN** SHALL 保留对应 Adapter 的原生调用边界与真实探测行为
- **AND** MUST NOT 因 Codex 引入桥接而强制它们通过 Codex 转换层

#### Scenario: 旧前端读取结果
- **WHEN** 现有前端读取增加诊断字段后的 profile 和 probe
- **THEN** 既有状态、分项与 message 字段 SHALL 保持可消费
- **AND** 本次修复 MUST NOT 依赖前端代码改动才能生效
