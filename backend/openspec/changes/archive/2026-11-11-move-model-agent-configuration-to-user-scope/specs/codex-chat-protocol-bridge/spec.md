## MODIFIED Requirements

### Requirement: OpenCodex 启动必须验证实际配置且不得采用隐式默认值
worker SHALL 在启动前严格验证生成配置，在启动后确认本次实例的有效 provider、上游、模型、端口及监听范围；MUST NOT 把进程存在或单次健康 HTTP 200 当作完整就绪判定。

#### Scenario: 配置无效或默认配置回退
- **WHEN** 配置无效、出现 invalid 备份、实际模型/上游/端口不匹配、额外 provider 或意外监听
- **THEN** SHALL 在启动 Codex 或发送模型请求前失败并回收资源
- **AND** MUST NOT 接受默认 10100 端口、默认 provider 或仅凭版本输出判成功

#### Scenario: 禁用无关运行功能
- **WHEN** 生成并启动本次 OpenCodex 配置
- **THEN** SHALL 关闭运行期更新、定时模型刷新、Claude intercept、无关客户端集成及模型/provider fallback，并验证实际行为
- **AND** SHALL 使用独立 OPENCODEX_HOME 和 CODEX_HOME，不修改共享用户配置，不执行服务安装或交互初始化
- **AND** Codex 配置 SHALL 显式指定本次受控 URL、模型和 Responses 协议，不依赖第三方修改用户配置

#### Scenario: 动态 profile 即时使用
- **WHEN** 配置所有者新建有效个人 profile 后首次执行 Codex CHAT
- **THEN** SHALL 直接从执行快照生成专属配置并启动代理
- **AND** MUST NOT 要求人工注册 OpenCodex provider、刷新共享注册表或重启部署服务

#### Scenario: 不同用户并发执行
- **WHEN** 不同用户的 Codex CHAT 任务同时领取
- **THEN** SHALL 校验各自的持久化身份及 profile 归属，使用各自快照与独立受管目录、端口和本地 token
- **AND** MUST NOT 读取共享默认 profile、串用凭据或回退其他用户配置
