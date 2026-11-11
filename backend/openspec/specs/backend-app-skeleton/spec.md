## Purpose

定义 RvPort 后端应用骨架的技术栈、配置、安全基础、健康检查、测试和 API/worker 启动边界要求。
## Requirements
### Requirement: 后端工程使用 Java Spring Boot 技术栈
后端应用 SHALL 使用 Java 25 LTS、Spring Boot 4.1.x 和 Maven 构建工程骨架。

#### Scenario: 创建后端工程
- **WHEN** 创建后端应用骨架
- **THEN** 工程 SHALL 使用 Maven 管理构建和依赖
- **AND** 工程 SHALL 使用 Java 25 LTS
- **AND** 工程 SHALL 使用 Spring Boot 4.1.x

#### Scenario: 选择 Web 框架
- **WHEN** 实现 MVP HTTP API
- **THEN** 后端 SHALL 使用 Spring Web MVC
- **AND** MVP MUST NOT 要求使用 WebFlux

### Requirement: 后端数据访问使用 Liquibase 和 jOOQ
后端应用 SHALL 使用 Liquibase 管理 schema 变更，并 SHALL 使用 jOOQ 作为 MVP 主数据访问方式。

#### Scenario: 管理数据库 schema
- **WHEN** 后端需要新增或修改数据库结构
- **THEN** 变更 SHALL 通过 Liquibase changelog 管理
- **AND** Liquibase SHALL 是 MVP schema 变更入口
- **AND** 开发者 MUST NOT 通过手工 SQL、psql、数据库客户端或应用外脚本直接创建或修改业务表后，再依赖 Liquibase 补登记变更记录
- **AND** 如果为了临时排障手工调整过开发库 schema，后续提交前 MUST 使用等价 Liquibase changelog 重新验证空库迁移和既有库升级路径

#### Scenario: 使用 jOOQ 访问数据库
- **WHEN** 后端业务代码需要访问 PostgreSQL
- **THEN** 后端 SHALL 使用 jOOQ 表达 SQL、状态转移、聚合查询和 PostgreSQL 特性
- **AND** 后端 MUST NOT 在 MVP 阶段引入 JPA 作为主数据访问方式

#### Scenario: 生成 jOOQ 代码
- **WHEN** 空 PostgreSQL 数据库已经通过 Liquibase changelog 创建或更新 schema
- **THEN** 后端 SHALL 能基于迁移后的 PostgreSQL schema 运行 jOOQ codegen
- **AND** jOOQ codegen MUST NOT 作为 schema 创建入口
- **AND** jOOQ codegen SHALL 作为显式开发期步骤执行，而不是普通 Maven 编译的默认步骤
- **AND** schema 变更后的顺序 MUST 是编写 Liquibase changelog、执行 Liquibase 迁移、运行 jOOQ codegen、提交 migration 与 generated sources

#### Scenario: 普通构建不依赖数据库 codegen
- **WHEN** 开发者执行普通 Maven 编译、测试、打包或 Docker build
- **THEN** 构建 SHALL 编译已经提交的 jOOQ 生成源码
- **AND** 构建 MUST NOT 默认连接 PostgreSQL 执行 jOOQ codegen
- **AND** 构建 MUST NOT 因 `mvn clean` 清理 `target` 而丢失 jOOQ 生成源码

#### Scenario: 跨平台生成脚本
- **WHEN** 开发者需要在 schema 变更后刷新 jOOQ 代码
- **THEN** 后端 SHALL 提供 Windows PowerShell 生成脚本
- **AND** 后端 SHALL 提供 Linux/macOS Shell 生成脚本
- **AND** 脚本 SHALL 从环境变量或根目录 `.env` 获取数据库连接信息

### Requirement: 后端配置通过环境变量管理
后端应用 SHALL 通过环境变量读取数据库、Redis、RabbitMQ、session cookie 和安全相关配置。

#### Scenario: 读取基础设施连接配置
- **WHEN** 后端应用启动
- **THEN** 应用 SHALL 从环境变量读取 PostgreSQL、Redis 和 RabbitMQ 连接配置
- **AND** 应用 MUST NOT 在日志中输出密码、token、私钥或加密主密钥

#### Scenario: 读取安全配置
- **WHEN** 后端应用启动
- **THEN** 应用 SHALL 从环境变量读取凭据加密主密钥配置
- **AND** 缺少必需安全配置时 SHOULD 失败启动或以明确错误提示退出

### Requirement: 浏览器登录态使用服务端 Session 和 Cookie
MVP 浏览器登录态 SHALL 使用 Spring Security Session、Spring Session Redis 和 HttpOnly Cookie，不 SHALL 使用 JWT 作为默认登录态。

#### Scenario: 用户登录
- **WHEN** 用户通过登录接口完成认证
- **THEN** 后端 SHALL 创建服务端 session
- **AND** session SHALL 存储到 Redis
- **AND** 响应 SHALL 设置 `RVPORT_SESSION` Cookie
- **AND** `RVPORT_SESSION` Cookie SHALL 使用 HttpOnly

#### Scenario: 前端获取当前用户
- **WHEN** 前端刷新页面或初始化应用状态
- **THEN** 前端 SHALL 调用 `GET /api/auth/me` 获取当前用户信息
- **AND** 未登录用户 SHALL 收到 `401` 响应

#### Scenario: 避免 JWT 作为 MVP 默认登录态
- **WHEN** 设计 MVP 浏览器认证
- **THEN** 系统 MUST NOT 要求前端保存 JWT
- **AND** 系统 MUST NOT 要求使用 JWT 完成浏览器登录态恢复

#### Scenario: 保留 CSRF 防护
- **WHEN** MVP 使用浏览器 Session Cookie 作为登录态
- **THEN** 后端 SHALL 保留 CSRF 防护
- **AND** 后端 SHALL 提供前端可获取 CSRF token 的方式
- **AND** 状态变更 API SHALL 校验约定的 CSRF token

#### Scenario: Session 中不保存秘密
- **WHEN** 后端创建认证 session
- **THEN** session principal MUST NOT 保存明文密码、密码哈希、业务凭据、角色、权限或项目成员列表

### Requirement: 密码和业务凭据必须使用不同安全策略
后端应用 SHALL 使用 Argon2id 保存用户密码哈希，并 SHALL 为 Git/SSH 等业务凭据提供应用层 AES-256-GCM 加密能力。

#### Scenario: 保存用户密码
- **WHEN** 后端保存或更新用户密码
- **THEN** 系统 SHALL 保存 Argon2id 哈希
- **AND** 系统 MUST NOT 保存明文密码
- **AND** 系统 MUST NOT 使用 SHA-256、MD5 等快哈希保存密码
- **AND** 响应、应用日志和审计详情 MUST NOT 包含明文密码或密码哈希

#### Scenario: 加密业务凭据
- **WHEN** 后续凭据管理能力保存 Git token、Git 密码、SSH 密码、SSH 私钥或 SSH passphrase
- **THEN** 秘密载荷 SHALL 使用应用层 AES-256-GCM 加密
- **AND** 加密主密钥 SHALL 来自环境变量或后续 KMS/Vault
- **AND** API、日志和审计 MUST NOT 输出明文秘密

#### Scenario: 区分平台级秘密和业务凭据
- **WHEN** 管理 PostgreSQL、Redis、RabbitMQ 密码或凭据加密主密钥
- **THEN** 这些平台级秘密 SHALL 来自 `.env` 或环境变量
- **AND** Git/SSH 等业务凭据 MAY 写入 `credentials` 表，但秘密字段 MUST 加密保存

### Requirement: 后端骨架提供统一响应和错误格式
后端应用 SHALL 提供统一成功响应包络和统一错误响应结构，使后续 API 与既有 API 设计保持一致。

#### Scenario: 返回成功响应
- **WHEN** 健康检查或后续业务 API 请求成功
- **THEN** 响应 SHALL 包含 `data`、`traceId` 和 `timestamp`

#### Scenario: 返回错误响应
- **WHEN** 请求失败
- **THEN** 响应 SHALL 包含错误 `code`、`message`、可选 `details`、`traceId` 和 `timestamp`

### Requirement: 后端必须封装 Redis 业务缓存客户端
后端应用 SHALL 提供 Redis 业务缓存客户端封装，使业务模块通过统一入口使用 Redis 短期缓存。

#### Scenario: 写入业务缓存
- **WHEN** 后端业务模块需要写入 Redis 短期业务缓存
- **THEN** 业务模块 SHALL 通过统一 Redis 缓存客户端写入
- **AND** 写入操作 MUST 显式指定 TTL
- **AND** 业务模块 SHOULD NOT 直接拼接 Redis key

#### Scenario: 区分业务缓存和 Session
- **WHEN** 后端同时使用 Spring Session Redis 和业务缓存
- **THEN** 业务缓存 key SHALL 使用独立 namespace
- **AND** 业务缓存 namespace MUST NOT 与 Spring Session namespace 混用

#### Scenario: Redis key 使用字符串序列化
- **WHEN** 后端业务缓存客户端写入 Redis key 或 hash key
- **THEN** key 和 hash key MUST 使用字符串序列化
- **AND** value 和 hash value MAY 使用 JSON 序列化
- **AND** Redis 原生命令看到的业务缓存 key MUST NOT 包含 JDK 序列化二进制前缀

#### Scenario: Redis 不承载权威业务状态
- **WHEN** Redis 缓存数据过期、丢失或被清理
- **THEN** 系统 MUST NOT 因此丢失 PostgreSQL 中的权威业务状态

### Requirement: 后端必须封装 RabbitMQ 任务投递客户端
后端应用 SHALL 提供 RabbitMQ 任务投递客户端封装，使长任务创建流程通过统一入口投递任务消息。

#### Scenario: 投递任务消息
- **WHEN** 后端需要将长任务投递到 RabbitMQ
- **THEN** 后端 SHALL 通过统一任务队列客户端投递
- **AND** 任务队列客户端 SHALL 使用可配置的 exchange、queue 和 routing key

#### Scenario: 最小化任务消息载荷
- **WHEN** 后端构造任务队列消息
- **THEN** 消息 MUST 包含 `taskId`
- **AND** 消息 MAY 包含任务类型、项目 ID 或必要路由字段
- **AND** 消息 MUST NOT 包含任务输入快照、任务结果、完整日志、任务状态事实或明文凭据

#### Scenario: 使用 JSON 消息格式
- **WHEN** 任务队列客户端发送 RabbitMQ 消息
- **THEN** 消息 SHOULD 使用 JSON 序列化
- **AND** 消息内容类型 SHOULD 标记为 `application/json`
- **AND** 消息 SHOULD 使用 persistent delivery mode

#### Scenario: 预留手动确认消费模式
- **WHEN** 后续 worker 消费 RabbitMQ 任务消息
- **THEN** RabbitMQ listener ack mode SHOULD 使用 manual
- **AND** worker SHOULD 在任务处理结果已可靠写回 PostgreSQL 后确认消息
- **AND** worker MUST NOT 依赖自动确认作为任务完成信号

### Requirement: 后端骨架提供健康检查和基础测试
后端应用 SHALL 提供最小健康检查接口、可运行且不依赖外部服务的 Maven 单元测试命令，并 SHALL 为后续 API change 预留独立 E2E 测试约定。后端项目自有可执行代码的整体及业务包行覆盖率和分支覆盖率 SHALL 均达到至少 90%。

#### Scenario: 调用健康检查
- **WHEN** 客户端调用 `GET /health` 或 `GET /api/health`
- **THEN** 后端 SHALL 返回统一响应包络
- **AND** 响应 SHALL 表示应用进程可用

#### Scenario: 在无外部服务环境运行单元测试
- **WHEN** 开发者在没有 PostgreSQL、Redis、RabbitMQ、MinIO、SSH、外部 HTTP 服务、Agent CLI 和 Docker 的环境执行普通 Maven 单元测试
- **THEN** 应用 SHALL 完成全部普通单元测试
- **AND** 测试 MUST NOT 发起真实网络连接或依赖宿主机外部服务状态
- **AND** 外部依赖的成功、失败、超时和异常 SHALL 通过 mock 应用客户端、适配器或连接边界的返回值进行验证
- **AND** 独立 API E2E、真实数据库迁移测试和真实环境 smoke test MUST NOT 随普通 `mvn test` 自动运行

#### Scenario: 编写后续 change 测试
- **WHEN** 后续 OpenSpec change 涉及后端代码实现
- **THEN** 该 change SHALL 在任务或设计中明确测试覆盖范围
- **AND** 测试 SHALL 覆盖正常流程、异常流程和 edge case
- **AND** 如果该 change 不补充测试，必须在任务或设计中说明原因

#### Scenario: 编写后续 API change 测试
- **WHEN** 后续 OpenSpec change 新增或修改 HTTP API
- **THEN** 该 change SHALL 同步新增或更新独立 API E2E 测试用例
- **AND** E2E 测试 SHALL 覆盖该 API 的正常流程、异常流程和 edge case
- **AND** 如果该 API 不适合 E2E 测试，change 的设计或任务 MUST 说明原因

#### Scenario: 覆盖正常流程
- **WHEN** 为后端能力编写单元测试、API 测试、轻量集成测试或独立 E2E 测试
- **THEN** 测试 SHALL 覆盖合法输入下的成功创建、查询、更新、任务投递或状态流转
- **AND** 测试 SHALL 对业务结果和关键协作行为包含有效断言

#### Scenario: 覆盖异常流程
- **WHEN** 为后端能力编写单元测试、API 测试、轻量集成测试或独立 E2E 测试
- **THEN** 测试 SHALL 覆盖鉴权失败、资源不存在、参数非法、状态不允许、外部依赖不可用、凭据或敏感配置缺失等错误路径
- **AND** 单元测试 SHALL 通过模拟依赖返回值或异常触发错误路径

#### Scenario: 覆盖 edge case
- **WHEN** 为后端能力编写单元测试、API 测试、轻量集成测试或独立 E2E 测试
- **THEN** 测试 SHALL 覆盖空列表、可选字段为空、重复请求、边界分页、边界数值、重复状态切换、幂等行为、脱敏输出和路径隐藏等边界场景

#### Scenario: 回补既有测试覆盖缺口
- **WHEN** 盘点发现既有后端能力缺少正常流程、异常流程或 edge case 覆盖
- **THEN** 后端 SHALL 通过独立单元测试回补适合在单元层验证的缺口
- **AND** 如果某个缺口不适合在当前测试层回补，change 的设计或任务 MUST 说明不适用原因和替代验证方式
- **AND** API E2E、真实联通或编译成功 MUST NOT 替代项目自有业务逻辑的单元测试覆盖

#### Scenario: 测试 jOOQ Repository
- **WHEN** 单元测试验证 Repository 的查询构造和数据映射
- **THEN** 测试 SHALL 在 jOOQ/JDBC 连接边界提供模拟结果
- **AND** 测试 SHALL 验证关键 SQL 条件、绑定参数、排序、分页、空结果、返回 Record 映射和数据库异常
- **AND** 测试 MUST NOT 连接真实 PostgreSQL 或以 mock Repository 自身代替 Repository 测试

#### Scenario: 验收覆盖率
- **WHEN** 开发者执行后端覆盖率验收命令
- **THEN** JaCoCo SHALL 将未被测试加载的项目自有生产类纳入统计
- **AND** BUNDLE 行覆盖率和分支覆盖率 SHALL 分别达到至少 90%
- **AND** 项目自有业务 package 的行覆盖率以及存在分支时的分支覆盖率 SHALL 分别达到至少 90%
- **AND** 默认仅可排除自动生成的 jOOQ 源码，不得排除项目自有业务逻辑或缩小统计范围
- **AND** 无有效断言的调用、空测试或仅加载类 MUST NOT 作为满足覆盖率的手段

#### Scenario: 为测试调整生产代码结构
- **WHEN** 现有实现直接创建外部连接、进程或静态依赖，导致异常路径无法稳定进行单元测试
- **THEN** 实现 MAY 提取可注入执行边界或纯函数
- **AND** 调整 MUST 保持原调用顺序、输入、输出、错误码和副作用语义不变
- **AND** 调整前 SHALL 通过特征测试锁定现有行为

#### Scenario: 单元测试发现明确的规格违例
- **WHEN** 新增单元测试证明现有业务实现违反已确认规格
- **THEN** 本 change MUST 记录最小复现、预期行为、实际行为和影响范围
- **AND** 本 change MUST NOT 未经确认直接修改该业务逻辑
- **AND** 对应测试和质量验收 MUST 保持未通过，直到独立修复 change 处理完成

#### Scenario: 单元测试发现语义尚不明确的可疑行为
- **WHEN** 新增单元测试发现可能错误但缺少明确规格依据的行为
- **THEN** 测试 SHALL 以 characterization test 锁定当前行为
- **AND** 本 change SHALL 登记该疑点和待确认决策
- **AND** 本 change MUST NOT 擅自把假设作为新业务行为实现

### Requirement: API 和 worker 同仓库但不同启动入口
后端工程 SHALL 支持 API 和 worker 在同一仓库、同一依赖包内开发，并 SHALL 为二者保留不同启动入口或启动参数约定。

#### Scenario: 启动 API
- **WHEN** 开发者以 API 模式启动后端
- **THEN** 应用 SHALL 启动 HTTP API 能力

#### Scenario: 启动 worker
- **WHEN** 后续开发者以 worker 模式启动后端
- **THEN** 应用 SHALL 能运行异步任务消费逻辑
- **AND** worker SHALL 复用同一套配置、数据访问、日志和安全基础组件

### Requirement: 后端构建必须支持 test-support 源码排除
后端 Maven 构建 SHALL 支持默认生产构建排除 test-support 源码，并 SHALL 支持显式 test-support 构建。

#### Scenario: 默认构建排除 test-support
- **WHEN** 开发者执行默认 `mvn package`
- **THEN** Maven SHALL NOT 编译 test-support 源码目录
- **AND** jar 中 MUST NOT 包含 test-support API 类或资源

#### Scenario: 显式构建包含 test-support
- **WHEN** 开发者执行带 test-support profile 的 Maven 构建或运行命令
- **THEN** Maven SHALL 编译 test-support 源码目录
- **AND** 后端 SHALL 能加载 test-support API 相关 bean

#### Scenario: 文档说明构建差异
- **WHEN** 开发者查看后端启动和构建文档
- **THEN** 文档 SHALL 明确区分生产构建命令和 E2E/test-support 构建命令

### Requirement: 后端生产安全配置不得依赖旧 dev session
后端默认生产源码和安全配置 SHALL 不再暴露 `/api/dev/session`。

#### Scenario: 默认生产运行
- **WHEN** 后端以默认生产构建启动
- **THEN** `/api/dev/session` SHALL 不可访问
- **AND** 安全配置 MUST NOT 对 `/api/dev/session` 配置 permit-all

#### Scenario: E2E 需要创建 session
- **WHEN** E2E 需要绕过交互式登录创建测试 session
- **THEN** E2E SHALL 使用 `/api/test-support/session`

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

### Requirement: 自动化测试运行时必须覆盖三类测试
自动化测试运行时实现 SHALL 配套单元测试、轻量集成测试和必要的独立 API E2E 测试，并 SHALL 覆盖正常流程、异常流程和 edge case。

#### Scenario: 正常流程测试
- **WHEN** 为自动化测试运行时编写测试
- **THEN** 测试 SHALL 覆盖创建测试任务、远程执行成功、测试输出回收、报告文件回收、日志保存、摘要入库和 SSE 状态事件

#### Scenario: 异常流程测试
- **WHEN** 为自动化测试运行时编写测试
- **THEN** 测试 SHALL 覆盖无权限访问、项目或源码版本不存在、环境不属于项目、构建运行不属于项目、SSH 执行失败、报告路径非法和任务取消

#### Scenario: Edge case 测试
- **WHEN** 为自动化测试运行时编写测试
- **THEN** 测试 SHALL 覆盖测试命令非 0 退出、测试命令超时、空日志、大日志摘要、stdout 很少但报告存在、报告目录无匹配、报告数量或大小超限、未传计数字段、重复取消和日志脱敏

### Requirement: 构建环境变量实现必须覆盖三类测试
构建环境变量实现 SHALL 配套单元测试、轻量集成测试和独立 API E2E 测试，并 SHALL 覆盖正常流程、异常流程和 edge case。

#### Scenario: 正常流程测试
- **WHEN** 为构建环境变量能力编写测试
- **THEN** 测试 SHALL 覆盖创建带环境变量的构建任务、worker 执行时读取变量、构建成功入库、日志引用保存和 SSE 状态事件

#### Scenario: 异常流程测试
- **WHEN** 为构建环境变量能力编写测试
- **THEN** 测试 SHALL 覆盖无权限访问、项目不存在、源码版本不属于项目、执行环境不属于项目、非法环境变量名、变量值超长、变量数量超限和 SSH 执行失败

#### Scenario: Edge case 测试
- **WHEN** 为构建环境变量能力编写测试
- **THEN** 测试 SHALL 覆盖空环境变量对象、未传环境变量、包含空字符串值、包含空格或引号的值、敏感变量名脱敏、重复构建请求和非 0 退出码

### Requirement: 测试结果比较实现必须覆盖三类测试
测试结果比较实现 SHALL 配套单元测试、轻量集成测试和独立 API E2E 测试，并 SHALL 覆盖正常流程、异常流程和 edge case。

#### Scenario: 正常流程测试
- **WHEN** 为测试结果比较能力编写测试
- **THEN** 测试 SHALL 覆盖同一自动化测试批次完成后自动创建比较任务、读取两条测试记录、聚合日志和报告、解析阶段模型、调用 agent、保存比较摘要、保存差异项、保存 agent 输出 artifact 和 SSE 状态事件

#### Scenario: 异常流程测试
- **WHEN** 为测试结果比较能力编写测试
- **THEN** 测试 SHALL 覆盖无权限访问、项目不存在、测试记录不存在、测试记录非同项目、测试记录未完成、模型未配置、模型不可用、agent 超时、agent 退出失败和 agent 输出不可解析

#### Scenario: Edge case 测试
- **WHEN** 为测试结果比较能力编写测试
- **THEN** 测试 SHALL 覆盖空日志、只有 stdout、只有 stderr、报告 artifact 缺失、大日志裁剪、大报告裁剪、无差异项、低置信度输出、重复创建比较、取消运行中比较和敏感信息脱敏

#### Scenario: test-support 手动触发接口测试
- **WHEN** 为测试结果比较能力编写 E2E 或开发辅助测试
- **THEN** 测试 MAY 使用 test-support only 的手动触发比较接口
- **AND** 测试 SHALL 验证默认生产构建不包含该接口
- **AND** 测试 SHALL 验证该接口不接受模型参数、prompt、完整日志或 provider secret
