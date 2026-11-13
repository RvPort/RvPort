## Purpose

定义 RvPort 自动化测试运行时的远程执行、日志回收、报告产物回收、任务状态、安全边界，以及与测试结果比较节点的自动衔接要求。
## Requirements
### Requirement: 自动化测试运行时必须支持远程测试执行和日志回收
自动化测试运行时 SHALL 基于关联构建运行或项目当前运行环境配置确定远程测试环境，并 SHALL 回收测试输出、退出码、stdout/stderr 日志和用户指定的测试报告产物。

#### Scenario: 创建远程测试任务
- **WHEN** 用户基于项目、源码版本、构建运行和测试命令创建自动化测试任务
- **THEN** 后端 SHALL 创建 `AUTOMATED_TEST` 任务
- **AND** 后端 SHALL 从关联构建运行确定测试环境
- **AND** 后端 SHALL 校验构建运行属于同一项目、同一源码版本且属于当前环境配置版本
- **AND** 后端 SHALL 创建或预留关联的测试运行记录
- **AND** 任务输入 SHALL 包含 `projectId`、`sourceRevisionId`、环境 ID、环境配置版本、可选 `buildRunId`、测试命令或脚本引用、工作目录、超时配置、可选 `environmentVariables` 和可选 `reportTargets` 数组
- **AND** 任务输入 MUST NOT 包含明文凭据、服务器真实路径或完整日志

#### Scenario: 执行远程测试
- **WHEN** worker 处理自动化测试任务
- **THEN** worker SHALL 在任务输入确定的远程环境执行测试脚本或命令
- **AND** worker SHALL 记录退出码、状态、开始时间、结束时间、stdout 日志和 stderr 日志
- **AND** worker SHALL 将测试输出写入任务日志事件和完整日志文件元数据
- **AND** worker SHALL 在命令结束后按报告回收规则回收测试报告产物

#### Scenario: 测试任务状态
- **WHEN** 远程测试命令完成
- **THEN** 如果退出码为 0，任务 SHALL 标记为 `SUCCEEDED`
- **AND** 如果退出码非 0 或运行失败，任务 SHALL 标记为 `FAILED`
- **AND** 如果任务被取消，任务 SHALL 标记为 `CANCELLED`
- **AND** 如果任务超时，任务 SHALL 标记为 `TIMEOUT`

#### Scenario: 请求不再提交环境 ID
- **WHEN** 用户创建自动化测试任务
- **THEN** 请求 MUST NOT 要求提交 `environmentId`
- **AND** 后端 MUST NOT 让请求体中的 `environmentId` 覆盖关联构建运行确定的环境

### Requirement: 自动化测试必须保存输出摘要而不解析用例级报告
自动化测试运行时 SHALL 保存退出码、状态和日志引用，并 MUST NOT 依赖 JUnit、CTest、pytest 或类似结构化报告解析。

#### Scenario: 保存测试输出摘要
- **WHEN** 测试执行完成
- **THEN** 后端 SHALL 保存退出码、状态、耗时、日志引用和受控输出摘要
- **AND** 后端 SHALL 允许通过数、失败数和跳过数字段为空

### Requirement: 自动化测试必须支持文字报告产物回收
自动化测试运行时 SHALL 支持回收测试程序生成的文字报告，使 stdout 内容很少时仍能保留可审计测试结果。

#### Scenario: 回收多个指定报告文件
- **WHEN** 测试任务提交 `reportTargets` 数组且包含多个 `type=FILE` 的相对报告文件路径
- **THEN** worker SHALL 在测试命令结束后从远端读取这些文件
- **AND** 后端 SHALL 将文件保存为受控制品或对象存储 artifact
- **AND** 测试详情 SHALL 返回报告文件名、大小、sha256、content type 和 artifact 摘要

#### Scenario: 回收报告输出目录
- **WHEN** 测试任务提交 `reportTargets` 数组且包含 `type=DIRECTORY` 的相对报告输出目录
- **THEN** worker SHALL 只在该目录下按受控规则扫描可回收报告文件
- **AND** worker SHALL 按配置限制扫描深度、最大匹配数量、单文件大小和总大小
- **AND** 超过限制时 SHALL 保存脱敏警告摘要并继续保留已成功回收的报告

#### Scenario: 报告路径安全
- **WHEN** `reportTargets` 包含非法 `type`、绝对路径、`..` 越界、空路径或不允许的目录扫描
- **THEN** 后端 SHALL 拒绝创建任务或在 worker 阶段标记报告回收失败
- **AND** 后端 MUST NOT 从远端读取工作目录外的文件
- **AND** API、日志和任务事件 MUST NOT 暴露远端敏感绝对路径

#### Scenario: 未配置报告回收
- **WHEN** 测试任务未提交 `reportTargets` 或提交空数组
- **THEN** worker SHALL 只回收退出码、stdout 和 stderr
- **AND** 测试任务 SHALL NOT 因未配置报告文件而失败

### Requirement: 自动化测试必须支持任务级环境变量
自动化测试运行时 SHALL 接收前端传入的任务级环境变量，并 SHALL 在远程测试命令或脚本执行时注入这些变量。

#### Scenario: 注入测试环境变量
- **WHEN** 测试任务请求包含 `environmentVariables`
- **THEN** 后端 SHALL 校验变量名格式和值长度
- **AND** worker SHALL 在远程测试命令或临时脚本执行时注入这些变量
- **AND** 变量只作用于当前测试任务

#### Scenario: 环境变量脱敏
- **WHEN** 后端保存任务输入摘要、任务事件、审计记录或 API 响应
- **THEN** 后端 MUST 对环境变量值进行脱敏
- **AND** 名称包含 `TOKEN`、`PASSWORD`、`SECRET`、`KEY` 或 `PASS` 的变量 MUST 被视为敏感变量

#### Scenario: 不创建用例级结果
- **WHEN** 测试日志或输出中存在结构化测试报告路径
- **THEN** MVP 后端 MUST NOT 解析用例级结果
- **AND** MVP 后端 MUST NOT 创建 `test_cases` 或等效用例级持久化记录

### Requirement: 自动化测试必须支持取消和超时
自动化测试运行时 SHALL 复用运行中任务取消机制终止本地或远程测试执行。

#### Scenario: 取消运行中测试
- **WHEN** 用户取消 `AUTOMATED_TEST` 任务
- **THEN** 后端 SHALL 将任务状态更新为 `CANCELLED`
- **AND** 后端 SHALL 投递取消清理消息
- **AND** worker SHALL 尝试终止正在运行的远程测试命令
- **AND** 已产生的测试输出、日志和已回收报告 SHALL 保留为取消前的事实记录

#### Scenario: 测试超时
- **WHEN** 单侧测试命令超过配置的超时时间
- **THEN** worker SHALL 尝试终止该侧测试进程
- **AND** 后端 SHALL 将该侧结果标记为超时
- **AND** 若任务无法确认远程测试完成状态，任务 SHALL 标记为 `TIMEOUT` 或 `FAILED`

### Requirement: 自动化测试运行时必须保护权限和敏感信息
自动化测试运行时 SHALL 对所有 API、任务输入、日志摘要和审计信息执行项目访问控制与脱敏。

#### Scenario: 权限校验
- **WHEN** 用户创建、查询、取消或下载自动化测试相关资源
- **THEN** 后端 SHALL 校验用户有权访问测试运行所属项目
- **AND** 无权限响应 MUST NOT 泄漏测试运行、任务、环境或日志是否存在

#### Scenario: 敏感信息脱敏
- **WHEN** 后端保存测试命令、任务输入、任务事件、日志摘要或审计记录
- **THEN** 后端 MUST 删除或遮蔽密码、token、SSH 私钥、passphrase、Authorization header 和服务器真实敏感路径

### Requirement: 自动化测试节点必须自动衔接测试结果比较
自动化测试运行时 SHALL 保存测试结果比较所需的最小引用，并 SHALL 在同一自动化测试节点两侧运行完成后自动触发比较流程。

#### Scenario: 保存比较输入引用
- **WHEN** 自动化测试任务完成
- **THEN** 后端 SHALL 保存退出码、状态、stdout 日志引用、stderr 日志引用、输出摘要和报告 artifact 摘要
- **AND** 这些引用 SHALL 足以让测试结果比较任务读取或下载原始材料

#### Scenario: 自动化测试不依赖比较模型
- **WHEN** 该测试批次原触发者的 `TEST_LOG_COMPARE` 阶段没有绑定模型 profile 或模型不可用
- **THEN** 自动化测试任务 SHALL 继续允许创建和执行
- **AND** 后端 MUST NOT 因 AI 配置缺失改变测试运行状态

#### Scenario: 两侧测试完成后自动触发比较
- **WHEN** 同一自动化测试节点的 source 和 target 测试记录均进入 `SUCCEEDED`、`FAILED` 或 `TIMEOUT`
- **THEN** 后端 SHALL 自动创建或更新该节点关联的测试比较运行
- **AND** 如果该批次原触发者的个人比较模型可用，后端 SHALL 自动投递 `TEST_LOG_COMPARE` 任务
- **AND** 如果该批次原触发者的个人比较模型不可用，后端 SHALL 保存比较不可用摘要供前端展示

#### Scenario: 自动化测试节点使用批次关联两侧结果
- **WHEN** 用户点击一次自动化测试节点开始执行
- **THEN** 后端 SHALL 为该次节点运行生成稳定批次标识
- **AND** source 和 target 两侧测试记录 SHALL 保存同一批次标识
- **AND** 自动比较 SHALL 只在同一批次内匹配 source 和 target 测试记录

#### Scenario: 测试被取消时不执行比较
- **WHEN** 同一自动化测试节点任一侧测试记录进入 `CANCELLED`
- **THEN** 后端 SHALL 保存测试真实状态
- **AND** 后端 SHALL 将比较摘要标记为不可比较或取消
- **AND** 后端 MUST NOT 投递 agent 比较任务
#### Scenario: 批次触发者继承
- **WHEN** 同一批次两侧结果完成并自动派生比较
- **THEN** 比较 SHALL 继承该批次持久化的原触发者，在派生时解析其个人绑定
- **AND** MUST NOT 采用查询页面的用户、最后完成任务的 worker 身份或项目所有者
- **AND** 批次触发者缺失或相互冲突 SHALL 记录不可用原因，不投递模型任务

#### Scenario: 手动重新触发测试
- **WHEN** 另一有权用户手动重新触发自动化测试
- **THEN** SHALL 创建新批次并记录新触发者，其自动派生比较 SHALL 使用新触发者的配置
- **AND** 原批次和比较的触发者审计 MUST NOT 被改写

### Requirement: 自动化测试运行时必须保存有序合并日志
自动化测试运行时 SHALL 在测试执行阶段保存 stdout 和 stderr 的有序合并日志，并 SHALL 将该日志作为测试结果比较和完成态日志查看的主要日志材料。

#### Scenario: 写入 merged log
- **WHEN** worker 执行自动化测试命令并读取 stdout 或 stderr
- **THEN** 后端 SHALL 按读取顺序写入 merged log
- **AND** 每个日志片段 SHALL 保留 stream 来源，区分 `STDOUT` 和 `STDERR`
- **AND** 后端 MUST NOT 只在任务结束后通过拼接 stdout.log 和 stderr.log 生成 merged log

#### Scenario: 保留原始分流日志
- **WHEN** 自动化测试完成
- **THEN** 后端 MAY 继续保存 stdout.log 和 stderr.log 作为调试材料
- **AND** 测试详情或日志查询 SHALL 能区分 merged log 与分流日志

#### Scenario: 测试比较优先使用 merged log
- **WHEN** 测试结果比较聚合自动化测试日志
- **THEN** 后端 SHALL 优先读取 merged log 作为 agent 输入
- **AND** stdout/stderr 分流日志 SHALL 只作为补充调试材料

#### Scenario: 完成后读取测试日志内容
- **WHEN** 自动化测试进入终态且前端需要展示历史日志
- **THEN** 前端 SHALL 通过 `GET /api/tasks/{taskId}/log-content` 读取 merged/stdout/stderr 日志内容
- **AND** 测试详情响应 SHALL 返回 merged/stdout/stderr 日志 ID 或明确缺失状态

#### Scenario: 旧记录没有 merged log
- **WHEN** 历史测试记录没有 merged log
- **THEN** 后端 MAY 回退到既有 stdout/stderr 日志摘要
- **AND** 比较输入 SHALL 记录日志材料发生降级

### Requirement: 自动化测试必须为比较 agent 提供稳定输入材料
自动化测试运行时 SHALL 保存足够的事实材料供测试结果比较 agent 生成结构化统计，但 MUST NOT 在后端引入面向具体测试工具的用例级 parser。

#### Scenario: 保存比较输入材料
- **WHEN** 自动化测试任务进入终态
- **THEN** 后端 SHALL 保存测试状态、退出码、stdout 日志引用、stderr 日志引用、merged log 引用、输出摘要和报告 artifact 摘要
- **AND** 这些材料 SHALL 足以让 `TEST_LOG_COMPARE` agent 读取测试统计证据
- **AND** 后端 SHALL 允许 `passedCount`、`failedCount` 和 `skippedCount` 为空

#### Scenario: 不在自动化测试运行时解析测试框架
- **WHEN** 自动化测试日志包含 CTest、pytest、JUnit、Maven Surefire 或其他测试框架输出
- **THEN** 自动化测试运行时 MUST NOT 为这些框架实现专用 parser 来填充用例级统计
- **AND** 测试统计 SHALL 由测试结果比较 agent 从日志和报告材料中提取

#### Scenario: 比较失败不影响测试事实
- **WHEN** 自动化测试已完成但后续测试结果比较失败
- **THEN** 自动化测试记录 SHALL 保持自身真实状态
- **AND** 自动化测试 API SHALL 继续返回测试日志引用、退出码和报告 artifact 摘要
- **AND** 后端 MUST NOT 因比较失败修改自动化测试运行状态

### Requirement: 自动化测试必须提供 latest
自动化测试领域 SHALL 提供当前 Context 下的 latest 接口，返回最新测试批次和测试结果比较摘要。

#### Scenario: 查询自动化测试 latest
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/automated-tests/latest`
- **THEN** 后端 SHALL 返回统一 latest 外壳
- **AND** `execution` SHALL 指向当前源码版本和当前运行环境版本下最新自动化测试批次
- **AND** `result` SHALL 包含 x86 与 RISC-V 测试运行摘要、日志入口、报告目标和最近测试结果比较摘要

#### Scenario: 自动化测试 latest 无比较结果
- **WHEN** 自动化测试已完成但模型或 agent 配置不可用导致比较未执行
- **THEN** latest SHALL 仍返回测试执行结果
- **AND** `result` SHALL 明确比较不可用原因

### Requirement: 自动化测试 readiness 必须依赖构建验证
项目级 readiness 在 `stageId=test` 时 SHALL 返回自动化测试所需的构建验证前置条件。

#### Scenario: 构建验证已成功
- **WHEN** 当前 Context 下存在成功的双环境构建验证
- **THEN** `BUILD_VALIDATION` prerequisite SHALL 返回 `SATISFIED`
- **AND** `referenceId` SHALL 指向构建验证记录或批次

#### Scenario: 构建验证缺失
- **WHEN** 当前 Context 下没有双环境构建验证记录
- **THEN** `BUILD_VALIDATION` prerequisite SHALL 返回 `MISSING`
- **AND** `reasonCode` SHALL 为 `BUILD_VALIDATION_MISSING`

#### Scenario: 构建验证失败
- **WHEN** 当前 Context 下最近一次双环境构建验证失败
- **THEN** `BUILD_VALIDATION` prerequisite SHALL 返回 `FAILED`
- **AND** `reasonCode` SHALL 为 `BUILD_VALIDATION_FAILED`
