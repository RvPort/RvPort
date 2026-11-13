## Purpose

定义后端 Agent Runtime 的选择、执行和输出边界，使测试结果比较阶段可以在 Codex、DeepSeek Harness、OpenCode 等不同 agent 之间切换，同时保持模型密钥、命令参数、工作目录和输出制品的安全约束。

## Requirements

### Requirement: 后端必须提供可选 Agent Runtime
后端 SHALL 支持多个 agent runtime，并 SHALL 至少提供 `CODEX`、`DEEPSEEK_HARNESS` 和 `OPENCODE` 三种可选 agent 类型。

#### Scenario: 初始化默认 agent runtime
- **WHEN** 执行数据库迁移或应用初始化
- **THEN** 后端 SHALL 创建或保留 `CODEX`、`DEEPSEEK_HARNESS` 和 `OPENCODE` 三类 agent runtime 配置
- **AND** 每个 runtime SHALL 包含 agent 类型、展示名称、可执行路径、启用状态和安全摘要
- **AND** 初始化过程 SHALL 可重复执行

#### Scenario: 查询 agent runtime 列表
- **WHEN** 已认证用户打开模型或阶段配置页面
- **THEN** 后端 SHALL 提供 agent runtime 列表
- **AND** 列表项 SHALL 包含 runtime ID、agent 类型、展示名称、启用状态和安全能力摘要，不包含内部路径或共享个人探测状态
- **AND** 响应 MUST NOT 返回完整命令、API key、授权头、完整 prompt 或服务器真实敏感路径

#### Scenario: 查询 agent runtime 详情
- **WHEN** 已认证用户查询单个 agent runtime
- **THEN** 后端 SHALL 返回该 runtime 的安全选择元数据、支持阶段和启用状态，不返回全局个人 probe 结果
- **AND** 响应 MUST NOT 返回明文密钥或完整命令行

### Requirement: Agent Runtime 必须通过统一 Adapter 执行
后端 SHALL 通过统一 agent adapter 接口执行不同 agent，业务运行时 MUST NOT 直接拼接某个 agent 的专用命令。

#### Scenario: 执行 Codex agent
- **WHEN** 阶段绑定选择 `CODEX`
- **THEN** 后端 SHALL 使用 Codex adapter 构造受控命令并传入模型 profile
- **AND** adapter SHALL 在专用 workspace 中执行任务

#### Scenario: 执行 DeepSeek Harness agent
- **WHEN** 阶段绑定选择 `DEEPSEEK_HARNESS`
- **THEN** 后端 SHALL 使用 DeepSeek Harness adapter 构造受控命令并传入模型 profile
- **AND** adapter SHALL 在专用 workspace 中执行任务

#### Scenario: 执行 OpenCode agent
- **WHEN** 阶段绑定选择 `OPENCODE`
- **THEN** 后端 SHALL 使用 OpenCode adapter 构造受控命令并传入模型 profile
- **AND** adapter SHALL 在专用 workspace 中执行任务

#### Scenario: Agent 类型未支持
- **WHEN** 阶段绑定引用后端未注册 adapter 的 agent 类型
- **THEN** 后端 SHALL 返回明确业务错误
- **AND** 后端 MUST NOT 尝试执行任意外部命令

### Requirement: Agent 输出必须保留合并输出
后端 SHALL 为所有 agent adapter 生成统一执行结果，其中 `mergedOutput` 必须表达 stdout 和 stderr 按真实产生顺序交织后的完整输出。

#### Scenario: 记录 stdout stderr 顺序
- **WHEN** agent 进程同时产生 stdout 和 stderr
- **THEN** 后端 SHALL 分别保存 stdout 和 stderr
- **AND** 后端 SHALL 生成 `mergedOutput`
- **AND** `mergedOutput` SHALL 保留输出事件顺序和流来源标签

#### Scenario: agent 执行失败
- **WHEN** agent 进程退出码非 0、超时或被中断
- **THEN** 后端 SHALL 使用脱敏后的 `mergedOutput` 生成失败摘要
- **AND** 错误响应和任务事件 MUST NOT 泄漏 API key、授权头、完整 prompt 或服务器真实敏感路径

#### Scenario: agent 输出保存
- **WHEN** 业务需要保存原始 agent 输出 artifact
- **THEN** 后端 SHALL 优先保存包含 `stdout`、`stderr`、`mergedOutput`、`exitCode` 和 `timedOut` 的结构化输出
- **AND** 保存前 SHALL 对密钥和授权头做脱敏处理

### Requirement: Agent Runtime 选择必须仅用于受控阶段
后端 SHALL 只允许已声明阶段使用 agent runtime，当前允许 `TEST_LOG_COMPARE` 和 `KB_AI_EXTENSION` 阶段绑定 agent runtime。

#### Scenario: 保存 TEST_LOG_COMPARE agent 绑定
- **WHEN** 已认证用户保存 `TEST_LOG_COMPARE` 阶段绑定
- **THEN** 请求 SHALL 同时指定属于当前用户的已启用模型 profile 和已启用 agent runtime
- **AND** 后端 SHALL 保存当前用户与该阶段到模型 profile 和 agent runtime 的唯一绑定

#### Scenario: 保存 KB_AI_EXTENSION agent 绑定
- **WHEN** 已认证用户保存 `KB_AI_EXTENSION` 阶段绑定
- **THEN** 请求 SHALL 同时指定属于当前用户的已启用模型 profile 和已启用 agent runtime
- **AND** 后端 SHALL 保存当前用户与该阶段到模型 profile 和 agent runtime 的唯一绑定

#### Scenario: 绑定未知阶段
- **WHEN** 请求绑定未知阶段或非本 change 允许阶段
- **THEN** 后端 SHALL 返回统一参数错误
- **AND** 后端 MUST NOT 保存部分绑定

#### Scenario: 绑定禁用 agent
- **WHEN** 请求绑定已禁用或不存在的 agent runtime
- **THEN** 后端 SHALL 拒绝绑定
- **AND** 响应 MUST NOT 泄漏内部命令路径或配置细节

### Requirement: Agent Runtime Probe 必须支持按模型 Profile 批量执行
后端 agent runtime 选择能力 SHALL 支持模型 profile 针对多个指定 agent runtime 执行阶段无关的通用兼容性 probe，并 SHALL 使用统一 adapter 返回一致的分项结果。

#### Scenario: 按 Runtime 列表执行 Probe
- **WHEN** 模型 profile agent probe run 包含多个 agent runtime
- **THEN** 后端 SHALL 对每个 runtime 使用对应 agent adapter 执行兼容性 probe
- **AND** 每个 runtime SHALL 生成独立的 probe 状态、通用分项结果、耗时和脱敏消息
- **AND** 单个 runtime 失败 SHALL NOT 阻止其他 runtime 执行

#### Scenario: Runtime 不可用
- **WHEN** 请求中的 agent runtime 不存在、未启用或没有已注册 adapter
- **THEN** 后端 SHALL 拒绝创建 probe run 或将对应 runtime 标记为受控失败
- **AND** 响应 MUST NOT 泄漏完整命令、内部路径或 provider secret

#### Scenario: Probe 输出统一
- **WHEN** 任意 agent runtime 执行 profile 兼容性 probe
- **THEN** 后端 SHALL 使用统一结果结构保存 Agent 模型响应、workspace 读取、workspace 写入和基础 JSON 解析分项结果
- **AND** 聚合兼容状态 SHALL 只由这些通用分项计算
- **AND** probe MUST NOT 执行或返回 `TEST_COMPARE_SCHEMA`、`KB_AI_EXTENSION_SCHEMA` 或其他业务阶段专属检查

#### Scenario: Workspace 写入闭环
- **WHEN** Agent Runtime 执行通用 workspace 写入检查
- **THEN** Agent SHALL 根据中性输入文件写出受控 JSON 结果文件
- **AND** 后端 SHALL 校验文件存在、JSON 合法且 marker 与预期一致
- **AND** 临时 workspace SHALL 在 probe 完成、失败或超时后按现有安全策略清理

#### Scenario: 容器内 Agent CLI 完整安装
- **WHEN** 构建后端运行镜像
- **THEN** 镜像 SHALL 安装与 Codex CLI 版本匹配的平台二进制依赖
- **AND** 构建过程 SHALL 实际执行各内置 Agent CLI 的版本命令
- **AND** backend 与 backend-worker SHALL 使用包含相同 Agent CLI 的同一后端镜像

#### Scenario: 个人测试不覆盖平台状态
- **WHEN** 任一用户对受管 runtime 完成 probe
- **THEN** SHALL 仅按其个人 profile 与 runtime 保存结果，不更新共享 last_probe_* 作为全局兼容结论，不影响其他用户摘要

### Requirement: Agent Runtime 选择必须允许 KB_AI_EXTENSION 阶段
后端 SHALL 将 `KB_AI_EXTENSION` 纳入受控 agent runtime 阶段白名单，使 KB AI 扩展扫描能通过统一 adapter 执行。

#### Scenario: 执行 KB AI 扩展 agent
- **WHEN** `KB_AI_EXTENSION` 阶段绑定选择某个已启用 agent runtime
- **THEN** 后端 SHALL 使用对应 agent adapter 构造受控命令并传入模型 profile
- **AND** adapter SHALL 在 KB AI 扩展专用 workspace 中执行任务
- **AND** 业务代码 MUST NOT 直接拼接某个 agent 的专用命令

#### Scenario: KB AI 扩展 agent 输出保存
- **WHEN** KB AI 扩展任务需要保存 agent 原始输出 artifact
- **THEN** 后端 SHALL 保存包含 `stdout`、`stderr`、`mergedOutput`、`exitCode` 和 `timedOut` 的结构化输出
- **AND** 保存前 SHALL 对 API key、授权头、完整 prompt 和服务器真实敏感路径做脱敏处理

#### Scenario: 未声明阶段被拒绝
- **WHEN** 请求为非白名单阶段绑定或执行 agent runtime
- **THEN** 后端 SHALL 返回统一参数错误或业务错误
- **AND** 后端 MUST NOT 尝试执行任意外部命令

### Requirement: Agent Runtime 输入输出必须保持 UTF-8 字符完整性
后端 SHALL 在统一 Agent Runtime 执行边界以 UTF-8 传递文本输入并解码文本输出，任何受支持 Agent Adapter MUST NOT 因平台默认编码或读取缓冲区边界损坏非 ASCII 内容。

#### Scenario: Codex 接收中文提示词
- **WHEN** 业务阶段通过 Codex Adapter 提交包含中文的 prompt
- **THEN** Adapter SHALL 通过 Codex 支持的非交互输入协议以 UTF-8 提交完整 prompt
- **AND** 完整 prompt MUST NOT 作为依赖平台默认原生编码的命令行参数传递
- **AND** Codex 接收到的 Unicode 内容 SHALL 与业务阶段生成的内容一致

#### Scenario: 其他 Agent 接收中文提示词
- **WHEN** 业务阶段通过 DeepSeek Harness、OpenCode 或后续受支持 Adapter 提交包含中文的 prompt
- **THEN** Adapter SHALL 优先通过该 CLI 支持的非交互 stdin 协议以 UTF-8 传递 prompt
- **AND** 固定 CLI 版本不支持 stdin 时，Adapter SHALL 仅在已验证的 UTF-8 原生编码环境中通过受控参数传递
- **AND** Agent 接收到的 Unicode 内容 SHALL 与业务阶段生成的内容一致

#### Scenario: prompt 为空或仅包含 ASCII
- **WHEN** Agent 执行请求不包含 stdin 内容或 prompt 仅包含 ASCII
- **THEN** 统一进程执行器 SHALL 正确关闭 stdin 并保持非交互执行
- **AND** 现有 ASCII Agent 调用行为 SHALL NOT 因 UTF-8 支持而改变

#### Scenario: 中文输出跨读取缓冲区边界
- **WHEN** Agent stdout 或 stderr 中的 UTF-8 多字节字符跨越底层读取缓冲区边界
- **THEN** 后端 SHALL 使用有状态 UTF-8 解码保留完整字符
- **AND** stdout、stderr 与 `mergedOutput` MUST NOT 因分块解码产生替换字符或问号

#### Scenario: 达到 Agent 输出上限
- **WHEN** Agent 输出达到统一收集大小上限
- **THEN** 后端 SHALL 在完整 UTF-8 字符边界停止收集
- **AND** 已收集的 stdout、stderr 与 `mergedOutput` SHALL 保持可解码

#### Scenario: stdin 写入期间 Agent 提前退出
- **WHEN** Agent 在 UTF-8 prompt 写入完成前退出或关闭 stdin
- **THEN** 后端 SHALL 返回受控执行失败并保留已产生的脱敏输出
- **AND** 错误信息 MUST NOT 包含完整 prompt、API key 或授权头

#### Scenario: Workspace 文本文件读写
- **WHEN** Agent 业务阶段生成输入 JSON、读取结果 JSON 或写入 Agent 文本配置
- **THEN** 后端 SHALL 显式使用 UTF-8 编码
- **AND** 文件读写 MUST NOT 依赖宿主机或容器的平台默认编码

### Requirement: Agent CLI 安装必须锁定完整依赖树
后端运行镜像 SHALL 使用已提交的精确版本清单和完整依赖锁文件安装受管 Agent CLI 及 OpenCodex/Bun，覆盖传递、peer、optional 与目标平台依赖；MUST NOT 在常规构建中浮动解析 latest 或仅固定主包。

#### Scenario: 构建固定 DSH 版本
- **WHEN** 构建本次后端镜像
- **THEN** DSH 主包 SHALL 为 `0.1.0-rc.8`，相关 DSH 组件和独立 Cordis/HMR 组件 SHALL 匹配经过验证的锁定组合
- **AND** Node、npm、平台、依赖版本及完整性信息 SHALL 可追溯
- **AND** backend 与 backend-worker SHALL 使用包含相同受管 CLI 的同一镜像

#### Scenario: 重复干净安装
- **WHEN** 在本地 Docker 以同一清单和锁文件进行两次空安装
- **THEN** SHALL 使用 `npm ci` 或已证实等价的冻结安装机制，得到相同规范化依赖树
- **AND** 第二次安装 MUST NOT 更新锁文件
- **AND** 缺包、额外包、版本或完整性不匹配 SHALL 使验收失败

#### Scenario: 锁定 Codex 最新稳定版本
- **WHEN** 开始实施 Codex 升级
- **THEN** SHALL 核实官方最新稳定版并记录精确版本与来源，2026-10-29 的提案基线为 `0.165.0`
- **AND** 如版本更新 SHALL 先更新设计，再锁定 wrapper 与匹配平台二进制及完整性校验
- **AND** MUST NOT 使用预发布版替代用户要求的最新稳定版或在镜像构建时重新解析 latest

#### Scenario: 依赖解析失败
- **WHEN** 安装出现 peer 冲突、堆溢出、网络失败或缺少平台包
- **THEN** SHALL 保留失败证据并保持安装验收未完成
- **AND** MUST NOT 通过忽略退出码、无依据强制安装或复用旧 node_modules 声称当前版本通过

#### Scenario: 本轮固定 OpenCodex 并保持既有版本基线
- **WHEN** 构建本轮 OpenCodex 替换方案
- **THEN** SHALL 使用 `@bitkyc08/opencodex@2.94.0`，固定匹配的 Bun 1.4.4、实际平台二进制及完整传递依赖和完整性信息
- **AND** SHALL 保持已验收的 Codex 0.165.0、DSH 0.1.0-rc.8/HMR 1.0.17 和 OpenCode 1.18.18 基线，不因新依赖扁平化而改变其依赖组合
- **AND** SHALL 记录许可证、安装脚本产生的依赖/二进制及全部下载来源；任何版本调整先更新设计并重新验证
- **AND** worker SHALL 使用冻结安装产物，运行期自动更新 MUST 被禁用

#### Scenario: worker 专属执行与共享镜像
- **WHEN** backend 与 backend-worker 使用同一含代理文件的运行镜像
- **THEN** 只有 worker 运行角色 SHALL 启动受管 OpenCodex，backend API 角色 MUST NOT 启动代理
- **AND** CHAT 的管理探针 SHALL 经内部调度到 worker，不因共用镜像而绕过该边界

### Requirement: DSH 必须通过真实 headless 启动门槛
DSH 的安装可用性 SHALL 由固定版本的真实非交互执行证明；`--version` 或 `--help` 成功 MUST NOT 作为 headless 工作流通过的充分条件。

#### Scenario: HMR 故障本地复现与修复
- **WHEN** 修复 `user patch-layer watching requires the Cordis HMR service`
- **THEN** SHALL 在本地 Docker 用基线和 rc.8 候选验证 profile、patch 与 HMR/loader/timer 激活链
- **AND** SHALL 记录根因与修复依据，不预设版本漂移或 Node 参数是唯一原因
- **AND** 固定候选 SHALL 完成真实 headless 启动，不吞掉初始化异常

#### Scenario: 运行三项通用任务
- **WHEN** 使用本地模拟模型服务、假凭据与独立 workspace 验证 DSH
- **THEN** SHALL 完成文本 JSON、文件读取与文件写入闭环，并正常退出
- **AND** SHALL 同时校验响应与实际文件内容，不能只验证 stdout

### Requirement: Agent 超时必须保留安全且有界的诊断
后端 SHALL 为超时、非零退出、协议错误和清理失败提供稳定原因码及有界脱敏诊断；公共响应 MUST NOT 返回原始完整输出、prompt、密钥、授权头、代理 token、真实敏感路径或异常堆栈。

#### Scenario: Codex 超时但已有输出
- **WHEN** Codex 超过单项执行预算且已产生 stdout 或 stderr
- **THEN** SHALL 保留 `AGENT_PROBE_TIMEOUT` 及安全诊断摘要，不能只返回无上下文的“超时”
- **AND** details SHALL 包含预算、执行/清理/总耗时、CLI 版本、路由和可确定的故障阶段
- **AND** 不能确定模型响应或网络原因时 SHALL 明确未知，不推断为未安装或协议不支持

#### Scenario: 脱敏后限长
- **WHEN** 输出包含授权头、上游错误正文、内部路径、ANSI、超长内容或跨块敏感值
- **THEN** SHALL 在截断前完成脱敏与公共摘要清洗，并保持 UTF-8 有效
- **AND** 新增前端展示说明 SHALL 同时提供中文和英文字段

#### Scenario: 有效 JSON 伴随执行失败
- **WHEN** 进程超时或非零退出而输出存在正确 JSON
- **THEN** SHALL 保持执行失败，不能以 JSON 命中覆盖失败状态

### Requirement: Agent 预算和资源清理必须覆盖完整执行链
后端 SHALL 将 CLI、转换层和上游请求纳入单项截止时间，并以独立有界宽限时间清理本次拥有的进程树、流、HTTP 请求、监听端口与临时文件。

#### Scenario: wrapper 启动原生子进程
- **WHEN** 超时或取消发生在 wrapper 已启动原生 CLI 后
- **THEN** SHALL 终止属于本次执行的后代进程，关闭流及桥接请求
- **AND** MUST NOT 遗留后台进程持有输出管道或继续调用模型
- **AND** MUST NOT 终止其他任务的进程

#### Scenario: 清理超出宽限
- **WHEN** 资源不能在清理预算内释放
- **THEN** SHALL 返回有限时间内可诊断的清理失败状态并记录残留类别
- **AND** MUST NOT 通过逐个追加无说明等待或忽略清理失败宣称成功

#### Scenario: 上游重试与取消
- **WHEN** CLI 或桥接执行重试、调用方取消或 HTTP 客户端断开
- **THEN** SHALL 遵守剩余总预算并传播取消
- **AND** MUST NOT 静默扩大 profile 超时或在任务结束后继续请求

### Requirement: Agent 变更必须通过本地 Docker 质量验收
本 change 的依赖安装、编译、实验、单元测试、覆盖率和完整运行验收 SHALL 全部在本地 Docker 完成；远程 dev/test SHALL 仅允许只读排查，MUST NOT 创建临时测试资源、安装软件、修改服务配置或数据库。

#### Scenario: 单元测试与完整统计
- **WHEN** 交付 CLI 锁定、诊断或转换实现
- **THEN** SHALL 执行后端全量 Java/资源脚本单元测试及自动覆盖率门槛
- **AND** 整体及全部受影响完整模块的行与分支覆盖率 SHALL 各至少 90%，保留更严格规则
- **AND** 未加载自有源码、新增桥接逻辑及自有安装/检查脚本 SHALL 纳入；生成/第三方排除理由 SHALL 明列
- **AND** MUST NOT 缩小分母、仅统计变更行或以集成测试代替单元测试

#### Scenario: 完整运行验收
- **WHEN** 单元测试和覆盖率通过
- **THEN** SHALL 从仓库根目录在本地对全部默认 Compose 服务构建，并一次 `docker compose up -d --wait` 启动
- **AND** SHALL 使用独立 env、网络、数据卷和端口，限制 CPU、内存和构建并发，检查健康、日志和代表性 API 流程
- **AND** 证据 SHALL 记录实际命令、退出码、源码/镜像/CLI 标识、测试数量、covered/total 与报告位置

#### Scenario: 验证失败或历史缺口
- **WHEN** 必需测试、覆盖率或启动验收未通过
- **THEN** 相关任务 SHALL 保持未完成，不提示可归档
- **AND** 大规模无关历史缺口 SHALL 报告并请求扩大范围，不自行改造无关代码或降低门槛

### Requirement: OpenCodex 集成必须延续完整执行监管及质量门槛
项目 SHALL 将代理排队、启动、就绪、推理和关闭纳入现有预算与进程监管，并对本轮当前源码重新执行单元测试、覆盖率和本地完整 Docker 验收。

#### Scenario: 阶段和探针共用资源预算
- **WHEN** 多个业务阶段和 Codex CHAT probe 同时等待启动代理
- **THEN** SHALL 共同遵守受控并发限额，排队/就绪/执行共用绝对截止时间
- **AND** SHALL 显式设置有限上游等待，不依赖第三方对本地地址的无限超时默认值
- **AND** 超时或取消 SHALL 终止本执行拥有的代理和 CLI 进程树，在有限清理宽限结束后给出已清理或清理失败诊断

#### Scenario: 单元测试和覆盖率归属
- **WHEN** 验证本轮替换
- **THEN** SHALL 对 backend 全部自有 Java/资源脚本及完整受影响模块执行独立单元测试，整体及各受影响模块行/分支各至少 90%
- **AND** SHALL 纳入未加载源码、自有代理监管/准入/配置、worker 分派/结果归并、锁校验脚本及复用模块
- **AND** 第三方源码和自动生成文件可按明确理由排除，自有胶水逻辑 MUST NOT 排除或用仅变更行覆盖率替代

#### Scenario: 当前源码完整本地验收
- **WHEN** 本轮单元测试与覆盖率通过
- **THEN** SHALL 从根目录对当前候选源码顺序构建全部默认 Compose 服务，并一次 `docker compose up -d --wait` 启动隔离 stack，检查健康、日志和代表性业务/管理 API
- **AND** SHALL 验证无新增代理 Compose 服务/主机端口、仅 worker 启动代理、动态 profile、三项 probe、旧兼容入口、原生直连与 DSH/OpenCode 回归
- **AND** SHALL 记录完整命令、测试结果、源码/镜像/lock 标识、覆盖率 covered/total 和清理证据；未执行或失败不得沿用历史勾选
- **AND** 全部安装、测试和验收 SHALL 只在本地 Docker，远程 dev/test 只允许只读排查且不得执行测试
