# Tasks

## 1. 实施边界与镜像

- [x] 1.1 确认基线分支、backend 独立 OpenSpec 根；执行器资产放 `backend/executor/`，根 compose 仅改 backend-worker。
- [x] 1.2 编写 `backend/executor/Dockerfile`：qemu-user-static + riscv64 交叉工具链 + node + pi（+pi-subagents/pi-mcp-adapter）+ wxai provider extension + entrypoint.sh；构建参数模板化（APT_URI/NODEJS_MIRROR/NPM_REGISTRY）；构建期三连自检。
  - 2026-11-24 修订：pi-subagents / pi-mcp-adapter 已移除（与所钉 pi 版本不兼容，见第 6 节）。
- [x] 1.3 本地构建执行器镜像并验证自检通过；受限网络出口记录在案（ARIA2/NPM mirror）。

## 2. 执行器容器服务

- [x] 2.1 `ExecutorContainerService`：docker run 命令拼装（命名、挂载、env 显式传值、代理置空、--rm）、启动前同名孤儿 rm、退出码/超时映射。
- [x] 2.2 取消集成：`registerCancellableResource` → `docker kill`；与任务取消链路（worker cancel）联通。
- [x] 2.3 单测：命令快照断言（挂载/env/命名/代理置空）、孤儿清理、超时 kill、退出码映射（不真跑 docker）。

## 3. 技能 volume 与管理面

> 本节三项曾按原设计实施，**已于 2026-11-24 整体撤销**（平台不再对技能做独立的 git 版本管理）。
> 历史记录保留如下以说明来龙去脉；现行做法见第 6 节。

- [x] ~~3.1 `SkillRepository`：volume 路径解析、空 volume 初始化 clone、`currentRevision()`、`update()`（fetch/rev-list/diff --stat 解析）。~~（已撤销）
- [x] ~~3.2 管理 API：GET `/api/admin/ai-migration/skills`、POST `/api/admin/ai-migration/skills:update`（管理员权限、审计日志）。~~（已撤销）
- [x] ~~3.3 单测：git 输出 fixture 解析、初始化幂等、API 鉴权与响应形态。~~（已撤销）

## 4. 执行通道切换

- [x] 4.1 `AiMigrationExecutorProperties`（独立 `@ConfigurationProperties`，`rvport.ai-migration.executor.*`）+ application.yml 默认值 + compose 变量透传。
- [x] 4.2 `AiMigrationAgentRuntime` 模式分派：container 路径（prompt 合同复用、经 ExecutorContainerService）与 process 回退路径并存。
- [x] 4.3 compose：backend-worker 挂 docker.sock 与技能 volume。（技能 volume 挂载已于 2026-11-24 移除，见第 6 节）
- [x] 4.4 单测：分派逻辑、容器路径契约（mock 服务）、process 路径回归不破。

## 5. 验证

- [x] 5.1 Docker 全量单测；整体与新增模块行/分支覆盖率 ≥90%。
- [x] 5.2 冒烟：执行器镜像构建自检 → compose 全栈起 → 容器模式发起一次 AI_MIGRATION → 产物入库可下载；process 模式回归一次。
- [x] 5.3 `openspec validate add-ai-migration-executor --strict` 通过；不自动归档。

## 6. 技能改为随镜像内置（2026-11-24 修订）

背景：技能卷只读挂载使技能自带脚本无法在自身目录写入；技能与工具链分属两套来源、版本容易错位；
运行期更新能力收益有限而引入了克隆、远端对齐、专用代理等一整条链路。改为技能随源码入库、随镜像发布。

- [x] 6.1 技能文件入库：`backend/executor/skills/`（来源 zte-riscv/rvport-skills@d0422e96，含 LICENSE、NOTICE；不含 .git 与运行期产物）。
- [x] 6.2 执行器镜像：`COPY` 技能到 `/root/.pi/agent/skills/`；补 `make`、`cmake`、`python3`（含 pip、venv）；
      构建期自检增加技能存在性与新增工具检查；移除与所钉 pi 版本不兼容的 `pi-subagents` / `pi-mcp-adapter`。
- [x] 6.3 后端：移除 `SkillRepository`、`SkillRepositoryProperties`、`SkillAdminService`、`SkillAdminController` 及其测试；
      `AiMigrationExecutorProperties` / `ExecutorContainerProperties` 去掉技能卷与技能仓库配置项；
      容器启动命令不再挂载技能卷；`AiMigrationAgentRuntime` 不再在迁移前克隆技能。
      `SecurityConfiguration` 中 `/api/admin/ai-migration/**` 的管理员授权规则**保留**，避免该前缀将来失去保护。
- [x] 6.4 前端：移除技能管理页、路由、侧边栏入口、API 模块、类型与查询键。
- [x] 6.5 配置与文档：`application.yml`、`docker-compose.yml`（含 named volume）、`.env.example` 去掉技能仓库/技能卷配置，
      补充执行器镜像需单独构建的说明；本规范 `ai-migration-executor-runtime` 的技能要求整条替换。
- [x] 6.6 验证：执行器镜像重建（技能 76 个文件与仓库逐文件哈希一致、`make`/`cmake`/`python3` 可用、
      技能目录可写、两个技能脚本的失败原因由只读/缺 python3 变为网络与 pip 环境）；
      迁移模块单测 132 项通过；compose 配置校验通过；
      前端类型检查、lint、生产构建通过，单测 1348 通过 / 4 失败（均为未改动目录中既有的超时类用例），
      路由测试 39 项全过。
- [x] 6.7 部署：后端镜像重建为 `7592a48cd7b5` 并 `docker compose up -d --wait`。backend 与 worker 均运行新镜像、
      不再挂载技能卷、无技能相关环境变量与启动日志；7 个服务健康；原技能查询/更新接口返回 404；
      迁移列表与评估报告前置条件读取正常。执行器镜像为 `58f267a1e01f`，尚未经真实迁移任务验证。
- [x] 6.8 遗留处理（随第 7 节）：`run_query.sh` 的 Python 依赖改为构建时预装进镜像，脚本改为依赖已满足即跳过安装，
      系统 Python 的保护保持不变；`prepare_verify_env.sh` 与 `resources/*_env.sh` 在技能的「平台运行模式」一节中明确禁用。

## 7. 条目契约对齐技能协议（2026-11-24）

背景：后端投影输出的条目结构与 riscv-migrate 技能协议不一致——`solver_type` 是平台自定义的字符串而技能按整数分流，
构建文件条目被误归为内联汇编；技能要求把进度写回条目文件，而后端对该文件做哈希校验，写入即任务失败。

- [x] 7.1 投影器：条目文件按技能协议输出（顶层 `target`/`suggestion_class_count`/`missing_class_count`/`suggestion_class[]`/
      `missing_class[]`，条目含 11 个协议字段）；平台专有字段作为附加字段保留；`solver_type` 改为整数枚举并按
      `findingType` 细分探测类；补上结束行；`file_path` 在写出前按 agent 实际看到的源码根定位（容器 `/app/source`，
      子进程模式为工作区实际路径）。记录组件与访问方法保持原名，后端逻辑未改。
- [x] 7.2 技能（`riscv-migrate`；`riscv-scan` 与三份共用的 `scan_result_protocol.md` 未改）：
      `SKILL.md` 新增「平台运行模式」一节（工作区布局、条目文件只读、进度写入结论文件、工具链名称、不向用户提问）；
      正文与 `code_migrate.md` 中 13 处「写回条目文件」改为记录进度，并区分独立运行与平台两种模式；
      知识库查询失败时的降级说明更正；`run_query.sh` 依赖已满足即跳过安装。
- [x] 7.3 后端提示词：容器模式提示词去掉与技能「平台运行模式」重复的内容，修正 `<project_root>` 指向的错误说法；
      子进程模式的 `migrator.md` 按新的条目字段与整数分流更新。
- [x] 7.4 执行器镜像：构建时预装知识库查询所需的 `mcp`、`httpx`（新增 `PIP_INDEX_URL` 构建参数），不解除系统 Python 的保护。
- [x] 7.5 规范：`add-ai-migration-task` 的 `ai-migration-runtime` 修订 `solver_type` 映射与条目文件结构。
- [x] 7.6 验证：迁移模块全部 17 个测试类 155 项通过（含新增的条目文件 JSON 形状测试 10 项）。
      更正：6.6 记录的 132 项未包含投影器的两个测试类——当时所用的 `-Dtest` 匹配模式漏掉了它们。
      反向验证：撤销 3 处改动（去掉 `file_path` 注解、`items` 重新出现、构建选择类改回内联汇编）后有 4 项测试失败。
      执行器镜像重建为 `32f5d2acc626`：技能 76 个文件在镜像内，三处技能改动已生效，查询脚本不再卡在 pip 上。
- [ ] 7.7 部署与真实迁移验证：后端镜像需重建并部署；尚未在真实迁移任务中验证新契约。
- [ ] 7.8 `openspec validate --strict`：宿主机无 openspec 命令，仅做了结构自查（每个场景均有 WHEN/THEN）。
