## Purpose

定义个人模型 profile、阶段绑定、兼容性测试和结果的所有者隔离、个人 API、安全目录及项目结果访问边界。

## Requirements

### Requirement: 个人模型配置必须按认证所有者隔离
后端 SHALL 从认证主体确定模型 profile 所有者，所有列表、计数、详情、增删改、连接测试、兼容性 run、历史与 latest 查询均 SHALL 校验归属；超级管理员在个人接口 MUST NOT 绕过该边界。未认证、禁用账号、强制改密码及 CSRF 的既有规则 SHALL 保持。

#### Scenario: 普通用户和管理员管理自己的配置
- **WHEN** 已认证且具备操作资格的普通用户或 admin 请求个人模型 API
- **THEN** 后端 SHALL 只读取和修改该用户的配置，客户端提交的 owner 或 actor MUST NOT 改变归属
- **AND** API key SHALL 加密保存，响应 MUST NOT 包含明文或密文

#### Scenario: 跨用户 UUID 访问
- **WHEN** 用户 A 或 admin 使用用户 B 的 profile、probe run 或结果 ID 查询、修改、删除或测试
- **THEN** 后端 SHALL 返回与不存在资源一致的 404，且不调用模型、不产生任务或修改摘要

#### Scenario: 名称与批量绑定隔离
- **WHEN** 两个用户创建同名 profile 或保存同一阶段绑定
- **THEN** 后端 SHALL 允许跨用户同名与独立绑定，同一用户内名称大小写不敏感唯一
- **AND** 批量绑定包含其他用户 profile 时 SHALL 整体拒绝，不能部分写入

### Requirement: 个人测试结果不得成为全局 Runtime 状态
后端 SHALL 将连通性和通用兼容性结果限定到个人 profile 与 runtime 组合；平台 SHALL 继续统一维护 CLI 安装、版本和受管执行路径。

#### Scenario: 不同用户测试同一 Runtime
- **WHEN** A 和 B 分别用自己的 profile 测试同一 runtime，结果不同
- **THEN** 各自矩阵、活跃 run、latest 和摘要 SHALL 只反映自己的测试
- **AND** 后端 MUST NOT 以个人结果覆盖共享 runtime 兼容状态或影响另一用户的 readiness

#### Scenario: 查询平台能力目录
- **WHEN** 普通用户查询 `/api/me/agent-runtimes`
- **THEN** 后端 SHALL 返回受管 runtime 的安全选择元数据
- **AND** MUST NOT 返回其他用户测试详情、全局个人探针摘要、内部路径或完整命令

#### Scenario: 尝试覆盖可执行程序
- **WHEN** 个人创建、更新或临时测试请求指定服务器可执行路径或任意命令
- **THEN** 后端 SHALL 拒绝该覆盖且不执行命令；历史 executable_path MUST NOT 成为用户执行入口

### Requirement: 个人 API 必须明确替代旧全局接口
后端 SHALL 提供 `/api/me/model-profiles`、`/api/me/model-stage-assignments`、`/api/me/agent-runtimes` 及 `/api/me/model-profile-agent-test-runs/{runId}` 个人接口，并提供请求响应、错误及旧路径迁移文档。新增展示说明 SHALL 同时提供中文和英文字段。本 change MUST NOT 修改前端。

#### Scenario: 删除旧 admin 接口
- **WHEN** 发布个人 API
- **THEN** 后端 SHALL 删除 `/api/admin/model-profiles`、`/api/admin/model-stage-assignments`、`/api/admin/model-profile-agent-test-runs` 和 `/api/admin/agent-runtimes` 路径族的全部现有方法、动作后缀及子资源路由，并清除专用安全匹配规则与当前 OpenAPI 定义
- **AND** MUST NOT 保留旧接口兼容处理器、别名、重定向或专用迁移响应
- **AND** 对接说明 SHALL 明确旧前端需要独立适配，不宣称个人中心入口已交付

#### Scenario: 验证旧路由已移除
- **WHEN** 通过认证与适用 CSRF 校验的 admin 请求旧模型相关路径
- **THEN** 后端 SHALL 按未注册 API 返回 404，不调用业务服务、不返回配置数据、不写入数据且不发起模型调用
- **AND** 单元测试 SHALL 校验旧路由与 OpenAPI 定义不存在，不能将仅返回 401/403 当作删除证据
- **AND** 无关 admin 功能及平台受管 CLI 维护 SHALL 保持原有边界

#### Scenario: 服务端缓存隔离
- **WHEN** A、B 或 admin 先后请求同一项目 readiness 或个人配置
- **THEN** 服务端 SHALL 使用包含用户归属的缓存键或不缓存，禁止共享个人敏感响应
- **AND** B 的响应 MUST NOT 复用 A 的个人模型、测试状态或可执行性判定

### Requirement: 业务结果和个人配置必须采用不同访问边界
后端 SHALL 保持项目业务结果的既有项目访问授权，个人模型配置与测试历史 SHALL 仅由配置所有者访问。

#### Scenario: 有项目权限的其他用户读取历史结果
- **WHEN** 有项目访问权限的用户查看另一触发者生成的历史业务结果
- **THEN** 后端 SHALL 返回有权查看的业务事实，不因访问者模型不同使历史结果失效
- **AND** SHALL 仅提供必要的安全执行审计，不返回触发者完整 profile、上游地址、密钥或私人探针详情

#### Scenario: 有个人模型但无项目权限
- **WHEN** 用户以自己有效的模型配置操作无权访问的项目
- **THEN** 后端 SHALL 按既有项目权限拒绝，不创建任务或调用模型
