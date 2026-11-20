## Purpose

定义构建验证运行时能力：基于成功的源码部署记录在远端环境执行用户提交的构建脚本，记录构建结果、日志和可查询状态，并通过任务事件/SSE 支持前端观察。
## Requirements
### Requirement: 构建验证必须基于成功源码部署创建
后端 SHALL 提供构建验证创建 API，并 SHALL 创建构建任务和构建运行。`BUILD_VALIDATION` worker SHALL 在执行构建脚本前基于项目当前运行环境配置自动创建或复用同源码版本、同环境配置版本下的源码部署记录。构建验证不再要求前端预先调用源码部署创建接口。

#### Scenario: 创建双环境构建验证
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/builds` 并提交构建脚本、可选源码版本和超时时间
- **THEN** 后端 SHALL 校验当前用户有权访问该项目
- **AND** 后端 SHALL 读取项目当前运行环境配置
- **AND** 后端 SHALL 校验源环境和目标环境都已配置且已启用
- **AND** 后端 SHALL 为 source/x86 和 target/RISC-V 环境分别创建独立的 `BUILD_VALIDATION` 任务和 `build_runs` 记录
- **AND** 每个 `BUILD_VALIDATION` worker SHALL 在执行构建脚本前查找同源码版本、同环境配置版本下的可复用源码部署记录
- **AND** 对缺少成功部署的环境，worker SHALL 在当前构建任务内执行源码部署
- **AND** 对已经存在成功部署的环境，worker SHALL 直接复用对应 `source_deploy_runs` 记录
- **AND** 两条构建运行 SHALL 保存同一个环境配置版本
- **AND** 响应 SHALL 返回构建运行 ID、任务 ID、环境 ID、源码版本 ID、环境配置版本和初始状态

#### Scenario: 缺少成功部署时自动准备
- **WHEN** 当前运行环境下缺少源环境或目标环境的成功源码部署记录
- **THEN** 后端 SHALL 仍然创建对应环境的构建任务和构建运行
- **AND** `BUILD_VALIDATION` worker SHALL 在当前构建任务内自动执行源码部署
- **AND** 后端 MUST NOT 返回 `SOURCE_DEPLOYMENT_NOT_READY` 要求前端先手动部署
- **AND** 对应环境构建脚本 SHALL 在源码部署成功后开始执行

#### Scenario: 拒绝跨版本部署
- **WHEN** 存在成功源码部署记录但其环境配置版本不是当前项目运行环境配置版本
- **THEN** 后端 SHALL 将该部署记录视为非当前可用输入
- **AND** `BUILD_VALIDATION` worker SHALL 为当前环境配置版本自动创建或复用新的源码部署记录

#### Scenario: 任一环境部署或构建失败不阻止另一环境
- **WHEN** 双环境构建任务已经创建
- **THEN** 任一环境部署或构建失败 MUST NOT 阻止另一环境任务执行或写回结果
- **AND** 两侧部署和构建结果 SHALL 独立保存退出码、状态和日志引用

#### Scenario: 拒绝客户端指定源码部署记录
- **WHEN** 用户创建构建验证并提交 `sourceDeploymentIds`
- **THEN** 后端 SHALL 返回 `BUILD_SOURCE_DEPLOYMENTS_DEPRECATED`
- **AND** 后端 MUST NOT 使用客户端提交的源码部署记录作为构建输入

### Requirement: 构建脚本必须安全存储和执行
后端 SHALL 对构建脚本做输入校验、加密存储和远端临时脚本执行。

#### Scenario: 保存构建脚本
- **WHEN** 用户创建构建验证任务
- **THEN** 后端 SHALL 将完整构建脚本加密保存到 `task_script_payloads`
- **AND** `tasks.input_snapshot` MUST NOT 保存完整构建脚本明文
- **AND** `build_runs.command` SHALL 只保存脱敏命令摘要或脚本哈希

#### Scenario: 拒绝空脚本
- **WHEN** 用户提交空构建脚本或纯空白构建脚本
- **THEN** 后端 SHALL 返回参数校验错误
- **AND** 后端 MUST NOT 创建任务或脚本 payload

#### Scenario: 远端脚本执行
- **WHEN** `BUILD_VALIDATION` worker 执行任务
- **THEN** worker SHALL 在远端创建临时脚本文件
- **AND** worker SHALL 在 `source_deploy_runs.remote_path` 目录中执行该脚本
- **AND** worker SHALL 在执行完成、失败、超时或取消后尝试删除远端临时脚本

### Requirement: 构建验证必须持久化运行结果
构建验证 worker SHALL 将构建运行状态和任务状态可靠写回 PostgreSQL，并 SHALL 保存 merged/stdout/stderr 三类日志引用。

#### Scenario: 构建成功
- **WHEN** 远端构建脚本退出码为 `0`
- **THEN** worker SHALL 将任务和 `build_runs.status` 标记为 `SUCCEEDED`
- **AND** worker SHALL 写入 `exit_code=0`、merged/stdout/stderr 日志引用、开始时间和结束时间
- **AND** worker SHALL 追加任务终态事件供项目级 SSE 推送

#### Scenario: 构建失败
- **WHEN** 远端构建脚本退出码非 `0`
- **THEN** worker SHALL 将任务和 `build_runs.status` 标记为 `FAILED`
- **AND** worker SHALL 写入退出码、失败分类、merged/stdout/stderr 日志引用和安全错误摘要
- **AND** worker SHALL 在失败状态可靠写回后确认 RabbitMQ 消息

#### Scenario: SSH 或远端命令失败
- **WHEN** SSH 连接失败、认证失败、远端工作目录不存在或远端命令无法启动
- **THEN** worker SHALL 将任务和 `build_runs.status` 标记为 `FAILED`
- **AND** 失败摘要 MUST NOT 暴露明文凭据或服务器本机路径

#### Scenario: 构建超时
- **WHEN** 构建执行超过 `timeoutSeconds`
- **THEN** worker SHALL 尝试终止远端命令
- **AND** worker SHALL 将任务和 `build_runs.status` 标记为 `TIMEOUT`
- **AND** worker SHALL 写入失败分类 `BUILD_TIMEOUT`

### Requirement: 构建验证必须支持日志和 SSE 观察
构建验证 SHALL 复用任务日志、任务日志内容读取 API 和项目级 SSE，使前端能观察运行中状态和完成后历史结果。

#### Scenario: 推送运行中状态
- **WHEN** 构建任务进入 `RUNNING`
- **THEN** 后端 SHALL 追加任务事件
- **AND** 项目级 SSE SHALL 向有项目权限的订阅方推送该事件
- **AND** SSE 事件 MUST 只发送给同一 `projectId` 的连接

#### Scenario: 记录构建输出
- **WHEN** worker 捕获远端 stdout 或 stderr
- **THEN** 后端 SHALL 写入 stdout、stderr 和 merged 任务日志文件
- **AND** 后端 SHALL 写入任务日志事件用于运行中 SSE
- **AND** 响应 MUST NOT 暴露服务器真实日志文件路径

#### Scenario: 完成后读取构建日志内容
- **WHEN** 构建任务进入终态且前端需要展示历史日志
- **THEN** 前端 SHALL 通过 `GET /api/tasks/{taskId}/log-content` 读取 merged/stdout/stderr 日志内容
- **AND** 构建详情响应 SHALL 返回 merged/stdout/stderr 日志 ID 或明确缺失状态

### Requirement: 构建验证查询必须支持分页排序过滤
后端 SHALL 提供构建验证列表、详情和取消 API，并 SHALL 对所有访问执行项目权限校验。构建详情 SHALL 返回可用于前端回填的完整构建脚本；构建列表 SHALL 保持轻量并使用摘要字段展示脚本信息。

#### Scenario: 查询构建列表
- **WHEN** 用户调用 `GET /api/projects/{projectId}/builds`
- **THEN** 后端 SHALL 返回分页构建运行列表
- **AND** 列表 SHALL 支持按 `sourceRevisionId`、`environmentId`、`status` 和创建时间范围过滤
- **AND** 列表 SHALL 支持按 `createdAt`、`startedAt`、`finishedAt`、`status` 和 `environmentId` 排序
- **AND** 列表响应 SHALL 返回构建脚本摘要字段 `commandSummary`
- **AND** 列表响应 MUST NOT 批量返回完整构建脚本明文用于回填

#### Scenario: 查询构建详情
- **WHEN** 用户调用 `GET /api/builds/{buildId}`
- **THEN** 后端 SHALL 根据构建所属项目执行项目访问守卫
- **AND** 响应 SHALL 包含构建运行 ID、项目 ID、任务 ID、源码版本摘要、环境摘要、源码部署 ID、状态、退出码、失败分类、merged/stdout/stderr 日志引用和时间字段
- **AND** 响应 SHALL 在 `command` 中返回该构建运行提交的完整构建脚本，用于前端回填
- **AND** 响应 SHALL 在 `commandSummary` 中返回脱敏脚本摘要
- **AND** 响应 MUST NOT 返回明文凭据或服务器本机路径

#### Scenario: 构建详情脚本 payload 缺失或校验失败
- **WHEN** 用户查询构建详情
- **AND** 对应构建运行的脚本 payload 不存在、无法解密或 SHA-256 校验失败
- **THEN** 后端 SHALL 返回安全错误
- **AND** 后端 MUST NOT 使用 `build_runs.command` 摘要冒充完整构建脚本
- **AND** 错误响应 MUST NOT 暴露脚本内容、密钥信息或服务器本机路径

#### Scenario: 取消构建
- **WHEN** 用户调用 `POST /api/builds/{buildId}/cancel`
- **THEN** 后端 SHALL 根据构建所属项目执行项目访问守卫
- **AND** 后端 SHALL 复用任务取消服务取消对应任务
- **AND** `build_runs.status` SHALL 最终与任务终态一致

#### Scenario: 无权限访问构建
- **WHEN** 无项目权限用户查询或取消构建运行
- **THEN** 后端 SHALL 返回与资源不存在一致的安全错误
- **AND** 后端 MUST NOT 暴露构建运行是否存在

### Requirement: 构建验证必须支持前置 source-only 构建
构建验证运行时 SHALL 在保持默认双环境构建行为不变的前提下，支持用于依赖分析前置的 source/x86 单环境构建范围。

#### Scenario: 默认双环境构建
- **WHEN** 用户创建构建验证且未提交 `buildScope`
- **THEN** 后端 SHALL 使用 `BOTH` 作为默认构建范围
- **AND** 后端 SHALL 为 source/x86 和 target/RISC-V 创建构建运行
- **AND** 每个构建任务执行时 SHALL 自动准备或复用自身环境的源码部署
- **AND** 该行为 SHALL 与现有正式构建验证节点兼容

#### Scenario: 创建 source-only 构建
- **WHEN** 用户创建构建验证并提交 `buildScope=SOURCE_ONLY`
- **THEN** 后端 SHALL 只为项目当前 source/x86 环境创建构建运行
- **AND** 该构建任务执行时 SHALL 自动执行或复用 source 环境源码部署
- **AND** 后端 MUST NOT 为 target/RISC-V 环境创建源码部署或构建运行

#### Scenario: source-only 构建作为依赖分析前置
- **WHEN** source-only 构建成功
- **THEN** 构建运行 SHALL 保存源码版本、source 环境、环境配置版本、构建日志引用、构建脚本摘要和产物事实摘要
- **AND** 这些事实 SHALL 能被依赖分析任务引用

#### Scenario: 拒绝不支持的构建范围
- **WHEN** 构建创建请求提交 MVP 不支持的 `buildScope`
- **THEN** 后端 SHALL 返回统一参数校验错误
- **AND** 后端 MUST NOT 创建构建运行、任务或队列消息

#### Scenario: 正式构建验证仍使用双环境
- **WHEN** 前端进入正式构建验证节点
- **THEN** 前端 SHALL 默认调用 `buildScope=BOTH` 或省略该参数
- **AND** 后端 SHALL 同时验证 source/x86 和 target/RISC-V 构建结果

#### Scenario: 前置构建可选
- **WHEN** 用户没有发起 SOURCE_ONLY 构建
- **THEN** SHALL 保持未执行事实，不创建跳过记录，不阻止三个源码分析或后续 BOTH 构建。

#### Scenario: source-only 无目标环境
- **WHEN** 源码、源环境与脚本有效但目标环境尚未配置
- **THEN** SHALL 允许 SOURCE_ONLY，只准备源端，不以 requireComplete 双环境配置阻塞。

### Requirement: 构建验证必须采集 CMake compiler flag probe 映射
构建验证运行时 SHALL 在构建事实采集中扫描 CMake 脚本并输出 compiler flag probe 映射，供依赖分析准确解释构建 define。

#### Scenario: 输出 CMake flag probe 观测
- **WHEN** 构建 wrapper 解析到 CMake compiler flag 检测调用
- **THEN** wrapper SHALL 输出结构化 `cmakeCompilerFlagProbe` 观测
- **AND** 观测 SHALL 包含变量名、语言、真实 compiler flag 和来源文件
- **AND** 观测 MUST NOT 包含服务器真实路径或明文凭据

#### Scenario: CMake probe 采集失败不影响构建
- **WHEN** CMake 脚本解析失败、文件不可读或扫描超出限制
- **THEN** 构建验证 SHALL 继续按用户脚本结果决定构建状态
- **AND** wrapper SHALL 记录 warning 或跳过不可解析文件
- **AND** CMake probe 采集失败 MUST NOT 导致成功构建变为失败

#### Scenario: 非 CMake 项目不输出 probe
- **WHEN** 源码树和构建目录不存在 CMake 文件
- **THEN** wrapper SHALL 不输出 `cmakeCompilerFlagProbe` 观测
- **AND** 依赖分析 SHALL 继续使用其他构建事实信号

### Requirement: 构建验证必须保存 compile database 构建上下文
构建验证 worker SHALL 在成功构建后保存可复用的 `compile_commands.json` 构建上下文制品，并 SHALL 将安全摘要写入构建事实。

#### Scenario: 成功保存 compile database artifact
- **WHEN** `BUILD_VALIDATION` worker 在远端源码部署目录完成构建且退出码为 `0`
- **AND** 远端工作目录存在合法 `compile_commands.json`
- **THEN** worker SHALL 将该文件下载到后端受控临时位置
- **AND** worker SHALL 校验 JSON 顶层为数组
- **AND** worker SHALL 将文件保存为对象存储制品
- **AND** worker SHALL 在 `build_runs.build_facts.compileDatabase` 中写入 `available=true`、stored artifact ID、sha256、size bytes、command count、source environment ID 和 build run ID

#### Scenario: compile database 不存在时构建仍成功
- **WHEN** 构建命令退出码为 `0`
- **AND** 远端工作目录不存在 `compile_commands.json`
- **THEN** worker SHALL 将构建运行标记为 `SUCCEEDED`
- **AND** worker SHALL 在 `build_runs.build_facts.compileDatabase` 中写入 `available=false`
- **AND** worker MUST NOT 因缺少 compile database 将成功构建改为失败

#### Scenario: compile database 无效时降级
- **WHEN** 构建命令退出码为 `0`
- **AND** 远端 `compile_commands.json` 下载失败、超过大小限制或 JSON 格式非法
- **THEN** worker SHALL 将构建运行标记为 `SUCCEEDED`
- **AND** worker SHALL 记录 warning 和安全失败原因
- **AND** `build_runs.build_facts.compileDatabase.available` SHALL 为 `false`

#### Scenario: 构建详情只返回安全摘要
- **WHEN** 用户查询包含 compile database 的构建详情
- **THEN** 响应 SHALL 返回 compile database 是否可用、artifact ID、sha256、size bytes、command count 和来源构建摘要
- **AND** 响应 MUST NOT 返回完整 `compile_commands.json`、远端工作目录、服务器本机路径、bucket 或 object key

### Requirement: 构建验证列表必须默认收敛到当前上下文
构建验证列表 API SHALL 默认返回当前 active source revision 和当前运行环境配置版本下的构建记录，并 SHALL 支持显式查看历史记录。

#### Scenario: 默认查询当前构建记录
- **WHEN** 用户调用 `GET /api/projects/{projectId}/builds` 且未指定 `sourceRevisionId`、`environmentConfigVersion` 或 `includeStale`
- **THEN** 后端 SHALL 使用项目 active source revision 作为默认源码版本
- **AND** 后端 SHALL 使用当前项目运行环境配置版本作为默认环境版本
- **AND** 响应 MUST NOT 混入旧源码版本或旧运行环境配置版本的构建记录

#### Scenario: 显式查看历史构建记录
- **WHEN** 用户调用构建列表 API 并显式提交历史 `sourceRevisionId`、历史 `environmentConfigVersion` 或 `includeStale=true`
- **THEN** 后端 SHALL 按请求参数返回匹配历史记录
- **AND** 响应 SHALL 标明每条记录是否属于当前源码版本和当前运行环境配置版本

#### Scenario: 构建记录返回陈旧原因
- **WHEN** 构建记录不属于当前 active source revision 或当前运行环境配置版本
- **THEN** 响应 SHALL 返回 `currentSourceRevision=false` 或 `currentRuntimeEnvironment=false`
- **AND** 响应 SHALL 返回 `staleReasons`，取值至少支持 `SOURCE_REVISION_CHANGED` 和 `RUNTIME_ENVIRONMENT_CHANGED`

### Requirement: 构建任务必须暴露内部部署阶段状态
构建验证任务 SHALL 将内部源码部署阶段写入同一构建任务的进度、日志和结果状态，使前端无需理解源码部署业务接口也能展示进度。

#### Scenario: 推送内部部署阶段
- **WHEN** 构建任务开始执行源码部署步骤
- **THEN** worker SHALL 写入任务进度和 merged/stdout/stderr 日志
- **AND** 项目级 SSE SHALL 推送同一 `BUILD_VALIDATION` 任务的阶段状态
- **AND** 失败摘要 MUST NOT 暴露明文凭据或服务器本机路径

#### Scenario: 回填源码部署记录
- **WHEN** 构建任务复用或完成源码部署
- **THEN** worker SHALL 将实际使用的 `source_deploy_runs.id` 写入 `build_runs.source_deploy_run_id`
- **AND** 构建详情响应 SHALL 返回该 `sourceDeploymentId`

### Requirement: 构建运行必须提供 latest
构建运行领域 SHALL 提供当前 Context 下的 latest 接口，支持区分 x86 构建和双环境构建验证。latest 返回当前记录时 SHALL 提供可回填构建脚本和脚本摘要。

#### Scenario: 查询 x86 构建 latest
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/builds/latest?mode=SOURCE_ONLY`
- **THEN** 后端 SHALL 返回统一 latest 外壳
- **AND** `execution` SHALL 指向当前源码版本和当前运行环境版本下最新 source-only 构建运行
- **AND** `result` SHALL 包含构建环境、部署记录、构建状态、日志入口和 artifact 摘要
- **AND** 当 `freshness=CURRENT` 且 `result` 存在时，`result.command` SHALL 返回完整构建脚本用于前端回填
- **AND** 当 `result` 存在时，`result.commandSummary` SHALL 返回脱敏脚本摘要

#### Scenario: 查询构建验证 latest
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/builds/latest?mode=VALIDATION`
- **THEN** 后端 SHALL 返回统一 latest 外壳
- **AND** `execution` SHALL 指向当前源码版本和当前运行环境版本下最新构建验证批次或运行摘要
- **AND** `result` SHALL 包含 x86 与 RISC-V 构建运行摘要
- **AND** 当 `freshness=CURRENT` 且 `result` 存在时，`result.command` SHALL 返回完整构建脚本用于前端回填
- **AND** 当 `result` 存在时，`result.commandSummary` SHALL 返回脱敏脚本摘要

#### Scenario: latest 脚本 payload 缺失或校验失败
- **WHEN** 用户查询构建 latest
- **AND** latest 选中的构建运行脚本 payload 不存在、无法解密或 SHA-256 校验失败
- **THEN** 后端 SHALL 返回安全错误
- **AND** 后端 MUST NOT 使用 `build_runs.command` 摘要冒充完整构建脚本

#### Scenario: 构建 latest 不跳过失败
- **WHEN** 当前 Context 下最新构建运行失败
- **THEN** latest SHALL 返回失败 execution
- **AND** 后端 MUST NOT 回退到更早成功构建

### Requirement: 构建 readiness 必须返回源码和环境事实
项目级 readiness 在 `stageId=x86-build` 或 `stageId=build` 时 SHALL 返回构建所需前置条件。

#### Scenario: 查询 x86 构建 readiness
- **WHEN** 用户调用 `GET /api/projects/{projectId}/readiness?stageId=x86-build`
- **THEN** 后端 SHALL 返回 `ACTIVE_SOURCE` 和 `SOURCE_ENVIRONMENT`
- **AND** 如果已有当前 Context 的源码部署执行记录，后端 SHALL 返回 `SOURCE_DEPLOYMENT`

#### Scenario: 查询双环境构建 readiness
- **WHEN** 用户调用 `GET /api/projects/{projectId}/readiness?stageId=build`
- **THEN** 后端 SHALL 返回 `ACTIVE_SOURCE`、`SOURCE_ENVIRONMENT` 和 `TARGET_ENVIRONMENT`
- **AND** 缺少任一运行环境时对应 prerequisite SHALL 返回 `MISSING`

#### Scenario: 环境配置过期
- **WHEN** 构建相关记录的 `environmentConfigVersion` 不等于项目当前运行环境版本
- **THEN** 对应 prerequisite SHALL 返回 `STALE`
- **AND** `reasonCode` SHALL 为 `RUNTIME_ENVIRONMENT_CHANGED`

#### Scenario: 直接发起后续构建验证
- **WHEN** 源码、所需源/目标环境和有效脚本已具备，但没有前置 x86 成功记录或报告
- **THEN** SHALL 允许 BOTH 验证创建并在任务内准备源码，不要求前置构建、报告或人工确认；配置就绪不承诺实际连接/构建成功，运行失败如实保存。

### Requirement: x86 构建必须执行非阻断共享 BC 后处理
构建验证 worker SHALL 在当前 source/x86 构建成功后执行共享 LLVM BC 后处理，但真实构建退出码 MUST 是决定构建成功或失败的唯一依据。

#### Scenario: BC 成功且构建成功
- **WHEN** 用户构建命令退出码为 `0`
- **AND** BC artifact-set 状态为 `READY` 或 `PARTIAL`
- **THEN** 构建运行 SHALL 标记为 `SUCCEEDED`
- **AND** `build_facts.bitcode` SHALL 保存安全状态摘要和 stored artifact 引用

#### Scenario: BC 失败但构建仍成功
- **WHEN** 用户构建命令退出码为 `0`
- **AND** BC 生成、验证、回收或持久化失败
- **THEN** 构建运行 SHALL 标记为 `SUCCEEDED`
- **AND** BC 状态 SHALL 为 `FAILED`、`UNSUPPORTED` 或适用的非成功状态
- **AND** worker SHALL 记录 warning 而不是抛出改变构建结果的异常

#### Scenario: 构建失败不被 BC 状态覆盖
- **WHEN** 用户构建命令退出码非 `0`
- **THEN** 构建运行 SHALL 按现有构建失败语义结束
- **AND** BC 状态 MUST NOT 将构建改为成功

#### Scenario: 构建任务等待 BC 达到终态
- **WHEN** 用户构建命令已经成功且 worker 已进入 BC 后处理
- **THEN** 构建任务 SHALL 保持运行状态并更新进度和安全日志
- **AND** 构建任务只在 BC 达到 `READY`、`PARTIAL`、`FAILED` 或 `UNSUPPORTED` 后结束

### Requirement: 构建查询必须返回共享 BC 安全摘要
构建列表、详情和 latest 响应 SHALL 返回统一 `bitcode` 安全摘要，供前端识别 BC 是否可用、生成质量、协议版本、可移植源码身份和编译上下文身份。

#### Scenario: 返回当前 v2 BC 摘要
- **WHEN** 用户查询包含 v2 BC 构建事实的构建记录
- **THEN** 响应 SHALL 返回 `available`、`status`、artifact-set ID、planned/succeeded/failed/skipped 数量、toolchain ID、schema/profile、source identity digest、compile context digest、reason code、warning count、generated at 和 `legacy=false`
- **AND** 响应 MUST NOT 返回 BC 内容、完整 manifest、完整编译命令、远端绝对路径、服务器本机路径、bucket 或 object key

#### Scenario: latest 返回 BC 摘要
- **WHEN** 用户查询 source-only 或 validation 构建 latest
- **THEN** latest 的构建结果 SHALL 使用与详情接口相同的 `bitcode` 摘要结构

#### Scenario: 兼容 v1 历史记录
- **WHEN** 历史构建事实包含 v1 BC 安全摘要
- **THEN** 响应 SHALL 返回已有状态、可用性、计数和协议版本
- **AND** SHALL 返回 `legacy=true`
- **AND** 不存在于 v1 的 v2 digest 字段 SHALL 返回 `null`，不得伪造

#### Scenario: 兼容没有 BC 字段的历史记录
- **WHEN** 历史构建记录不存在 `build_facts.bitcode`
- **THEN** 响应 SHALL 返回 `available=false`、`status=NOT_ATTEMPTED`、`reasonCode=LEGACY_RECORD` 和 `legacy=true`
- **AND** 后端 MUST NOT 为读取历史记录临时生成 BC
