## 1. 本地验证边界与版本基线

- [x] 1.1 确认在 D:\projects\RvPort\backend 独立 OpenSpec 根实施并保持 dev 分支；核对现有改动，记录全部安装、实验、测试和验收只在本地 Docker，远程 dev/test 禁止任何修改或临时测试资源。
- [x] 1.2 核实最新稳定 Codex 的官方 release 与 npm 元数据，记录精确版本、平台包和完整性；基线为 0.165.0，如已有更新先更新设计，不使用浮动 latest 构建。
- [x] 1.3 盘点 DSH rc.8、Cordis/HMR、Node/npm 及现有 OpenCode 的依赖和平台需求，记录主包/传递/peer/optional 实际树及当前基线，不把依赖漂移直接当成 HMR 根因。
- [x] 1.4 建立单元测试与覆盖率模块清单：backend 全部 Java/资源脚本，以及 agentruntime、bridge、codexprovider、modelprofile、实际触及的 worker/取消组件、kb.ai、testcomparison.runtime 等完整模块；列明生成与第三方排除理由。

## 2. 固定 CLI 完整依赖与安装流程

- [x] 2.1 在 backend 内建立独立 CLI 依赖清单、锁文件和版本/来源说明，固定 DSH 0.1.0-rc.8 与经验证组件版本；同步维护锁清单/版本检查的单元测试。
- [x] 2.2 在资源受限的本地 Docker 定位 npm peer 解析与堆溢出，确定可重复成功的 Node/npm/内存预算；如需 overrides 记录兼容依据，不默认 force 或 legacy-peer-deps 绕过。
- [x] 2.3 修改 backend Dockerfile 采用冻结安装并提供受管 CLI 入口；固定 Codex wrapper 与匹配平台二进制及完整性，不顺便升级 OpenCode，不使用旧 node_modules 替代安装。
- [x] 2.4 两次空安装核对规范化依赖树、平台和完整性，确认第二次不改锁；覆盖锁文件不匹配、缺包、额外包、平台不符及校验失败，新增自有检查脚本配套单元测试和覆盖率。

## 3. DSH rc.8 headless 启动修复

- [x] 3.1 在本地 Docker 复现旧基线 HMR 报错，并用新 rc.8 候选检查 profile/provider patch、HMR/loader/timer 激活顺序与 Node 能力，记录对照证据。
- [x] 3.2 根据证据实现最小启动/依赖兼容修复并补充正常、缺失组件、激活失败和异常传播测试；必要上游兼容补丁先更新设计，禁止吞掉 HMR 错误或热改远程软件。
- [x] 3.3 在本地模拟模型服务和假凭据下验证 DSH 真实文本、文件读写和正常退出；既检查结构化响应也检查文件，版本/帮助命令仅作为附加检查。

## 4. Codex 协议基线与 profile 路由

- [x] 4.1 用固定最新稳定 Codex 与本地 recording mock 获取安全协议夹具，确认文本、function/custom 工具、工具回传、SSE、续接/compaction 和配置开关，冻结实际支持矩阵。
- [x] 4.2 调整 Codex 命令工厂与 Adapter，使 RESPONSES 原生直连、CHAT 进入受管转换，CLI 始终使用 responses；添加路由/命令/环境单元测试，不更改持久化 CHAT 枚举。
- [x] 4.3 保留连通性按原始上游协议请求的行为，区分连通性和 Agent 兼容性；测试授权、配置缺失、临时覆盖、阶段绑定及禁止自动协议降级。
- [x] 4.4 回归 DSH/OpenCode 原生 Adapter 及 KB_AI_EXTENSION、TEST_LOG_COMPARE 统一调用，确认桥接不会仅对 probe 生效或强制其他 Agent 走 Codex 路径。

## 5. 执行级 CHAT 转换层

- [x] 5.1 实现后端管理的 loopback 动态端口和执行级上下文，固定 profile 快照与临时 token；测试启动失败、并发隔离、错误 token、非法路径和任意上游/模型覆盖拒绝。
- [x] 5.2 实现 instructions、角色消息和多轮文本的 Responses/CHAT 双向映射；覆盖空输入、Unicode、顺序、无效类型和不完整响应。
- [x] 5.3 实现 function 工具定义、schema、tool_choice、并行调用和 tool result 精确关联；覆盖缺失/重复/错误 call ID、参数错误和多个工具交错回传。
- [x] 5.4 按 4.1 的实际协议实现必要 custom/freeform 工具映射并恢复调用类型；不能等价表达的格式明确拒绝，补充边界测试，不通过丢约束实现表面兼容。
- [x] 5.5 实现非流式响应、finish_reason、成功/失败/不完整状态和有来源的 usage；测试截断、缺失 usage、上游无工具能力及格式异常，不伪造成功或用量。
- [x] 5.6 实现有界流式转换和 Responses SSE 事件序列；覆盖 UTF-8/JSON 跨分块、空 delta、多工具交错、索引/ID 一致、终止事件、畸形流和连接中断。
- [x] 5.7 固化无状态完整 input 路径与不支持能力的明确错误；验证 previous_response_id、加密 reasoning、远端 compaction、WebSocket、多模态及托管工具不会被静默忽略，当前必需工作流不能通过时更新设计而非缩减验收。
- [x] 5.8 实现上游鉴权、限流、连接、协议和服务错误分类，以及流开始后的失败事件；补充取消、超时、重定向、缓冲上限和关闭状态单元测试。

## 6. 超时预算、进程清理与脱敏诊断

- [x] 6.1 为 probe、CLI、bridge 和上游调用定义同一执行截止时间与独立清理宽限，测试剩余预算、重试上限、时钟边界与中断，不无条件放大 timeoutSeconds。
- [x] 6.2 修复 wrapper/原生 CLI 后代进程及 I/O 清理，追踪本次资源归属，测试进程已退出、启动失败、孙进程持管道、终止失败、并发任务隔离和清理超时。
- [x] 6.3 在超时分支保留有界安全诊断与稳定错误码，增加阶段、预算、执行/清理/总耗时、版本、路由和 cleanup 结果；补充中英文展示字段及未知根因处理测试。
- [x] 6.4 统一脱敏后限长，覆盖 API key、Authorization、bridge token、URL 查询密钥、真实路径、堆栈、完整 prompt/上游正文、ANSI、跨块敏感值和 Unicode，不公开原始输出。
- [x] 6.5 回归三项 probe 分项与聚合：文本超时但读写成功、非零退出伴有效 JSON、写文件缺失/marker 错误、协议失败及历史记录不可改写。
- [x] 6.6 验证正常退出、异常、超时及取消后无残留进程、监听端口、HTTP 请求或临时凭据文件；关闭后的 token 不可复用。

## 7. 本地真实 CLI 与协议闭环验证

- [x] 7.1 在本地 Docker 建立独立的 Responses/CHAT 模拟上游与确定性响应夹具，假凭据、隔离网络和资源限额；夹具不加入生产默认服务，不连接远程部署库或真实 SSH。
- [x] 7.2 用固定 Codex 验证原生 RESPONSES 与 CHAT bridge 均完成文本、workspace 读写、真实工具调用/回传及多轮交互，包含实际 custom 工具形态。
- [x] 7.3 验证 401/403/429/5xx、超时、慢流、断流、无终止事件、不支持能力、并发不同 profile 与取消；核对错误分类、预算和资源释放。
- [x] 7.4 完成 DSH rc.8 与现有 OpenCode 的三项通用 probe 回归，核对 backend/worker 同镜像的实际 CLI 版本和依赖清单。
- [x] 7.5 区分独立单元测试、模拟上游 CLI 集成和真实模型验证证据；未执行真实模型验证时明确注明，禁止用模拟成功宣称任意兼容 API 已通过。

## 8. 全量单元测试与至少 90% 覆盖率

- [x] 8.1 从当前源码在本地 Docker 重建 backend unit-test 镜像，按开发指南执行完整 Maven clean verify，设置 CPU/内存/编译并发上限，导出 Surefire 与 JaCoCo 报告。
- [x] 8.2 保留 JaCoCo 全量 BUNDLE/PACKAGE 及既有 CLASS 更严格规则，新增 bridge 与实际受影响完整模块的行/分支分别至少 90%，未加载自有类纳入分母。
- [x] 8.3 在本地 Docker 执行 backend 资源 Python 完整单元测试、全源码行/分支统计及整体/每脚本门槛；新增其他语言自有安装/检查逻辑同样独立单测和验收。
- [x] 8.4 汇总整体及每个受影响模块 covered/total、百分比、排除项与报告位置，核对无业务代码排除、无空断言凑数、无仅变更行统计；历史缺口需大规模改造时报告并请求扩大范围。
- [x] 8.5 检查全部质量门槛，任何测试失败、覆盖率不足或环境阻塞保留任务未完成，不以 CLI 集成、构建或旧报告替代。

## 9. 本地完整 Docker 验收

- [x] 9.1 从 D:\projects\RvPort 根目录准备专用 Compose project、测试 env、网络、卷、端口和资源限制；按模块顺序构建全部默认服务，不连接远程 dev/test。
- [x] 9.2 在本地 Docker 执行既有前端 lint、类型检查、普通单元测试和生产构建，前端代码零修改，保持现行前端覆盖率例外。
- [x] 9.3 以一次 docker compose up -d --wait 启动全部默认服务，核对健康、worker 在线、日志和实际 CLI 版本；不新增默认公网代理服务。
- [x] 9.4 通过正常 API 验证管理员创建/更新 RESPONSES 与 CHAT profile、连通性、三类 Agent 分项 probe、历史结果、非法权限及阶段绑定；使用隔离模拟上游验证两种 Codex 路由。
- [x] 9.5 验证并发 profile 不串用密钥/上下文，超时结果含安全诊断且无残留进程/端口，历史失败不被覆盖；记录实际执行场景和未执行项。
- [x] 9.6 将源码标识、锁文件/CLI/镜像信息、命令/退出码、覆盖率和服务结果导出至本地 .migration-reports/fix-agent-runtime-dependency-locking-and-probe-diagnostics，只清理本次本地资源，不提交私有报告或环境文件。

## 10. 文档、规范和交接

- [x] 10.1 更新 backend 中文安装/升级说明，记录版本来源、完整锁定与更新流程、DSH HMR 根因、安装资源预算及无需远程热修复的交付方式。
- [x] 10.2 更新 profile 上游协议、原生/bridge 路由、支持/拒绝能力矩阵、诊断双语字段与时间预算说明；明确官方 Responses 转发代理不等于 CHAT 转换器。
- [x] 10.3 记录 backend/worker 同镜像部署、已有 profile/历史结果兼容及回滚限制；部署须另行授权，本次仅本地交付，不修改远程 dev/test。
- [x] 10.4 核对文档链接、变更范围、openspec validate fix-agent-runtime-dependency-locking-and-probe-diagnostics --strict 及任务证据；全部必需验证通过后才提示 sync/archive，未完成时提供继续 apply 命令。
