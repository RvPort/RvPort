## Context

本文中的项目文件路径均相对于仓库根目录，`.` 表示仓库根目录，`backend/` 表示后端目录；`/api/...` 表示 HTTP API 路径。

本设计依据当前源码中的 ModelProfileController、ModelProfileRepository、AgentRuntimeRepository、CodexProbeDispatcher/Recovery、KbAiExtensionService/AgentRuntime 和 TestComparisonService/Handler。模型 API 位于 `/api/admin` 并要求 SUPER_ADMIN；profile 的 created_by 可为空且只是审计字段；名称全局唯一，阶段绑定以 stage 为主键。两个 AI 业务入口解析配置时没有用户参数，自动测试结束通过 onAutomatedTestFinished 派生比较任务。项目访问允许项目创建者和超级管理员，任务已有 createdBy，但尚不能等同于经过验证、全链路传递的模型配置所属用户。

用户已确认：谁触发使用谁的配置；自动派生/自动重试继承原触发者；手动重新触发使用新触发者；旧平台配置与密钥全部归 admin。用户进一步限定本次只修改后端，不涉及前端。现有根、backend、frontend 是独立 OpenSpec 根；本 change 仅在 backend，其他根及既有 change 均不操作。

## Goals / Non-Goals

**Goals:**

- 建立个人 profile、阶段绑定、探针历史的后端权限边界，涵盖后台恢复与服务端缓存。
- 让 readiness、创建、执行和自动派生流程对配置所属用户得出一致结果，保留项目访问授权与触发者审计。
- 迁移旧配置而不复制密钥、不篡改历史操作者；可追溯地阻止旧任务意外使用迁移后的凭据。
- 在本地 Docker 验证当前源码、完整覆盖率和完整 Compose 服务。

**Non-Goals:**

- 不新增平台共享模型后备、团队共享、项目级模型配置、计费或配额系统。
- 不开放任意 CLI 路径、参数或用户安装插件；不更新 Agent 版本和第三方补丁。
- 不改扫描器，不修改或测试远程 dev/test，不自动同步归档或提交推送。
- 不修改前端页面、路由、API 客户端、类型或账号缓存，不创建 frontend change，不把个人中心入口作为本次交付成果。

## Decisions

### 1. 归属与审计分离

model_profiles 增加非空 owner_user_id；名称索引调整为 owner_user_id + lower(name)。created_by、updated_by 原值保留。阶段绑定使用 (owner_user_id, stage) 主键，并通过 profile 的 (owner_user_id, id) 唯一键和复合外键确保同所有者绑定，服务层提前返回友好错误。

个人 API 仅从认证主体取得 owner，客户端提交 owner/actor 字段不能决定归属。列表、count、详情、更新、删除、测试、run 查询、最新结果均使用 owner 条件；对其他用户 UUID 与不存在 UUID 返回一致的 404。超级管理员在个人接口没有绕过权限的特例。删除约束限定正确引用范围，不允许越权解除他人引用。

替代方案：直接用 created_by 隔离会破坏旧配置迁移与操作者审计；仅在 Controller 过滤列表无法保护详情、worker 和恢复流程，均不采用。

### 2. 个人 Agent 选择与平台运行能力分开

agent_runtimes 继续是平台维护的受管能力目录，个人只能选择启用的 runtime。个人目录只返回可消费的 ID、类型、名称、启用状态及必要能力，不暴露内部路径或全局 last_probe_*。停止将个人兼容性测试回写为全局 runtime 健康结论；profile + runtime 的结果才是兼容性的来源。

不新增重复的个人 CLI 安装表。现有 profile 中历史 executable_path 保留迁移兼容用途，但个人创建/更新/测试请求不能用它覆盖受管 runtime 路径；测试临时覆盖同样遵守限制。默认 Codex runtime 只是平台能力选择，不能隐含全局模型或凭据回退。

### 3. 个人接口与后续对接契约

规范同步同时更新 backend-api-design 的总接口合同和 backend-app-skeleton 的模型配置测试条目，消除仍要求旧 admin 路由或拒绝普通用户的现行表述；明确退役的 api-e2e-test-suite 历史规范及归档保留原样，不恢复相关设施。

新接口使用 `/api/me/model-profiles`、`/api/me/model-stage-assignments`、`/api/me/agent-runtimes`、`/api/me/model-profile-agent-test-runs/{runId}`；测试子路径和主要响应字段延续原语义。个人接口仍受认证、账号状态、强制改密码与 CSRF 约束。

删除原 admin 模型配置相关 API 的 Controller 路由映射、专用安全匹配规则和当前 API 文档定义，包括以下路径族及其全部现有方法、动作后缀和子资源：

- `/api/admin/model-profiles`：列表、详情、增删改、`:test`、`:test-connectivity`、`:test-agents` 和 `/{profileId}/agent-probe-results`。
- `/api/admin/model-stage-assignments`：阶段绑定查询与保存。
- `/api/admin/model-profile-agent-test-runs/{runId}`：测试 run 查询。
- `/api/admin/agent-runtimes` 及 `/{runtimeId}`：原模型配置页面使用的 Agent 目录查询。

这些路径不保留兼容处理器、别名、重定向或专用迁移响应；通过认证及 CSRF 等通用安全校验的请求按未注册 API 返回 404。以路由注册表及 OpenAPI 不存在旧接口、完整旧方法矩阵无业务副作用作为删除验收，不能仅凭 401/403 判断接口已删除。平台仍通过受管镜像、安装配置及内部运行能力目录维护 CLI，不为此保留上述 admin API；其他管理员功能不在删除范围内。文档仅提供客户端从旧路径迁入新 `/api/me` 路径的说明。此为明确的 API 破坏性变更，旧前端模型页面需另行适配，本 change 不修改前端。

后端任何个人配置、测试结果和 readiness 缓存必须以用户归属分区，个人敏感响应使用禁止共享缓存的策略。对接说明建议前端查询 key 包含 userId，退出/换号取消请求、轮询和迟到写入；这些前端行为只记录为后续需求，不在本 change 实施或验证。项目 readiness 在服务端按项目及当前用户判断，不因 admin 查看同一项目而借用其他人的可用性。

### 4. 持久化执行身份与配置引用

业务任务创建时，由服务端确定 requested_by（操作者审计）、configuration_user_id（模型配置主体）；普通手动触发二者相同。持久化源任务/父任务关联、选中的 model_profile_id 与 agent_runtime_id。优先复用既有任务/业务 run 字段；不足时以明确外键或执行上下文表补齐，不依赖进程内 ThreadLocal 或请求结束后的会话。

| 入口 | 配置主体 | 配置绑定解析 |
|---|---|---|
| 本人或 admin 手动触发 | 当前认证用户 | 创建时解析该用户阶段绑定并保存引用 |
| 自动测试结束派生比较 | 父任务的原始触发者 | 派生时解析该用户对应阶段绑定，保存父任务与配置引用 |
| 系统自动重试/恢复 | 既有任务配置主体 | 沿用既有 profile/runtime 引用，不能换人或重新选择阶段绑定 |
| 另一用户手动重新触发 | 新认证用户 | 新执行与新配置引用，不覆写旧任务 |
| 个人连通性/兼容性测试 | 当前认证用户 | 仅可选择本人的 profile |

项目访问仍按既有项目授权校验，持有个人模型不等于拥有他人项目操作权。admin 代操作其他人项目时使用 admin 的模型。自动任务若不能从可信父任务明确推导触发者，返回可查询失败/未配置原因，禁止选择 worker 服务账号、项目所有者或 admin 兜底。

提交前 readiness 仅作提示，创建事务必须再次校验。worker 领取与每次重试时复核用户存在/启用、profile 所有者、profile/runtime 启用状态及项目执行资格；对匹配的已选 profile 读取当前配置，冻结本次执行尝试的不可变快照。排队期间改绑只影响新任务；原 profile 更新在下一次领取生效；禁用/删除使排队任务明确失败。运行中更新不混用新旧配置，不承诺立即撤销已经发出的请求。记录安全配置版本或摘要、实际 profile/runtime ID 与配置主体，不在任务消息和日志记录密钥。

自动重试保持主体和选中的配置引用，但新的尝试可读取该 profile 的新版本，需记录尝试版本；不承诺跨尝试配置字节相同。采用此规则避免无限保存密钥快照，同时让凭据修复后能继续受控重试。

### 5. 探针分派、恢复与项目结果隔离

probe run 和内部 job 具有明确配置主体及 profile 引用；跨表归属一致性需受数据库或事务约束保护。RabbitMQ 保持引用型消息，不加入明文密钥。个人测试入口的临时覆盖只属于当前用户本次测试，加密短期暂存并按终态清理。

原子领取、重复消息幂等、迟到终态保护、摘要时间单调规则继续保留，并加入所有者验证；恢复也不能只凭 UUID 写入别人的 run 或 summary。个人历史测试通过关联 profile 所有者归属，原测试操作者和结果内容保持事实不变。

个人配置与测试历史只对本人可见；AI 生成的项目业务结果仍按项目权限共享。项目历史结果不能因访问者使用不同模型而凭空失效，但“我能否发起下一次执行”必须使用访问者自己的配置。项目结果响应不得携带其他触发者的完整 profile、地址、密钥或个人测试详情，只保留必要的执行审计摘要。

### 6. 独立 OpenSpec 根衔接

本 change 唯一归属为 `backend/openspec/changes/move-model-agent-configuration-to-user-scope/`，负责后端实现、对接契约和本地验收；不是仓库根级 change。OpenSpec CLI 在 `backend/` 执行，实施范围为 backend。用户已排除前端修改，因此不创建 frontend 关联 change，不修改其主规范；根目录与 tools 同样不创建本 change。

| 工作目录 | 需要对齐的规范/职责 |
|---|---|
| `.` | 仅执行完整 Compose 验收；不新增根级 change 或根级能力规范 |
| `backend/` | 本 change 三项新能力及 model-profile-management、agent-runtime-selection、codex-chat-protocol-bridge、backend-database-design、kb-ai-extension-runtime、test-result-comparison-runtime、automated-test-runtime、task-runtime 的 delta；接口对接文档和总验收记录 |
| `frontend/` | 无修改；account-profile、model-configuration-center 及页面缓存实现留给独立前端需求，不创建关联 change |
| `tools/` 下各独立根 | 无功能变更，不创建或同步无关 change |

后续 sync/archive 仅在 backend 进行；仅在后端任务与规定的本地验收全部完成后具备归档条件，不在仓库根或 frontend 操作本 change。此次 propose 不执行实现或 sync/archive。

## Risks / Trade-offs

- [全局模型迁移后普通用户原流程不可用] → 显式显示个人配置缺失，引导配置，禁止静默使用 admin 密钥。
- [有项目权限但无个人配置，或反之] → 两种资格分别检查，覆盖 admin 代操作及自动派生测试。
- [旧任务缺少可信主体/绑定] → 发布前停止接收相关任务并排空运行中任务；未执行的旧队列任务受控终止，保留记录，要求用户重新触发。
- [摘要或缓存跨账号传播] → 清除全局个人测试摘要的读取/写入路径，服务端缓存按用户分区并纳入单测；前端缓存问题单独对接，不能声称本次已修复。
- [旧前端仍调用全局接口] → 记录破坏性接口迁移及后续前端适配清单；本次使用直接 API 验收，不把旧模型页面可用性作为通过条件。
- [配置在排队与重试期间变化] → 固定任务所选引用，领取时冻结配置尝试快照，显示实际版本，明确失败语义。
- [历史记录归属与操作者不同] → 新增 owner 语义，不覆写 created_by/updated_by，不用最后修改者推断所有者。

## Migration Plan

1. 本地隔离数据库准备普通用户、admin、历史创建者为空/非 admin 的平台配置、测试历史、阶段绑定及旧队列任务。生产迁移操作仅形成文档，本轮不执行远程变更。
2. 发布时先阻止旧 API/worker 继续创建或领取 AI 任务，排空运行中任务；剩余旧排队任务按新原因码受控结束。不能依靠新应用替旧二进制补充个人边界。
3. 迁移精确查找 username=`admin` 的唯一账号，校验超级管理员身份；缺失或不符合条件则事务失败，不自动创建、改密码或挑选其他账号。admin 禁用状态不被迁移改变，禁用时执行照常被拒绝。
4. 所有迁移前 profile owner 设置为该 admin，密文原样保留；既有阶段绑定归 admin，测试历史随 profile 归属，历史操作人不改写。替换唯一约束并建立所有者一致性约束。重复启动不得将升级后新建的个人 profile 再次归给 admin。
5. 发布新 API 与 worker，使用 API 核验普通用户只看到空配置、admin 看到迁移配置；核对旧 admin 模型相关路由及当前 OpenAPI 定义已删除，认证后访问旧路径返回 404 且无业务副作用。未知身份旧 job 的恢复必须有界失败。前端部署与页面迁移不属于本次工作。
6. 回滚仅在隔离本地演练：有个人数据写入后，不直接降级到全局旧程序；维护窗口内备份当前新数据并恢复升级前完整数据库/镜像配套备份。文档说明备份恢复会失去升级后的写入，需要先导出留存与确认，不能宣称无损自动回滚。

## 验证方案

- Docker 单元测试覆盖正常、边界与异常：A/B 用户互相越权、admin 个人边界、同名 profile、跨 owner 绑定、所有测试入口、快照与改绑、自动派生、手动重触发、禁用账号、重复投递、恢复/迟到结果、服务端缓存隔离。
- Java 统计全部自有生产类（含未加载），仅允许列明的生成 jOOQ/第三方排除；整体和完整受影响模块行/分支分别至少 90%。自有 Python/资源脚本整体及受影响脚本同样验收；不以变更行统计或集成测试替代。
- 本次不修改前端，不设置独立前端 lint、typecheck、单元测试或覆盖率实施任务；现有前端覆盖率例外保持。根 Compose 的默认前端服务仍随完整构建与启动验证，不能据此宣称已完成个人中心改造或前端质量验收。
- 独立数据库迁移和 API/worker 集成验证补充单测，使用本地模拟上游与假密钥，断言请求实际到达正确上游、跨用户拒绝时无上游调用。
- 从根目录按模块顺序、限 CPU/内存/并发构建完整 Compose；独立 env/网络/卷/端口，一次 `docker compose up -d --wait` 启动全部默认服务，验证健康、日志、个人管理、业务派生、恢复、迁移和清理。报告记录 Git tree、镜像、命令、退出码、测试数、覆盖率分母与原始报告位置，不能复用旧镜像或旧报告替代本轮源码验收。

## Open Questions

产品归属与迁移决策已由用户确认，无阻塞产品问题。实施前需清点所有实际模型调用/派生入口，并以本地迁移夹具验证数据库字段映射；发现超出本次范围的历史覆盖率缺口时报告差距，不自行扩大改造或降低门槛。
