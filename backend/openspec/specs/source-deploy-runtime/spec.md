## Purpose

定义源码部署运行时能力：将项目源码版本部署到一个或多个远程环境工作目录，为后续构建验证提供稳定的远端源码输入。
## Requirements
### Requirement: 源码部署任务必须绑定项目源码版本和环境
后端 SHALL 将源码部署作为构建验证任务内部准备能力，将指定项目的指定源码版本部署到项目当前运行环境配置中的源环境和目标环境。正式前端业务流程 MUST NOT 依赖单独的源码部署创建接口来启动构建。

#### Scenario: 构建任务执行时准备源码部署
- **WHEN** `BUILD_VALIDATION` worker 开始处理构建任务
- **AND** 当前源码版本在所需运行环境中缺少同环境配置版本的成功源码部署记录
- **THEN** 后端 SHALL 校验任务中的源码版本属于任务项目
- **AND** 后端 SHALL 读取任务绑定的环境和环境配置版本
- **AND** 后端 SHALL 校验所需源环境或目标环境已配置且已启用
- **AND** 后端 SHALL 在当前构建任务内创建 `source_deploy_runs` 记录并执行源码传输
- **AND** 后端 SHALL 将成功部署记录 ID 回填到 `build_runs.source_deploy_run_id`
- **AND** 后端 SHALL 继续在部署目录执行构建脚本

#### Scenario: 创建双环境构建时准备源码部署
- **WHEN** 项目当前运行环境配置包含 x86 原始环境和 RISC-V 目标环境
- **AND** 用户创建默认双环境构建验证
- **THEN** 后端 SHALL 分别创建两个 `BUILD_VALIDATION` 任务和构建记录
- **AND** 每个构建任务执行时 SHALL 为自身环境创建或复用源码部署记录
- **AND** 两个部署记录 SHALL 绑定同一个 `sourceRevisionId`
- **AND** 两个部署记录 SHALL 保存同一个环境配置版本
- **AND** 两个部署记录 SHALL 拥有各自基于环境工作目录生成的 `remote_path`

#### Scenario: 创建 source-only 构建时准备源码部署
- **WHEN** 用户创建 `buildScope=SOURCE_ONLY` 的构建验证
- **THEN** 后端 SHALL 只创建项目当前 source/x86 环境的构建任务和构建记录
- **AND** 该构建任务执行时 SHALL 为 source/x86 环境创建或复用源码部署记录
- **AND** 后端 MUST NOT 为 target/RISC-V 环境创建源码部署记录

#### Scenario: 拒绝跨项目源码部署
- **WHEN** 构建创建或执行过程需要准备源码部署
- **AND** `sourceRevisionId` 不属于 URL 中的 `projectId` 或项目运行环境配置引用了非法环境
- **THEN** 后端 SHALL 拒绝请求
- **AND** 后端 MUST NOT 创建任务、源码部署记录或队列消息

#### Scenario: 拒绝不可用源码工作副本
- **WHEN** 构建执行过程需要准备源码部署
- **AND** 源码版本缺少可读的后端本机工作副本
- **THEN** 后端 SHALL 返回 `SOURCE_REVISION_WORKING_COPY_NOT_FOUND`
- **AND** 错误响应 MUST NOT 暴露服务器本机源码路径
- **AND** 后端 MUST NOT 投递队列消息

### Requirement: 源码部署路径必须由后端生成
后端 SHALL 使用环境工作目录生成远端源码部署路径，并 MUST NOT 接受客户端提交的任意远端部署路径。

#### Scenario: 生成远端部署路径
- **WHEN** 后端创建源码部署记录
- **THEN** `remote_path` SHALL 使用 `<environment.working_directory>/rvport-sources/<sourceRevisionId>` 生成
- **AND** 后端 SHALL 将该路径保存到 `source_deploy_runs.remote_path`
- **AND** 任务输入快照 SHALL 只保存部署记录 ID、项目 ID、源码版本 ID、环境 ID、force 和 timeout 配置

#### Scenario: 拒绝缺少工作目录的环境
- **WHEN** 用户创建源码部署任务
- **AND** 目标环境 `working_directory` 为空
- **THEN** 后端 SHALL 返回 `ENVIRONMENT_WORKING_DIRECTORY_REQUIRED`
- **AND** 后端 MUST NOT 创建部署任务或投递队列消息

#### Scenario: 不允许客户端指定远端路径
- **WHEN** 用户创建源码部署任务并提交 `remotePath` 或等效字段
- **THEN** 后端 SHALL 忽略该字段或返回参数校验错误
- **AND** 实际部署路径 MUST 继续使用后端生成路径

### Requirement: SOURCE_DEPLOY Worker 必须通过 SSH 同步源码
`SOURCE_DEPLOY` handler SHALL 通过现有任务执行上下文和 SSH runtime 将源码工作副本同步到远端部署路径。

#### Scenario: 源码部署成功
- **WHEN** worker 认领 `SOURCE_DEPLOY` 任务
- **THEN** worker SHALL 读取 `source_deploy_runs`、`source_revisions` 和 `environments`
- **AND** worker SHALL 使用 tar over SSH 将源码工作副本传输到远端生成路径
- **AND** worker SHALL 写入任务进度、任务日志事件和工具执行记录
- **AND** worker SHALL 将任务和源码部署记录标记为 `SUCCEEDED`
- **AND** worker SHALL 保存文件数量、总字节数、退出码、开始时间和结束时间

#### Scenario: 远端缺少 tar
- **WHEN** worker 执行源码部署且远端环境缺少可用 `tar`
- **THEN** worker SHALL 将任务和源码部署记录标记为 `FAILED`
- **AND** 错误码 SHALL 为 `SOURCE_DEPLOY_REMOTE_TAR_REQUIRED`
- **AND** 错误摘要 MUST NOT 暴露明文凭据或服务器本机路径

#### Scenario: 源码部署失败
- **WHEN** SSH 连接失败、认证失败、传输失败、远端命令失败或本机打包失败
- **THEN** worker SHALL 将任务和源码部署记录标记为 `FAILED`
- **AND** worker SHALL 保存安全错误摘要和可下载日志元数据
- **AND** worker MUST NOT 将失败部署作为后续构建可用输入

#### Scenario: 取消源码部署任务
- **WHEN** 用户取消正在运行的 `SOURCE_DEPLOY` 任务
- **THEN** 后端 SHALL 按既有运行中任务取消机制将任务标记为 `CANCELLED`
- **AND** 取消处理 SHALL 尝试终止本机打包进程或远端 SSH 命令
- **AND** `source_deploy_runs.status` SHALL 最终与任务终态一致

### Requirement: 源码部署查询必须支持分页排序过滤
后端 SHALL 提供源码部署列表和详情查询 API，并 SHALL 对所有查询执行项目访问守卫。

#### Scenario: 查询项目源码部署列表
- **WHEN** 用户调用 `GET /api/projects/{projectId}/source-deployments`
- **THEN** 后端 SHALL 校验当前用户有权访问该项目
- **AND** 后端 SHALL 返回分页源码部署列表
- **AND** 列表 SHALL 支持按 `sourceRevisionId`、`environmentId`、`status` 和创建时间范围过滤
- **AND** 列表 SHALL 支持按 `createdAt`、`startedAt`、`finishedAt` 和 `status` 排序

#### Scenario: 查询源码部署详情
- **WHEN** 用户调用 `GET /api/source-deployments/{sourceDeploymentId}`
- **THEN** 后端 SHALL 根据源码部署所属项目执行项目访问守卫
- **AND** 响应 SHALL 包含源码部署 ID、项目 ID、源码版本摘要、环境摘要、任务 ID、远端路径、状态、统计摘要和时间字段
- **AND** 响应 MUST NOT 返回后端本机源码工作副本路径

#### Scenario: 无权限查询源码部署
- **WHEN** 无项目权限用户查询源码部署列表、详情或取消源码部署
- **THEN** 后端 SHALL 返回与资源不存在一致的安全错误
- **AND** 后端 MUST NOT 暴露项目、源码版本、环境、任务或远端路径是否存在

### Requirement: 构建验证必须使用成功的源码部署作为远端输入
源码部署运行时 SHALL 为构建验证提供稳定的远端源码目录事实。构建验证任务 SHALL 在执行构建脚本前自动创建或复用源码部署步骤。

#### Scenario: 构建验证引用成功部署
- **WHEN** 后续构建验证任务需要在远端环境执行
- **THEN** 构建验证 SHALL 使用状态为 `SUCCEEDED` 的源码部署记录作为远端源码输入
- **AND** 构建验证运行目录 SHALL 基于 `source_deploy_runs.remote_path`
- **AND** 构建验证 MUST NOT 使用失败、取消、超时或仍在运行的部署记录作为输入

#### Scenario: 构建任务内隐式执行源码部署
- **WHEN** `BUILD_VALIDATION` worker 准备执行构建脚本
- **AND** 当前源码版本在所需环境下缺少成功源码部署记录
- **THEN** 后端 SHALL 在当前构建任务内执行源码部署
- **AND** 后端 SHALL 在部署成功后继续执行对应环境的构建脚本
- **AND** 前端 MUST NOT 被要求调用正式源码部署创建 API 才能开始构建

#### Scenario: 部署失败阻止对应环境构建
- **WHEN** 构建任务内部源码部署失败、取消或超时
- **THEN** 后端 MUST NOT 执行对应环境的构建脚本
- **AND** 后端 SHALL 将对应构建任务和构建运行标记为失败、取消或超时
- **AND** 错误响应或任务摘要 MUST NOT 暴露明文凭据或服务器本机路径

### Requirement: 构建内重试复用部署必须在副作用前校验身份
后端 SHALL 在按taskId、显式deploymentId或成功上下文复用部署的所有路径中，先校验任务授权、项目、源码、环境及配置版本、受控路径和请求关联；错误关联 MUST NOT 修改旧记录、打包、SSH或通过新建记录规避约束。

#### Scenario: 同任务失败重试
- **WHEN** 非force构建任务重试查到其FAILED部署且全部身份匹配
- **THEN** SHALL 复用同一部署行和服务端路径，受租约保护重部署；仍只有一个task关联部署行，成功后才执行构建。

#### Scenario: 错误关联
- **WHEN** task匹配但项目/源码/环境/环境配置版本或显式deploymentId不匹配，或路径不符合受控规则
- **THEN** SHALL 在markRunning与工具记录创建前受控失败，返回稳定双语安全原因，不能将新源码写入旧记录路径。

#### Scenario: 跨任务成功复用
- **WHEN** 同项目同源码同环境同配置版本已有成功部署且非force
- **THEN** SHALL 经身份校验复用，允许原部署属于另一个任务，不因原task不同错误拒绝合法缓存；force沿用既有语义且不豁免身份校验。

#### Scenario: 双端与并发重试
- **WHEN** BOTH两个环境各自任务执行或重复消息触发重试
- **THEN** SHALL 每个任务只绑定自身环境及同一固定源码版本，结合租约/事务/唯一约束保证单次有效执行；过期worker不得改写部署结果。
