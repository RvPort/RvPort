## MODIFIED Requirements

### Requirement: Codex Provider 配置实现必须覆盖三类测试
个人模型 profile 和两个受控阶段绑定实现 SHALL 配套独立单元测试与本地隔离 Compose API 验收，覆盖正常流程、异常流程和 edge case；MUST NOT 因此恢复已退役的 E2E 设施。

#### Scenario: 覆盖正常流程
- **WHEN** 普通用户或 admin 创建、查询、更新并测试本人的模型 profile
- **THEN** 测试 SHALL 验证普通配置写入 `model_profiles`
- **AND** 测试 SHALL 验证 API key 加密保存且查询响应只返回遮蔽摘要
- **AND** 测试 SHALL 验证测试接口返回脱敏后的成功或失败摘要
- **AND** 测试 SHALL 验证 TEST_LOG_COMPARE 和 KB_AI_EXTENSION 个人阶段绑定能解析到本人已启用模型 profile

#### Scenario: 覆盖异常流程
- **WHEN** 用户访问他人模型 profile、probe run 或绑定目标
- **THEN** 测试 SHALL 验证越权资源返回 404、跨用户绑定整体拒绝且零上游调用
- **WHEN** 请求包含非法 `baseUrl`、非法 `modelProviderType`、非法 `wireApi`、空 `model`、同一所有者内重复 profile 名称、两个受控阶段以外的阶段或越界超时参数
- **THEN** 测试 SHALL 验证 API 返回参数校验错误
- **WHEN** Codex executable 不存在、profile 配置不完整、阶段未绑定或绑定 profile 已禁用
- **THEN** 测试 SHALL 验证接口或阶段解析返回受控失败且不泄漏 secret

#### Scenario: 覆盖 edge case
- **WHEN** 更新模型 profile 时未传入 `apiKey`
- **THEN** 测试 SHALL 验证已有 API key 被保留
- **WHEN** 更新模型 profile 时传入空字符串 `apiKey`
- **THEN** 测试 SHALL 验证已有 API key 被清空
- **WHEN** provider 测试失败信息包含 API key、授权头或本地敏感路径
- **THEN** 测试 SHALL 验证响应和持久化摘要均已脱敏
- **WHEN** 删除已被阶段绑定的模型 profile
- **THEN** 测试 SHALL 验证删除被拒绝

#### Scenario: 删除旧接口的回归测试
- **WHEN** 使用认证用户与有效 CSRF 请求旧 admin 模型相关 API
- **THEN** 测试 SHALL 验证全部旧路由不存在且返回 404，业务服务零调用，不以 401/403 替代删除证据
- **AND** SHALL 验证普通用户、admin 个人权限隔离，以及未登录、禁用账号、强制改密码和敏感响应禁止缓存规则
