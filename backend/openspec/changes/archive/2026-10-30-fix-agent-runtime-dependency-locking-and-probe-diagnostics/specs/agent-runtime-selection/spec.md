## ADDED Requirements

### Requirement: Agent CLI 安装必须锁定完整依赖树
后端运行镜像 SHALL 使用已提交的精确版本清单和完整依赖锁文件安装受管 Agent CLI，覆盖传递、peer、optional 与目标平台依赖；MUST NOT 在常规构建中浮动解析 latest 或仅固定主包。

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
